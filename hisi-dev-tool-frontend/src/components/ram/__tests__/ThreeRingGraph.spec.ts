/**
 * Unit test for ThreeRingGraph — SVG concentric ring visualization.
 *
 * Asserts:
 *  - 3 ring outlines render with brand colors (gold / orange / grey).
 *  - Each input file produces a node circle (6 nodes for 1/2/3 inputs).
 *  - Clicking a node emits {@code nodeClick} with the file path.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ThreeRingGraph from '../ThreeRingGraph.vue'

const INVOLVED = '#FFD700'
const MODIFIED = '#FF8C00'
const IMPACTED = '#B0B0B0'

describe('ThreeRingGraph', () => {
  const props = {
    involved: ['a.java'],
    modified: ['b.java', 'c.java'],
    impacted: ['d.java', 'e.java', 'f.java']
  }

  it('renders 3 concentric ring outlines with the brand colors', () => {
    const wrapper = mount(ThreeRingGraph, { props })
    const rings = wrapper.findAll('circle.ring-outline')
    expect(rings).toHaveLength(3)
    const strokes = rings.map((r) => r.attributes('stroke')?.toUpperCase())
    expect(strokes).toContain(INVOLVED)
    expect(strokes).toContain(MODIFIED)
    expect(strokes).toContain(IMPACTED)
  })

  it('renders one node circle per input file', () => {
    const wrapper = mount(ThreeRingGraph, { props })
    const nodes = wrapper.findAll('circle.ring-node')
    expect(nodes).toHaveLength(6)
  })

  it('emits nodeClick with the file path when a node is clicked', async () => {
    const onNodeClick = (file: string) => clicked.push(file)
    const clicked: string[] = []
    const wrapper = mount(ThreeRingGraph, {
      props,
      attrs: { onNodeClick }
    })
    // Find the <g> wrapper for b.java and trigger native click — this drives
    // the template @click handler reliably under happy-dom (avoiding SVG
    // bubbling issues with inner <circle>).
    const groups = wrapper.findAll('g.ring-node-group')
    const target = groups.find((g) => g.find('circle').attributes('data-file') === 'b.java')
    expect(target).toBeTruthy()
    await target!.trigger('click')
    expect(clicked).toEqual(['b.java'])
  })
})
