import { describe, expect, it, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ElementPlus from 'element-plus'
import { useAppStore } from '@/stores/app'
import ArchitectureDiagnosisView from '../ArchitectureDiagnosisView.vue'

// Mock the API module so report generation / snapshot loading is fully controllable.
const { getReportMock, getSnapshotsMock, getSnapshotReportMock, exportReportMock } = vi.hoisted(() => ({
  getReportMock: vi.fn(),
  getSnapshotsMock: vi.fn(),
  getSnapshotReportMock: vi.fn(),
  exportReportMock: vi.fn()
}))

vi.mock('@/api/architectureDiagnosis', () => ({
  getReport: getReportMock,
  getSnapshots: getSnapshotsMock,
  getSnapshotReport: getSnapshotReportMock,
  exportReport: exportReportMock
}))

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

function reportOf(healthScore: number, grade: string) {
  return {
    healthScore,
    grade,
    deductions: [],
    issues: [],
    chapters: {
      project: {},
      module: {
        dsm: { modules: ['com.a.Foo', 'com.b.Bar'], cells: [{ sourceIdx: 0, targetIdx: 1, weight: 3 }], level: 'package' }
      },
      class: {
        callGraph: {
          nodes: [
            { className: 'com.a.Foo', packageName: 'com.a' },
            { className: 'com.b.Bar', packageName: 'com.b' }
          ],
          edges: [{ source: 'com.a.Foo', target: 'com.b.Bar', weight: 3 }]
        }
      },
      method: {}
    },
    layerOrder: [],
    projectPaths: [],
    generatedAt: '2026-10-13 10:00:00'
  }
}

function snapshot(id: number, healthScore: number, grade: string) {
  return {
    id,
    scope: '/a',
    healthScore,
    grade,
    highCount: 0,
    mediumCount: 0,
    lowCount: 0,
    createdAt: '2026-10-13 10:00:00'
  }
}

describe('ArchitectureDiagnosisView history snapshot flow', () => {
  let pinia: Pinia

  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    getReportMock.mockReset()
    getSnapshotsMock.mockReset()
    getSnapshotReportMock.mockReset()
    exportReportMock.mockReset()
    getSnapshotsMock.mockResolvedValue({ snapshots: [], total: 0 })
    ;(URL as any).createObjectURL = vi.fn(() => 'blob:mock')
    ;(URL as any).revokeObjectURL = vi.fn()
  })

  it('entering page shows latest snapshot without auto-generating (refresh flow)', { timeout: 20000 }, async () => {
    localStorage.setItem('hisi-selected-projects', JSON.stringify([{ name: 'A', path: '/a' }]))
    getSnapshotsMock.mockResolvedValue({ snapshots: [snapshot(7, 88, 'B')], total: 1 })
    getSnapshotReportMock.mockResolvedValue(reportOf(88, 'B'))

    const wrapper = mount(ArchitectureDiagnosisView, { global: { plugins: [ElementPlus, pinia] } })
    await flushPromises()
    await nextTick()

    // 进入页面展示最新快照，不触发实时生成
    expect(getReportMock).not.toHaveBeenCalled()
    expect(getSnapshotsMock).toHaveBeenCalledWith('/a')
    expect(getSnapshotReportMock).toHaveBeenCalledWith(7)
    expect(wrapper.find('.score-number').text().trim()).toBe('88')
  })

  it('shows empty state when no snapshot exists and does not auto-generate', { timeout: 20000 }, async () => {
    localStorage.setItem('hisi-selected-projects', JSON.stringify([{ name: 'A', path: '/a' }]))
    getSnapshotsMock.mockResolvedValue({ snapshots: [], total: 0 })

    const wrapper = mount(ArchitectureDiagnosisView, { global: { plugins: [ElementPlus, pinia] } })
    await flushPromises()
    await nextTick()

    expect(getReportMock).not.toHaveBeenCalled()
    expect(getSnapshotReportMock).not.toHaveBeenCalled()
    expect(wrapper.find('.score-number').exists()).toBe(false)
  })

  it('view button loads a historical snapshot into the report area', { timeout: 20000 }, async () => {
    localStorage.setItem('hisi-selected-projects', JSON.stringify([{ name: 'A', path: '/a' }]))
    getSnapshotsMock.mockResolvedValue({
      snapshots: [snapshot(1, 88, 'B'), snapshot(2, 60, 'C')],
      total: 2
    })
    getSnapshotReportMock.mockImplementation(async (id: number) => {
      return id === 1 ? reportOf(88, 'B') : reportOf(60, 'C')
    })

    const wrapper = mount(ArchitectureDiagnosisView, { global: { plugins: [ElementPlus, pinia] } })
    await flushPromises()
    await nextTick()

    // 进入页面自动展示最新（id=1）
    expect(wrapper.find('.score-number').text().trim()).toBe('88')

    // 点击第二行「查看」→ 展示 id=2 的历史快照
    const viewButtons = wrapper.findAll('button').filter(b => b.text().trim() === '查看')
    expect(viewButtons.length).toBeGreaterThanOrEqual(2)
    await viewButtons[1].trigger('click')
    await flushPromises()

    expect(getSnapshotReportMock).toHaveBeenCalledWith(2)
    expect(wrapper.find('.score-number').text().trim()).toBe('60')
  })

  it('exports the displayed historical snapshot via snapshotId', { timeout: 20000 }, async () => {
    localStorage.setItem('hisi-selected-projects', JSON.stringify([{ name: 'A', path: '/a' }]))
    getSnapshotsMock.mockResolvedValue({ snapshots: [snapshot(7, 88, 'B')], total: 1 })
    getSnapshotReportMock.mockResolvedValue(reportOf(88, 'B'))
    exportReportMock.mockResolvedValue(new Blob(['markdown']))

    const wrapper = mount(ArchitectureDiagnosisView, { global: { plugins: [ElementPlus, pinia] } })
    await flushPromises()
    await nextTick()

    const exportBtn = wrapper.findAll('button').find(b => b.text().includes('导出 Markdown'))!
    await exportBtn.trigger('click')
    await flushPromises()

    expect(exportReportMock).toHaveBeenCalledWith({ snapshotId: 7 })
  })

  it('ignores stale snapshot response when switching projects (race)', { timeout: 20000 }, async () => {
    localStorage.setItem('hisi-selected-projects', JSON.stringify([
      { name: 'A', path: '/a' },
      { name: 'B', path: '/b' }
    ]))
    getSnapshotsMock.mockImplementation(async (scope: string) => {
      return scope === '/a'
        ? { snapshots: [snapshot(1, 88, 'B')], total: 1 }
        : { snapshots: [snapshot(2, 99, 'A')], total: 1 }
    })
    const pending = [] as { promise: Promise<unknown>; resolve: (v: unknown) => void }[]
    getSnapshotReportMock.mockImplementation(() => {
      const d = deferred<unknown>()
      pending.push(d)
      return d.promise
    })

    const store = useAppStore()
    const wrapper = mount(ArchitectureDiagnosisView, { global: { plugins: [ElementPlus, pinia] } })
    await flushPromises()
    await nextTick()

    store.selectProjects([{ name: 'B', path: '/b' }])
    await flushPromises()
    await nextTick()

    expect(getSnapshotReportMock).toHaveBeenCalledTimes(2)

    // 后发先至：先解析 B（新），再解析 A（旧）
    pending[1].resolve(reportOf(99, 'A'))
    await flushPromises()
    pending[0].resolve(reportOf(11, 'D'))
    await flushPromises()

    expect(wrapper.find('.score-number').text().trim()).toBe('99')
  })
})
