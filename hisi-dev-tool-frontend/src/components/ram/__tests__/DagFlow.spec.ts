/**
 * DagFlow component tests — verifies the 5-card layout, status pills, and
 * click event emission.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DagFlow from '../DagFlow.vue'
import { deriveDagSnapshot, type DagNodeSnapshot } from '../dagModel'

const baseNodes = (): DagNodeSnapshot[] => [
  { key: 'clarify', label: '澄清', status: 'done', tokens: 1200, events: 5 },
  { key: 'impact', label: '影响', status: 'running', tokens: 3400, events: 8, riskLevel: 'HIGH' },
  { key: 'implement', label: '实现', status: 'pending', tokens: 0, events: 0 },
  { key: 'verify', label: '验证', status: 'pending', tokens: 0, events: 0 },
  { key: 'tech_plan', label: '技术方案', status: 'pending', tokens: 0, events: 0 }
]

describe('DagFlow', () => {
  it('renders one card per node', () => {
    const wrapper = mount(DagFlow, { props: { nodes: baseNodes() } })
    expect(wrapper.findAll('g.dag-card-group')).toHaveLength(5)
  })

  it('renders edges between consecutive nodes (n-1 edges)', () => {
    const wrapper = mount(DagFlow, { props: { nodes: baseNodes() } })
    expect(wrapper.findAll('line.dag-edge')).toHaveLength(4)
  })

  it('marks the edge entering a running node as flowing', () => {
    const wrapper = mount(DagFlow, { props: { nodes: baseNodes() } })
    const flowing = wrapper.findAll('line.dag-edge--flowing')
    // The "running" node is impact; edge clarify→impact must flow.
    expect(flowing).toHaveLength(1)
    expect(flowing[0].attributes('data-to')).toBe('impact')
  })

  it('renders a risk badge when riskLevel is present', () => {
    const wrapper = mount(DagFlow, { props: { nodes: baseNodes() } })
    expect(wrapper.find('g.dag-card-risk').exists()).toBe(true)
  })

  it('omits risk badges when no riskLevel set', () => {
    const nodes = baseNodes().map((n) => ({ ...n, riskLevel: undefined }))
    const wrapper = mount(DagFlow, { props: { nodes } })
    expect(wrapper.findAll('g.dag-card-risk')).toHaveLength(0)
  })

  it('emits nodeClick with the key when a card is clicked', async () => {
    const clicked: string[] = []
    const wrapper = mount(DagFlow, {
      props: { nodes: baseNodes() },
      attrs: { onNodeClick: (k: string) => clicked.push(k) }
    })
    ;(wrapper.vm as unknown as { onCardClick: (k: string) => void }).onCardClick('impact')
    expect(clicked).toEqual(['impact'])
  })

  it('highlights the active card', () => {
    const wrapper = mount(DagFlow, {
      props: { nodes: baseNodes(), activeKey: 'impact' }
    })
    const active = wrapper.findAll('g.dag-card-group--active')
    expect(active).toHaveLength(1)
    expect(active[0].attributes('data-key')).toBe('impact')
  })

  it('integrates with deriveDagSnapshot for an end-to-end stream', () => {
    const snap = deriveDagSnapshot(
      [
        { seq: 1, type: 'ASSISTANT_DELTA', payload: { phase: 'clarify', usage: { tokens: 100 } } },
        { seq: 2, type: 'ASSISTANT_DELTA', payload: { phase: 'impact', usage: { tokens: 500 } } }
      ],
      'running'
    )
    const wrapper = mount(DagFlow, { props: { nodes: snap } })
    expect(wrapper.findAll('g.dag-card-group')).toHaveLength(5)
  })
})
