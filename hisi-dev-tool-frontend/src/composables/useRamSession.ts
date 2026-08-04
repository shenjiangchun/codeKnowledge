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
  confirmRamNode,
  getRamSession,
  getRamSessionEvents,
  ramStreamUrl,
  resumeRamSession,
  startRamSession,
  submitRamClarify
} from '@/api/ram'
import type { ClarifySchema, HitlSchema, RamCostSnapshot, RamEvent, RamStatus } from '@/types/ram'

export interface UseRamSessionReturn {
  sessionId: Ref<string | null>
  events: Ref<RamEvent[]>
  status: Ref<RamStatus>
  cost: ComputedRef<RamCostSnapshot>
  clarifyQuestions: Ref<ClarifySchema | null>
  hitlSchema: Ref<HitlSchema | null>
  start: (rawInput: string, projectPath: string) => Promise<string>
  submitClarify: (answers: Record<string, unknown>) => Promise<void>
  submitConfirm: (
    action: 'approve' | 'reject' | 'edit',
    feedback?: string,
    editedOutput?: Record<string, unknown>
  ) => Promise<void>
  resume: () => Promise<void>
  abort: () => Promise<void>
  rejoin: (sid: string, afterSeq?: number) => Promise<void>
  disconnect: () => void
}

function asNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

// Diagnostic logger for the RAM session lifecycle. Uses console.info so
// messages are visible in the default browser console filter level.
const dbg = (...args: unknown[]): void => {
  // eslint-disable-next-line no-console
  console.info('[RAM]', ...args)
}

