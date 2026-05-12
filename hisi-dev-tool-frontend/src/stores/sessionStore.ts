import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Session, Message, SessionStatus } from '@/types/session'
import { sessionApi } from '@/api/session'
import { claudeApi } from '@/api/claude'

export const useSessionStore = defineStore('session', () => {
  // 状态
  const sessions = ref<Session[]>([])
  const currentSessionId = ref<string | null>(null)
  const messages = ref<Message[]>([])
  const loading = ref(false)
  const total = ref(0)

  // 每个会话的消息缓存（按 sessionId 存储）
  const messagesCache = ref<Record<string, Message[]>>({})

  // 当前正在流式传输的会话 ID（保留用于取消请求等场景）
  const streamingSessionId = ref<string | null>(null)
  // 全局流式传输状态（保留用于全局判断）
  const isStreaming = ref(false)

  // 每个会话的流式内容缓存（会话级别隔离）
  const streamingContentCache = ref<Record<string, string>>({})
  // 每个会话的流式状态缓存（会话级别隔离）
  const streamingStatusCache = ref<Record<string, boolean>>({})

  // 计算属性
  const currentSession = computed(() =>
    sessions.value.find(s => s.id === currentSessionId.value) || null
  )

  const activeSessions = computed(() =>
    sessions.value.filter(s => s.status === 'active')
  )

  const archivedSessions = computed(() =>
    sessions.value.filter(s => s.status === 'archived')
  )

  // Actions

  /**
   * 加载会话列表
   */
  async function loadSessions(status?: SessionStatus, page = 1, pageSize = 20) {
    loading.value = true
    try {
      const response = await sessionApi.list({ status, page, pageSize })
      // axios 拦截器已解包，response 直接是数据对象
      sessions.value = response.list
      total.value = response.total
    } catch (error) {
      console.error('Failed to load sessions:', error)
    } finally {
      loading.value = false
    }
  }

  /**
   * 加载会话详情
   */
  async function loadSessionDetail(sessionId: string) {
    loading.value = true
    try {
      const response = await sessionApi.get(sessionId)
      // axios 拦截器已解包，response 直接是数据对象
      // 更新或添加会话
      const index = sessions.value.findIndex(s => s.id === sessionId)
      if (index >= 0) {
        sessions.value[index] = response.session
      } else {
        sessions.value.unshift(response.session)
      }
      currentSessionId.value = sessionId
      messages.value = response.messages
      // 缓存消息
      messagesCache.value[sessionId] = response.messages
    } catch (error) {
      console.error('Failed to load session detail:', error)
    } finally {
      loading.value = false
    }
  }

  /**
   * 设置当前会话
   */
  function setCurrentSession(sessionId: string | null) {
    currentSessionId.value = sessionId
    if (sessionId) {
      // 优先使用缓存的消息，避免重复加载
      if (messagesCache.value[sessionId]) {
        messages.value = messagesCache.value[sessionId]
      } else {
        loadSessionDetail(sessionId)
      }
    } else {
      messages.value = []
    }
  }

  /**
   * 设置正在流式传输的会话
   */
  function setStreamingSession(sessionId: string | null) {
    const previousSessionId = streamingSessionId.value
    streamingSessionId.value = sessionId

    if (sessionId) {
      isStreaming.value = true
      // 初始化该会话的流式状态
      streamingStatusCache.value[sessionId] = true
      streamingContentCache.value[sessionId] = ''
    } else {
      isStreaming.value = false
      // 清除之前的会话流式状态
      if (previousSessionId) {
        streamingStatusCache.value[previousSessionId] = false
      }
    }
  }

  /**
   * 追加流式内容到指定会话
   */
  function appendStreamingContent(sessionId: string, content: string) {
    if (!streamingContentCache.value[sessionId]) {
      streamingContentCache.value[sessionId] = ''
    }
    streamingContentCache.value[sessionId] += content
  }

  /**
   * 追加流式内容（兼容旧接口，追加到当前流式会话）
   */
  function appendStreamingContentLegacy(content: string) {
    if (streamingSessionId.value) {
      appendStreamingContent(streamingSessionId.value, content)
    }
  }

  /**
   * 清空指定会话的流式内容
   */
  function clearStreamingContent(sessionId: string) {
    streamingContentCache.value[sessionId] = ''
    streamingStatusCache.value[sessionId] = false
    if (streamingSessionId.value === sessionId) {
      isStreaming.value = false
      streamingSessionId.value = null
    }
  }

  /**
   * 清空当前流式会话的内容
   */
  function clearCurrentStreamingContent() {
    if (streamingSessionId.value) {
      clearStreamingContent(streamingSessionId.value)
    }
  }

  /**
   * 获取指定会话的流式内容
   */
  function getStreamingContent(sessionId: string): string {
    return streamingContentCache.value[sessionId] || ''
  }

  /**
   * 获取指定会话的流式状态
   */
  function isSessionStreaming(sessionId: string): boolean {
    return streamingStatusCache.value[sessionId] || false
  }

  /**
   * 添加消息到指定会话
   */
  function addMessageToSession(sessionId: string, message: Message) {
    // 更新缓存
    if (!messagesCache.value[sessionId]) {
      messagesCache.value[sessionId] = []
    }
    messagesCache.value[sessionId].push(message)

    // 如果是当前会话，也更新当前消息列表
    if (sessionId === currentSessionId.value) {
      messages.value = [...messagesCache.value[sessionId]]
    }
  }

  /**
   * 添加消息（兼容旧接口，添加到当前会话）
   */
  function addMessage(message: Message) {
    if (currentSessionId.value) {
      addMessageToSession(currentSessionId.value, message)
    }
  }

  /**
   * 清空当前会话消息
   */
  function clearCurrentMessages() {
    messages.value = []
    if (currentSessionId.value) {
      messagesCache.value[currentSessionId.value] = []
    }
  }

  /**
   * 更新会话标题
   */
  async function updateSessionTitle(sessionId: string, title: string) {
    try {
      await sessionApi.update(sessionId, { title })
      const session = sessions.value.find(s => s.id === sessionId)
      if (session) {
        session.title = title
      }
    } catch (error) {
      console.error('Failed to update session title:', error)
    }
  }

  /**
   * 归档会话
   */
  async function archiveSession(sessionId: string) {
    try {
      await sessionApi.archive(sessionId)
      const session = sessions.value.find(s => s.id === sessionId)
      if (session) {
        session.status = 'archived'
      }
    } catch (error) {
      console.error('Failed to archive session:', error)
    }
  }

  /**
   * 删除会话
   */
  async function deleteSession(sessionId: string) {
    try {
      await sessionApi.delete(sessionId)
      sessions.value = sessions.value.filter(s => s.id !== sessionId)
      // 清除消息缓存
      delete messagesCache.value[sessionId]
      // 清除流式缓存
      delete streamingContentCache.value[sessionId]
      delete streamingStatusCache.value[sessionId]
      if (currentSessionId.value === sessionId) {
        currentSessionId.value = null
        messages.value = []
      }
    } catch (error) {
      console.error('Failed to delete session:', error)
    }
  }

  /**
   * 清除会话历史
   */
  async function clearSessionMessages(sessionId: string) {
    try {
      await sessionApi.clearMessages(sessionId)
      messagesCache.value[sessionId] = []
      if (currentSessionId.value === sessionId) {
        messages.value = []
      }
    } catch (error) {
      console.error('Failed to clear messages:', error)
    }
  }

  /**
   * 导出会话
   */
  async function exportSession(sessionId: string, format: 'markdown' | 'json' = 'markdown') {
    try {
      const response = await sessionApi.export(sessionId, format) as unknown as Blob
      // 响应拦截器已返回 response.data，对于 blob 响应，response 本身就是 Blob
      const blob = response instanceof Blob ? response : new Blob([response], {
        type: format === 'json' ? 'application/json' : 'text/markdown'
      })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `session-${sessionId}.${format}`
      a.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      console.error('Failed to export session:', error)
    }
  }

  /**
   * 关闭会话（保存 Claude 会话码用于恢复）
   */
  async function closeSession(sessionId: string): Promise<string | null> {
    try {
      const response = await claudeApi.endSession(sessionId)
      // axios 拦截器已解包，response 直接是数据（字符串）
      const claudeSessionCode = response
      // 更新会话对象的 claudeSessionCode
      const session = sessions.value.find(s => s.id === sessionId)
      if (session && claudeSessionCode) {
        session.claudeSessionCode = claudeSessionCode
      }
      // 清除流式状态
      delete streamingContentCache.value[sessionId]
      delete streamingStatusCache.value[sessionId]
      return claudeSessionCode
    } catch (error) {
      console.error('Failed to close session:', error)
      return null
    }
  }

  /**
   * 恢复会话
   */
  async function resumeSession(sessionId: string): Promise<boolean> {
    try {
      const response = await claudeApi.resumeSession(sessionId)
      // axios 拦截器已解包，response 直接是布尔值
      return response === true
    } catch (error) {
      console.error('Failed to resume session:', error)
      return false
    }
  }

  /**
   * 切换会话（不关闭当前会话，只是切换视图）
   * 进行中的会话会继续在后台运行
   */
  function switchSession(newSessionId: string | null) {
    const previousSessionId = currentSessionId.value

    // 如果切换到不同的会话，只清除前端的流式显示状态
    // 不关闭后端会话，让进行中的会话继续运行
    if (previousSessionId && previousSessionId !== newSessionId) {
      // 只清除前端的流式显示缓存，不中断后端会话
      // 会话内容会在后端继续累积，切换回来时会显示
      streamingStatusCache.value[previousSessionId] = false
    }

    // 立即切换到新会话
    setCurrentSession(newSessionId)

    // 如果新会话有 claudeSessionCode，尝试恢复
    if (newSessionId) {
      const session = sessions.value.find(s => s.id === newSessionId)
      if (session?.claudeSessionCode) {
        // 非阻塞恢复
        resumeSession(newSessionId).catch(err => {
          console.warn('Background resume session failed:', err)
        })
      }
    }
  }

  return {
    // 状态
    sessions,
    currentSessionId,
    messages,
    loading,
    total,
    streamingSessionId,
    isStreaming,
    // 会话级别流式状态
    streamingContentCache,
    streamingStatusCache,
    // 计算属性
    currentSession,
    activeSessions,
    archivedSessions,
    // Actions
    loadSessions,
    loadSessionDetail,
    setCurrentSession,
    switchSession,
    setStreamingSession,
    appendStreamingContent,
    appendStreamingContentLegacy,
    clearStreamingContent,
    clearCurrentStreamingContent,
    getStreamingContent,
    isSessionStreaming,
    addMessageToSession,
    addMessage,
    clearCurrentMessages,
    updateSessionTitle,
    archiveSession,
    deleteSession,
    clearSessionMessages,
    exportSession,
    closeSession,
    resumeSession
  }
})
