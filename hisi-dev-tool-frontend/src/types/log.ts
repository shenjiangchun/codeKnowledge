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

/** 错误摘要：后端返回的是对象，不是字符串 */
export interface ErrorSummary {
  errorType?: string
  rootCauseException?: string
  message?: string
  stackTracePreview?: string
}

/** 一条证据：堆栈帧、图谱命中、源码、调用关系等 */
export interface EvidenceAnchor {
  id: string
  kind?: string
  kindLabel?: string
  summary: string
  className?: string
  methodName?: string
  nodeId?: string
  filePath?: string
  exact?: boolean
}

/** 对单条根因假设的验证结果（v2 分析才有） */
export interface HypothesisVerification {
  id?: string
  title?: string
  confidence?: string
  // verified=已由调用图证实 stack_verified=堆栈已证实 at_throw_site=根因落在抛出点
  // unverified=未证实 unknown=无法判断
  chainStatus?: string
  rootCauseInKg?: boolean
  kept?: boolean
  citedIds?: string[]
  unknownIds?: string[]
  reasons?: string[]
}

/** 根本原因：v1 与 v2 都含 rootCauseType / markdown / confidence；v2 另带证据与验证结果 */
export interface RootCauseSection {
  rootCauseType?: string
  markdown?: string
  confidence?: string
  evidenceAnchors?: EvidenceAnchor[]
  verification?: {
    overall?: Record<string, unknown>
    hypotheses?: HypothesisVerification[]
  }
}

export interface FixSuggestion {
  description?: string
  priority?: string
  affectedCode?: string
  expectedEffect?: string
}

/** 关键堆栈帧 */
export interface StackFrameRef {
  className?: string
  methodName?: string
  lineNumber?: number | string
  fileName?: string
}

export interface DetailedAnalysisReport {
  reportId: string
  status: string
  // 四个内容字段都是结构化数据；旧报告可能存成字符串，所以保留字符串形式
  errorSummary?: ErrorSummary | string
  rootCause?: RootCauseSection | string
  fixSuggestions?: FixSuggestion[] | string
  codeSnippets?: StackFrameRef[] | string
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