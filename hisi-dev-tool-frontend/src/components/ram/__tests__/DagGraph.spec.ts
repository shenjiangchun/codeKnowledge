import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DagGraph from '../DagGraph.vue'

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
})
