/**
 * Unit tests for SSE seq parsing logic.
 *
 * Background: Backend uses JacksonConfig to serialize Long to String.
 * This means `seq` field can arrive as either number or string.
 * The frontend must handle both cases robustly.
 *
 * Test scenarios:
 * - SSE-01: seq as Number
 * - SSE-02: seq as String
 * - SSE-03: seq as invalid string
 * - SSE-04: seq deduplication
 * - SSE-05: seq sequential increment
 * - SSE-06: seq as negative number
 * - SSE-07: seq as null
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

// ---------------------------------------------------------------------------
// EventSource stub (reused from useRamSession.spec.ts pattern)
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

// Stub request module before composable import
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
    }),
    get: vi.fn(async () => ({ status: 'RUNNING', report: null, events: [] })),
  }
}))

// Install global EventSource stub
;(globalThis as unknown as { EventSource: typeof FakeEventSource }).EventSource =
  FakeEventSource as unknown as typeof FakeEventSource

import { useRamSession } from '../useRamSession'

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

describe('SSE seq parsing', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
  })

  describe('SSE-01: seq as Number type', () => {
    it('should parse seq=1 as number correctly', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 1, type: 'CHECKPOINT', payload: { text: 'test' } })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(1)
      expect(session.events.value[0].type).toBe('CHECKPOINT')
    })

    it('should parse seq=42 as number correctly', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 42, type: 'ASSISTANT_DELTA', payload: {} })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(42)
    })
  })

  describe('SSE-02: seq as String type', () => {
    it('should parse seq="1" string as number 1', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: '1', type: 'CHECKPOINT', payload: { text: 'test' } })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(1)
      expect(typeof session.events.value[0].seq).toBe('number')
    })

    it('should parse seq="123" string as number 123', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: '123', type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(123)
    })
  })

  describe('SSE-03: seq as invalid string', () => {
    it('should drop event when seq="abc" (NaN after parseInt)', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 'abc', type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(0)
    })

    it('should drop event when seq="" (empty string)', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: '', type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(0)
    })

    it('should drop event when seq="xyz123" (non-numeric prefix)', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 'xyz123', type: 'ERROR', payload: {} })

      expect(session.events.value).toHaveLength(0)
    })
  })

  describe('SSE-04: seq deduplication', () => {
    it('should skip duplicate seq=1 (received twice)', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 1, type: 'CHECKPOINT', payload: { text: 'first' } })
      es.emit({ seq: 1, type: 'CHECKPOINT', payload: { text: 'duplicate' } })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].payload).toEqual({ text: 'first' })
    })

    it('should skip event when seq <= lastSeq', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 5, type: 'CHECKPOINT', payload: {} })
      es.emit({ seq: 3, type: 'CHECKPOINT', payload: {} }) // lower than lastSeq
      es.emit({ seq: 5, type: 'CHECKPOINT', payload: {} }) // equal to lastSeq

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(5)
    })

    it('should handle string seq dedup correctly', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: '10', type: 'CHECKPOINT', payload: {} })
      es.emit({ seq: 10, type: 'CHECKPOINT', payload: {} }) // same as string "10"

      expect(session.events.value).toHaveLength(1)
    })
  })

  describe('SSE-05: seq sequential increment', () => {
    it('should add events with seq: 1, 2, 3, 4 in order', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 1, type: 'CHECKPOINT', payload: { step: 1 } })
      es.emit({ seq: 2, type: 'CHECKPOINT', payload: { step: 2 } })
      es.emit({ seq: 3, type: 'CHECKPOINT', payload: { step: 3 } })
      es.emit({ seq: 4, type: 'CHECKPOINT', payload: { step: 4 } })

      expect(session.events.value).toHaveLength(4)
      expect(session.events.value[0].seq).toBe(1)
      expect(session.events.value[1].seq).toBe(2)
      expect(session.events.value[2].seq).toBe(3)
      expect(session.events.value[3].seq).toBe(4)
    })

    it('should handle mixed number/string seq increment', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 1, type: 'CHECKPOINT', payload: {} })
      es.emit({ seq: '2', type: 'CHECKPOINT', payload: {} })
      es.emit({ seq: 3, type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(3)
      expect(session.events.value.map(e => e.seq)).toEqual([1, 2, 3])
    })
  })

  describe('SSE-06: seq as negative number', () => {
    it('should drop event when seq=-1', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: -1, type: 'ERROR', payload: {} })

      expect(session.events.value).toHaveLength(0)
    })

    it('should drop event when seq=-100', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: -100, type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(0)
    })
  })

  describe('SSE-07: seq as null/undefined', () => {
    it('should drop event when seq=null', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: null, type: 'ERROR', payload: {} })

      expect(session.events.value).toHaveLength(0)
    })

    it('should drop event when seq is undefined', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ type: 'CHECKPOINT', payload: {} }) // no seq field

      expect(session.events.value).toHaveLength(0)
    })

    it('should drop event when seq is an object', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: { value: 1 }, type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(0)
    })
  })

  describe('Edge cases', () => {
    it('should drop event when type is missing', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 1, payload: {} }) // no type

      expect(session.events.value).toHaveLength(0)
    })

    it('should handle seq=0 as valid (edge case)', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: 0, type: 'CHECKPOINT', payload: {} })

      // seq=0 is valid but will be dropped because lastSeq starts at 0
      // and the check is: if (parsed.seq <= lastSeq.value) return
      expect(session.events.value).toHaveLength(0)
    })

    it('should handle large seq number', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      const largeSeq = Number.MAX_SAFE_INTEGER
      es.emit({ seq: largeSeq, type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(largeSeq)
    })

    it('should handle string large seq number', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: '9007199254740991', type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(Number.MAX_SAFE_INTEGER)
    })

    it('should handle malformed JSON gracefully', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      // Simulate malformed JSON by calling onmessage directly
      es.onmessage?.({ data: 'not valid json' } as MessageEvent)

      expect(session.events.value).toHaveLength(0)
    })

    it('should handle numeric string with leading zeros', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      es.emit({ seq: '007', type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(7)
    })

    it('should handle numeric string with whitespace', async () => {
      const session = useRamSession()
      await session.start('test input', '/tmp/proj')

      const es = FakeEventSource.instances[0]
      // parseInt('  5  ', 10) = 5 (parseInt ignores leading whitespace)
      es.emit({ seq: '  5  ', type: 'CHECKPOINT', payload: {} })

      expect(session.events.value).toHaveLength(1)
      expect(session.events.value[0].seq).toBe(5)
    })
  })
})