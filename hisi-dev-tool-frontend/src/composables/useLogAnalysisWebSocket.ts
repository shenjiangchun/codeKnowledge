import { ref, onUnmounted } from 'vue'

export interface LogNodeEvent {
  reportId: number
  type: 'NODE_START' | 'NODE_COMPLETE' | 'NODE_ERROR' | 'DAG_COMPLETE'
  nodeName: string
  timestamp: number
  payload?: {
    durationMs?: number
    summary?: unknown
    error?: string
    totalDurationMs?: number
  }
}

export function useLogAnalysisWebSocket(reportId: string | (() => string)) {
  const events = ref<LogNodeEvent[]>([])
  const connected = ref(false)
  let ws: WebSocket | null = null
  let reconnectAttempts = 0
  const maxReconnectAttempts = 5

  function getReportId(): string {
    return typeof reportId === 'function' ? reportId() : reportId
  }

  function connect(): void {
    const id = getReportId()
    if (!id) return

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws/log-analysis?reportId=${id}`

    ws = new WebSocket(url)

    ws.onopen = () => {
      connected.value = true
      reconnectAttempts = 0
    }

    ws.onmessage = (msg) => {
      try {
        const event = JSON.parse(msg.data) as LogNodeEvent
        const eventType = (event as { type?: string }).type
        if (eventType === 'connected') return
        events.value = [...events.value, event]
      } catch {
        // ignore parse errors
      }
    }

    ws.onclose = () => {
      connected.value = false
      if (reconnectAttempts < maxReconnectAttempts) {
        const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000)
        reconnectAttempts++
        setTimeout(connect, delay)
      }
    }

    ws.onerror = () => {
      ws?.close()
    }
  }

  function disconnect(): void {
    if (ws) {
      ws.close()
      ws = null
    }
    connected.value = false
  }

  onUnmounted(disconnect)

  return { events, connected, connect, disconnect }
}
