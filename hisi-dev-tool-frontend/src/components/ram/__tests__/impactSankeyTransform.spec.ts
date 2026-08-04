/**
 * Tests for the impact Sankey transform helpers.
 *
 * Covers:
 *   - extractPackage: nodeId → short package name
 *   - transformImpactToSankey: ring arrays → SankeyData (nodes + links + ringCounts)
 *   - buildSankeyOption: SankeyData → ECharts option
 */
import { describe, expect, it } from 'vitest'
import {
  extractPackage,
  transformImpactToSankey,
  buildSankeyOption,
  RING_LABELS,
  type SankeyData
} from '../impactSankeyTransform'
import { RING_COLORS, riskHeatColor } from '../threeRingLayout'

// ---------------------------------------------------------------------------
// extractPackage
// ---------------------------------------------------------------------------
describe('extractPackage', () => {
  it('takes the part after the last colon and returns last 3 pkg segments', () => {
    expect(
      extractPackage('C:/proj:com.huawei.hisi.ram.nodes.impact.Resolver.resolve.abc')
    ).toBe('ram.nodes.impact')
  })

  it('returns all segments when there are 2 or fewer', () => {
    expect(extractPackage('C:/proj:com.foo.Bar.method.hash')).toBe('com.foo')
  })

  it('handles nodeId without colon (FQN only)', () => {
    expect(extractPackage('com.example.service.MyClass.run.xyz')).toBe(
      'com.example.service'
    )
  })

  it('returns (default) when first segment starts with uppercase', () => {
    expect(extractPackage('MyClass.method.hash')).toBe('(default)')
  })

  it('returns (default) for empty string', () => {
    expect(extractPackage('')).toBe('(default)')
  })

  it('handles a single lowercase pkg segment', () => {
    expect(extractPackage('C:/proj:a.B.c')).toBe('a')
  })

  it('takes last 3 of 6 package segments', () => {
    expect(extractPackage('C:/x:a.b.c.d.e.f.Class.method.h')).toBe('d.e.f')
  })
})

