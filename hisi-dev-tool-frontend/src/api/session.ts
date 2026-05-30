import request from '@/utils/request'
import type {
  SessionDetailResponse,
  SessionListResponse,
  UpdateSessionRequest
} from '@/types/session'

export const sessionApi = {
  /**
   * 获取会话列表
   */
  list(params?: { status?: string; page?: number; pageSize?: number }) {
    return request.get<SessionListResponse>('/sessions', { params })
  },

  /**
   * 获取会话详情
   */
  get(sessionId: string) {
    return request.get<SessionDetailResponse>(`/sessions/${sessionId}`)
  },

  /**
   * 更新会话
   */
  update(sessionId: string, data: UpdateSessionRequest) {
    return request.patch<void>(`/sessions/${sessionId}`, data)
  },

  /**
   * 删除会话
   */
  delete(sessionId: string) {
    return request.delete<void>(`/sessions/${sessionId}`)
  },

  /**
   * 归档会话
   */
  archive(sessionId: string) {
    return request.post<void>(`/sessions/${sessionId}/archive`)
  },

  /**
   * 导出会话
   */
  export(sessionId: string, format: 'markdown' | 'json' = 'markdown') {
    return request.get<Blob>(`/sessions/${sessionId}/export`, {
      params: { format },
      responseType: 'blob'
    })
  },

  /**
   * 清除会话消息历史
   */
  clearMessages(sessionId: string) {
    return request.delete<void>(`/sessions/${sessionId}/messages`)
  }
}
