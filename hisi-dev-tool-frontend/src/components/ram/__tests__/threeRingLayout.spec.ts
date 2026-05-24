/**
 * Tests for the three-ring layout math.
 */
import { describe, expect, it } from 'vitest'
import {
  MAX_NODES_PER_RING,
  computeLayout,
  riskHeatColor,
  shortName,
  truncate
} from '../threeRingLayout'

describe('computeLayout', () => {
  const baseInput = {
    width: 600,
    height: 600,
    involved: ['a.java', 'b.java'],
    modified: ['b.java', 'c.java'],
    impacted: ['c.java', 'd.java', 'e.java']
  }

  it('produces 3 rings in fixed order', () => {
    const out = computeLayout(baseInput)
    expect(out.rings.map((r) => r.key)).toEqual(['involved', 'modified', 'impacted'])
  })

  it('reports totalCount on every ring', () => {
    const out = computeLayout(baseInput)
    expect(out.rings.map((r) => r.totalCount)).toEqual([2, 2, 3])
    expect(out.rings.every((r) => r.hiddenCount === 0)).toBe(true)
  })

  it('places one node per file per ring when under cap', () => {
    const out = computeLayout(baseInput)
    expect(out.nodes).toHaveLength(2 + 2 + 3)
  })

  it('centers nodes on each ring radius', () => {
    const out = computeLayout(baseInput)
    const involvedNodes = out.nodes.filter((n) => n.ring === 'involved')
    for (const n of involvedNodes) {
      const dx = n.cx - out.center.x
      const dy = n.cy - out.center.y
      const dist = Math.sqrt(dx * dx + dy * dy)
      expect(dist).toBeCloseTo(out.radii.involved, 3)
    }
  })

  it('emits inter-ring edges for files appearing in adjacent rings', () => {
    const out = computeLayout(baseInput)
    // b.java is in involved + modified; c.java is in modified + impacted.
    const files = out.edges.map((e) => e.from).sort()
    expect(files).toEqual(['b.java', 'c.java'])
  })

  it('uses risk heat color when riskScores provided', () => {
    const out = computeLayout({
      ...baseInput,
      riskScores: { 'a.java': 0.9, 'c.java': 0.1 }
    })
    const a = out.nodes.find((n) => n.file === 'a.java' && n.ring === 'involved')!
    const c = out.nodes.find((n) => n.file === 'c.java' && n.ring === 'modified')!
    expect(a.color).not.toBe('#FFD700') // overridden by risk
    expect(a.color).toMatch(/^#[0-9a-f]{6}$/)
    expect(a.radius).toBeGreaterThan(c.radius) // high risk → bigger node
  })

  it('caps a ring at MAX_NODES_PER_RING and surfaces overflow marker', () => {
    const many = Array.from({ length: MAX_NODES_PER_RING + 30 }, (_, i) => `f${i}.java`)
    const out = computeLayout({
      width: 600,
      height: 600,
      involved: many,
      modified: [],
      impacted: []
    })
    const involvedNodes = out.nodes.filter((n) => n.ring === 'involved')
    expect(involvedNodes).toHaveLength(MAX_NODES_PER_RING)
    expect(out.rings.find((r) => r.key === 'involved')!.hiddenCount).toBe(30)
    expect(out.overflow.find((o) => o.ring === 'involved')!.hiddenCount).toBe(30)
  })

  it('keeps highest-risk files when capping', () => {
    const many = Array.from({ length: MAX_NODES_PER_RING + 5 }, (_, i) => `f${i}.java`)
    const riskScores: Record<string, number> = {}
    // Give f0 the lowest risk; everyone else 0.9 so f0 should be dropped.
    for (const f of many) riskScores[f] = 0.9
    riskScores['f0.java'] = 0.0
    const out = computeLayout({
      width: 600,
      height: 600,
      involved: many,
      modified: [],
      impacted: [],
      riskScores
    })
    const visible = new Set(out.nodes.filter((n) => n.ring === 'involved').map((n) => n.file))
    expect(visible.has('f0.java')).toBe(false)
  })

  it('hides per-node labels when ring is dense', () => {
    const many = Array.from({ length: 24 }, (_, i) => `f${i}.java`)
    const out = computeLayout({
      width: 600,
      height: 600,
      involved: many,
      modified: [],
      impacted: []
    })
    expect(out.nodes.every((n) => !n.showLabel)).toBe(true)
  })

  it('shows per-node labels when ring is sparse', () => {
    const out = computeLayout(baseInput)
    expect(out.nodes.every((n) => n.showLabel)).toBe(true)
  })

  it('supports overriding the per-ring cap', () => {
    const out = computeLayout({
      width: 600,
      height: 600,
      involved: ['a.java', 'b.java', 'c.java', 'd.java'],
      modified: [],
      impacted: [],
      maxNodesPerRing: 2
    })
    expect(out.nodes.filter((n) => n.ring === 'involved')).toHaveLength(2)
    expect(out.rings.find((r) => r.key === 'involved')!.hiddenCount).toBe(2)
  })
})

describe('riskHeatColor', () => {
  it('returns green-ish at score 0', () => {
    expect(riskHeatColor(0).toLowerCase()).toBe('#67c23a')
  })
  it('returns red-ish at score 1', () => {
    expect(riskHeatColor(1).toLowerCase()).toBe('#f56c6c')
  })
  it('clamps out-of-range values', () => {
    expect(riskHeatColor(-1).toLowerCase()).toBe('#67c23a')
    expect(riskHeatColor(2).toLowerCase()).toBe('#f56c6c')
  })
})

describe('truncate', () => {
  it('shortens long strings with an ellipsis', () => {
    expect(truncate('a-very-long-file-name.java', 12)).toBe('a-very-long…')
  })
  it('leaves short strings alone', () => {
    expect(truncate('short.java')).toBe('short.java')
  })
})

describe('shortName', () => {
  it('takes basename without extension from a slash path', () => {
    expect(shortName('src/main/java/com/foo/OrderService.java')).toBe('OrderService')
  })
  it('takes the last dot segment from an FQN', () => {
    expect(shortName('com.foo.bar.OrderService')).toBe('OrderService')
  })
  it('keeps the method tail when an FQN includes #method', () => {
    expect(shortName('com.foo.bar.Order#go')).toBe('Order#go')
  })
  it('truncates very long names', () => {
    expect(shortName('com.foo.SomeReallyExtremelyLongIdentifierName').length).toBeLessThanOrEqual(22)
  })
})
