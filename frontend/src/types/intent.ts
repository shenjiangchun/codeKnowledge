/**
 * 方案3: 自然语言驱动诊断入口 - 类型定义
 */

/** 意图类型 */
export type IntentType =
  | 'DIAGNOSE_LOG'    // 诊断日志
  | 'QUERY_CODE'      // 查询代码
  | 'EXPLAIN_ERROR'   // 解释错误
  | 'INTERVENE'       // 用户干预
  | 'FOLLOW_UP'       // 追问
  | 'UNKNOWN'         // 未知意图

/** 意图类型名称映射 */
export const INTENT_NAMES: Record<IntentType, string> = {
  'DIAGNOSE_LOG': '日志诊断',
  'QUERY_CODE': '代码查询',
  'EXPLAIN_ERROR': '错误解释',
  'INTERVENE': '干预调整',
  'FOLLOW_UP': '追问深入',
  'UNKNOWN': '未知意图'
}

/** 意图类型颜色映射 */
export const INTENT_COLORS: Record<IntentType, string> = {
  'DIAGNOSE_LOG': '#409eff',
  'QUERY_CODE': '#67c23a',
  'EXPLAIN_ERROR': '#e6a23c',
  'INTERVENE': '#f56c6c',
  'FOLLOW_UP': '#909399',
  'UNKNOWN': '#c0c4cc'
}

/** 实体信息 */
export interface IntentEntities {
  errorType?: string       // 错误类型，如 NPE
  focusArea?: string       // 关注领域，如认证逻辑
  className?: string       // 类名
  methodName?: string      // 方法名
  fileName?: string        // 文件名
  projectPath?: string     // 项目路径
  [key: string]: string | undefined  // 支持其他动态实体
}

/** 意图识别结果 */
export interface IntentResult {
  intent: IntentType
  confidence: number      // 置信度 0-1
  entities: IntentEntities
  rawInput: string        // 原始用户输入
  timestamp: string       // 识别时间
}

/** 对话消息 */
export interface DialogMessage {
  id: string
  sessionId: string
  role: 'user' | 'assistant' | 'system'
  content: string
  intent?: IntentResult    // 用户消息的意图识别结果（仅用户消息有）
  status: 'pending' | 'streaming' | 'completed' | 'error'
  createdAt: string
}

/** 对话会话 */
export interface DialogSession {
  id: string
  title: string | null
  status: 'active' | 'archived'
  context: DialogContext
  messages: DialogMessage[]
  createdAt: string
  updatedAt: string
}

/** 对话上下文 */
export interface DialogContext {
  currentIntent?: IntentType       // 当前意图
  currentEntities?: IntentEntities // 当前实体
  previousIntents?: IntentType[]   // 历史意图列表
  focusHistory?: string[]          // 关注点历史
  metadata?: Record<string, unknown>
}

/** 自然语言请求 */
export interface NaturalLanguageRequest {
  sessionId?: string
  userInput: string
  context?: DialogContext
  workingDirectory?: string
}

/** 自然语言流式响应 */
export interface NaturalLanguageStreamEvent {
  type: 'session' | 'intent' | 'output' | 'progress' | 'done' | 'error'
  data: string | IntentResult
}

/** 流式回调 */
export interface NaturalLanguageCallbacks {
  onSession?: (sessionId: string) => void
  onIntent?: (intent: IntentResult) => void
  onOutput?: (content: string) => void
  onProgress?: (progress: string) => void
  onDone?: (status: string) => void
  onError?: (error: string) => void
}

/** 诊断执行请求 */
export interface DiagnosisExecutionRequest {
  sessionId: string
  intent: IntentType
  entities: IntentEntities
}

/** 进度信息 */
export interface ProgressInfo {
  step: string
  status: 'started' | 'in_progress' | 'completed' | 'failed'
  message?: string
  percentage?: number
}