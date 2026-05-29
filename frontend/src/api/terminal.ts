/**
 * 终端 WebSocket API
 * 管理与后端 PTY 进程的实时通信
 */

import { ref } from 'vue'
import type { TerminalConnectionStatus, TerminalClientMessage, TerminalServerMessage } from '@/types/terminal'

export interface TerminalCallbacks {
  onOpen?: () => void
  onClose?: () => void
  onError?: (error: string) => void
  onOutput?: (data: string) => void
  onSessionInfo?: (claudeSessionId: string) => void
  onReady?: () => void
  onClaudeReady?: () => void
  onStatusChange?: (status: TerminalConnectionStatus) => void
}

export interface TerminalConnection {
  send: (message: TerminalClientMessage) => void
  close: () => void
  getStatus: () => TerminalConnectionStatus
}

const WS_ENDPOINT = '/ws/terminal'
const HEARTBEAT_INTERVAL = 30000

export function createTerminalConnection(callbacks: TerminalCallbacks): TerminalConnection {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const wsUrl = `${protocol}//${host}${WS_ENDPOINT}`

  const status = ref<TerminalConnectionStatus>('disconnected')
  let socket: WebSocket | null = null
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

  const updateStatus = (newStatus: TerminalConnectionStatus) => {
    status.value = newStatus
    callbacks.onStatusChange?.(newStatus)
  }

  const startHeartbeat = () => {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ action: 'ping' }))
      }
    }, HEARTBEAT_INTERVAL)
  }

  const stopHeartbeat = () => {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  const connect = () => {
    updateStatus('connecting')

    try {
      socket = new WebSocket(wsUrl)

      socket.onopen = () => {
        updateStatus('connected')
        startHeartbeat()
        callbacks.onOpen?.()
      }

      socket.onclose = () => {
        stopHeartbeat()
        updateStatus('disconnected')
        callbacks.onClose?.()
      }

      socket.onerror = () => {
        updateStatus('error')
        callbacks.onError?.('WebSocket connection error')
      }

      socket.onmessage = (event: MessageEvent) => {
        try {
          let data: string
          // 处理二进制数据：使用 TextDecoder UTF-8 解码
          if (event.data instanceof ArrayBuffer) {
            const decoder = new TextDecoder('utf-8')
            data = decoder.decode(event.data)
          } else {
            data = event.data
          }

          const msg: TerminalServerMessage = JSON.parse(data)
          switch (msg.type) {
            case 'output':
              callbacks.onOutput?.(msg.data || '')
              break
            case 'session_info':
              callbacks.onSessionInfo?.(msg.claudeSessionId || '')
              break
            case 'ready':
              callbacks.onReady?.()
              break
            case 'claude_ready':
              callbacks.onClaudeReady?.()
              break
            case 'pong':
              break
            case 'error':
              callbacks.onError?.(msg.data || 'Unknown error')
              break
          }
        } catch (e) {
          if (typeof event.data === 'string') {
            callbacks.onOutput?.(event.data)
          }
        }
      }
    } catch (error) {
      updateStatus('error')
      callbacks.onError?.(error instanceof Error ? error.message : 'Connection failed')
    }
  }

  const send = (message: TerminalClientMessage) => {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message))
    }
  }

  const close = () => {
    stopHeartbeat()
    if (socket) {
      socket.close()
      socket = null
    }
    updateStatus('disconnected')
  }

  const getStatus = (): TerminalConnectionStatus => status.value

  connect()

  return { send, close, getStatus }
}