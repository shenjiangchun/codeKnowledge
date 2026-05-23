/**
 * Tests for the RAM DAG state derivation helpers.
 */
import { describe, expect, it } from 'vitest'
import {
  DAG_ORDER,
  deriveDagSnapshot,
  formatTokens,
  statusColor,
  statusLabel
} from '../dagModel'
import type { RamEvent } from '@/types/ram'

function evt(seq: number, type: string, payload: Record<string, unknown> = {}): RamEvent {
  return { seq, type, payload }
}

describe('deriveDagSnapshot', () => {
  it('returns 4 pending nodes for an empty stream', () => {
    const snap = deriveDagSnapshot([])
    expect(snap.map((n) => n.key)).toEqual([...DAG_ORDER])
    expect(snap.every((n) => n.status === 'pending')).toBe(true)
    expect(snap.every((n) => n.events === 0 && n.tokens === 0)).toBe(true)
  })

  it('flips clarify to running on first phase=clarify event', () => {
    const snap = deriveDagSnapshot([evt(1, 'ASSISTANT_DELTA', { phase: 'clarify' })])
    expect(snap[0].status).toBe('running')
    expect(snap[0].events).toBe(1)
  })

  it('flips clarify to awaiting-hitl when CLARIFY_REQUIRED arrives', () => {
    const snap = deriveDagSnapshot([
      evt(1, 'ASSISTANT_DELTA', { phase: 'clarify' }),
      evt(2, 'CLARIFY_REQUIRED', { questions: ['q1'] })
    ])
    expect(snap[0].status).toBe('awaiting-hitl')
  })

  it('marks impact done on IMPACT_DONE', () => {
    const snap = deriveDagSnapshot([
      evt(1, 'ASSISTANT_DELTA', { phase: 'impact' }),
      evt(2, 'IMPACT_DONE', { phase: 'impact' })
    ])
    expect(snap[1].status).toBe('done')
  })

  it('aggregates tokens from usage.tokens across events of the same node', () => {
    const snap = deriveDagSnapshot([
      evt(1, 'ASSISTANT_DELTA', { phase: 'impact', usage: { tokens: 120 } }),
      evt(2, 'ASSISTANT_DELTA', { phase: 'impact', usage: { tokens: 80 } })
    ])
    expect(snap[1].tokens).toBe(200)
    expect(snap[1].events).toBe(2)
  })

  it('captures riskLevel when present in payload', () => {
    const snap = deriveDagSnapshot([
      evt(1, 'ASSISTANT_DELTA', { phase: 'impact', riskLevel: 'HIGH' })
    ])
    expect(snap[1].riskLevel).toBe('HIGH')
  })

  it('marks the last running node failed on RUN_FAILED', () => {
    const snap = deriveDagSnapshot([
      evt(1, 'ASSISTANT_DELTA', { phase: 'impact' }),
      evt(2, 'RUN_FAILED', {})
    ])
    expect(snap[1].status).toBe('failed')
  })

  it('marks the last running node circuit-open on CIRCUIT_OPEN', () => {
    const snap = deriveDagSnapshot([
      evt(1, 'ASSISTANT_DELTA', { phase: 'implement' }),
      evt(2, 'CIRCUIT_OPEN', {})
    ])
    expect(snap[2].status).toBe('circuit-open')
  })

  it('promotes all running/awaiting-hitl nodes to done on RUN_COMPLETED', () => {
    const snap = deriveDagSnapshot([
      evt(1, 'ASSISTANT_DELTA', { phase: 'clarify' }),
      evt(2, 'ASSISTANT_DELTA', { phase: 'impact' }),
      evt(3, 'RUN_COMPLETED', {})
    ])
    expect(snap[0].status).toBe('done')
    expect(snap[1].status).toBe('done')
  })

  it('overlays session-level aborted onto running nodes', () => {
    const snap = deriveDagSnapshot(
      [evt(1, 'ASSISTANT_DELTA', { phase: 'verify' })],
      'aborted'
    )
    expect(snap[3].status).toBe('failed')
  })
})

describe('formatTokens', () => {
  it.each([
    [0, '0'],
    [-5, '0'],
    [42, '42'],
    [999, '999'],
    [1000, '1k'],
    [1234, '1.2k'],
    [1_500_000, '1.5m']
  ])('formats %i as %s', (input, expected) => {
    expect(formatTokens(input)).toBe(expected)
  })
})

describe('statusColor / statusLabel', () => {
  it('returns a hex color for every status', () => {
    const all = ['pending', 'running', 'awaiting-hitl', 'done', 'failed', 'circuit-open'] as const
    for (const s of all) {
      expect(statusColor(s)).toMatch(/^#[0-9A-F]{6}$/i)
      expect(statusLabel(s).length).toBeGreaterThan(0)
    }
  })
})
