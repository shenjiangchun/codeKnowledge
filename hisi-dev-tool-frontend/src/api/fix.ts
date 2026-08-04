import request from '@/utils/request'
import type { ChatEvent } from '@/api/ramChat'

export interface FixSession {
  id: string
  reportId: string
  chatSessionId: string | null
  sessionType: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'PAUSED' | 'REPRO_FAILED' | 'TEST_NOT_PASSED' | 'ERROR'
  worktreePath: string | null
  branchName: string | null
  commitHash: string | null
  throwPointSig: string | null
  errorMsg: string | null
  tenantId: string
  createBy: string
  updateBy: string
  delFlag: number
  createdAt: number
  updatedAt: number
}

export const fixApi = {
  /** Start a new fix session for a report. Returns the sessionId (String). */
  startSession(reportId: string) {
    return request.post<string>('/fix/sessions', null, { params: { reportId } })
  },

  /** Send a follow-up message in an existing fix session. */
  followUp(sessionId: string, message: string) {
    return request.post<void>(`/fix/sessions/${sessionId}/follow-up`, message, {
      headers: { 'Content-Type': 'text/plain' }
    })
  },

  /** Get chat history for a fix session as RAM-chat-compatible events. */
  getHistory(sessionId: string) {
    return request.get<ChatEvent[]>(`/fix/sessions/${sessionId}/history`)
  },

  /** Get fix session details. */
  getSession(sessionId: string) {
    return request.get<FixSession>(`/fix/sessions/${sessionId}`)
  },

  /** List fix sessions for a report. */
  listByReport(reportId: string) {
    return request.get<FixSession[]>('/fix/sessions', { params: { reportId } })
  }
}
