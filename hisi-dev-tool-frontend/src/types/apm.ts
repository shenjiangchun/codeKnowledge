// APM Debug Types

export type ApmSessionStatus = 'IDLE' | 'LAUNCHING' | 'READY' | 'EXECUTING' | 'STREAMING' | 'COMPLETE' | 'ERROR'

export type ProcessStatus = 'idle' | 'launching' | 'ready' | 'running' | 'stopped'

// ============================================================
// KG Project & Entry Point Types
// ============================================================

export interface KgProject {
  projectPath: string
  label: string // short display name extracted from path
}

export interface KgEntryPoint {
  nodeId: string
  entryType: string        // HTTP, SCHEDULED, MQ_CONSUMER, FEIGN_CLIENT
  entryKey: string         // e.g. "GET /api/users"
  entryInfo: string | null // JSON: {className, methodName, returnType, parameters}
  methodNodeId?: string    // link to MethodNode
  projectPath: string
  // Parsed from entryKey for convenience
  httpMethod?: string      // GET, POST, PUT, DELETE, PATCH
  httpPath?: string        // /api/users
  // Parsed from entryInfo JSON for convenience
  parsedInfo?: ParsedEntryInfo
}

/** Parsed from entryInfo JSON string */
export interface ParsedEntryInfo {
  className: string
  methodName: string
  returnType: string
  parameters: KgMethodParam[]
}

export interface KgMethodParam {
  name: string
  type: string
  annotations: string[]    // @RequestBody, @PathVariable, @RequestParam, etc.
  aliasName?: string       // annotation value e.g. @PathVariable("userId") → "userId"
  defaultValue?: string    // @RequestParam(defaultValue = "10")
  required?: boolean       // @RequestParam(required = false)
}

export interface KgMethodDetail {
  nodeId: string
  className: string
  methodName: string
  signature: string
  filePath: string
  startLine: number
  endLine: number
  complexity: number
  thrownExceptions: string[]
  caughtExceptions: string[]
  methodBody: string
  projectPath: string
  parameters?: KgMethodParam[]
}

// ============================================================
// Session & Request Types
// ============================================================

export interface ApmSession {
  id: string
  projectPath: string
  serviceName: string
  targetPort: number
  status: string
  createdAt: number
  finishedAt: number | null
}

export type InstrumentationMode = 'PRECISE' | 'FULL_PROJECT' | 'NONE'

export interface LaunchRequest {
  projectPath: string
  targetPort?: number
  serviceName?: string
  /**
   * Optional KG nodeId of the entry method. When provided, backend builds
   * OTEL_INSTRUMENTATION_METHODS_INCLUDE from the KG callee tree so the
   * OTel Java agent captures method-level spans matching the expected chain.
   */
  entryNodeId?: string
  /**
   * Bytecode instrumentation strategy. Defaults (server-side):
   * - PRECISE when entryNodeId is set
   * - FULL_PROJECT otherwise
   */
  instrumentationMode?: InstrumentationMode
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

export interface RequestConfig {
  method: string
  url: string
  headers: Record<string, string>
  body: string
  queryParams: Array<{ key: string; value: string; enabled: boolean }>
}

// ============================================================
// Span & Trace Types
// ============================================================

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
  attributes?: Record<string, unknown>
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

export interface TraceNode {
  span: ApmSpan
  children: TraceNode[]
  depth: number
}

// ============================================================
// Report Types
// ============================================================

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

// ============================================================
// WebSocket Types
// ============================================================

export interface WsMessage {
  type: string
  timestamp: number
  [key: string]: unknown
}

// ============================================================
// Utility Functions
// ============================================================

/** Parse "GET /api/users" into { method, path } */
export function parseEntryKey(entryKey: string): { httpMethod: string; httpPath: string } | null {
  const match = entryKey.match(/^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s+(.+)$/)
  if (match) {
    return { httpMethod: match[1], httpPath: match[2] }
  }
  return null
}

/** Parse entryInfo JSON string into structured data */
export function parseEntryInfo(entryInfo: string | null | undefined): ParsedEntryInfo | null {
  if (!entryInfo) return null
  try {
    const parsed = JSON.parse(entryInfo)
    return {
      className: parsed.className || '',
      methodName: parsed.methodName || '',
      returnType: parsed.returnType || 'void',
      parameters: Array.isArray(parsed.parameters)
        ? parsed.parameters.map((p: Record<string, unknown>) => ({
            name: (p.name as string) || '',
            type: (p.type as string) || 'String',
            annotations: Array.isArray(p.annotations) ? p.annotations as string[] : [],
            aliasName: (p.aliasName as string) || undefined,
            defaultValue: (p.defaultValue as string) || undefined,
            required: p.required as boolean | undefined,
          }))
        : [],
    }
  } catch {
    return null
  }
}

/** Extract short project name from full path */
export function extractProjectLabel(projectPath: string): string {
  const parts = projectPath.replace(/\\/g, '/').split('/')
  return parts[parts.length - 1] || projectPath
}

/** Build a tree of TraceNodes from flat span array */
export function buildTraceTree(spans: ApmSpan[]): TraceNode[] {
  const nodeMap = new Map<string, TraceNode>()
  const roots: TraceNode[] = []

  // Create all nodes
  for (const span of spans) {
    nodeMap.set(span.spanId, { span, children: [], depth: 0 })
  }

  // Build tree
  for (const span of spans) {
    const node = nodeMap.get(span.spanId)!
    if (span.parentSpanId && nodeMap.has(span.parentSpanId)) {
      const parent = nodeMap.get(span.parentSpanId)!
      parent.children.push(node)
      node.depth = parent.depth + 1
    } else {
      roots.push(node)
    }
  }

  return roots
}
