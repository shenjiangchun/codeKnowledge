import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  DialogSession,
  DialogMessage,
  IntentResult,
  DialogContext,
  IntentType
} from '@/types/intent'
import { naturalLanguageApi } from '@/api/naturalLanguage'

// Always use real API
const api = naturalLanguageApi

/**
 * 自然语言交互状态管理
 */
export const useNaturalLanguageStore = defineStore('naturalLanguage', () => {
  // 状态
  const sessions = ref<DialogSession[]>([])
  const currentSessionId = ref<string | null>(null)
  const loading = ref(false)
  const total = ref(0)

  // 消息缓存（按 sessionId 存储）
  const messagesCache = ref<Record<string, DialogMessage[]>>({})

  // 流式状态缓存
  const streamingContentCache = ref<Record<string, string>>({})
  const streamingStatusCache = ref<Record<string, boolean>>({})

  // 意图识别结果缓存
  const intentCache = ref<Record<string, IntentResult[]>>({})

  // 进度信息缓存
  const progressCache = ref<Record<string, string>>({})

  // 当前abort函数
  const currentAbortFn = ref<(() => void) | null>(null)

  // 计算属性
  const currentSession = computed(() =>
    sessions.value.find(s => s.id === currentSessionId.value) || null
  )

  const currentMessages = computed(() => {
    if (!currentSessionId.value) return []
    return messagesCache.value[currentSessionId.value] || []
  })

  const currentIntentHistory = computed(() => {
    if (!currentSessionId.value) return []
    return intentCache.value[currentSessionId.value] || []
  })

  const currentProgress = computed(() => {
    if (!currentSessionId.value) return ''
    return progressCache.value[currentSessionId.value] || ''
  })

  const isStreaming = computed(() => {
    if (!currentSessionId.value) return false
    return streamingStatusCache.value[currentSessionId.value] || false
  })

  const streamingContent = computed(() => {
    if (!currentSessionId.value) return ''
    return streamingContentCache.value[currentSessionId.value] || ''
  })

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
  async function loadSessions(status?: 'active' | 'archived', page = 1, pageSize = 20) {
    loading.value = true
    try {
      const response = await api.listSessions(status, page, pageSize)
      // Mock API 和真实 API 都不需要访问 .data
      // Mock API 直接返回数据，真实 API 通过 axios 拦截器已解包
      const data = response
      sessions.value = data.list
      total.value = data.total
    } catch (error) {
      console.error('Failed to load sessions:', error)
    } finally {
      loading.value = false
    }
  }

  /**
   * 创建新会话
   */
  async function createSession(workingDirectory?: string) {
    try {
      const response = await api.createSession(workingDirectory)
      // Mock API 和真实 API 都不需要访问 .data
      const session = response
      sessions.value.unshift(session)
      messagesCache.value[session.id] = []
      intentCache.value[session.id] = []
      return session
    } catch (error) {
      console.error('Failed to create session:', error)
      throw error
    }
  }

  /**
   * 设置当前会话
   */
  function setCurrentSession(sessionId: string | null) {
    currentSessionId.value = sessionId
    if (sessionId && !messagesCache.value[sessionId]) {
      // 加载会话详情
      loadSessionDetail(sessionId)
    }
  }

  /**
   * 加载会话详情
   */
  async function loadSessionDetail(sessionId: string) {
    loading.value = true
    try {
      const response = await api.getSession(sessionId)
      // Mock API 和真实 API 都不需要访问 .data
      const session = response

      // 更新会话列表
      const index = sessions.value.findIndex(s => s.id === sessionId)
      if (index >= 0) {
        sessions.value[index] = session
      } else {
        sessions.value.unshift(session)
      }

      // 缓存消息
      messagesCache.value[sessionId] = session.messages || []

      currentSessionId.value = sessionId
    } catch (error) {
      console.error('Failed to load session detail:', error)
    } finally {
      loading.value = false
    }
  }

  /**
   * 开始流式处理
   */
  function startStreaming(sessionId: string) {
    streamingStatusCache.value[sessionId] = true
    streamingContentCache.value[sessionId] = ''
  }

  /**
   * 追加流式内容
   */
  function appendStreamingContent(sessionId: string, content: string) {
    if (!streamingContentCache.value[sessionId]) {
      streamingContentCache.value[sessionId] = ''
    }
    streamingContentCache.value[sessionId] += content
  }

  /**
   * 结束流式处理
   */
  function endStreaming(sessionId: string) {
    streamingStatusCache.value[sessionId] = false
    if (currentAbortFn.value) {
      currentAbortFn.value = null
    }
  }

  /**
   * 取消当前流式请求
   */
  function abortCurrentStream() {
    if (currentAbortFn.value) {
      currentAbortFn.value()
      currentAbortFn.value = null
    }
    if (currentSessionId.value) {
      endStreaming(currentSessionId.value)
    }
  }

  /**
   * 添加消息到会话
   */
  function addMessage(sessionId: string, message: DialogMessage) {
    if (!messagesCache.value[sessionId]) {
      messagesCache.value[sessionId] = []
    }
    messagesCache.value[sessionId].push(message)
  }

  /**
   * 更新消息状态
   */
  function updateMessageStatus(sessionId: string, messageId: string, status: DialogMessage['status']) {
    const messages = messagesCache.value[sessionId]
    if (messages) {
      const message = messages.find(m => m.id === messageId)
      if (message) {
        message.status = status
      }
    }
  }

  /**
   * 添加意图识别结果
   */
  function addIntentResult(sessionId: string, intent: IntentResult) {
    if (!intentCache.value[sessionId]) {
      intentCache.value[sessionId] = []
    }
    intentCache.value[sessionId].push(intent)
  }

  /**
   * 更新进度信息
   */
  function updateProgress(sessionId: string, progress: string) {
    progressCache.value[sessionId] = progress
  }

  /**
   * 清除进度信息
   */
  function clearProgress(sessionId: string) {
    progressCache.value[sessionId] = ''
  }

  /**
   * 处理用户输入（流式）
   */
  async function processInput(
    sessionId: string,
    userInput: string,
    workingDirectory?: string
  ): Promise<void> {
    // 创建用户消息
    const userMessageId = `user-${Date.now()}`
    const userMessage: DialogMessage = {
      id: userMessageId,
      sessionId,
      role: 'user',
      content: userInput,
      status: 'completed',
      createdAt: new Date().toISOString()
    }
    addMessage(sessionId, userMessage)

    // 创建助手消息占位
    const assistantMessageId = `assistant-${Date.now()}`
    const assistantMessage: DialogMessage = {
      id: assistantMessageId,
      sessionId,
      role: 'assistant',
      content: '',
      status: 'pending',
      createdAt: new Date().toISOString()
    }
    addMessage(sessionId, assistantMessage)

    // 开始流式
    startStreaming(sessionId)
    updateMessageStatus(sessionId, assistantMessageId, 'streaming')

    // 发起请求
    const abort = api.streamProcess(
      {
        sessionId,
        userInput,
        workingDirectory
      },
      {
        onIntent: (intent) => {
          // 更新用户消息的意图
          const msg = messagesCache.value[sessionId]?.find(m => m.id === userMessageId)
          if (msg) {
            msg.intent = intent
          }
          addIntentResult(sessionId, intent)
        },
        onOutput: (content) => {
          appendStreamingContent(sessionId, content)
        },
        onProgress: (progress) => {
          updateProgress(sessionId, progress)
        },
        onDone: (status) => {
          // 完成助手消息
          const fullContent = streamingContentCache.value[sessionId] || ''
          const msg = messagesCache.value[sessionId]?.find(m => m.id === assistantMessageId)
          if (msg) {
            msg.content = fullContent
            msg.status = 'completed'
          }
          endStreaming(sessionId)
          clearProgress(sessionId)
        },
        onError: (error) => {
          updateMessageStatus(sessionId, assistantMessageId, 'error')
          endStreaming(sessionId)
          clearProgress(sessionId)
          console.error('Process error:', error)
        }
      }
    )

    currentAbortFn.value = abort
  }

  /**
   * 发送干预
   */
  async function sendIntervention(sessionId: string, intervention: string) {
    try {
      await api.sendIntervention(sessionId, intervention)
      // 添加干预消息
      const message: DialogMessage = {
        id: `intervention-${Date.now()}`,
        sessionId,
        role: 'user',
        content: `[干预] ${intervention}`,
        intent: {
          intent: 'INTERVENE' as IntentType,
          confidence: 1.0,
          entities: {},
          rawInput: intervention,
          timestamp: new Date().toISOString()
        },
        status: 'completed',
        createdAt: new Date().toISOString()
      }
      addMessage(sessionId, message)
    } catch (error) {
      console.error('Failed to send intervention:', error)
      throw error
    }
  }

  /**
   * 删除会话
   */
  async function deleteSession(sessionId: string) {
    try {
      await api.deleteSession(sessionId)
      sessions.value = sessions.value.filter(s => s.id !== sessionId)
      delete messagesCache.value[sessionId]
      delete intentCache.value[sessionId]
      delete streamingContentCache.value[sessionId]
      delete streamingStatusCache.value[sessionId]
      delete progressCache.value[sessionId]
      if (currentSessionId.value === sessionId) {
        currentSessionId.value = null
      }
    } catch (error) {
      console.error('Failed to delete session:', error)
    }
  }

  /**
   * 归档会话
   */
  async function archiveSession(sessionId: string) {
    try {
      await api.archiveSession(sessionId)
      const session = sessions.value.find(s => s.id === sessionId)
      if (session) {
        session.status = 'archived'
      }
    } catch (error) {
      console.error('Failed to archive session:', error)
    }
  }

  /**
   * 清除会话消息
   */
  function clearSessionMessages(sessionId: string) {
    messagesCache.value[sessionId] = []
    intentCache.value[sessionId] = []
  }

  return {
    // 状态
    sessions,
    currentSessionId,
    loading,
    total,
    // 缓存
    messagesCache,
    streamingContentCache,
    streamingStatusCache,
    intentCache,
    progressCache,
    // 计算属性
    currentSession,
    currentMessages,
    currentIntentHistory,
    currentProgress,
    isStreaming,
    streamingContent,
    activeSessions,
    archivedSessions,
    // Actions
    loadSessions,
    createSession,
    setCurrentSession,
    loadSessionDetail,
    startStreaming,
    appendStreamingContent,
    endStreaming,
    abortCurrentStream,
    addMessage,
    updateMessageStatus,
    addIntentResult,
    updateProgress,
    clearProgress,
    processInput,
    sendIntervention,
    deleteSession,
    archiveSession,
    clearSessionMessages
  }
})