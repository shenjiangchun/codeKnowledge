import request from '@/utils/request'

export interface CommitInfo {
  commitId: string
  shortId: string
  message: string
  shortMessage: string
  authorName: string
  authorEmail: string
  authorDate: string
  committerName: string
  committerEmail: string
  committerDate: string
  parentIds: string[]
  changedFiles: string[]
}

export interface FileDiff {
  changeType: string
  oldPath: string
  newPath: string
  diff: string
}

export interface CommitDetail {
  commitId: string
  shortId: string
  message: string
  authorName: string
  authorEmail: string
  authorDate: string
  committerName: string
  committerEmail: string
  committerDate: string
  diffs: FileDiff[]
}

export interface AnalyzeRequest {
  projectPath: string
  commitIds: string[]
  analysisTypes?: string[]
}

export interface StreamCallbacks {
  onSession?: (sessionId: string) => void
  onOutput?: (line: string) => void
  onDone?: (status: string) => void
  onError?: (error: string) => void
}

export const codeAnalysisApi = {
  /**
   * 获取提交列表
   */
  getCommits(path: string, limit: number = 20): Promise<{ data: CommitInfo[] }> {
    return request.get('/code-analysis/commits', {
      params: { path, limit }
    })
  },

  /**
   * 获取提交详情
   */
  getCommitDetail(commitId: string, path: string): Promise<{ data: CommitDetail }> {
    return request.get(`/code-analysis/commit/${commitId}`, {
      params: { path }
    })
  },

  /**
   * 获取文件内容
   */
  getFileContent(path: string, filePath: string, commitId?: string): Promise<{ data: string }> {
    return request.get('/code-analysis/file', {
      params: { path, filePath, commitId }
    })
  },

  /**
   * 更新项目 (git pull)
   */
  updateProject(path: string): Promise<{ data: { successful: boolean; branch: string } }> {
    return request.post('/code-analysis/update', { path })
  },

  /**
   * 流式分析提交
   */
  analyzeCommitsStream(
    request: AnalyzeRequest,
    callbacks: StreamCallbacks
  ): () => void {
    // 使用 fetch 进行 POST 请求并处理 SSE 响应
    const controller = new AbortController()

    fetch('/api/code-analysis/analyze-stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal: controller.signal
    })
      .then(async response => {
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
        if (!reader) throw new Error('No reader')

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
            if (line.startsWith('event:')) {
              // 事件类型
            } else if (line.startsWith('data:')) {
              const data = line.slice(5).trim()
              // 根据前一个 event 处理
              callbacks.onOutput?.(data)
            }
          }
        }

        callbacks.onDone?.('completed')
      })
      .catch(error => {
        if (error.name !== 'AbortError') {
          callbacks.onError?.(error.message)
        }
      })

    return () => controller.abort()
  },

  /**
   * 流式聊天
   */
  async streamChat(
    sessionId: string,
    message: string,
    callbacks: StreamCallbacks
  ): Promise<void> {
    try {
      const response = await fetch('/api/code-analysis/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message })
      })

      const reader = response.body?.getReader()
      if (!reader) throw new Error('No reader')

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            callbacks.onOutput?.(line.slice(5).trim())
          }
        }
      }

      callbacks.onDone?.('completed')
    } catch (error: any) {
      callbacks.onError?.(error.message)
    }
  },

  /**
   * 关闭会话
   */
  closeSession(sessionId: string): Promise<{ data: string }> {
    return request.delete(`/code-analysis/session/${sessionId}`)
  }
}