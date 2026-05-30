/**
 * Agent 诊断 Composable
 * 管理 WebSocket 连接、事件处理和状态
 */

import { ref, computed, onMounted, onUnmounted } from 'vue'
import type {
  AgentEvent,
  AgentResult,
  FinalDiagnosticResult,
  DiagnosisRequest,
  ConnectionStatus
} from '@/types/agent'
import { AgentEventType } from '@/types/agent'

const WS_ENDPOINT = '/ws/diagnosis'
const HEARTBEAT_INTERVAL = 30000

export function useDiagnosis() {
  // 状态
  const connectionStatus = ref<ConnectionStatus>('disconnected')
  const events = ref<AgentEvent[]>([])
  const agentResults = ref(new Map<string, AgentResult>())
  const finalResult = ref<FinalDiagnosticResult | undefined>()
  const currentPhase = ref('')
  const isRunning = ref(false)
  const error = ref<string>('')

  // WebSocket 相关
  let socket: WebSocket | null = null
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null

  // 计算属性
  const hasResult = computed(() => finalResult.value !== undefined)

  const overallProgress = computed(() => {
    if (events.value.length === 0) return 0
    const lastEvent = events.value[events.value.length - 1]
    return lastEvent.progress || 0
  })

  const agentStates = computed(() => {
    const states: Record<string, { status: string; progress: number; confidence?: number }> = {}
    const agentTypes = ['STACK_TRACE', 'CODE_CONTEXT', 'GIT_HISTORY', 'CONSENSUS']

    for (const agentType of agentTypes) {
      const agentEvents = events.value.filter(e => e.agentType === agentType)
      const lastEvent = agentEvents[agentEvents.length - 1]
      const result = agentResults.value.get(agentType)

      if (result) {
        states[agentType] = {
          status: result.success ? 'COMPLETED' : 'FAILED',
          progress: 100,
          confidence: result.confidence
        }
      } else if (lastEvent) {
        states[agentType] = {
          status: getEventStatus(lastEvent.eventType),
          progress: lastEvent.progress || 0,
          confidence: lastEvent.confidence
        }
      } else {
        states[agentType] = {
          status: 'IDLE',
          progress: 0
        }
      }
    }

    return states
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
        error.value = ''
        startHeartbeat()
      }

      socket.onclose = () => {
        connectionStatus.value = 'disconnected'
        stopHeartbeat()
        isRunning.value = false
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
    if (socket) {
      socket.close()
      socket = null
    }
    connectionStatus.value = 'disconnected'
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

  // 处理消息
  function handleMessage(data: string) {
    try {
      const msg = JSON.parse(data)

      switch (msg.type) {
        case 'agent_event':
          if (msg.payload) {
            handleEvent(msg.payload as AgentEvent)
          }
          break
        case 'result':
          if (msg.result) {
            handleResult(msg.result as AgentResult)
          }
          break
        case 'final_result':
          if (msg.finalResult) {
            handleFinalResult(msg.finalResult as FinalDiagnosticResult)
          }
          break
        case 'error':
          error.value = msg.message || '未知错误'
          isRunning.value = false
          break
        case 'pong':
          break
      }
    } catch (e) {
      console.error('Failed to parse message:', e)
    }
  }

  // 处理事件
  function handleEvent(event: AgentEvent) {
    events.value.push(event)

    // 更新当前阶段
    if (event.phase) {
      currentPhase.value = event.phase
    }

    // 处理中间结果
    if (event.partialResult && event.agentType) {
      agentResults.value.set(event.agentType, event.partialResult)
    }

    // 检查是否结束
    if (event.eventType === AgentEventType.ORCHESTRATION_END ||
        event.eventType === AgentEventType.FINAL_RESULT) {
      isRunning.value = false
    }
  }

  // 处理结果
  function handleResult(result: AgentResult) {
    if (result.agentName) {
      agentResults.value.set(result.agentName.toUpperCase(), result)
    }
  }

  // 处理最终结果
  function handleFinalResult(result: FinalDiagnosticResult) {
    finalResult.value = result
    isRunning.value = false

    // 合并所有 Agent 结果
    if (result.agentResults) {
      for (const agentResult of result.agentResults) {
        if (agentResult.agentName) {
          agentResults.value.set(agentResult.agentName.toUpperCase(), agentResult)
        }
      }
    }
  }

  // 获取事件状态
  function getEventStatus(eventType: AgentEventType): string {
    switch (eventType) {
      case AgentEventType.AGENT_STARTED:
      case AgentEventType.AGENT_PROGRESS:
        return 'RUNNING'
      case AgentEventType.AGENT_COMPLETED:
        return 'COMPLETED'
      case AgentEventType.AGENT_FAILED:
        return 'FAILED'
      case AgentEventType.AGENT_SKIPPED:
        return 'SKIPPED'
      default:
        return 'IDLE'
    }
  }

  // 发送消息
  function send(message: object) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message))
    }
  }

  // 开始诊断
  function startDiagnosis(request: DiagnosisRequest) {
    // 重置状态
    events.value = []
    agentResults.value = new Map()
    finalResult.value = undefined
    currentPhase.value = ''
    error.value = ''
    isRunning.value = true

    // 发送请求
    send({
      action: 'start',
      query: request.query,
      context: request
    })
  }

  // 取消诊断
  function cancelDiagnosis() {
    send({ action: 'cancel' })
    isRunning.value = false
  }

  // 清空状态
  function clearState() {
    events.value = []
    agentResults.value = new Map()
    finalResult.value = undefined
    currentPhase.value = ''
    error.value = ''
    isRunning.value = false
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
    connectionStatus,
    events,
    agentResults,
    finalResult,
    currentPhase,
    isRunning,
    error,

    // 计算属性
    hasResult,
    overallProgress,
    agentStates,

    // 方法
    connect,
    disconnect,
    startDiagnosis,
    cancelDiagnosis,
    clearState
  }
}