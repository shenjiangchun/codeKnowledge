import request from '@/utils/request'
import type { LaunchRequest, ExecuteRequest } from '@/types/apm'

export const apmApi = {
  launch(data: LaunchRequest) {
    return request.post('/apm/launch', data)
  },

  execute(data: ExecuteRequest) {
    return request.post('/apm/execute', data)
  },

  stop(sessionId: string) {
    return request.post('/apm/stop', { sessionId })
  },

  getSessions(limit = 20) {
    return request.get('/apm/sessions', { params: { limit } })
  },

  getSession(id: string) {
    return request.get(`/apm/session/${id}`)
  },

  getSpans(sessionId: string) {
    return request.get(`/apm/spans/${sessionId}`)
  },

  getTrace(traceId: string) {
    return request.get(`/apm/trace/${traceId}`)
  },

  getReport(sessionId: string) {
    return request.get(`/apm/report/${sessionId}`)
  },

  getProcessOutput(sessionId: string, maxLines = 100) {
    return request.get(`/apm/process-output/${sessionId}`, { params: { maxLines } })
  },
}
