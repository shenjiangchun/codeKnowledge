import { getCurrentInstance, onUnmounted, ref, type Ref } from 'vue'
import { startMergeAnalysis, mergeAnalysisStreamUrl } from '@/api/merge-analysis'
import type { MergeAnalysisEvent, MergeAnalysisStatus } from '@/types/merge-analysis'

export interface UseMergeAnalysisReturn {
  sessionId: Ref<string>
  status: Ref<MergeAnalysisStatus>
  events: Ref<MergeAnalysisEvent[]>
  currentNode: Ref<string>
  lastSeq: Ref<number>
  start: (projectPath: string, sourceBranch: string, targetBranch: string) => Promise<string>
  rejoin: (sid: string, afterSeq?: number) => void
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
      const node = parsed.payload['node']
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

  const disconnect = (): void => {
    tearDown()
  }

  if (getCurrentInstance()) {
    onUnmounted(() => tearDown())
  }

  return { sessionId, status, events, currentNode, lastSeq, start, rejoin, disconnect }
}
