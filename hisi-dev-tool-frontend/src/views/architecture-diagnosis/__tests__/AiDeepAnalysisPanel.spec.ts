/**
 * 回归：架构「AI 深度分析」面板无法回放历史分析。
 *
 * 故障：历史会话 id 存在 localStorage，键里拼了面板勾选的项目路径；而面板勾选不跨刷新保留，
 * 挂载时恒为空 → 读到的是空串键 → 永远对不上 → 只能重新跑一遍分析。
 *
 * 这组用例 mock 掉底层 request（useWorkflow 真实运行，rejoin 真实走到 REST 调用），
 * 通过「哪些会话 id 被请求了」来断言是否发生了回放，不断言模型生成的文案。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const getMock = vi.fn()
const postMock = vi.fn()
vi.mock('@/utils/request', () => ({
  default: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
    defaults: { baseURL: '' },
  },
}))

import AiDeepAnalysisPanel from '../AiDeepAnalysisPanel.vue'

const PROJECTS = [
  { name: 'hisi-dev-tool', path: 'C:/proj/hisi-dev-tool' },
  { name: 'hisi-dev-tool-frontend', path: 'C:/proj/hisi-dev-tool-frontend' },
]
const KEY_ALL = 'arch-deep-analysis-session:' + PROJECTS.map(p => p.path).join('|')

/** 让 rejoin 拿到一个「已完成、带报告」的会话；返回被请求过的会话 id。 */
function stubCompletedSession() {
  const requested = new Set<string>()
  getMock.mockImplementation(async (url: string) => {
    const m = url.match(/\/sessions\/([^/]+)\/(status|report|events)$/)
    if (!m) return {}
    requested.add(m[1])
    if (m[2] === 'status') return { status: 'DONE' }
    if (m[2] === 'report') return { report: { markdownReport: '# 历史报告' } }
    return []
  })
  return requested
}

function mountPanel(props: { availableProjects: { name: string; path: string }[] }) {
  const stubs = { MermaidDiagram: true }
  return mount(AiDeepAnalysisPanel, { props, global: { stubs } })
}

describe('AiDeepAnalysisPanel 历史回放', () => {
  beforeEach(() => {
    // 先清场；用例随后再写入各自需要的历史记录（clear 不能放在 mountPanel 里，否则会把刚写的记录清掉）
    getMock.mockReset()
    postMock.mockReset()
    globalThis.localStorage.clear()
  })

  it('刷新后（面板勾选为空）仍能按全局已选项目回放历史——原故障场景', async () => {
    globalThis.localStorage.setItem(KEY_ALL, 'sid-history-1')
    const requested = stubCompletedSession()

    const w = mountPanel({ availableProjects: PROJECTS })
    await flushPromises()

    expect([...requested]).toContain('sid-history-1')
    // 是回放历史，不是悄悄发起新分析
    expect(postMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('没有历史时不发任何请求（不能凭空 rejoin）', async () => {
    const requested = stubCompletedSession()

    const w = mountPanel({ availableProjects: PROJECTS })
    await flushPromises()

    expect([...requested]).toStrictEqual([])
    w.unmount()
  })

  it('全局项目稍后才就绪（挂载时为空）也能回放——不只读一次', async () => {
    globalThis.localStorage.setItem(KEY_ALL, 'sid-history-2')
    const requested = stubCompletedSession()

    const w = mountPanel({ availableProjects: [] })
    await flushPromises()
    expect([...requested]).toStrictEqual([])

    await w.setProps({ availableProjects: PROJECTS })
    await flushPromises()
    expect([...requested]).toContain('sid-history-2')
    w.unmount()
  })

  it('只按当初分析的那组项目回放：键不同（项目集合不同）时不读别人的历史', async () => {
    const onlyFirst = 'arch-deep-analysis-session:' + PROJECTS[0].path
    globalThis.localStorage.setItem(onlyFirst, 'sid-other-project')
    const requested = stubCompletedSession()

    const w = mountPanel({ availableProjects: PROJECTS })
    await flushPromises()

    expect([...requested]).toStrictEqual([])
    w.unmount()
  })

  it('历史会话已失效：清掉失效记录，不让每次进页面都去拉一个不存在的会话', async () => {
    globalThis.localStorage.setItem(KEY_ALL, 'sid-gone')
    let calls = 0
    getMock.mockImplementation(async () => {
      calls += 1
      throw new Error('404')
    })

    const w = mountPanel({ availableProjects: PROJECTS })
    await flushPromises()

    expect(calls).toBeGreaterThan(0)
    expect(globalThis.localStorage.getItem(KEY_ALL)).toBeNull()
    w.unmount()
  })
})
