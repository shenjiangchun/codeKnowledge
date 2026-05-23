/**
 * Unit tests for CostMeter — verifies color thresholds (60% / 90%) flip
 * the level class based on usd/budget ratio.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CostMeter from '../CostMeter.vue'

describe('CostMeter', () => {
  it('renders neutral class when no budget is provided', () => {
    const w = mount(CostMeter, { props: { tokens: 100, usd: 0.5 } })
    expect(w.get('[data-test="cost-meter"]').classes()).toContain('cost-neutral')
  })

  it('is green below 60% of budget', () => {
    const w = mount(CostMeter, { props: { tokens: 100, usd: 0.59, budget: 1 } })
    expect(w.get('[data-test="cost-meter"]').classes()).toContain('cost-green')
  })

  it('is orange at 60% and below 90% of budget', () => {
    const w = mount(CostMeter, { props: { tokens: 100, usd: 0.6, budget: 1 } })
    expect(w.get('[data-test="cost-meter"]').classes()).toContain('cost-orange')
    const w2 = mount(CostMeter, { props: { tokens: 100, usd: 0.89, budget: 1 } })
    expect(w2.get('[data-test="cost-meter"]').classes()).toContain('cost-orange')
  })

  it('is red at 90% or above of budget', () => {
    const w = mount(CostMeter, { props: { tokens: 100, usd: 0.9, budget: 1 } })
    expect(w.get('[data-test="cost-meter"]').classes()).toContain('cost-red')
    const w2 = mount(CostMeter, { props: { tokens: 100, usd: 1.5, budget: 1 } })
    expect(w2.get('[data-test="cost-meter"]').classes()).toContain('cost-red')
  })

  it('formats large token counts with k/M suffix', () => {
    const w = mount(CostMeter, { props: { tokens: 12_345, usd: 0 } })
    expect(w.text()).toContain('12.3k')
    const w2 = mount(CostMeter, { props: { tokens: 2_500_000, usd: 0 } })
    expect(w2.text()).toContain('2.50M')
  })
})
