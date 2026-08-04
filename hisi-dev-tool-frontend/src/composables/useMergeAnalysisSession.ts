import { getCurrentInstance, onUnmounted, ref, type Ref } from 'vue'
import { startMergeAnalysis, mergeAnalysisStreamUrl, getMergeAnalysisSessionEvents } from '@/api/merge-analysis'
import type { MergeAnalysisEvent, MergeAnalysisStatus } from '@/types/merge-analysis'

export interface UseMergeAnalysisReturn {
  sessionId: Ref<string>
  status: Ref<MergeAnalysisStatus>
  events: Ref<MergeAnalysisEvent[]>
  currentNode: Ref<string>
  lastSeq: Ref<number>
  start: (projectPath: string, sourceBranch: string, targetBranch: string) => Promise<string>
  rejoin: (sid: string, afterSeq?: number) => Promise<void>
  disconnect: () => void
}

export function useMergeAnalysisSession(): UseMergeAnalysisReturn {
  const sessionId = ref('')
  const status = ref<MergeAnalysisStatus>('idle')
  const events = ref<MergeAnalysisEvent[]>([])
  const currentNode = ref('')
  const lastSeq = ref(0)

  let source: EventSource | null = null

  const tearDown = (): void => {
    if (source) {
      source.close()
      source = null
    }
  }

  const handleEvent = (raw: MessageEvent): void => {
    let parsed: MergeAnalysisEvent | null = null
    try {
      const data = JSON.parse(raw.data as string) as Partial<MergeAnalysisEvent>
      if (data && typeof data.seq === 'number') {
        parsed = {
          seq: data.seq,
          type: typeof data.type === 'string' ? data.type : '',
          payload: (data.payload ?? {}) as Record<string, unknown>
        }
      }
    } catch {
      return
    }
    if (!parsed || parsed.seq <= lastSeq.value) return

    lastSeq.value = parsed.seq
    events.value = [...events.value, parsed]

    if (parsed.type === 'CHECKPOINT') {
      const node = parsed.payload['nodeName'] ?? parsed.payload['node']
      if (typeof node === 'string') {
        currentNode.value = node
      }
    }

    switch (parsed.type) {
      case 'RUN_COMPLETED':
        status.value = 'completed'
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
    const url = mergeAnalysisStreamUrl(sid, lastSeq.value)
    const es = new EventSource(url)
    es.onmessage = handleEvent
    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED && status.value === 'running') {
        status.value = 'error'
        tearDown()
      }
    }
    source = es
  }

  const start = async (
    projectPath: string,
    sourceBranch: string,
    targetBranch: string
  ): Promise<string> => {
    events.value = []
    currentNode.value = ''
    lastSeq.value = 0
    status.value = 'running'
    try {
      const resp = await startMergeAnalysis({ projectPath, sourceBranch, targetBranch })
      sessionId.value = resp.sessionHandle
      openStream(resp.sessionHandle)
      return resp.sessionHandle
    } catch {
      status.value = 'error'
      throw new Error('Failed to start merge analysis session')
    }
  }

  const rejoin = async (sid: string, afterSeq = 0): Promise<void> => {
    // Reset all state to avoid leaking data from a prior session
    tearDown()
    events.value = []
    currentNode.value = ''
    lastSeq.value = 0
    sessionId.value = sid

    // Load historical events from REST before opening SSE
    try {
      const allEvents = await getMergeAnalysisSessionEvents(sid)
      if (allEvents && allEvents.length > 0) {
        events.value = allEvents.map(e => ({
          seq: e.seq,
          type: e.type ?? '',
          payload: e.payload ?? {}
        }))
        const maxSeq = allEvents.reduce((max, e) => Math.max(max, e.seq ?? 0), 0)
        if (maxSeq > lastSeq.value) lastSeq.value = maxSeq

        // Derive status from the last event
        const lastEvent = allEvents[allEvents.length - 1]
        if (lastEvent) {
          const t = lastEvent.type
          if (t === 'RUN_COMPLETED') {
            status.value = 'completed'
          } else if (t === 'RUN_FAILED' || t === 'ERROR') {
            status.value = 'error'
          } else {
            status.value = 'running'
          }
        }
      }
    } catch { /* ignore — SSE will still work */ }

    if (afterSeq > lastSeq.value) {
      lastSeq.value = afterSeq
    }
    // Only open SSE if session is still in-progress
    if (status.value === 'running' || status.value === 'idle') {
      status.value = 'running'
      openStream(sid)
    }
  }

  const disconnect = (): void => {
    tearDown()
  }

  if (getCurrentInstance()) {
    onUnmounted(() => tearDown())
  }

  return { sessionId, status, events, currentNode, lastSeq, start, rejoin, disconnect }
}
