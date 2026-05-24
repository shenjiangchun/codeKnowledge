import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import FileBrowserPanel from '../FileBrowserPanel.vue'
import { useRamStore } from '@/stores/ram'

describe('FileBrowserPanel', () => {
  beforeEach(() => setActivePinia(createPinia()))
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
})
