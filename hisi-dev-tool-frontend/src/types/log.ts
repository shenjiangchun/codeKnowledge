export interface LogEntry {
  id: number | null
  timestamp: string | null
  level: string | null
  message: string | null
  rawContent: string | null
  stackTrace: string | null
  traceId: string | null
  serviceName: string | null
  podName: string | null
  hostname: string | null
  containerName: string | null
  namespace: string | null
  logSource: string | null
  rawFields: Record<string, any> | null
  errorType: string | null
  hasStackTrace: boolean
  lineCount: number
}

export interface LogQueryDto {
  appId?: string
  logLevel?: string
  keyword?: string
  startTime?: Date | null
  endTime?: Date | null
  page?: number
  pageSize?: number
  size?: number
  dslQuery?: string  // 自定义 DSL 查询
  errorOnly?: boolean
  traceId?: string
  contentContains?: string
  sortBy?: string
  sortOrder?: string
}

export interface LogAnalyzeRequest {
  message?: string
  stackTrace?: string
  errorType?: string
  traceId?: string
  serviceName?: string
  userId?: string
}

export interface AnalyzeTaskResponse {
  reportId: string
  status: string
  createdAt: string
}

export interface Report {
  reportId: string
  status: string
  errorType?: string
  serviceName?: string
  occurrenceCount?: number
  createdAt: string
  updatedAt: string
  errorSummary?: string
  rootCause?: string
  fixSuggestions?: string
  codeSnippets?: string
  userId?: string
  /** 前端临时状态：重新分析中 */
  reanalyzing?: boolean
}

export interface ReportListResponse {
  total: number
  page: number
  pageSize: number
  list: Report[]
}

export interface DetailedAnalysisReport {
  reportId: string
  status: string
  errorSummary?: string
  rootCause?: string
  fixSuggestions?: string
  codeSnippets?: string
  createdAt: string
  updatedAt: string
  occurrenceCount?: number  // 出现次数（同类错误合并计数）
  patternType?: string
  patternConfidence?: string | number
  analysisVersion?: string
}

export interface LogQueryResponse {
  logs: LogEntry[]
  total: number
}

/** 报告处理进度状态（轮询接口返回） */
export interface ReportStatus {
  status: string
  progress: number
  stage?: string
  etaSeconds?: number
}

// ========== 日志分析实时节点事件 (WebSocket) ==========

/** DAG 节点实时事件 */
export interface LogNodeEvent {
  reportId: number
  type: 'NODE_START' | 'NODE_COMPLETE' | 'NODE_ERROR' | 'DAG_COMPLETE'
  nodeName: string
  timestamp: number
  payload?: {
    durationMs?: number
    summary?: unknown
    error?: string
    totalDurationMs?: number
  }
}

// ========== 追问 Follow-up ==========

/** 追问启动响应 */
export interface FollowupStartResponse {
  sessionId: string
  status: string
}

/** 追问消息发送响应 */
export interface FollowupContinueResponse {
  sessionId: string
  status: string
}

/** 追问会话详情 */
export interface FollowupSessionDetail {
  sessionId: string
  reportId: string
  messages: Array<{ role: string; content: string; createdAt: string }>
  status: string
  createdAt: string
  updatedAt: string
}