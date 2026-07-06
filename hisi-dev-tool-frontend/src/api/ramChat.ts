import request from '@/utils/request'

export interface CreateSessionRequest {
  projectPaths: string[]
  projectName?: string
  initialQuestion?: string
}

export interface CreateSessionResponse {
  sessionId: string
  projectPath: string
  projectName: string
}

export interface SendMessageResponse {
  turnId: string
  status: 'STARTED' | 'DONE' | 'FAILED'
  errorMessage?: string
}

export interface ChatEvent {
  id: number
  sessionId: number
  seq: number
  type: string
  payload: string
  createdAt: number
}

export interface InterruptResponse {
  interrupted: boolean
  turnId?: string
  partialText?: string
}

export interface SessionSummary {
  sessionId: string
  projectName: string
  projectPath: string
  intent: string
  status: string
  createdAt: number
  lastActivityAt: number
  messageCount: number
}

export const ramChatApi = {
  createSession(req: CreateSessionRequest) {
    return request.post<CreateSessionResponse>('/ram/chat/sessions', req)
  },
  sendMessage(sid: string, text: string) {
    return request.post<SendMessageResponse>(`/ram/chat/${sid}/messages`, { text })
  },
  injectMessage(sid: string, content: string) {
    return request.post<void>(`/ram/chat/${sid}/inject`, { content })
  },
  getEvents(sid: string) {
    return request.get<ChatEvent[]>(`/ram/chat/${sid}/events`)
  },
  listSessions() {
    return request.get<SessionSummary[]>('/ram/chat/sessions')
  },
  renameSession(sid: string, title: string) {
    return request.patch<void>(`/ram/chat/${sid}/title`, { title })
  },
  deleteSession(sid: string) {
    return request.delete<void>(`/ram/chat/${sid}`)
  },
  interruptTurn(sid: string) {
    return request.post<InterruptResponse>(`/ram/chat/${sid}/interrupt`)
  }
}