// ---------------------------------------------------------------------------
// transformImpactToSankey
// ---------------------------------------------------------------------------
describe('transformImpactToSankey', () => {
  it('creates 3 nodes and 2 links when all rings share the same package', () => {
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.A.m1.h1',
        'C:/p:com.foo.bar.B.m2.h2',
        'C:/p:com.foo.bar.C.m3.h3'
      ],
      modified: [
        'C:/p:com.foo.bar.D.m4.h4',
        'C:/p:com.foo.bar.E.m5.h5',
        'C:/p:com.foo.bar.F.m6.h6'
      ],
      impacted: [
        'C:/p:com.foo.bar.G.m7.h7',
        'C:/p:com.foo.bar.H.m8.h8',
        'C:/p:com.foo.bar.I.m9.h9'
      ]
    })

    expect(result.nodes).toHaveLength(3)
    expect(result.links).toHaveLength(2)

    // All nodes share the same package label (with count)
    expect(result.nodes.every((n) => n.label.startsWith('com.foo.bar'))).toBe(true)

    // Verify columns
    const cols = result.nodes.map((n) => n.column).sort()
    expect(cols).toEqual(['impacted', 'involved', 'modified'])

    // Verify depths
    expect(result.nodes.find((n) => n.column === 'involved')!.depth).toBe(0)
    expect(result.nodes.find((n) => n.column === 'modified')!.depth).toBe(1)
    expect(result.nodes.find((n) => n.column === 'impacted')!.depth).toBe(2)
  })

  it('produces 0 links when no package overlaps between adjacent rings', () => {
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.a.b.c.X.m.h1',
        'C:/p:com.a.b.c.X.m.h2',
        'C:/p:com.a.b.c.X.m.h3'
      ],
      modified: [
        'C:/p:com.x.y.z.Y.m.h1',
        'C:/p:com.x.y.z.Y.m.h2',
        'C:/p:com.x.y.z.Y.m.h3'
      ],
      impacted: [
        'C:/p:com.p.q.r.Z.m.h1',
        'C:/p:com.p.q.r.Z.m.h2',
        'C:/p:com.p.q.r.Z.m.h3'
      ]
    })

    expect(result.nodes).toHaveLength(3)
    expect(result.links).toHaveLength(0)
  })

  it('creates multiple nodes per ring when there are multiple packages', () => {
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.A.m.h1',
        'C:/p:com.foo.bar.B.m.h2',
        'C:/p:com.foo.bar.C.m.h3',
        'C:/p:com.baz.qux.D.m.h1',
        'C:/p:com.baz.qux.E.m.h2',
        'C:/p:com.baz.qux.F.m.h3'
      ],
      modified: [],
      impacted: []
    })

    const involvedNodes = result.nodes.filter((n) => n.column === 'involved')
    expect(involvedNodes).toHaveLength(2)

    // Verify labels contain count in parens
    for (const n of involvedNodes) {
      expect(n.label).toMatch(/\(\d+\)$/)
    }
  })

  it('returns empty nodes and links for empty input', () => {
    const result = transformImpactToSankey({
      involved: [],
      modified: [],
      impacted: []
    })

    expect(result.nodes).toEqual([])
    expect(result.links).toEqual([])
    expect(result.ringCounts).toEqual({ involved: 0, modified: 0, impacted: 0 })
  })

  it('produces nodes but no links when only one ring has data', () => {
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.X.m.h1',
        'C:/p:com.foo.bar.X.m.h2',
        'C:/p:com.foo.bar.X.m.h3'
      ],
      modified: [],
      impacted: []
    })

    expect(result.nodes).toHaveLength(1)
    expect(result.links).toHaveLength(0)
  })

  it('deduplicates nodeIds within a ring', () => {
    const dup = 'C:/p:com.foo.bar.X.m.h'
    const result = transformImpactToSankey({
      involved: [dup, dup, dup, dup],
      modified: [],
      impacted: []
    })

    // Only 1 unique nodeId, but even with 1 it won't be coalesced if it's
    // the only group (groups.size <= 2 means no coalescing)
    expect(result.nodes).toHaveLength(1)
    expect(result.nodes[0].nodeIds).toHaveLength(1)
  })

  it('uses riskHeatColor for the max risk score in the package group', () => {
    const highRiskId = 'C:/p:com.foo.bar.A.m.h1'
    const lowRiskId = 'C:/p:com.foo.bar.B.m.h2'
    const extraId = 'C:/p:com.foo.bar.C.m.h3'
    const result = transformImpactToSankey({
      involved: [highRiskId, lowRiskId, extraId],
      modified: [],
      impacted: [],
      riskScores: { [highRiskId]: 0.9, [lowRiskId]: 0.1, [extraId]: 0.5 }
    })

    const node = result.nodes[0]
    // Should use the max risk color (0.9), not the default ring color
    expect(node.color).toBe(riskHeatColor(0.9))
    expect(node.color).not.toBe(RING_COLORS.involved)
  })

  it('uses default ring color when all risk scores are 0', () => {
    const ids = [
      'C:/p:com.foo.bar.A.m.h1',
      'C:/p:com.foo.bar.B.m.h2',
      'C:/p:com.foo.bar.C.m.h3'
    ]
    const result = transformImpactToSankey({
      involved: ids,
      modified: [],
      impacted: [],
      riskScores: Object.fromEntries(ids.map((id) => [id, 0]))
    })

    expect(result.nodes[0].color).toBe(RING_COLORS.involved)
  })

  it('uses default ring color when riskScores is not provided', () => {
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.A.m.h1',
        'C:/p:com.foo.bar.B.m.h2',
        'C:/p:com.foo.bar.C.m.h3'
      ],
      modified: [],
      impacted: []
    })

    expect(result.nodes[0].color).toBe(RING_COLORS.involved)
  })

  it('assigns unique name with column prefix', () => {
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.A.m.h1',
        'C:/p:com.foo.bar.B.m.h2',
        'C:/p:com.foo.bar.C.m.h3'
      ],
      modified: [
        'C:/p:com.foo.bar.D.m.h1',
        'C:/p:com.foo.bar.E.m.h2',
        'C:/p:com.foo.bar.F.m.h3'
      ],
      impacted: []
    })

    const names = result.nodes.map((n) => n.name).sort()
    expect(names).toEqual(['involved::com.foo.bar', 'modified::com.foo.bar'])
  })

  it('creates links only between adjacent rings, not involved to impacted directly', () => {
    // Package appears in involved and impacted but NOT in modified
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.A.m.h1',
        'C:/p:com.foo.bar.B.m.h2',
        'C:/p:com.foo.bar.C.m.h3'
      ],
      modified: [
        'C:/p:com.other.pkg.D.m.h1',
        'C:/p:com.other.pkg.E.m.h2',
        'C:/p:com.other.pkg.F.m.h3'
      ],
      impacted: [
        'C:/p:com.foo.bar.G.m.h1',
        'C:/p:com.foo.bar.H.m.h2',
        'C:/p:com.foo.bar.I.m.h3'
      ]
    })

    // There should be no link from involved to impacted (they're not adjacent)
    expect(result.links).toHaveLength(0)
  })

  it('includes ringCounts in output', () => {
    const result = transformImpactToSankey({
      involved: ['C:/p:com.foo.bar.A.m.h1', 'C:/p:com.foo.bar.B.m.h2'],
      modified: ['C:/p:com.foo.bar.C.m.h3'],
      impacted: [
        'C:/p:com.foo.bar.D.m.h4',
        'C:/p:com.foo.bar.E.m.h5',
        'C:/p:com.foo.bar.F.m.h6'
      ]
    })

    expect(result.ringCounts).toEqual({ involved: 2, modified: 1, impacted: 3 })
  })

  it('coalesces small packages into (其他) when > 2 groups exist', () => {
    // 3 groups: bigPkg (5 methods), tinyPkg1 (1 method), tinyPkg2 (1 method)
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.big.pkg.A.m.h1',
        'C:/p:com.big.pkg.B.m.h2',
        'C:/p:com.big.pkg.C.m.h3',
        'C:/p:com.big.pkg.D.m.h4',
        'C:/p:com.big.pkg.E.m.h5',
        'C:/p:com.tiny1.pkg.X.m.h1',
        'C:/p:com.tiny2.pkg.Y.m.h1'
      ],
      modified: [],
      impacted: []
    })

    const involvedNodes = result.nodes.filter((n) => n.column === 'involved')
    // bigPkg stays, tiny1 + tiny2 → (其他)
    expect(involvedNodes).toHaveLength(2)

    const otherNode = involvedNodes.find((n) => n.name.includes('(其他)'))
    expect(otherNode).toBeDefined()
    expect(otherNode!.nodeIds).toHaveLength(2) // tiny1 + tiny2

    const bigNode = involvedNodes.find((n) => n.name.includes('com.big.pkg'))
    expect(bigNode).toBeDefined()
    expect(bigNode!.nodeIds).toHaveLength(5)
  })

  it('does not coalesce when only 2 or fewer groups exist', () => {
    // 2 groups: pkgA (1 method), pkgB (1 method) → both stay (no coalescing)
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.A.m.h1',
        'C:/p:com.baz.qux.B.m.h2'
      ],
      modified: [],
      impacted: []
    })

    const involvedNodes = result.nodes.filter((n) => n.column === 'involved')
    expect(involvedNodes).toHaveLength(2)
    expect(involvedNodes.every((n) => !n.name.includes('(其他)'))).toBe(true)
  })

  it('node labels include method count', () => {
    const result = transformImpactToSankey({
      involved: [
        'C:/p:com.foo.bar.A.m.h1',
        'C:/p:com.foo.bar.B.m.h2',
        'C:/p:com.foo.bar.C.m.h3'
      ],
      modified: [],
      impacted: []
    })

    expect(result.nodes[0].label).toBe('com.foo.bar (3)')
  })
})

