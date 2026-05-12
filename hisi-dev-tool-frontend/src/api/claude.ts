import request from '@/utils/request'
import type { UniversalChatRequest, StreamCallbacks as StreamCallbacksType } from '@/types/session'

export interface AnalyzeLogRequest {
  /** 错误摘要（日志头+异常类型+消息） */
  errorMessage: string
  /** 异常类型，如 NullPointerException */
  errorType?: string
  /** 异常详细消息 */
  errorMessageDetail?: string
  /** 结构化的堆栈信息 */
  stackTrace?: string
  /** Caused by 链 */
  causedBy?: string
  /** 项目路径或服务名 */
  projectPath?: string
  /** 额外上下文 */
  additionalContext?: string
}

export interface AnalyzeCodeRequest {
  code: string
  language?: string
  projectPath?: string
  additionalContext?: string
}

export interface ClaudeAnalysisResult {
  errorType: string
  rootCause: string
  affectedCode: string[]
  fixSuggestions: string[]
  confidence: number
  timestamp: string
  analysisType: string
  requestId: string
}

export interface ChatRequest {
  sessionId: string
  message: string
}

// 流式分析回调 (兼容旧接口)
export type StreamCallbacks = StreamCallbacksType

export const claudeApi = {
  // 非流式分析（保留兼容）
  analyzeLog(data: AnalyzeLogRequest) {
    return request.post<ClaudeAnalysisResult>('/claude/analyze', data)
  },

  analyzeCode(data: AnalyzeCodeRequest) {
    return request.post<ClaudeAnalysisResult>('/claude/analyze-code', data)
  },

  healthCheck() {
    return request.get<boolean>('/claude/health')
  },

  /**
   * 流式分析日志 - 使用 SSE
   * @param params 分析参数
   * @param callbacks 回调函数
   * @returns 返回一个 abort 函数用于取消请求
   */
  streamAnalyze(
    params: {
      errorMessage: string
      errorType?: string
      errorMessageDetail?: string
      stackTrace?: string
      causedBy?: string
      projectPath?: string
    },
    callbacks: StreamCallbacks
  ): () => void {
    const queryParams = new URLSearchParams()
    queryParams.append('errorMessage', params.errorMessage)
    if (params.errorType) queryParams.append('errorType', params.errorType)
    if (params.errorMessageDetail) queryParams.append('errorMessageDetail', params.errorMessageDetail)
    if (params.stackTrace) queryParams.append('stackTrace', params.stackTrace)
    if (params.causedBy) queryParams.append('causedBy', params.causedBy)
    if (params.projectPath) queryParams.append('projectPath', params.projectPath)

    const url = `/api/claude/stream?${queryParams.toString()}`
    const eventSource = new EventSource(url)

    eventSource.addEventListener('session', (event) => {
      callbacks.onSession?.(event.data)
    })

    eventSource.addEventListener('output', (event) => {
      callbacks.onOutput?.(event.data)
    })

    eventSource.addEventListener('done', (event) => {
      callbacks.onDone?.(event.data)
      eventSource.close()
    })

    eventSource.addEventListener('error', (event: any) => {
      if (event.data) {
        callbacks.onError?.(event.data)
      }
      eventSource.close()
    })

    eventSource.onerror = () => {
      callbacks.onError?.('Connection error')
      eventSource.close()
    }

    // 返回 abort 函数
    return () => {
      eventSource.close()
    }
  },

  /**
   * 流式聊天 - 使用 fetch + POST
   * @param data 聊天请求
   * @param callbacks 回调函数
   */
  async streamChat(
    data: ChatRequest,
    callbacks: StreamCallbacks
  ): Promise<void> {
    try {
      const response = await fetch('/api/claude/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(data),
      })

      if (!response.ok) {
        // 尝试解析错误响应
        const contentType = response.headers.get('content-type')
        if (contentType && contentType.includes('application/json')) {
          const errData = await response.json()
          throw new Error(errData?.message || `HTTP error! status: ${response.status}`)
        }
        const text = await response.text()
        throw new Error(text || `HTTP error! status: ${response.status}`)
      }

      const reader = response.body?.getReader()
      if (!reader) {
        throw new Error('No reader available')
      }

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // 解析 SSE 格式
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            callbacks.onOutput?.(line.slice(5).trim())
          } else if (line.startsWith('event:session')) {
            // 下一行是 session 数据
          } else if (line.startsWith('event:done')) {
            callbacks.onDone?.('completed')
          } else if (line.startsWith('event:error')) {
            callbacks.onError?.(line.slice(5).trim())
          }
        }
      }

      callbacks.onDone?.('completed')
    } catch (error: any) {
      callbacks.onError?.(error.message)
    }
  },

  /**
   * 结束会话（返回 Claude 会话码用于恢复）
   */
  endSession(sessionId: string) {
    return request.delete(`/claude/session/${sessionId}`)
  },

  /**
   * 恢复会话
   */
  resumeSession(sessionId: string) {
    return request.post(`/claude/session/${sessionId}/resume`)
  },

  /**
   * 获取会话的 Claude 会话码
   */
  getSessionCode(sessionId: string) {
    return request.get(`/claude/session/${sessionId}/code`)
  },

  /**
   * 通用对话接口 - 支持多种场景
   * @param data 通用对话请求
   * @param callbacks 回调函数
   * @returns 返回 sessionId 的 Promise
   */
  async universalChat(
    data: UniversalChatRequest,
    callbacks: StreamCallbacks
  ): Promise<string> {
    return new Promise((resolve, reject) => {
      let sessionId = data.sessionId || ''
      let buffer = ''
      let currentEventType = ''

      fetch('/api/claude/universal-chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(data),
      })
        .then(async response => {
          if (!response.ok) {
            // 尝试解析错误响应
            const contentType = response.headers.get('content-type')
            if (contentType && contentType.includes('application/json')) {
              const errData = await response.json()
              throw new Error(errData?.message || `HTTP error! status: ${response.status}`)
            }
            // 对于纯文本响应（如 SSE 错误）
            const text = await response.text()
            throw new Error(text || `HTTP error! status: ${response.status}`)
          }

          const reader = response.body?.getReader()
          if (!reader) {
            throw new Error('No reader available')
          }

          const decoder = new TextDecoder()

          const readChunk = (): Promise<void> => {
            return reader.read().then(({ done, value }) => {
              if (done) {
                callbacks.onDone?.('completed')
                resolve(sessionId)
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

                  if (currentEventType === 'session') {
                    // 收到 session ID
                    sessionId = content
                    callbacks.onSession?.(content)
                  } else if (currentEventType === 'done') {
                    callbacks.onDone?.(content)
                  } else if (currentEventType === 'error') {
                    callbacks.onError?.(content)
                  } else {
                    // 默认是 output 事件
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
          callbacks.onError?.(error.message)
          reject(error)
        })
    })
  }
}
