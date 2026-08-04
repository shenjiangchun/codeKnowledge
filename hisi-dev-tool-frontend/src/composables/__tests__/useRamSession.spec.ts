/**
 * Smoke test for useRamSession — verifies status transitions when SSE events
 * arrive on a stubbed EventSource. UI-level interactions are covered by the
 * Task 13 view tests.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

// ---------------------------------------------------------------------------
// EventSource stub
// ---------------------------------------------------------------------------

class FakeEventSource {
  static instances: FakeEventSource[] = []
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2

  readonly url: string
  readyState: number = FakeEventSource.OPEN
  onmessage: ((ev: MessageEvent) => void) | null = null
  onerror: ((ev: Event) => void) | null = null
  onopen: ((ev: Event) => void) | null = null

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  emit(payload: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent)
  }

  close(): void {
    this.readyState = FakeEventSource.CLOSED
  }
}

// Stub request module before composable import so api/ram.ts picks up the mock.
vi.mock('@/utils/request', () => ({
  default: {
    post: vi.fn(async (url: string) => {
      if (url === '/ram/sessions') {
        return { sessionId: 'uuid-test' }
      }
      if (url.endsWith('/clarify')) {
        return { accepted: true, nextSeq: 3 }
      }
      if (url.endsWith('/resume')) {
        return { resumed: true }
      }
      if (url.endsWith('/abort')) {
        return { aborted: true }
      }
      return {}
    })
  }
}))

// Install global EventSource stub.
;(globalThis as unknown as { EventSource: typeof FakeEventSource }).EventSource =
  FakeEventSource as unknown as typeof FakeEventSource

import { useRamSession } from '../useRamSession'

describe('useRamSession', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
  })

  it('transitions idle -> running -> clarify -> running -> completed', async () => {
    const session = useRamSession()
    expect(session.status.value).toBe('idle')

    const sid = await session.start('add login', '/tmp/proj')
    expect(sid).toBe('uuid-test')
    expect(session.status.value).toBe('running')
    expect(FakeEventSource.instances).toHaveLength(1)

    const first = FakeEventSource.instances[0]
    first.emit({ seq: 1, type: 'ASSISTANT_DELTA', payload: { text: 'hi' } })
    expect(session.events.value).toHaveLength(1)
    expect(session.status.value).toBe('running')

    first.emit({
      seq: 2,
      type: 'CLARIFY_REQUIRED',
      payload: { questions: ['target user?'], nodeName: 'clarify' }
    })
    expect(session.status.value).toBe('clarify')
    expect(session.clarifyQuestions.value?.questions).toEqual(['target user?'])
    expect(first.readyState).toBe(FakeEventSource.CLOSED)

    await session.submitClarify({ q1: 'developers' })
    expect(session.status.value).toBe('running')
    expect(session.clarifyQuestions.value).toBeNull()
    expect(FakeEventSource.instances).toHaveLength(2)

    const second = FakeEventSource.instances[1]
    second.emit({
      seq: 4,
      type: 'CHECKPOINT',
      payload: { nodeName: 'impact', usage: { tokens: 100, cost_usd: 0.01 } }
    })
    expect(session.cost.value.tokens).toBe(100)
    expect(session.cost.value.usd).toBeCloseTo(0.01)

    second.emit({ seq: 5, type: 'RUN_COMPLETED', payload: {} })
    expect(session.status.value).toBe('completed')
    expect(second.readyState).toBe(FakeEventSource.CLOSED)
  })
})
