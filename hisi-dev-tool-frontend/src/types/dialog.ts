/**
 * 自然语言对话 WebSocket 相关类型定义
 * 用于方案3: 自然语言驱动诊断入口
 */

// 对话意图类型
export const IntentType = {
  DIAGNOSE_LOG: 'DIAGNOSE_LOG',
  QUERY_CODE: 'QUERY_CODE',
  EXPLAIN_ERROR: 'EXPLAIN_ERROR',
  INTERVENE: 'INTERVENE',
  FOLLOW_UP: 'FOLLOW_UP',
  UNKNOWN: 'UNKNOWN'
} as const

export type IntentType = typeof IntentType[keyof typeof IntentType]

// 对话事件类型
export const DialogEventType = {
  // 连接事件
  CONNECTED: 'CONNECTED',
  DISCONNECTED: 'DISCONNECTED',
  ERROR: 'ERROR',

  // 对话事件
  SESSION_CREATED: 'SESSION_CREATED',
  INTENT_PARSED: 'INTENT_PARSED',
  ENTITY_EXTRACTED: 'ENTITY_EXTRACTED',

  // 进度事件
  PHASE_STARTED: 'PHASE_STARTED',
  PHASE_PROGRESS: 'PHASE_PROGRESS',
  PHASE_COMPLETED: 'PHASE_COMPLETED',
  PHASE_FAILED: 'PHASE_FAILED',

  // Agent事件
  AGENT_DISPATCHED: 'AGENT_DISPATCHED',
  AGENT_UPDATE: 'AGENT_UPDATE',
  AGENT_RESULT: 'AGENT_RESULT',

  // 用户干预事件
  INTERVENTION_REQUESTED: 'INTERVENTION_REQUESTED',
  INTERVENTION_ACKNOWLEDGED: 'INTERVENTION_ACKNOWLEDGED',
  INTERVENTION_APPLIED: 'INTERVENTION_APPLIED',

  // 结果事件
  PARTIAL_RESULT: 'PARTIAL_RESULT',
  FINAL_RESULT: 'FINAL_RESULT',

  // 流式输出
  STREAM_OUTPUT: 'STREAM_OUTPUT',
  STREAM_DONE: 'STREAM_DONE'
} as const

export type DialogEventType = typeof DialogEventType[keyof typeof DialogEventType]

// 意图识别结果
export interface IntentResult {
  intent: IntentType
  confidence: number
  entities: {
    errorType?: string
    focusArea?: string
    className?: string
    methodName?: string
    filePath?: string
    lineNumber?: number
    projectPath?: string
  }
}

// 对话阶段信息
export interface DialogPhase {
  phaseId: string
  name: string
  description: string
  progress: number  // 0-100
  status: 'pending' | 'running' | 'completed' | 'failed' | 'interrupted'
  startTime?: string
  endTime?: string
}

// Agent诊断状态
export interface AgentDiagnosticStatus {
  agentId: string
  agentType: string
  agentName: string
  status: 'idle' | 'dispatched' | 'running' | 'completed' | 'failed' | 'skipped'
  progress: number  // 0-100
  confidence?: number
  output?: string
  startTime?: string
  endTime?: string
}

// 用户干预请求
export interface InterventionRequest {
  requestId: string
  sessionId: string
  interventionType: 'adjust_focus' | 'skip_agent' | 'change_strategy' | 'provide_hint' | 'cancel'
  message: string
  context?: {
    targetAgent?: string
    newFocus?: string
    hint?: string
  }
  timestamp: string
}

// 用户干预响应
export interface InterventionResponse {
  requestId: string
  acknowledged: boolean
  applied: boolean
  message: string
  affectedAgents?: string[]
  timestamp: string
}

// 流式输出片段
export interface StreamChunk {
  sessionId: string
  content: string
  isMarkdown: boolean
  timestamp: string
}

// WebSocket 客户端消息
export interface DialogClientMessage {
  action: 'start_session' | 'send_message' | 'intervene' | 'cancel' | 'ping' | 'close_session'
  sessionId?: string
  message?: string
  context?: Record<string, unknown>
  intervention?: InterventionRequest
}

// WebSocket 服务端消息
export interface DialogServerMessage {
  type: DialogEventType
  sessionId?: string
  payload?: unknown
  timestamp: string

  // 具体 payload 类型
  intentResult?: IntentResult
  phase?: DialogPhase
  agentStatus?: AgentDiagnosticStatus
  interventionRequest?: InterventionRequest
  interventionResponse?: InterventionResponse
  streamChunk?: StreamChunk
  finalResult?: DialogFinalResult
  error?: {
    code: string
    message: string
    details?: string
  }
}

// 对话最终结果
export interface DialogFinalResult {
  sessionId: string
  summary: string
  rootCause?: string
  confidence: number
  recommendations: string[]
  relatedCode: {
    filePath: string
    lineNumber: number
    methodName?: string
    className?: string
  }[]
  agentResults: AgentDiagnosticStatus[]
  dialogHistory: {
    role: 'user' | 'assistant'
    content: string
    timestamp: string
  }[]
}

// WebSocket 连接状态 (复用已有的 ConnectionStatus)
export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error' | 'reconnecting'

// 对话会话状态
export interface DialogSessionState {
  sessionId: string | null
  connectionStatus: ConnectionStatus
  currentIntent: IntentResult | null
  currentPhase: DialogPhase | null
  phases: DialogPhase[]
  agents: Map<string, AgentDiagnosticStatus>
  streamingContent: string
  isStreaming: boolean
  isInterventionPending: boolean
  pendingInterventionRequest: InterventionRequest | null
  finalResult: DialogFinalResult | null
  error: string | null
  eventLog: DialogServerMessage[]
}