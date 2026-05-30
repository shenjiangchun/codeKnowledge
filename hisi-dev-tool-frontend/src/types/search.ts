/**
 * 语义搜索相关类型定义
 */

// 搜索请求
export interface SemanticSearchRequest {
  query: string
  projectPath?: string
  projectPaths?: string[] // 多项目搜索
  limit?: number
  threshold?: number // 相似度阈值 0-1
  filters?: SearchFilters
}

// 搜索过滤条件
export interface SearchFilters {
  filePattern?: string // 文件名模式
  language?: string // 编程语言
  scope?: 'class' | 'method' | 'function' | 'variable' | 'comment' | undefined
  author?: string
  dateRange?: {
    start: string
    end: string
  }
}

// 搜索结果
export interface SemanticSearchResult {
  id: string
  nodeId: string // 语义节点ID
  type: 'class' | 'method' | 'function' | 'variable' | 'comment' | 'code_block'
  name: string
  filePath: string
  lineNumber: number
  endLineNumber: number
  codeSnippet: string
  relevanceScore: number // 相关度 0-1
  embeddingVector?: number[] // 向量嵌入（可选）
  metadata?: SearchResultMetadata
}

// 搜索结果元数据
export interface SearchResultMetadata {
  className?: string
  methodName?: string
  signature?: string
  returnType?: string
  parameters?: string[]
  annotations?: string[]
  documentation?: string
  dependencies?: string[]
}

// 搜索响应
export interface SemanticSearchResponse {
  results: SemanticSearchResult[]
  total: number
  queryTime: number // 查询耗时(ms)
  suggestedQueries?: string[] // 建议的相似查询
}

// 代码节点（用于结果详情展示）
export interface CodeNode {
  id: string
  type: string
  name: string
  filePath: string
  content: string
  startLine: number
  endLine: number
  relations: CodeRelation[]
}

// 代码关系
export interface CodeRelation {
  id: string
  sourceId: string
  targetId: string
  type: 'calls' | 'implements' | 'extends' | 'references' | 'depends_on'
  weight: number
}

// WebSocket 消息类型
export interface SearchClientMessage {
  action: 'search' | 'refine' | 'cancel' | 'ping'
  query?: string
  requestId?: string
  filters?: SearchFilters
}

export interface SearchServerMessage {
  type: 'result' | 'partial_result' | 'suggestion' | 'error' | 'pong'
  requestId: string
  results?: SemanticSearchResult[]
  suggestion?: string
  error?: string
  progress?: number // 搜索进度 0-100
}

// 搜索历史记录
export interface SearchHistoryItem {
  id: string
  query: string
  timestamp: number
  resultCount: number
  favorite: boolean
}