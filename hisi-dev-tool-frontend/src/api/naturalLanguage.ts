import request from '@/utils/request'
import type {
  NaturalLanguageRequest,
  NaturalLanguageCallbacks,
  DialogContext,
  IntentResult
} from '@/types'

// SSE版本需要的会话类型
interface DialogSession {
  id: string
  title: string | null
  status: 'active' | 'archived'
  context?: DialogContext
  messages: Array<{
    id: string
    role: 'user' | 'assistant'
    content: string
    createdAt: string
  }>
  createdAt: string
  updatedAt: string
}

/**
 * 自然语言交互 API
 * 对接后端 /api/dialog/* 路径
 */
export const naturalLanguageApi = {
  /**
   * 创建新对话会话
   * 后端: POST /api/dialog/sessions
   */
  createSession(workingDirectory?: string) {
    return request.post<DialogSession>('/dialog/sessions', {
      workingDirectory
    })
  },

  /**
   * 获取对话会话列表
   */
  listSessions(status?: 'active' | 'archived', page = 1, pageSize = 20) {
    return request.get<{ list: DialogSession[]; total: number }>('/dialog/sessions', {
      params: { status, page, pageSize }
    })
  },

  /**
   * 获取对话会话详情
   * 后端: GET /api/dialog/sessions/{sessionId}
   */
  getSession(sessionId: string) {
    return request.get<DialogSession>(`/dialog/sessions/${sessionId}`)
  },

  /**
   * 删除对话会话
   */
  deleteSession(sessionId: string) {
    return request.delete(`/dialog/sessions/${sessionId}`)
  },

  /**
   * 归档对话会话
   */
  archiveSession(sessionId: string) {
    return request.post(`/dialog/sessions/${sessionId}/archive`)
  },

  /**
   * 流式处理自然语言输入
   * 后端: POST /api/dialog/sessions/{sessionId}/messages/stream
   * 使用 SSE 接收意图识别和执行结果
   * @param data 请求参数
   * @param callbacks 回调函数
   * @returns abort 函数用于取消请求
   */
  streamProcess(
    data: NaturalLanguageRequest,
    callbacks: NaturalLanguageCallbacks
  ): () => void {
    const abortController = new AbortController()

    // 流式处理需要sessionId
    const sessionId = data.sessionId || 'new'
    const url = `/api/dialog/sessions/${sessionId}/messages/stream`

    fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        message: data.userInput,
        context: data.context,
        workingDirectory: data.workingDirectory
      }),
      signal: abortController.signal
    })
      .then(response => {
        if (!response.ok) {
          // 尝试解析错误响应
          const contentType = response.headers.get('content-type')
          if (contentType && contentType.includes('application/json')) {
            return response.json().then(errData => {
              const errorMsg = errData?.message || `HTTP error! status: ${response.status}`
              throw new Error(errorMsg)
            })
          }
          // 对于纯文本响应（如 SSE 错误）
          return response.text().then(text => {
            throw new Error(text || `HTTP error! status: ${response.status}`)
          })
        }

        const reader = response.body?.getReader()
        if (!reader) {
          throw new Error('No reader available')
        }

        const decoder = new TextDecoder()
        let buffer = ''
        let currentEventType = ''

        const readChunk = (): Promise<void> => {
          return reader.read().then(({ done, value }) => {
            if (done) {
              callbacks.onDone?.('completed')
              return
            }

            buffer += decoder.decode(value, { stream: true })

            // 解析 SSE 格式
            const lines = buffer.split('\n')
            buffer = lines.pop() || ''

            for (const line of lines) {
              if (line.startsWith('event:')) {
                currentEventType = line.slice(6).trim()
              } else if (line.startsWith('data:')) {
                const content = line.slice(5).trim()

                switch (currentEventType) {
                  case 'session':
                    callbacks.onSession?.(content)
                    break
                  case 'intent':
                    try {
                      const intent = JSON.parse(content) as IntentResult
                      callbacks.onIntent?.(intent)
                    } catch (e) {
                      console.warn('Failed to parse intent:', content)
                    }
                    break
                  case 'output':
                    callbacks.onOutput?.(content)
                    break
                  case 'progress':
                    callbacks.onProgress?.(content)
                    break
                  case 'done':
                    callbacks.onDone?.(content)
                    break
                  case 'error':
                    callbacks.onError?.(content)
                    break
                  default:
                    // 默认作为 output 处理
                    callbacks.onOutput?.(content)
                }
                currentEventType = ''
              }
            }

            return readChunk()
          })
        }

        return readChunk()
      })
      .catch(error => {
        if (error.name === 'AbortError') {
          console.log('Request aborted')
        } else {
          callbacks.onError?.(error.message)
        }
      })

    // 返回 abort 函数
    return () => {
      abortController.abort()
    }
  },

  /**
   * 发送用户干预
   * 后端: POST /api/dialog/sessions/{sessionId}/interventions
   */
  sendIntervention(sessionId: string, intervention: string) {
    return request.post(`/dialog/sessions/${sessionId}/interventions`, {
      intervention
    })
  },

  /**
   * 获取对话上下文
   * 后端: GET /api/dialog/context/{sessionId}
   */
  getContext(sessionId: string) {
    return request.get<DialogContext>(`/dialog/context/${sessionId}`)
  },

  /**
   * 更新对话上下文
   */
  updateContext(sessionId: string, context: Partial<DialogContext>) {
    return request.put(`/dialog/context/${sessionId}`, context)
  },

  /**
   * 快速意图识别（不执行）
   * 后端: POST /api/dialog/intent/parse
   */
  quickIntentRecognition(userInput: string, context?: DialogContext) {
    return request.post<IntentResult>('/dialog/intent/parse', {
      userInput,
      context
    })
  },

  /**
   * 获取会话状态
   * 后端: GET /api/dialog/sessions/{sessionId}/status
   */
  getSessionStatus(sessionId: string) {
    return request.get(`/dialog/sessions/${sessionId}/status`)
  },

  /**
   * 发送消息（非流式）
   * 后端: POST /api/dialog/sessions/{sessionId}/messages
   */
  sendMessage(sessionId: string, message: string) {
    return request.post(`/dialog/sessions/${sessionId}/messages`, {
      message
    })
  }
}