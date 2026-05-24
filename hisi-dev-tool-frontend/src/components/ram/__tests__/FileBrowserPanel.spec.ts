import { describe, expect, it, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import FileBrowserPanel from '../FileBrowserPanel.vue'
import { useRamStore } from '@/stores/ram'

describe('FileBrowserPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // happy-dom lacks URL.createObjectURL
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:stub', revokeObjectURL: () => {} })
  })
  const props = {
    involved: ['com/foo/A.java'],
    modified: ['com/foo/B.java'],
    impacted: ['com/bar/C.java', 'com/bar/D.java'],
    riskScores: { 'com/foo/A.java': 0.9 }
  }

  it('renders all files', () => {
    const w = mount(FileBrowserPanel, { props })
    expect(w.findAll('.file-row')).toHaveLength(4)
  })

  it('filters by search keyword', async () => {
    const w = mount(FileBrowserPanel, { props })
    await w.find('input.search').setValue('bar')
    expect(w.findAll('.file-row')).toHaveLength(2)
  })

  it('groups by package when group=package', async () => {
    const w = mount(FileBrowserPanel, { props: { ...props, groupBy: 'package' as const } })
    const groups = w.findAll('.file-group-header')
    expect(groups.length).toBeGreaterThanOrEqual(2)
  })

  it('highlights the store.selectedFile', async () => {
    const w = mount(FileBrowserPanel, { props })
    const store = useRamStore()
    store.selectFile('com/foo/A.java')
    await w.vm.$nextTick()
    expect(w.find('.file-row.is-selected').attributes('data-file')).toBe('com/foo/A.java')
  })

  it('clicking a row calls store.selectFile', async () => {
    const w = mount(FileBrowserPanel, { props })
    const store = useRamStore()
    await w.find('[data-file="com/foo/A.java"]').trigger('click')
    expect(store.selectedFile).toBe('com/foo/A.java')
  })

  it('hovering a row sets store.hoveredFile and applies .is-hovered', async () => {
    const w = mount(FileBrowserPanel, { props })
    const store = useRamStore()
    const row = w.find('[data-file="com/foo/A.java"]')
    await row.trigger('mouseenter')
    expect(store.hoveredFile).toBe('com/foo/A.java')
    expect(w.find('.file-row.is-hovered').exists()).toBe(true)
    await row.trigger('mouseleave')
    expect(store.hoveredFile).toBeNull()
    expect(w.find('.file-row.is-hovered').exists()).toBe(false)
  })

  it("checkbox toggles row's is-checked class", async () => {
    const w = mount(FileBrowserPanel, { props })
    const row = w.find('[data-file="com/foo/A.java"]')
    const box = row.find('input[type="checkbox"]')
    await box.trigger('click')
    expect(w.find('[data-file="com/foo/A.java"]').classes()).toContain('is-checked')
    await box.trigger('click')
    expect(w.find('[data-file="com/foo/A.java"]').classes()).not.toContain('is-checked')
  })

  it('Enter key on a row activates it like click', async () => {
    const w = mount(FileBrowserPanel, { props })
    const store = useRamStore()
    await w.find('[data-file="com/bar/C.java"]').trigger('keydown.enter')
    expect(store.selectedFile).toBe('com/bar/C.java')
  })

  it('ring grouping headers include counts in 涉及 / 修改 / 影响 format', () => {
    const w = mount(FileBrowserPanel, { props })
    const headers = w.findAll('.file-group-header').map((h) => h.text())
    expect(headers.some((t) => t.includes('涉及 (1)'))).toBe(true)
    expect(headers.some((t) => t.includes('修改 (1)'))).toBe(true)
    expect(headers.some((t) => t.includes('影响 (2)'))).toBe(true)
  })
})
