import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DagGraph from '../DagGraph.vue'
import { useRamStore } from '@/stores/ram'

describe('DagGraph', () => {
  beforeEach(() => setActivePinia(createPinia()))
  const props = {
    seeds: ['A'],
    edges: [
      { from: 'A', to: 'B', kind: 'call' as const },
      { from: 'B', to: 'C', kind: 'call' as const }
    ],
    riskScores: { A: 0.9, B: 0.4, C: 0.1 },
    inDegree: { A: 0, B: 1, C: 1 }
  }

  it('renders one circle per node', () => {
    const w = mount(DagGraph, { props })
    expect(w.findAll('circle.dag-node')).toHaveLength(3)
  })

  it('renders one path/line per edge', () => {
    const w = mount(DagGraph, { props })
    expect(w.findAll('.dag-edge')).toHaveLength(2)
  })

  it('marks seeds with the seed class', () => {
    const w = mount(DagGraph, { props })
    const seedNode = w.findAll('circle.dag-node').find((n) => n.attributes('data-file') === 'A')!
    expect(seedNode.classes()).toContain('is-seed')
  })

  it('emits nodeClick with the file id', async () => {
    // Known toolchain bug: with vue-test-utils 2.4.10 + vitest 4 + happy-dom,
    // `wrapper.emitted()` does NOT capture programmatic `emit()` calls (only
    // DOM events bubble through). We use the same workaround as the sibling
    // specs ThreeRingGraph.spec.ts and ClarifyModal.spec.ts: register an
    // `onNodeClick` listener via `attrs` and assert against a captured array.
    const clicked: string[] = []
    const w = mount(DagGraph, {
      props,
      attrs: { onNodeClick: (f: string) => clicked.push(f) }
    })
    const node = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'B'
    )!
    await node.trigger('click')
    expect(clicked).toEqual(['B'])
  })

  it('exposes accessible attributes on each node (role/tabindex/aria-label)', () => {
    const w = mount(DagGraph, { props })
    const group = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'C'
    )!
    expect(group.attributes('role')).toBe('button')
    expect(group.attributes('tabindex')).toBe('0')
    expect(group.attributes('aria-label')).toBe('C, risk 0.10')
  })

  it('Enter key activates the node (same effect as click)', async () => {
    const clicked: string[] = []
    const w = mount(DagGraph, {
      props,
      attrs: { onNodeClick: (f: string) => clicked.push(f) }
    })
    const node = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'B'
    )!
    await node.trigger('keydown', { key: 'Enter' })
    expect(clicked).toEqual(['B'])
  })

  it('hover writes the full upstream closure to store.highlightPath', async () => {
    const store = useRamStore()
    const w = mount(DagGraph, { props })
    const cNode = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'C'
    )!
    await cNode.trigger('mouseenter')
    // Hovering terminal node C should highlight C plus all ancestors A and B.
    expect([...store.highlightPath].sort()).toEqual(['A', 'B', 'C'])
    expect(store.hoveredFile).toBe('C')
  })

  it('mouseleave clears the highlight and the hovered file', async () => {
    const store = useRamStore()
    const w = mount(DagGraph, { props })
    const cNode = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'C'
    )!
    await cNode.trigger('mouseenter')
    expect(store.highlightPath.size).toBe(3)
    await cNode.trigger('mouseleave')
    expect(store.highlightPath.size).toBe(0)
    expect(store.hoveredFile).toBeNull()
  })

  it('cycles in edges do not infinite-loop collectUpstream', async () => {
    // A->B and B->A; hovering A must terminate and return finite set {A, B}.
    const cyclicProps = {
      seeds: ['A'],
      edges: [
        { from: 'A', to: 'B', kind: 'call' as const },
        { from: 'B', to: 'A', kind: 'call' as const }
      ],
      riskScores: { A: 0.5, B: 0.5 },
      inDegree: { A: 1, B: 1 }
    }
    const store = useRamStore()
    const w = mount(DagGraph, { props: cyclicProps })
    const aNode = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'A'
    )!
    await aNode.trigger('mouseenter')
    expect([...store.highlightPath].sort()).toEqual(['A', 'B'])
  })

  it('dims non-highlighted edges to opacity 0.25 while highlighted edges stay at 1', async () => {
    const w = mount(DagGraph, { props })
    // Hover B -> upstream is {A, B}; edge A->B is highlighted, B->C is dimmed.
    const bNode = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'B'
    )!
    await bNode.trigger('mouseenter')
    const edges = w.findAll('path.dag-edge')
    expect(edges).toHaveLength(2)
    const opacities = edges.map((e) => e.attributes('opacity'))
    expect(opacities).toContain('1')
    expect(opacities).toContain('0.25')
  })

  it('renders nothing and does not crash on empty input', () => {
    const w = mount(DagGraph, {
      props: { seeds: [], edges: [], riskScores: {}, inDegree: {} }
    })
    expect(w.findAll('circle.dag-node')).toHaveLength(0)
    expect(w.findAll('path.dag-edge')).toHaveLength(0)
  })
})
