/**
 * 自然语言对话 WebSocket Composable
 * 支持实时进度反馈和用户干预
 */

import { ref, computed, onMounted, onUnmounted } from 'vue'
import type {
  DialogServerMessage,
  DialogClientMessage,
  ConnectionStatus,
  IntentResult,
  DialogPhase,
  AgentDiagnosticStatus,
  InterventionRequest,
  InterventionResponse,
  DialogFinalResult,
  StreamChunk
} from '@/types/dialog'
import { DialogEventType } from '@/types/dialog'

const WS_ENDPOINT = '/ws/dialog'
const HEARTBEAT_INTERVAL = 30000
const RECONNECT_DELAY = 3000
const MAX_RECONNECT_ATTEMPTS = 5

export function useDialogWebSocket() {
  // 状态
  const sessionId = ref<string | null>(null)
  const connectionStatus = ref<ConnectionStatus>('disconnected')
  const currentIntent = ref<IntentResult | null>(null)
  const currentPhase = ref<DialogPhase | null>(null)
  const phases = ref<DialogPhase[]>([])
  const agents = ref(new Map<string, AgentDiagnosticStatus>())
  const streamingContent = ref('')
  const isStreaming = ref(false)
  const isInterventionPending = ref(false)
  const pendingInterventionRequest = ref<InterventionRequest | null>(null)
  const finalResult = ref<DialogFinalResult | null>(null)
  const error = ref<string | null>(null)
  const eventLog = ref<DialogServerMessage[]>([])
  const isRunning = ref(false)

  // WebSocket 相关
  let socket: WebSocket | null = null
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null
  let reconnectAttempts = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  // 计算属性
  const overallProgress = computed(() => {
    if (phases.value.length === 0) return 0
    const totalProgress = phases.value.reduce((sum, phase) => sum + phase.progress, 0)
    return Math.round(totalProgress / phases.value.length)
  })

  const activeAgents = computed(() => {
    return Array.from(agents.value.values()).filter(
      a => a.status === 'running' || a.status === 'dispatched'
    )
  })

  const completedAgents = computed(() => {
    return Array.from(agents.value.values()).filter(
      a => a.status === 'completed'
    )
  })

  const agentStates = computed(() => {
    const states: Record<string, AgentDiagnosticStatus> = {}
    for (const [key, value] of agents.value.entries()) {
      states[key] = value
    }
    return states
  })

  const connectionStatusText = computed(() => {
    switch (connectionStatus.value) {
      case 'connected': return '已连接'
      case 'connecting': return '连接中...'
      case 'reconnecting': return '重连中...'
      case 'error': return '连接错误'
      default: return '未连接'
    }
  })

  const connectionTagType = computed(() => {
    switch (connectionStatus.value) {
      case 'connected': return 'success'
      case 'connecting': return 'warning'
      case 'reconnecting': return 'warning'
      case 'error': return 'danger'
      default: return 'info'
    }
  })

  // 连接 WebSocket
  function connect() {
    if (socket && socket.readyState === WebSocket.OPEN) {
      return
    }

    connectionStatus.value = 'connecting'

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}${WS_ENDPOINT}`

    try {
      socket = new WebSocket(wsUrl)

      socket.onopen = () => {
        connectionStatus.value = 'connected'
        error.value = null
        reconnectAttempts = 0
        startHeartbeat()
      }

      socket.onclose = (event) => {
        stopHeartbeat()
        if (event.code !== 1000 && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
          attemptReconnect()
        } else {
          connectionStatus.value = 'disconnected'
          isRunning.value = false
          isStreaming.value = false
        }
      }

      socket.onerror = () => {
        connectionStatus.value = 'error'
        error.value = 'WebSocket 连接错误'
      }

      socket.onmessage = (event: MessageEvent) => {
        handleMessage(event.data)
      }
    } catch (e) {
      connectionStatus.value = 'error'
      error.value = e instanceof Error ? e.message : '连接失败'
    }
  }

  // 断开连接
  function disconnect() {
    stopHeartbeat()
    stopReconnect()
    if (socket) {
      socket.close(1000, 'Client disconnect')
      socket = null
    }
    connectionStatus.value = 'disconnected'
  }

  // 尝试重连
  function attemptReconnect() {
    if (reconnectTimer) return

    connectionStatus.value = 'reconnecting'
    reconnectAttempts++

    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, RECONNECT_DELAY)
  }

  // 停止重连
  function stopReconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    reconnectAttempts = 0
  }

  // 启动心跳
  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ action: 'ping' }))
      }
    }, HEARTBEAT_INTERVAL)
  }

  // 停止心跳
  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  // 发送消息
  function send(message: DialogClientMessage) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message))
    } else {
      error.value = 'WebSocket 未连接'
    }
  }

  // 处理消息
  function handleMessage(data: string) {
    try {
      const msg: DialogServerMessage = JSON.parse(data)

      // 记录事件日志
      eventLog.value.push(msg)
      if (eventLog.value.length > 100) {
        eventLog.value = eventLog.value.slice(-50)
      }

      // 处理不同类型的事件
      switch (msg.type) {
        case DialogEventType.CONNECTED:
          connectionStatus.value = 'connected'
          break

        case DialogEventType.SESSION_CREATED:
          if (msg.sessionId) {
            sessionId.value = msg.sessionId
          }
          break

        case DialogEventType.INTENT_PARSED:
          if (msg.intentResult) {
            currentIntent.value = msg.intentResult
          }
          break

        case DialogEventType.PHASE_STARTED:
        case DialogEventType.PHASE_PROGRESS:
        case DialogEventType.PHASE_COMPLETED:
          if (msg.phase) {
            handlePhaseUpdate(msg.phase)
          }
          break

        case DialogEventType.AGENT_DISPATCHED:
        case DialogEventType.AGENT_UPDATE:
        case DialogEventType.AGENT_RESULT:
          if (msg.agentStatus) {
            handleAgentUpdate(msg.agentStatus)
          }
          break

        case DialogEventType.INTERVENTION_REQUESTED:
          if (msg.interventionRequest) {
            handleInterventionRequest(msg.interventionRequest)
          }
          break

        case DialogEventType.INTERVENTION_ACKNOWLEDGED:
        case DialogEventType.INTERVENTION_APPLIED:
          if (msg.interventionResponse) {
            handleInterventionResponse(msg.interventionResponse)
          }
          break

        case DialogEventType.STREAM_OUTPUT:
          if (msg.streamChunk) {
            handleStreamOutput(msg.streamChunk)
          }
          break

        case DialogEventType.STREAM_DONE:
          isStreaming.value = false
          break

        case DialogEventType.PARTIAL_RESULT:
          // 中间结果处理
          break

        case DialogEventType.FINAL_RESULT:
          if (msg.finalResult) {
            handleFinalResult(msg.finalResult)
          }
          break

        case DialogEventType.ERROR:
          if (msg.error) {
            error.value = msg.error.message
            isRunning.value = false
            isStreaming.value = false
          }
          break

        case DialogEventType.DISCONNECTED:
          connectionStatus.value = 'disconnected'
          break
      }
    } catch (e) {
      console.error('Failed to parse WebSocket message:', e)
    }
  }

  // 处理阶段更新
  function handlePhaseUpdate(phase: DialogPhase) {
    currentPhase.value = phase

    const index = phases.value.findIndex(p => p.phaseId === phase.phaseId)
    if (index >= 0) {
      phases.value[index] = phase
    } else {
      phases.value.push(phase)
    }
  }

  // 处理Agent更新
  function handleAgentUpdate(agentStatus: AgentDiagnosticStatus) {
    agents.value.set(agentStatus.agentId, agentStatus)
  }

  // 处理干预请求
  function handleInterventionRequest(request: InterventionRequest) {
    isInterventionPending.value = true
    pendingInterventionRequest.value = request
  }

  // 处理干预响应
  function handleInterventionResponse(response: InterventionResponse) {
    if (response.applied) {
      isInterventionPending.value = false
      pendingInterventionRequest.value = null
    }
  }

  // 处理流式输出
  function handleStreamOutput(chunk: StreamChunk) {
    isStreaming.value = true
    streamingContent.value += chunk.content
  }

  // 处理最终结果
  function handleFinalResult(result: DialogFinalResult) {
    finalResult.value = result
    isRunning.value = false
    isStreaming.value = false
  }

  // 开始对话会话
  function startSession(userMessage: string, context?: Record<string, unknown>) {
    // 重置状态
    resetState()
    isRunning.value = true

    send({
      action: 'start_session',
      message: userMessage,
      context
    })
  }

  // 发送消息
  function sendMessage(message: string) {
    send({
      action: 'send_message',
      sessionId: sessionId.value ?? undefined,
      message
    })
  }

  // 发送用户干预
  function sendIntervention(
    interventionType: InterventionRequest['interventionType'],
    message: string,
    context?: InterventionRequest['context']
  ) {
    const request: InterventionRequest = {
      requestId: `${sessionId.value}-${Date.now()}`,
      sessionId: sessionId.value || '',
      interventionType,
      message,
      context,
      timestamp: new Date().toISOString()
    }

    send({
      action: 'intervene',
      sessionId: sessionId.value ?? undefined,
      intervention: request
    })

    // 立即更新本地状态
    isInterventionPending.value = false
    pendingInterventionRequest.value = null
  }

  // 响应干预请求（用户接受或拒绝）
  function respondToIntervention(accept: boolean, message?: string) {
    if (!pendingInterventionRequest.value) return

    if (accept) {
      // 用户确认干预
      sendIntervention(
        pendingInterventionRequest.value.interventionType,
        message || '用户确认',
        pendingInterventionRequest.value.context
      )
    } else {
      // 用户拒绝干预，继续原流程
      isInterventionPending.value = false
      pendingInterventionRequest.value = null
    }
  }

  // 取消当前操作
  function cancel() {
    send({
      action: 'cancel',
      sessionId: sessionId.value ?? undefined
    })
    isRunning.value = false
    isStreaming.value = false
    isInterventionPending.value = false
  }

  // 关闭会话
  function closeSession() {
    send({
      action: 'close_session',
      sessionId: sessionId.value ?? undefined
    })
    sessionId.value = null
  }

  // 重置状态
  function resetState() {
    currentIntent.value = null
    currentPhase.value = null
    phases.value = []
    agents.value = new Map()
    streamingContent.value = ''
    isStreaming.value = false
    isInterventionPending.value = false
    pendingInterventionRequest.value = null
    finalResult.value = null
    error.value = null
    eventLog.value = []
  }

  // 清空流式内容
  function clearStreamingContent() {
    streamingContent.value = ''
  }

  // 清空事件日志
  function clearEventLog() {
    eventLog.value = []
  }

  // 生命周期
  onMounted(() => {
    connect()
  })

  onUnmounted(() => {
    disconnect()
  })

  return {
    // 状态
    sessionId,
    connectionStatus,
    currentIntent,
    currentPhase,
    phases,
    agents,
    streamingContent,
    isStreaming,
    isInterventionPending,
    pendingInterventionRequest,
    finalResult,
    error,
    eventLog,
    isRunning,

    // 计算属性
    overallProgress,
    activeAgents,
    completedAgents,
    agentStates,
    connectionStatusText,
    connectionTagType,

    // 方法
    connect,
    disconnect,
    startSession,
    sendMessage,
    sendIntervention,
    respondToIntervention,
    cancel,
    closeSession,
    resetState,
    clearStreamingContent,
    clearEventLog
  }
}