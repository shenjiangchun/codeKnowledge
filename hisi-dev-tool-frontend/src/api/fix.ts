import request from '@/utils/request'

export interface FixSession {
  id: number
  reportId: number
  chatSessionId: number | null
  sessionType: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'PAUSED'
  worktreePath: string | null
  branchName: string | null
  commitHash: string | null
  throwPointSig: string | null
  errorMsg: string | null
  createdAt: number
  updatedAt: number
}

export interface FixChatMessage {
  id: number
  role: 'user' | 'assistant' | 'system'
  content: string
  metadata?: Record<string, unknown>
  createdAt: number
}

export const fixApi = {
  /** Start a new fix session for a report. Returns the sessionId (Long). */
  startSession(reportId: number) {
    return request.post<number>('/fix/sessions', null, { params: { reportId } })
  },

  /** Send a follow-up message in an existing fix session. */
  followUp(sessionId: number, message: string) {
    return request.post<void>(`/fix/sessions/${sessionId}/follow-up`, message, {
      headers: { 'Content-Type': 'text/plain' }
    })
  },

  /** Get chat history for a fix session. */
  getHistory(sessionId: number) {
    return request.get<FixChatMessage[]>(`/fix/sessions/${sessionId}/history`)
  },

  /** Get fix session details. */
  getSession(sessionId: number) {
    return request.get<FixSession>(`/fix/sessions/${sessionId}`)
  },

  /** List fix sessions for a report. */
  listByReport(reportId: number) {
    return request.get<FixSession[]>('/fix/sessions', { params: { reportId } })
  }
}
