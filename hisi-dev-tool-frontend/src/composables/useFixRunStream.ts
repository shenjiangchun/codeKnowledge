import { ref, onBeforeUnmount } from 'vue'
import { workflowStreamUrl } from '@/api/workflow'
import { workbenchApi } from '@/api/workbench'
import type { FixRunDetail } from '@/types/workbench'

export interface RunEvent {
  seq: number
  type: string
  payload?: Record<string, unknown>
}

/**
 * 订阅一次修复运行的事件流：复用工作流 SSE（?token= + afterSeq 续传），
 * 按 seq 去重；CHECKPOINT 到达时刷新运行详情；断线自动带 afterSeq 重连。
 * 传入运行 UUID 后开始，切换运行或停止时关闭。
 */
export function useFixRunStream(onEvent?: (event: RunEvent) => void) {
  const detail = ref<FixRunDetail | null>(null)
  const connected = ref(false)
  let sessionUuid = ''
  let source: EventSource | null = null
  let lastSeq = 0
  let closed = true
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  async function resolveId(): Promise<number> {
    const runs = await workbenchApi.runs()
    const run = runs.find(r => r.sessionUuid === sessionUuid)
    if (!run) throw new Error('运行不存在')
    return run.id
  }

  async function refreshDetail() {
    try {
      const id = detail.value?.id ?? (await resolveId())
      detail.value = await workbenchApi.runDetail(id)
      // 运行已到终态：停止订阅，不再重连
      const status = detail.value?.status
      if (status && ['DONE', 'FAILED', 'ABORTED'].includes(status)) {
        stop()
      }
    } catch {
      // 运行可能刚创建；下次事件再刷新
    }
  }

  function connect() {
    if (closed || !sessionUuid) return
    source = new EventSource(workflowStreamUrl(sessionUuid, lastSeq))
    source.onopen = () => {
      connected.value = true
    }
    source.onmessage = (message) => {
      let event: RunEvent
      try {
        event = JSON.parse(message.data)
      } catch {
        return
      }
      const seq = Number(event.seq ?? 0)
      if (seq <= lastSeq) return
      lastSeq = seq
      if (event.type === 'CHECKPOINT' || event.type === 'NODE_COMPLETE' || event.type === 'ERROR') {
        void refreshDetail()
      }
      onEvent?.(event)
    }
    source.onerror = () => {
      connected.value = false
      source?.close()
      source = null
      if (!closed) {
        retryTimer = setTimeout(connect, 3000)
      }
    }
  }

  /** 订阅某次运行的事件流（重复调用会先停掉旧连接）。 */
  function start(uuid: string) {
    stop()
    sessionUuid = uuid
    lastSeq = 0
    closed = false
    connect()
  }

  function stop() {
    closed = true
    if (retryTimer) clearTimeout(retryTimer)
    source?.close()
    source = null
    connected.value = false
  }

  onBeforeUnmount(stop)

  return { detail, connected, start, stop, refreshDetail }
}
