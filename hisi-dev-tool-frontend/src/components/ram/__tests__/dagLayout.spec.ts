// dagLayout.spec.ts
import { describe, expect, it } from 'vitest'
import { computeDagLayout, type DagInput } from '../dagLayout'

describe('computeDagLayout', () => {
  const baseInput: DagInput = {
    width: 800,
    height: 500,
    seeds: ['A'],
    edges: [
      { from: 'A', to: 'B', kind: 'call' },
      { from: 'B', to: 'C', kind: 'call' },
      { from: 'A', to: 'D', kind: 'feign' }
    ],
    riskScores: { A: 0.9, B: 0.5, C: 0.1, D: 0.7 },
    inDegree: { A: 0, B: 1, C: 1, D: 1 }
  }

  it('returns one node per unique file', () => {
    const out = computeDagLayout(baseInput)
    expect(out.nodes.map((n) => n.id).sort()).toEqual(['A', 'B', 'C', 'D'])
  })

  it('places seeds at the leftmost layer', () => {
    const out = computeDagLayout(baseInput)
    const a = out.nodes.find((n) => n.id === 'A')!
    const others = out.nodes.filter((n) => n.id !== 'A')
    for (const o of others) expect(a.x).toBeLessThan(o.x)
  })

  it('assigns increasing layer index from seeds to leaves', () => {
    const out = computeDagLayout(baseInput)
    const layer = (id: string) => out.nodes.find((n) => n.id === id)!.layer
    expect(layer('A')).toBe(0)
    expect(layer('B')).toBe(1)
    expect(layer('C')).toBe(2)
  })

  it('encodes risk as node color and size from inDegree', () => {
    const out = computeDagLayout(baseInput)
    const a = out.nodes.find((n) => n.id === 'A')!
    const b = out.nodes.find((n) => n.id === 'B')!
    expect(a.color).toMatch(/^#[0-9a-f]{6}$/i)
    expect(b.radius).toBeGreaterThanOrEqual(6)
  })

  it('exposes edge stroke width proportional to risk flow', () => {
    const out = computeDagLayout(baseInput)
    const ab = out.edges.find((e) => e.from === 'A' && e.to === 'B')!
    const bc = out.edges.find((e) => e.from === 'B' && e.to === 'C')!
    expect(ab.strokeWidth).toBeGreaterThanOrEqual(bc.strokeWidth)
  })

  it('marks cross-service edges as dashed', () => {
    const out = computeDagLayout(baseInput)
    const ad = out.edges.find((e) => e.from === 'A' && e.to === 'D')!
    expect(ad.dashed).toBe(true)
  })
})
