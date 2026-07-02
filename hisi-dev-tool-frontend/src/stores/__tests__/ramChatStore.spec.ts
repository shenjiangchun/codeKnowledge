import { setActivePinia, createPinia } from 'pinia'
import { describe, expect, it, beforeEach, vi } from 'vitest'

vi.mock('@/api/ramChat', () => {
  return {
    ramChatApi: {
      createSession: vi.fn(),
      sendMessage: vi.fn(),
      getEvents: vi.fn(),
      listSessions: vi.fn(),
      renameSession: vi.fn(),
      deleteSession: vi.fn(),
      interruptTurn: vi.fn()
    }
  }
})

import { ramChatApi, type ChatEvent } from '@/api/ramChat'
import { useRamChatStore } from '../ramChatStore'

function makeEvent(type: string, seq = 1): ChatEvent {
  return {
    id: seq,
    sessionId: 1,
    seq,
    type,
    payload: '{}',
    createdAt: Date.now()
  }
}

describe('useRamChatStore — in-turn injection wiring', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('sendMessage forwards the raw text to ramChatApi.sendMessage', async () => {
    const store = useRamChatStore()
    store.currentSessionId = 'sid-1'
    await store.sendMessage('hi')
    expect(ramChatApi.sendMessage).toHaveBeenCalledTimes(1)
    expect(ramChatApi.sendMessage).toHaveBeenCalledWith('sid-1', 'hi')
  })

  it('interrupt() calls ramChatApi.interruptTurn with the current sid', async () => {
    const store = useRamChatStore()
    store.currentSessionId = 'sid-42'
    await store.interrupt()
    expect(ramChatApi.interruptTurn).toHaveBeenCalledTimes(1)
    expect(ramChatApi.interruptTurn).toHaveBeenCalledWith('sid-42')
  })

  it('interrupt() is a no-op when there is no current session', async () => {
    const store = useRamChatStore()
    store.currentSessionId = null
    await expect(store.interrupt()).resolves.toBeUndefined()
    expect(ramChatApi.interruptTurn).not.toHaveBeenCalled()
  })

  it('appendEvent flips isStreaming based on event.type', () => {
    const store = useRamChatStore()
    expect(store.isStreaming).toBe(false)

    store.appendEvent(makeEvent('ASSISTANT_DELTA', 1))
    expect(store.isStreaming).toBe(true)

    store.appendEvent(makeEvent('ASSISTANT_DELTA', 2))
    expect(store.isStreaming).toBe(true)

    store.appendEvent(makeEvent('CHECKPOINT', 3))
    expect(store.isStreaming).toBe(false)

    store.appendEvent(makeEvent('ASSISTANT_DELTA', 4))
    expect(store.isStreaming).toBe(true)

    store.appendEvent(makeEvent('TURN_INTERRUPTED', 5))
    expect(store.isStreaming).toBe(false)
  })
})
