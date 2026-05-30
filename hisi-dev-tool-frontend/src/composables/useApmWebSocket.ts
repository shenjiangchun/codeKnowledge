import { ref, onUnmounted } from 'vue'
import type { WsMessage, ApmSpan } from '@/types/apm'

export interface ProcessLogLine {
  timestamp: number
  line: string
}

export function useApmWebSocket() {
  const connected = ref(false)
  const spans = ref<ApmSpan[]>([])
  const events = ref<WsMessage[]>([])
  const processStatus = ref<string>('IDLE')
  const processLogs = ref<ProcessLogLine[]>([])
  const processError = ref<{ exitCode?: number; tailLines?: string[] } | null>(null)

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  function connect(sessionId: string): void {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const url = `${protocol}//${host}/ws/apm`

    ws = new WebSocket(url)

    ws.onopen = () => {
      connected.value = true
      ws?.send(JSON.stringify({ action: 'connect', sessionId }))
    }

    ws.onmessage = (event: MessageEvent) => {
      try {
        const msg: WsMessage = JSON.parse(event.data as string)
        events.value = [...events.value, msg]

        if (msg.type === 'SPAN_BATCH') {
          const newSpans = (msg as Record<string, unknown>).spans as ApmSpan[]
          if (newSpans) {
            spans.value = [...spans.value, ...newSpans]
          }
        } else if (msg.type === 'PROCESS_LOG') {
          const logMsg = msg as Record<string, unknown>
          const line = (logMsg.line as string) || ''
          processLogs.value = [...processLogs.value, {
            timestamp: (logMsg.timestamp as number) || Date.now(),
            line,
          }]
          // Limit buffer to 2000 lines to prevent memory leak
          if (processLogs.value.length > 2000) {
            processLogs.value = processLogs.value.slice(-1500)
          }
        } else if (msg.type?.startsWith('PROCESS_')) {
          const data = msg as Record<string, unknown>
          processStatus.value = (data.status as string) || msg.type.replace('PROCESS_', '')
          // Capture error details
          if (processStatus.value === 'ERROR' || processStatus.value === 'STOPPED') {
            processError.value = {
              exitCode: data.exitCode as number | undefined,
              tailLines: data.tailLines as string[] | undefined,
            }
          }
        }
      } catch {
        // ignore parse errors from non-JSON messages
      }
    }

    ws.onclose = () => {
      connected.value = false
    }

    ws.onerror = () => {
      connected.value = false
    }
  }

  function disconnect(): void {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
    connected.value = false
  }

  function reset(): void {
    spans.value = []
    events.value = []
    processStatus.value = 'IDLE'
    processLogs.value = []
    processError.value = null
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    connected,
    spans,
    events,
    processStatus,
    processLogs,
    processError,
    connect,
    disconnect,
    reset,
  }
}