export function useRamSession(): UseRamSessionReturn {
  const sessionId: Ref<string | null> = ref(null)
  const events: Ref<RamEvent[]> = ref([])
  const status: Ref<RamStatus> = ref('idle')
  const clarifyQuestions: Ref<ClarifySchema | null> = ref(null)
  const hitlSchema: Ref<HitlSchema | null> = ref(null)
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
      // Backend serializes Long to String (JacksonConfig), so seq may be string or number
      const seqVal = typeof data.seq === 'number' ? data.seq :
                     typeof data.seq === 'string' ? parseInt(data.seq, 10) : NaN
      if (data && typeof data.type === 'string' && !isNaN(seqVal) && seqVal >= 0) {
        parsed = {
          seq: seqVal,
          type: data.type,
          payload: (data.payload ?? {}) as Record<string, unknown>
        }
      }
    } catch (err) {
      dbg('handleEvent JSON.parse failed', err, raw.data)
      // ignore malformed event
    }
    if (!parsed) {
      dbg('handleEvent dropped event (unparsed or missing seq/type)')
      return
    }
    dbg('event', { seq: parsed.seq, type: parsed.type, payload: parsed.payload })
    // Dedup: skip seqs we've already processed (re-opens after clarify/resume
    // will start the SSE poll at lastSeq via ?afterSeq=, but defend in depth).
    if (parsed.seq <= lastSeq.value) {
      dbg('event dedup-skip', { seq: parsed.seq, lastSeq: lastSeq.value })
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
        dbg('status -> clarify', clarifyQuestions.value)
        tearDown()
        break
      }
      case 'HITL_REQUIRED':
      case 'HITL_REQ': {
        const payload = parsed.payload
        hitlSchema.value = {
          nodeName: typeof payload['nodeName'] === 'string' ? (payload['nodeName'] as string) : '',
          output: (payload['output'] ?? {}) as Record<string, unknown>
        }
        status.value = 'confirm'
        dbg('status -> confirm', hitlSchema.value)
        tearDown()
        break
      }
      case 'RUN_COMPLETED':
        status.value = 'completed'
        dbg('status -> completed')
        tearDown()
        break
      case 'RUN_ABORTED':
        status.value = 'aborted'
        dbg('status -> aborted')
        tearDown()
        break
      case 'RUN_FAILED':
      case 'ERROR':
        status.value = 'error'
        dbg('status -> error type=' + parsed.type + ' payload=', parsed.payload)
        tearDown()
        break
      default:
        if (status.value === 'idle') {
          status.value = 'running'
          dbg('status -> running (first non-terminal event)')
        }
    }
  }

  const openStream = (sid: string): void => {
    tearDown()
    const url = ramStreamUrl(sid, lastSeq.value)
    dbg('openStream', { sid, afterSeq: lastSeq.value, url })
    const es = new EventSource(url)
    let consecutiveErrors = 0
    es.onopen = () => {
      consecutiveErrors = 0
      dbg('SSE onopen', { sid, readyState: es.readyState, url })
    }
    es.onmessage = (raw) => {
      consecutiveErrors = 0
      handleEvent(raw)
    }
    es.onerror = (evt) => {
      consecutiveErrors++
      dbg('SSE onerror', { sid, readyState: es.readyState, status: status.value, consecutiveErrors, eventType: evt.type, url })
      // EventSource auto-retries while readyState !== CLOSED. Surface a failure
      // only when the connection is permanently closed by the browser, OR
      // when we get too many consecutive errors (proxy/server restart).
      if (es.readyState === EventSource.CLOSED && status.value === 'running') {
        dbg('SSE permanently closed → error')
        status.value = 'error'
        tearDown()
      } else if (consecutiveErrors > 10 && status.value === 'running') {
        // Too many consecutive errors without a successful message — give up
        dbg('SSE too many consecutive errors, giving up')
        status.value = 'error'
        tearDown()
      }
    }
    source = es
    dbg('EventSource created', { sid, url, readyState: es.readyState })
  }

  const start = async (rawInput: string, projectPath: string | undefined, projectPaths?: string[]): Promise<string> => {
    dbg('start() called', { rawInput, projectPath, projectPaths })
    events.value = []
    clarifyQuestions.value = null
    hitlSchema.value = null
    tokens.value = 0
    usd.value = 0
    lastSeq.value = 0
    status.value = 'running'
    try {
      const resp = await startRamSession({ rawInput, projectPath, projectPaths })
      dbg('start() POST /sessions OK', resp)
      sessionId.value = resp.sessionId
      openStream(resp.sessionId)
      return resp.sessionId
    } catch (err: unknown) {
      dbg('start() POST /sessions FAILED', err)
      status.value = 'error'
      throw err
    }
  }

  const submitClarify = async (answers: Record<string, unknown>): Promise<void> => {
    if (!sessionId.value) {
      throw new Error('no active session')
    }
    const resp = await submitRamClarify(sessionId.value, answers)
    clarifyQuestions.value = null

    // The backend dispatches the orchestrator asynchronously and returns
    // immediately.  We always open an SSE stream to track subsequent events
    // (CHECKPOINT, HITL_REQ, RUN_COMPLETED, etc.).
    status.value = 'running'
    if (typeof resp.nextSeq === 'number' && resp.nextSeq > lastSeq.value) {
      lastSeq.value = resp.nextSeq
    }
    openStream(sessionId.value)
  }

  const submitConfirm = async (
    action: 'approve' | 'reject' | 'edit',
    feedback?: string,
    editedOutput?: Record<string, unknown>
  ): Promise<void> => {
    if (!sessionId.value || !hitlSchema.value) {
      throw new Error('no active HITL request')
    }
    await confirmRamNode(sessionId.value, {
      nodeName: hitlSchema.value.nodeName,
      action,
      feedback,
      editedOutput
    })
    hitlSchema.value = null
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
   * Re-attach to an in-flight session (e.g. after a page refresh or switching
   * from the session list). Resets all composable state before loading the new
   * session so no data leaks from a prior session.
   */
  const rejoin = async (sid: string, afterSeq = 0): Promise<void> => {
    // Reset all state to avoid leaking data from a prior session
    tearDown()
    events.value = []
    clarifyQuestions.value = null
    hitlSchema.value = null
    tokens.value = 0
    usd.value = 0
    lastSeq.value = 0
    sessionId.value = sid

    // Load historical events from REST so page refresh doesn't lose content
    try {
      const [allEvents, sessionInfo] = await Promise.all([
        getRamSessionEvents(sid),
        getRamSession(sid).catch(() => null)
      ])

      // Derive status from session info (authoritative source)
      if (sessionInfo) {
        const s = sessionInfo.status
        if (s === 'DONE') {
          status.value = 'completed'
        } else if (s === 'FAILED') {
          status.value = 'error'
        } else if (s === 'ABORTED') {
          status.value = 'aborted'
        }
      }

      if (allEvents && allEvents.length > 0) {
        // Coerce API shape (type: string | null) to canonical type (type: string).
        const normalized: RamEvent[] = allEvents.map((e) => ({
          seq: e.seq,
          type: e.type ?? '',
          payload: e.payload as Record<string, unknown>
        }))
        events.value = normalized
        const maxSeq = normalized.reduce((max, e) => Math.max(max, e.seq ?? 0), 0)
        if (maxSeq > lastSeq.value) lastSeq.value = maxSeq
        dbg('rejoin: loaded', allEvents.length, 'historical events, maxSeq=', maxSeq)

        // Derive clarify/hitl status from historical events
        // Process in reverse to find the latest state
        for (let i = allEvents.length - 1; i >= 0; i--) {
          const ev = allEvents[i]
          if (!ev || typeof ev.type !== 'string') continue
          const payload = (ev.payload ?? {}) as Record<string, unknown>

          if ((ev.type === 'CLARIFY_REQUIRED' || ev.type === 'CLARIFY_REQ') && !clarifyQuestions.value) {
            const questions = Array.isArray(payload['questions'])
              ? (payload['questions'] as unknown[]).map((q) => String(q))
              : []
            clarifyQuestions.value = {
              nodeName: typeof payload['nodeName'] === 'string' ? (payload['nodeName'] as string) : undefined,
              questions
            }
          }
          if ((ev.type === 'HITL_REQUIRED' || ev.type === 'HITL_REQ') && !hitlSchema.value) {
            hitlSchema.value = {
              nodeName: typeof payload['nodeName'] === 'string' ? (payload['nodeName'] as string) : '',
              output: (payload['output'] ?? {}) as Record<string, unknown>
            }
          }
          // Terminal events take precedence — break early
          if (ev.type === 'RUN_COMPLETED') { break }
          if (ev.type === 'RUN_FAILED' || ev.type === 'ERROR') { break }
          if (ev.type === 'RUN_ABORTED') { break }
        }

        // Derive status from events only if session info didn't provide a terminal status
        if (status.value === 'idle' || status.value === 'running') {
          const lastEvent = allEvents[allEvents.length - 1]
          if (lastEvent) {
            const t = lastEvent.type
            if (t === 'RUN_COMPLETED') {
              status.value = 'completed'
            } else if (t === 'RUN_FAILED' || t === 'ERROR') {
              status.value = 'error'
            } else if (t === 'RUN_ABORTED') {
              status.value = 'aborted'
            } else if (t === 'CLARIFY_REQUIRED' || t === 'CLARIFY_REQ') {
              status.value = 'clarify'
            } else if (t === 'HITL_REQUIRED' || t === 'HITL_REQ') {
              status.value = 'confirm'
            } else {
              status.value = 'running'
            }
          }
        }
      }
    } catch (e) {
      dbg('rejoin: failed to load historical events', e)
    }

    if (afterSeq > lastSeq.value) {
      lastSeq.value = afterSeq
    }
    // Only open SSE if the session is still in-progress
    if (status.value === 'running' || status.value === 'idle') {
      status.value = 'running'
      openStream(sid)
    }
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
    hitlSchema,
    start,
    submitClarify,
    submitConfirm,
    resume,
    abort,
    rejoin,
    disconnect
  }
}