// ---------------------------------------------------------------------------
// buildSankeyOption
// ---------------------------------------------------------------------------
describe('buildSankeyOption', () => {
  const sampleData: SankeyData = {
    nodes: [
      {
        name: 'involved::com.foo.bar',
        label: 'com.foo.bar (2)',
        column: 'involved',
        depth: 0,
        color: '#FFD700',
        nodeIds: ['C:/p:com.foo.bar.A.m.h', 'C:/p:com.foo.bar.B.m.h']
      },
      {
        name: 'modified::com.foo.bar',
        label: 'com.foo.bar (1)',
        column: 'modified',
        depth: 1,
        color: '#FF8C00',
        nodeIds: ['C:/p:com.foo.bar.C.m.h']
      }
    ],
    links: [
      {
        source: 'involved::com.foo.bar',
        target: 'modified::com.foo.bar',
        value: 2
      }
    ],
    ringCounts: { involved: 2, modified: 1, impacted: 0 }
  }

  it('returns an object with series[0].type === "sankey"', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const series = option.series as any[]
    expect(series[0].type).toBe('sankey')
  })

  it('series[0].data length matches data.nodes.length', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const series = option.series as any[]
    expect(series[0].data).toHaveLength(sampleData.nodes.length)
  })

  it('series[0].links length matches data.links.length', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const series = option.series as any[]
    expect(series[0].links).toHaveLength(sampleData.links.length)
  })

  it('each node in series[0].data has correct depth', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const series = option.series as any[]
    for (const node of series[0].data) {
      const original = sampleData.nodes.find((n) => n.name === node.name)!
      expect(node.depth).toBe(original.depth)
    }
  })

  it('produces valid structure with empty SankeyData', () => {
    const emptyData: SankeyData = {
      nodes: [],
      links: [],
      ringCounts: { involved: 0, modified: 0, impacted: 0 }
    }
    const option = buildSankeyOption(emptyData, 800, 600)
    const series = option.series as any[]
    expect(series[0].type).toBe('sankey')
    expect(series[0].data).toEqual([])
    expect(series[0].links).toEqual([])
  })

  it('each node has itemStyle.color matching the source node', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const series = option.series as any[]
    for (const node of series[0].data) {
      const original = sampleData.nodes.find((n) => n.name === node.name)!
      expect(node.itemStyle.color).toBe(original.color)
    }
  })

  it('each node has a label.formatter matching the source label', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const series = option.series as any[]
    for (const node of series[0].data) {
      const original = sampleData.nodes.find((n) => n.name === node.name)!
      expect(node.label.formatter).toBe(original.label)
    }
  })

  it('has tooltip.trigger set to "item"', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const tooltip = option.tooltip as any
    expect(tooltip.trigger).toBe('item')
  })

  it('has tooltip.formatter as a function', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const tooltip = option.tooltip as any
    expect(typeof tooltip.formatter).toBe('function')
  })

  it('includes graphic elements for column headers', () => {
    const option = buildSankeyOption(sampleData, 800, 600)
    const graphic = option.graphic as any
    expect(graphic.elements).toHaveLength(3)
    // Each header should contain ring label text
    const texts = graphic.elements.map((el: any) => el.style.text)
    expect(texts[0]).toContain(RING_LABELS.involved)
    expect(texts[1]).toContain(RING_LABELS.modified)
    expect(texts[2]).toContain(RING_LABELS.impacted)
  })
})
