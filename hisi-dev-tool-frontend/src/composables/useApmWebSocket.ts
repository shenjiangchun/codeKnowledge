import { ref, onUnmounted } from 'vue'
import type { WsMessage, ApmSpan } from '@/types/apm'

export function useApmWebSocket() {
  const connected = ref(false)
  const spans = ref<ApmSpan[]>([])
  const events = ref<WsMessage[]>([])
  const processStatus = ref<string>('IDLE')

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
        } else if (msg.type?.startsWith('PROCESS_')) {
          processStatus.value = (msg as Record<string, unknown>).status as string || msg.type.replace('PROCESS_', '')
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
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    connected,
    spans,
    events,
    processStatus,
    connect,
    disconnect,
    reset,
  }
}
