/**
 * Generic workflow composable — manages SSE lifecycle, event processing,
 * and state machine for any workflow type.
 *
 * Design: REST authoritative + SSE incremental (proven by StatusPage fix).
 */
import { ref, onUnmounted } from 'vue'
import type {
  WorkflowStatus,
  WorkflowEvent,
  WorkflowCostSnapshot,
  ClarifySchema,
  HitlSchema,
} from '@/types/workflow'
import {
  startWorkflow,
  getWorkflowStatus,
  getWorkflowReport,
  getWorkflowEvents,
  workflowStreamUrl,
  submitWorkflowClarify as apiSubmitClarify,
  submitWorkflowConfirm as apiSubmitConfirm,
  rerunWorkflowFromNode,
  abortWorkflow as apiAbort,
} from '@/api/workflow'

export interface UseWorkflowOptions {
  workflowType: string
  /** Custom event handler for workflow-specific events (e.g. CHECKPOINT → markdown) */
  onEvent?: (evt: WorkflowEvent) => void
}

export interface UseWorkflowReturn {
  sessionId: import('vue').Ref<string | null>
  events: import('vue').Ref<WorkflowEvent[]>
  status: import('vue').Ref<WorkflowStatus>
  report: import('vue').Ref<Record<string, unknown> | null>
  cost: import('vue').Ref<WorkflowCostSnapshot>
  clarifyQuestions: import('vue').Ref<ClarifySchema | null>
  hitlSchema: import('vue').Ref<HitlSchema | null>

  start: (input: Record<string, unknown>) => Promise<string>
  rejoin: (sid: string, afterSeq?: number) => Promise<void>
  submitClarify: (answers: Record<string, unknown>) => Promise<void>
  submitConfirm: (action: 'approve' | 'reject', feedback?: string) => Promise<void>
  rerunFrom: (nodeName: string) => Promise<void>
  abort: () => Promise<void>
  disconnect: () => void
}

