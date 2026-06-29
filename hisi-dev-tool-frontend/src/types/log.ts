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
  // v2 深度分析字段
  causalChain?: CausalChainStep[]
  multiFactorAnalysis?: MultiFactorAnalysis
  timeline?: TimelinePhase[]
}

export interface LogQueryResponse {
  logs: LogEntry[]
  total: number
}

// v2: 深度分析类型

/** 因果链步骤 */
export interface CausalChainStep {
  step: number
  event: string
  mechanism: string
  evidence: string
}

/** 多因素叠加分析 */
export interface MultiFactorAnalysis {
  primaryFactor: string
  contributingFactors: Array<{
    factor: string
    interaction: string
  }>
  cascadeEffect: string
}

/** 时序重建阶段 */
export interface TimelinePhase {
  phase: string
  event: string
  duration: string
  evidence: string
}