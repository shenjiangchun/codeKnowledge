import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import GraphPreviewPage from '../GraphPreviewPage.vue'
import { useRamStore } from '@/stores/ram'

describe('GraphPreviewPage', () => {
  beforeEach(() => setActivePinia(createPinia()))

  async function mountPage() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/:sid', name: 'RamGraph', component: GraphPreviewPage },
        { path: '/draft/:sid', name: 'RamDraft', component: { template: '<div/>' } }
      ]
    })
    router.push('/sess-1')
    await router.isReady()
    const store = useRamStore()
    store.impact = {
      involved: ['A.java'],
      modified: ['B.java'],
      impacted: ['C.java'],
      riskScores: { 'A.java': 0.8 }
    }
    return mount(GraphPreviewPage, { global: { plugins: [router] } })
  }

  it('renders DagGraph in main canvas', async () => {
    const w = await mountPage()
    expect(w.find('.dag-graph').exists()).toBe(true)
  })

  it('renders FileBrowserPanel in side column', async () => {
    const w = await mountPage()
    expect(w.find('.file-browser').exists()).toBe(true)
  })

  it('renders Minimap overlay', async () => {
    const w = await mountPage()
    expect(w.find('.minimap').exists()).toBe(true)
  })
})