export function useWorkflow(options: UseWorkflowOptions): UseWorkflowReturn {
  // ──────────────────── State ────────────────────
  const sessionId = ref<string | null>(null)
  const events = ref<WorkflowEvent[]>([])
  const status = ref<WorkflowStatus>('idle')
  const report = ref<Record<string, unknown> | null>(null)
  const cost = ref<WorkflowCostSnapshot>({ tokens: 0, usd: 0 })
  const clarifyQuestions = ref<ClarifySchema | null>(null)
  const hitlSchema = ref<HitlSchema | null>(null)

  let lastSeq = 0
  let source: EventSource | null = null

  // ──────────────────── Start ────────────────────
  async function start(input: Record<string, unknown>): Promise<string> {
    const resp = await startWorkflow({
      workflowType: options.workflowType,
      ...input,
    })
    sessionId.value = resp.sessionId
    status.value = 'running'
    openSseStream(resp.sessionId)
    return resp.sessionId
  }

  // ──────────────────── Rejoin ────────────────────
  async function rejoin(sid: string, afterSeq = 0): Promise<void> {
    sessionId.value = sid
    lastSeq = afterSeq

    // Step 1: REST authoritative — load session status + report + events in parallel
    const [statusResp, reportResp, eventsResp] = await Promise.all([
      getWorkflowStatus(sid).catch(() => null),
      getWorkflowReport(sid).catch(() => null),
      getWorkflowEvents(sid).catch(() => []),
    ])

    // Derive terminal status from REST
    if (statusResp) {
      const s = statusResp.status
      if (s === 'DONE') status.value = 'completed'
      else if (s === 'FAILED') status.value = 'error'
      else if (s === 'ABORTED') status.value = 'aborted'
    }

    // Load report (only if it has actual content)
    if (reportResp?.report && Object.keys(reportResp.report).length > 0) {
      report.value = reportResp.report
    }

    // Load historical events and derive lastSeq
    if (eventsResp && eventsResp.length > 0) {
      events.value = eventsResp
      const maxSeq = Math.max(...eventsResp.map(e => e.seq ?? 0))
      if (maxSeq > lastSeq) lastSeq = maxSeq

      // Process historical events through custom handler
      if (options.onEvent) {
        for (const evt of eventsResp) {
          options.onEvent(evt)
        }
      }
    }

    // Step 2: If session still in-progress, open SSE for incremental updates
    if (status.value === 'running' || status.value === 'idle') {
      status.value = 'running'
      openSseStream(sid)
    }
  }

  // ──────────────────── Clarify ────────────────────
  async function submitClarify(answers: Record<string, unknown>): Promise<void> {
    if (!sessionId.value) return
    await apiSubmitClarify(sessionId.value, { answers })
    clarifyQuestions.value = null
    status.value = 'running'
    openSseStream(sessionId.value)
  }

  // ──────────────────── HITL Confirm ────────────────────
  async function submitConfirm(action: 'approve' | 'reject', feedback?: string): Promise<void> {
    if (!sessionId.value) return
    await apiSubmitConfirm(sessionId.value, { action, feedback })
    hitlSchema.value = null
    status.value = 'running'
    openSseStream(sessionId.value)
  }

  // ──────────────────── Rerun ────────────────────
  async function rerunFrom(nodeName: string): Promise<void> {
    if (!sessionId.value) return
    await rerunWorkflowFromNode(sessionId.value, nodeName)
    status.value = 'running'
    openSseStream(sessionId.value)
  }

  // ──────────────────── Abort ────────────────────
  async function abort(): Promise<void> {
    if (!sessionId.value) return
    await apiAbort(sessionId.value)
    status.value = 'aborted'
    tearDown()
  }

  // ──────────────────── Disconnect ────────────────────
  function disconnect(): void {
    tearDown()
  }

  // ──────────────────── SSE Management ────────────────────
  function openSseStream(sid: string): void {
    tearDown()

    const url = workflowStreamUrl(sid, lastSeq)
    source = new EventSource(url)

    source.onmessage = (raw: MessageEvent) => {
      const rawEvt = JSON.parse(raw.data)
      // Backend serializes Long to String, so seq may be string or number
      const seq = typeof rawEvt.seq === 'number' ? rawEvt.seq :
                  typeof rawEvt.seq === 'string' ? parseInt(rawEvt.seq, 10) : 0
      const evt: WorkflowEvent = {
        seq,
        type: rawEvt.type,
        payload: rawEvt.payload || {}
      }
      if (evt.seq <= lastSeq) return // dedup
      lastSeq = evt.seq
      events.value = [...events.value, evt]

      // Custom event handler
      if (options.onEvent) {
        options.onEvent(evt)
      }

      handleEvent(evt)
    }

    source.onerror = () => {
      if (source?.readyState === EventSource.CLOSED) {
        // Server closed the stream (terminal state)
        return
      }
      // Network error — browser auto-reconnects
    }
  }

  function handleEvent(evt: WorkflowEvent): void {
    const payload = evt.payload || {}

    switch (evt.type) {
      case 'CLARIFY_REQUIRED':
        clarifyQuestions.value = {
          nodeName: payload.nodeName as string | undefined,
          questions: Array.isArray(payload.questions) ? payload.questions as string[] : [],
        }
        status.value = 'clarify'
        tearDown()
        break

      case 'HITL_REQUIRED':
        hitlSchema.value = {
          nodeName: (payload.nodeName as string) || '',
          output: (payload.output as Record<string, unknown>) || {},
        }
        status.value = 'confirm'
        tearDown()
        break

      case 'RUN_COMPLETED':
        status.value = 'completed'
        // Try to load final report
        if (sessionId.value) {
          getWorkflowReport(sessionId.value).then(r => {
            if (r?.report) report.value = r.report
          }).catch(() => {})
        }
        tearDown()
        break

      case 'RUN_FAILED':
      case 'ERROR':
        status.value = 'error'
        tearDown()
        break

      case 'RUN_ABORTED':
        status.value = 'aborted'
        tearDown()
        break

      case 'COST': {
        const usage = payload.usage as Record<string, unknown> | undefined
        if (usage) {
          const newTokens = (usage.input_tokens as number || 0) + (usage.output_tokens as number || 0)
          if (newTokens > cost.value.tokens) {
            cost.value = { tokens: newTokens, usd: cost.value.usd }
          }
        }
        break
      }
    }
  }

  function tearDown(): void {
    if (source) {
      source.close()
      source = null
    }
  }

  // Auto-cleanup
  onUnmounted(() => {
    tearDown()
  })

  return {
    sessionId,
    events,
    status,
    report,
    cost,
    clarifyQuestions,
    hitlSchema,
    start,
    rejoin,
    submitClarify,
    submitConfirm,
    rerunFrom,
    abort,
    disconnect,
  }
}
