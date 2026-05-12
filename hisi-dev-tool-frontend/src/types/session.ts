/**
 * Claude 会话相关类型定义
 */

/** 会话状态 */
export type SessionStatus = 'active' | 'archived'

/** 场景类型 */
export type SceneType = 'log-analysis' | 'code-analysis' | 'trace-analysis' | 'impact-analysis' | 'free-chat'

/** 场景名称映射 */
export const SCENE_NAMES: Record<SceneType, string> = {
  'log-analysis': '日志分析',
  'code-analysis': '代码分析',
  'trace-analysis': '调用链分析',
  'impact-analysis': '影响分析',
  'free-chat': '自由对话'
}

/** 会话接口 */
export interface Session {
  id: string
  title: string | null
  scene: SceneType
  status: SessionStatus
  metadata: string | null
  workingDirectory: string | null
  claudeSessionCode: string | null
  createdAt: string
  updatedAt: string
}

/** 消息接口 */
export interface Message {
  id: number
  sessionId: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

/** 提示词模板接口 */
export interface PromptTemplate {
  templateKey: string
  name: string
  content: string
  variables: string
  description: string
  createdAt: string
  updatedAt: string
}

/** 会话详情响应 */
export interface SessionDetailResponse {
  session: Session
  messages: Message[]
}

/** 会话列表响应 */
export interface SessionListResponse {
  list: Session[]
  total: number
  page: number
  pageSize: number
}

/** 通用对话请求 */
export interface UniversalChatRequest {
  sessionId?: string
  prompt: string
  scene?: SceneType
  metadata?: Record<string, unknown>
  workingDirectory?: string
}

/** 流式回调 */
export interface StreamCallbacks {
  onSession?: (sessionId: string) => void
  onOutput?: (content: string) => void
  onDone?: (status: string) => void
  onError?: (error: string) => void
}

/** 更新会话请求 */
export interface UpdateSessionRequest {
  title?: string
}

/** 更新模板请求 */
export interface UpdatePromptRequest {
  content: string
  variables?: string
}

/** 渲染模板请求 */
export interface RenderTemplateRequest {
  [key: string]: string
}
