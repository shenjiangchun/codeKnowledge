import { ref, onUnmounted } from 'vue'

export interface FollowupEvent {
  type: 'connected' | 'user_msg' | 'assistant_delta' | 'tool_use' | 'tool_result' | 'turn_complete' | 'error'
  sessionId?: string
  text?: string
  delta?: string
  toolName?: string
  input?: unknown
  result?: unknown
  error?: string
}

export function useLogFollowupWebSocket(sessionId: string | (() => string)) {
  const events = ref<FollowupEvent[]>([])
  const connected = ref(false)
  const assistantText = ref('')
  let ws: WebSocket | null = null

  function getSessionId(): string {
    return typeof sessionId === 'function' ? sessionId() : sessionId
  }

  function connect(): void {
    const id = getSessionId()
    if (!id) return

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws/log-followup?sessionId=${id}`

    ws = new WebSocket(url)

    ws.onopen = () => {
      connected.value = true
    }

    ws.onmessage = (msg) => {
      try {
        const event: FollowupEvent = JSON.parse(msg.data)
        if (event.type === 'connected') return

        events.value = [...events.value, event]

        // Accumulate assistant text from deltas
        if (event.type === 'assistant_delta' && event.delta) {
          assistantText.value += event.delta
        }
        if (event.type === 'turn_complete' && event.text) {
          assistantText.value = event.text
        }
      } catch {
        // ignore parse errors
      }
    }

    ws.onclose = () => {
      connected.value = false
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

  function resetText(): void {
    assistantText.value = ''
    events.value = []
  }

  onUnmounted(disconnect)

  return { events, connected, assistantText, connect, disconnect, resetText }
}
