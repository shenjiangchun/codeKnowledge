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
  reportId: number
  status: string
  createdAt: string
}

export interface Report {
  reportId: number
  status: string
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
  reportId: number
  status: string
  errorSummary?: string
  rootCause?: string
  fixSuggestions?: string
  codeSnippets?: string
  createdAt: string
  updatedAt: string
}

export interface LogQueryResponse {
  logs: LogEntry[]
  total: number
}