/**
 * Vue 3 composable for driving a Requirement Analysis Master (RAM) session.
 *
 * Lifecycle:
 *   idle -> running (via {@code start()})
 *         -> clarify (CLARIFY_REQUIRED arrives, EventSource closed)
 *         -> running (after {@code submitClarify()} re-opens the stream)
 *         -> completed (RUN_COMPLETED)
 *         | aborted   (RUN_ABORTED)
 *         | error     (transport / RUN_FAILED)
 *
 * The composable owns at most one {@code EventSource} at a time and tears it
 * down on terminal events, {@code disconnect()}, or replacement subscription.
 */
import { computed, getCurrentInstance, onUnmounted, ref, type ComputedRef, type Ref } from 'vue'
import {
  abortRamSession,
  ramStreamUrl,
  resumeRamSession,
  startRamSession,
  submitRamClarify
} from '@/api/ram'
import type { ClarifySchema, RamCostSnapshot, RamEvent, RamStatus } from '@/types/ram'

export interface UseRamSessionReturn {
  sessionId: Ref<string | null>
  events: Ref<RamEvent[]>
  status: Ref<RamStatus>
  cost: ComputedRef<RamCostSnapshot>
  clarifyQuestions: Ref<ClarifySchema | null>
  start: (rawInput: string, projectPath: string) => Promise<string>
  submitClarify: (answers: Record<string, unknown>) => Promise<void>
  resume: () => Promise<void>
  abort: () => Promise<void>
  rejoin: (sid: string, afterSeq?: number) => void
  disconnect: () => void
}

function asNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

export function useRamSession(): UseRamSessionReturn {
  const sessionId: Ref<string | null> = ref(null)
  const events: Ref<RamEvent[]> = ref([])
  const status: Ref<RamStatus> = ref('idle')
  const clarifyQuestions: Ref<ClarifySchema | null> = ref(null)
  const tokens = ref(0)
  const usd = ref(0)
  // Highest seq we have already processed. Survives stream re-opens (clarify /
  // resume) so we never double-count events that the backend re-emits.
  const lastSeq = ref(0)

  let source: EventSource | null = null

  const cost: ComputedRef<RamCostSnapshot> = computed(() => ({
    tokens: tokens.value,
    usd: usd.value
  }))

  const tearDown = (): void => {
    if (source) {
      source.close()
      source = null
    }
  }

  const handleEvent = (raw: MessageEvent): void => {
    let parsed: RamEvent | null = null
    try {
      const data = JSON.parse(raw.data as string) as Partial<RamEvent>
      if (data && typeof data.type === 'string' && typeof data.seq === 'number') {
        parsed = {
          seq: data.seq,
          type: data.type,
          payload: (data.payload ?? {}) as Record<string, unknown>
        }
      }
    } catch {
      // ignore malformed event
    }
    if (!parsed) {
      return
    }
    // Dedup: skip seqs we've already processed (re-opens after clarify/resume
    // will start the SSE poll at lastSeq via ?afterSeq=, but defend in depth).
    if (parsed.seq <= lastSeq.value) {
      return
    }
    lastSeq.value = parsed.seq
    events.value = [...events.value, parsed]

    const usage = parsed.payload['usage']
    if (usage && typeof usage === 'object') {
      const u = usage as Record<string, unknown>
      // When the backend reports a cumulative snapshot, replace the accumulator
      // instead of adding; otherwise treat the values as deltas.
      const cumulative = u['cumulative'] === true
      const tokensVal = asNumber(u['tokens'])
      const usdVal = asNumber(u['cost_usd'])
      if (cumulative) {
        tokens.value = tokensVal
        usd.value = usdVal
      } else {
        tokens.value += tokensVal
        usd.value += usdVal
      }
    }

    switch (parsed.type) {
      case 'CLARIFY_REQUIRED':
      case 'CLARIFY_REQ': {
        const payload = parsed.payload
        const questions = Array.isArray(payload['questions'])
          ? (payload['questions'] as unknown[]).map((q) => String(q))
          : []
        clarifyQuestions.value = {
          nodeName: typeof payload['nodeName'] === 'string' ? (payload['nodeName'] as string) : undefined,
          questions
        }
        status.value = 'clarify'
        tearDown()
        break
      }
      case 'RUN_COMPLETED':
        status.value = 'completed'
        tearDown()
        break
      case 'RUN_ABORTED':
        status.value = 'aborted'
        tearDown()
        break
      case 'RUN_FAILED':
      case 'ERROR':
        status.value = 'error'
        tearDown()
        break
      default:
        if (status.value === 'idle') {
          status.value = 'running'
        }
    }
  }

  const openStream = (sid: string): void => {
    tearDown()
    const es = new EventSource(ramStreamUrl(sid, lastSeq.value))
    es.onmessage = handleEvent
    es.onerror = () => {
      // EventSource auto-retries while readyState !== CLOSED. Surface a failure
      // only when the connection is permanently closed by the browser.
      if (es.readyState === EventSource.CLOSED && status.value === 'running') {
        status.value = 'error'
        tearDown()
      }
    }
    source = es
  }

  const start = async (rawInput: string, projectPath: string): Promise<string> => {
    events.value = []
    clarifyQuestions.value = null
    tokens.value = 0
    usd.value = 0
    lastSeq.value = 0
    status.value = 'running'
    try {
      const resp = await startRamSession({ rawInput, projectPath })
      sessionId.value = resp.sessionId
      openStream(resp.sessionId)
      return resp.sessionId
    } catch (err: unknown) {
      status.value = 'error'
      throw err
    }
  }

  const submitClarify = async (answers: Record<string, unknown>): Promise<void> => {
    if (!sessionId.value) {
      throw new Error('no active session')
    }
    await submitRamClarify(sessionId.value, answers)
    clarifyQuestions.value = null
    status.value = 'running'
    openStream(sessionId.value)
  }

  const resume = async (): Promise<void> => {
    if (!sessionId.value) {
      throw new Error('no active session')
    }
    await resumeRamSession(sessionId.value)
    status.value = 'running'
    openStream(sessionId.value)
  }

  const abort = async (): Promise<void> => {
    if (!sessionId.value) {
      throw new Error('no active session')
    }
    await abortRamSession(sessionId.value)
    status.value = 'aborted'
    tearDown()
  }

  const disconnect = (): void => {
    tearDown()
  }

  /**
   * Re-attach to an in-flight session (e.g. after a page refresh). Unlike
   * {@link start}, this does not POST anywhere and does not reset accumulated
   * events/cost — it just opens the SSE stream against an existing session id,
   * routing every event through {@link handleEvent} so dedup, cumulative-cost
   * guard, and terminal-state transitions all apply consistently.
   */
  const rejoin = (sid: string, afterSeq = 0): void => {
    sessionId.value = sid
    if (afterSeq > lastSeq.value) {
      lastSeq.value = afterSeq
    }
    if (status.value === 'idle') {
      status.value = 'running'
    }
    openStream(sid)
  }

  // Best-effort auto-cleanup when used inside a component instance. Callers
  // outside a setup() context (e.g. tests) just don't get this hook.
  if (getCurrentInstance()) {
    onUnmounted(() => {
      tearDown()
    })
  }

  return {
    sessionId,
    events,
    status,
    cost,
    clarifyQuestions,
    start,
    submitClarify,
    resume,
    abort,
    rejoin,
    disconnect
  }
}
