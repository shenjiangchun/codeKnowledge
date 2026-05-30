/**
 * Agent 相关类型定义
 * 与后端 AgentEvent.java 保持一致
 */

// Agent 状态枚举
export const AgentStatus = {
  IDLE: 'IDLE',
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  SKIPPED: 'SKIPPED'
} as const

export type AgentStatus = typeof AgentStatus[keyof typeof AgentStatus]

// Agent 类型枚举
export const AgentType = {
  STACK_TRACE: 'STACK_TRACE',
  CODE_CONTEXT: 'CODE_CONTEXT',
  GIT_HISTORY: 'GIT_HISTORY',
  CONSENSUS: 'CONSENSUS'
} as const

export type AgentType = typeof AgentType[keyof typeof AgentType]

// Agent 事件类型常量（与后端 AgentEventType.java 一致）
export const AgentEventType = {
  REQUEST_RECEIVED: 'REQUEST_RECEIVED',
  AGENT_STARTED: 'AGENT_STARTED',
  AGENT_PROGRESS: 'AGENT_PROGRESS',
  AGENT_COMPLETED: 'AGENT_COMPLETED',
  AGENT_FAILED: 'AGENT_FAILED',
  AGENT_SKIPPED: 'AGENT_SKIPPED',
  ORCHESTRATION_START: 'ORCHESTRATION_START',
  ORCHESTRATION_END: 'ORCHESTRATION_END',
  FINAL_RESULT: 'FINAL_RESULT'
} as const

export type AgentEventType = typeof AgentEventType[keyof typeof AgentEventType]

// Agent 事件对象 - WebSocket 推送消息
export interface AgentEvent {
  requestId: string           // 请求ID（诊断请求的唯一标识）
  eventType: AgentEventType   // 事件类型
  agentType?: string          // Agent类型（可选）
  message: string             // 描述消息
  progress?: number           // 进度百分比 0-100
  confidence?: number         // 置信度（用于 AGENT_COMPLETED）
  phase?: string              // 当前阶段描述
  partialResult?: AgentResult // 中间结果
  timestamp: string           // ISO时间戳
}

// Agent 结果
export interface AgentResult {
  agentName: string
  success: boolean
  data: Record<string, unknown>  // 诊断数据
  confidence: number             // 置信度 0-1
  evidence: string[]             // 证据链
}

// 最终诊断结果
export interface FinalDiagnosticResult {
  requestId: string
  summary: string
  rootCause: string
  confidence: number
  recommendations: string[]
  relatedCode: CodeLocation[]
  agentResults: AgentResult[]
}

// 代码位置
export interface CodeLocation {
  filePath: string
  lineNumber: number
  endLineNumber?: number
  methodName?: string
  className?: string
}

// 诊断请求
export interface DiagnosisRequest {
  query: string
  stackTrace?: string
  errorCode?: string
  logSnippet?: string
  filePath?: string
  lineNumber?: number
  projectPath?: string
}

// WebSocket 客户端消息
export interface DiagnosisClientMessage {
  action: 'start' | 'cancel' | 'ping'
  requestId?: string
  query?: string
  context?: DiagnosisRequest
}

// WebSocket 服务端消息
export interface DiagnosisServerMessage {
  type: 'agent_event' | 'result' | 'final_result' | 'error' | 'pong'
  payload?: AgentEvent
  result?: AgentResult
  finalResult?: FinalDiagnosticResult
  message?: string
}

// WebSocket 连接状态
export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'