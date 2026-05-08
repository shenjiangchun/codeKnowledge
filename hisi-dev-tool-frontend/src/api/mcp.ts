import request from '@/utils/request'

export interface McpInfo {
  version: string
  name: string
  description: string
  tools: McpTool[]
  skills: McpSkill[]
}

export interface McpTool {
  name: string
  description: string
  params: string[]
}

export interface McpSkill {
  name: string
  title: string
  trigger: string
}

export interface InstallScripts {
  windows: string
  unix: string
}

export interface McpStatus {
  installed: boolean
  message?: string
  mcpDir?: string
  packageJsonExists?: boolean
  nodeModulesExists?: boolean
  distExists?: boolean
  claudeConfigPath?: string
  claudeConfigExists?: boolean
  // Claude Code CLI 相关
  claudeCodeInstalled?: boolean
  claudeType?: string
  mcpConfigured?: boolean
}

export interface InstallCallbacks {
  onStep?: (step: string) => void
  onInfo?: (info: string) => void
  onSuccess?: (msg: string) => void
  onWarning?: (msg: string) => void
  onError?: (error: string) => void
  onLog?: (log: string) => void
  onDone?: () => void
}

export const mcpApi = {
  /**
   * 获取 MCP 信息
   */
  async getInfo(): Promise<McpInfo> {
    return request.get('/mcp/info')
  },

  /**
   * 获取下载 URL
   */
  getDownloadUrl(): string {
    // 使用相对路径，通过 Vite proxy 代理到后端
    return '/api/mcp/download'
  },

  /**
   * 获取配置模板
   */
  async getConfigTemplate(): Promise<Record<string, unknown>> {
    return request.get('/mcp/config-template')
  },

  /**
   * 获取安装脚本
   */
  async getInstallScripts(): Promise<InstallScripts> {
    return request.get('/mcp/install-script')
  },

  /**
   * 检查 MCP 安装状态
   */
  async checkStatus(mcpDir?: string): Promise<McpStatus> {
    return request.get('/mcp/status', { params: { mcpDir } })
  },

  /**
   * 在线安装 MCP（流式）
   * @param mcpDir MCP 项目目录
   * @param callbacks 回调函数
   * @param projectDir 目标项目目录（可选，用于配置 MCP）
   */
  install(mcpDir: string, callbacks: InstallCallbacks, projectDir?: string): Promise<void> {
    return new Promise((resolve, reject) => {
      fetch('/api/mcp/install', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mcpDir, projectDir })
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
          if (!reader) throw new Error('No reader available')

          const decoder = new TextDecoder()
          let buffer = ''
          let currentEvent = ''

          const readChunk = (): Promise<void> => {
            return reader.read().then(({ done, value }) => {
              if (done) {
                callbacks.onDone?.()
                resolve()
                return
              }

              buffer += decoder.decode(value, { stream: true })
              const lines = buffer.split('\n')
              buffer = lines.pop() || ''

              for (const line of lines) {
                if (line.startsWith('event:')) {
                  currentEvent = line.slice(6).trim()
                } else if (line.startsWith('data:')) {
                  const data = line.slice(5).trim()
                  switch (currentEvent) {
                    case 'step':
                      callbacks.onStep?.(data)
                      break
                    case 'info':
                      callbacks.onInfo?.(data)
                      break
                    case 'success':
                      callbacks.onSuccess?.(data)
                      break
                    case 'warning':
                      callbacks.onWarning?.(data)
                      break
                    case 'error':
                      callbacks.onError?.(data)
                      reject(new Error(data))
                      return
                    case 'log':
                      callbacks.onLog?.(data)
                      break
                    case 'done':
                      callbacks.onDone?.()
                      resolve()
                      return
                  }
                  currentEvent = ''
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