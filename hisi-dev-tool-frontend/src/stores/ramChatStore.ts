import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ramChatApi, type SessionSummary, type ChatEvent, type CreateSessionResponse } from '@/api/ramChat'

export const useRamChatStore = defineStore('ramChat', () => {
  const sessions = ref<SessionSummary[]>([])
  const currentSessionId = ref<string | null>(null)
  const events = ref<ChatEvent[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const isStreaming = ref(false)

  async function fetchSessions() {
    loading.value = true
    try {
      // axios 拦截器已解包，response 直接是数据对象
      const data = await ramChatApi.listSessions()
      sessions.value = data as unknown as SessionSummary[]
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }

  async function createSession(projectPaths: string[], projectName: string) {
    const data = await ramChatApi.createSession({ projectPaths, projectName })
    const result = data as unknown as CreateSessionResponse
    await fetchSessions()
    currentSessionId.value = result.sessionId
    events.value = []
    return result
  }

  async function selectSession(sid: string) {
    currentSessionId.value = sid
    const data = await ramChatApi.getEvents(sid)
    events.value = data as unknown as ChatEvent[]
  }

  async function sendMessage(text: string) {
    if (!currentSessionId.value) return
    await ramChatApi.sendMessage(currentSessionId.value, text)
  }

  function appendEvent(event: ChatEvent) {
    switch (event.type) {
      case 'ASSISTANT_DELTA':
        isStreaming.value = true
        break
      case 'CHECKPOINT':
      case 'TURN_INTERRUPTED':
        isStreaming.value = false
        break
    }
    events.value.push(event)
  }

  async function interrupt() {
    if (!currentSessionId.value) return
    try {
      await ramChatApi.interruptTurn(currentSessionId.value)
    } catch (e: unknown) {
      console.error('[ramChatStore] interrupt failed', e)
    }
  }

  async function renameSession(sid: string, title: string) {
    await ramChatApi.renameSession(sid, title)
    await fetchSessions()
  }

  async function deleteSession(sid: string) {
    await ramChatApi.deleteSession(sid)
    if (currentSessionId.value === sid) {
      currentSessionId.value = null
      events.value = []
    }
    await fetchSessions()
  }

  return {
    sessions,
    currentSessionId,
    events,
    loading,
    error,
    isStreaming,
    fetchSessions,
    createSession,
    selectSession,
    sendMessage,
    appendEvent,
    interrupt,
    renameSession,
    deleteSession
  }
})
