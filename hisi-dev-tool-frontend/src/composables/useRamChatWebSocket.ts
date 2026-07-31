import { ref, onUnmounted } from 'vue'
import { useRamChatStore } from '@/stores/ramChatStore'

export function useRamChatWebSocket() {
  const ws = ref<WebSocket | null>(null)
  const connected = ref(false)
  const reconnectAttempts = ref(0)
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

  const store = useRamChatStore()

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (ws.value && ws.value.readyState === WebSocket.OPEN) {
        ws.value.send(JSON.stringify({ action: 'ping' }))
      }
    }, 30_000) // 30s ping keeps proxy/server from closing idle connection
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function connect(sid: string) {
    disconnect()
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const url = `${protocol}//${host}/ws/ram-chat?sessionId=${sid}`

    ws.value = new WebSocket(url)

    ws.value.onopen = () => {
      connected.value = true
      reconnectAttempts.value = 0
      startHeartbeat()
    }

    ws.value.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        handleMessage(msg)
      } catch (e) {
        console.error('[RamChatWS] parse error', e)
      }
    }

    ws.value.onclose = () => {
      connected.value = false
      scheduleReconnect(sid)
    }

    ws.value.onerror = (err) => {
      console.error('[RamChatWS] error', err)
    }
  }

  function disconnect() {
    stopHeartbeat()
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws.value) {
      ws.value.onclose = null
      ws.value.close()
      ws.value = null
    }
    connected.value = false
  }

  function scheduleReconnect(sid: string) {
    if (reconnectAttempts.value >= 5) {
      console.error('[RamChatWS] max reconnect attempts reached')
      return
    }
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts.value), 30000)
    reconnectAttempts.value++
    reconnectTimer = setTimeout(() => connect(sid), delay)
  }

  function handleMessage(msg: Record<string, unknown>) {
    store.appendEvent({
      id: (msg.eventId as number) ?? Date.now(),
      sessionId: (msg.sessionId as number) ?? Number(store.currentSessionId),
      seq: (msg.seq as number) ?? (store.events.length + 1),
      type: msg.type as string,
      payload: JSON.stringify(msg),
      createdAt: (msg.createdAt as number) ?? Date.now()
    })
  }

  onUnmounted(() => disconnect())

  return { connect, disconnect, connected }
}
