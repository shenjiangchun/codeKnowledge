import request from '@/utils/request'
import type {
  LaunchRequest,
  LaunchResult,
  ExecuteRequest,
  ExecuteResult,
  ApmSession,
  ApmSpan,
  DebugReport,
} from '@/types/apm'

/**
 * APM Debug API module.
 *
 * Note: The Axios response interceptor in utils/request.ts unwraps
 * `ApiResponse.data`, so the runtime return value is the inner payload.
 * We cast once here at the API boundary so callers get proper types
 * without needing `as unknown as`.
 */
export const apmApi = {
  launch(data: LaunchRequest): Promise<LaunchResult> {
    return request.post('/apm/launch', data) as unknown as Promise<LaunchResult>
  },

  execute(data: ExecuteRequest): Promise<ExecuteResult> {
    return request.post('/apm/execute', data) as unknown as Promise<ExecuteResult>
  },

  stop(sessionId: string): Promise<void> {
    return request.post('/apm/stop', { sessionId }) as unknown as Promise<void>
  },

  getSessions(limit = 20): Promise<ApmSession[]> {
    return request.get('/apm/sessions', { params: { limit } }) as unknown as Promise<ApmSession[]>
  },

  getSession(id: string): Promise<ApmSession> {
    return request.get(`/apm/session/${id}`) as unknown as Promise<ApmSession>
  },

  getSpans(sessionId: string): Promise<ApmSpan[]> {
    return request.get(`/apm/spans/${sessionId}`) as unknown as Promise<ApmSpan[]>
  },

  getTrace(traceId: string): Promise<ApmSpan[]> {
    return request.get(`/apm/trace/${traceId}`) as unknown as Promise<ApmSpan[]>
  },

  getReport(sessionId: string): Promise<DebugReport> {
    return request.get(`/apm/report/${sessionId}`) as unknown as Promise<DebugReport>
  },

  getProcessOutput(sessionId: string, maxLines = 100): Promise<string> {
    return request.get(`/apm/process-output/${sessionId}`, { params: { maxLines } }) as unknown as Promise<string>
  },
}
