// APM Debug Types

export type ApmSessionStatus = 'IDLE' | 'LAUNCHING' | 'READY' | 'EXECUTING' | 'STREAMING' | 'COMPLETE' | 'ERROR'

export interface ApmSession {
  id: string
  projectPath: string
  serviceName: string
  targetPort: number
  status: string
  createdAt: number
  finishedAt: number | null
}

export interface LaunchRequest {
  projectPath: string
  targetPort?: number
  serviceName?: string
}

export interface LaunchResult {
  sessionId: string
  serviceName: string
  targetPort: number
  status: string
}

export interface ExecuteRequest {
  sessionId: string
  method: string
  path: string
  body?: string
  headers?: Record<string, string>
}

export interface ExecuteResult {
  sessionId: string
  httpStatus: number
  responseHeaders: Record<string, string>
  responseBody: string
  durationMs: number
}

export interface ApmSpan {
  spanId: string
  parentSpanId: string | null
  traceId: string
  operationName: string
  serviceName: string
  spanKind: string
  startTimeNs: number
  endTimeNs: number
  durationMs: number
  statusCode: string
  statusMessage: string | null
  className: string | null
  methodName: string | null
  kgNodeId: string | null
  kgMatchLevel: number
}

export interface SpanNode {
  spanId: string
  parentSpanId: string | null
  operationName: string
  className: string | null
  methodName: string | null
  serviceName: string
  spanKind: string
  durationMs: number
  statusCode: string
  statusMessage: string | null
  kgNodeId: string | null
  kgMatchLevel: number
  children: SpanNode[]
}

export interface Hotspot {
  spanId: string
  operationName: string
  className: string | null
  methodName: string | null
  durationMs: number
  percentOfTotal: number
  kgNodeId: string | null
}

export interface ErrorPoint {
  spanId: string
  operationName: string
  className: string | null
  methodName: string | null
  statusMessage: string | null
  exceptionType: string | null
  exceptionMessage: string | null
  kgNodeId: string | null
}

export interface DebugReport {
  sessionId: string
  traceId: string | null
  entryPoint: string | null
  success: boolean
  totalDurationMs: number
  spanTree: SpanNode[]
  hotspots: Hotspot[]
  errors: ErrorPoint[]
  totalSpanCount: number
  matchedSpanCount: number
}

export interface WsMessage {
  type: string
  timestamp: number
  [key: string]: unknown
}
