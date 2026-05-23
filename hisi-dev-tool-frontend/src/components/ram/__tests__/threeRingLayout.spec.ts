/**
 * Tests for the three-ring layout math.
 */
import { describe, expect, it } from 'vitest'
import { computeLayout, riskHeatColor, truncate } from '../threeRingLayout'

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

  it('places one node per file per ring', () => {
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
