import { test, expect, type Page } from '@playwright/test';
import { TOKEN } from './helpers/agent-flow-v2-backend';

/**
 * 工作台的端到端测试：后端接口全部由替身接管（不依赖后端启动、不产生模型调用）。
 * 断言两件事：页面调的是 /api/workbench/**，待办聚合、自动修复、监视器确认的流程正确。
 */
const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173';

interface ApiCall { method: string; path: string; body: unknown }
interface State {
  calls: ApiCall[]
  waiting: boolean
}

const ITEM = {
  id: 1,
  itemKey: 'log:D:/proj/demo-svc:fp-1',
  source: 'log',
  projectPath: 'D:/proj/demo-svc',
  title: 'NullPointerException：UserController.getUser 第 20 行',
  location: 'com.example.controller.UserController#getUser',
  severity: 'P0',
  status: 'NEW',
  confidence: 'high',
  chainStatus: 'at_throw_site',
  why: '第 20 行唯一的解引用是 userService。',
  suggestion: '检查并修复 userService 注入。',
  evidenceJson: JSON.stringify(['[E1] 堆栈帧 UserController.getUser() 第 20 行']),
  sourceRefJson: JSON.stringify({ reportId: '9001' }),
  occurrenceCount: 14,
  firstSeenAt: 1700000000,
  lastSeenAt: 1700001000,
  currentRunId: null,
  trend: [0, 1, 2, 1, 3, 2, 4],
}

const RUN = {
  id: 7,
  itemKey: ITEM.itemKey,
  sessionUuid: 'sess-wb-e2e',
  projectPath: ITEM.projectPath,
  status: 'WAITING_CONFIRM',
  currentStage: 'proposal',
  stageIndex: 1,
  iteration: 1,
  chosenOption: null,
  pushRequested: false,
  worktreePath: null,
  branchName: null,
  commitHash: null,
  pushed: false,
  errorMessage: null,
  createdAt: 1700000000,
  updatedAt: 1700000000,
}

const STAGES = [
  { index: 0, key: 'evidence', label: '代码实证', hint: '图谱定位调用链' },
  { index: 1, key: 'proposal', label: '方案确认', hint: '等你确认' },
  { index: 2, key: 'spec', label: 'Spec 生成', hint: '目标、验收标准、测试用例' },
  { index: 3, key: 'tasks', label: '任务拆分', hint: '可独立验证的任务' },
  { index: 4, key: 'develop', label: '迭代开发', hint: '按任务修改代码' },
  { index: 5, key: 'tests', label: '用例回归', hint: '执行 Spec 确定的用例' },
  { index: 6, key: 'e2e', label: '全链路 E2E', hint: '构建、启动、用例' },
  { index: 7, key: 'ship', label: '提交推送', hint: '按你的选择推送' },
]

const RUN_DETAIL = {
  ...RUN,
  stages: STAGES,
  checkpoints: {
    evidence: { graphStatus: 'found', confidence: 'high', anchors: [{ id: 'E1', text: '堆栈帧' }] },
    proposal: {
      options: [
        { id: 'A', title: '按建议直接修复', summary: '直接修', risk: 'low', effort: '0.5 天', touchedFiles: 1 },
        { id: 'B', title: '修复并补上防护', summary: '修 + 测试', risk: 'medium', effort: '1 天', touchedFiles: 3 },
      ],
      recommendation: 'B',
    },
  },
  logTail: ['开始代码实证', '证据包完成：锚点 1 条，图谱 found', '候选方案已就绪，等待确认'],
}

async function installBackend(page: Page, state: State) {
  await page.addInitScript(
    ([token]) => {
      localStorage.setItem('hisi-token', token)
      localStorage.setItem('hisi-selected-projects', '[]')
    },
    [TOKEN] as const,
  )
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()
    let body: unknown = null
    try { body = request.postDataJSON() } catch { body = null }
    state.calls.push({ method, path: url.pathname, body })
    const json = (data: unknown) => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data }),
    })
    const path = url.pathname

    if (path === '/api/auth/me') return json({ username: 'root', role: 'ADMIN' })
    if (path === '/api/config/project-dir') return json({ projectDir: 'D:/proj' })
    if (path === '/api/workbench/summary') return json({
      total: 1, open: 1, running: 0, waiting: 0, done: 0, failed: 0, ignored: 0,
      p0: 1, p1: 0, p2: 0, cleared7d: 0,
      byProject: { 'D:/proj/demo-svc': { total: 1, done: 0, open: 1 } },
    })
    if (path === '/api/workbench/items') {
      return json([{ ...ITEM, status: state.waiting ? 'WAITING' : 'NEW', currentRunId: state.waiting ? 7 : null }])
    }
    if (path === '/api/workbench/runs') return json(state.waiting ? [RUN] : [])
    if (path === '/api/workbench/items/autofix' || path.endsWith('/autofix')) return json(RUN)
    if (path === `/api/workbench/runs/${RUN.id}`) return json(RUN_DETAIL)
    if (path === `/api/workbench/runs/${RUN.id}/confirm` && method === 'POST') {
      return json({ ...RUN, status: 'RUNNING', chosenOption: body?.optionId ?? 'B', pushRequested: body?.pushRequested ?? false })
    }
    // 监视器里的事件流（SSE）
    if (path === `/api/v2/workflow/sessions/${RUN.sessionUuid}/stream`) {
      const events = [
        { seq: 1, type: 'NODE_START', payload: { nodeName: 'evidence' } },
        { seq: 2, type: 'CHECKPOINT', payload: { nodeName: 'evidence', output: RUN_DETAIL.checkpoints.evidence } },
        { seq: 3, type: 'CHECKPOINT', payload: { nodeName: 'proposal', output: RUN_DETAIL.checkpoints.proposal } },
      ]
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache' },
        body: events.map(e => `data:${JSON.stringify(e)}\n\n`).join(''),
      })
    }
    return route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ code: 404, message: `E2E 未预期: ${method} ${path}` }),
    })
  })
}

test.describe('工作台', () => {
  test('聚合待办渲染：总览数字、表格行、证据强度', async ({ page }) => {
    const state: State = { calls: [], waiting: false }
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/workbench`)

    // 总览：余额 1、清零进度 0%
    await expect(page.locator('.m-stat').first()).toContainText('技术债余额')
    await expect(page.locator('.m-stat').first()).toContainText('1')
    await expect(page.locator('.m-stat').nth(1)).toContainText('0%')
    // 表格里出现待办
    await expect(page.locator('tbody')).toContainText('NullPointerException：UserController.getUser 第 20 行')
    await expect(page.locator('tbody')).toContainText('demo-svc')
    await expect(page.locator('tbody .wb-tag.sev-P0')).toHaveText('P0')

    // 读的是工作台接口
    expect(state.calls.map(c => c.path)).toContain('/api/workbench/summary')
    expect(state.calls.map(c => c.path)).toContain('/api/workbench/items')
  })

  test('自动修复：请求体正确，运行进入流水线，监视器里确认方案', async ({ page }) => {
    const state: State = { calls: [], waiting: false }
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/workbench`)
    await expect(page.locator('tbody')).toContainText('NullPointerException')

    // 点自动修复 → 发起 POST
    await page.locator('tbody .c-actions .el-button').first().click()
    const autofixCall = state.calls.find(c => c.method === 'POST' && c.path.endsWith('/autofix'))
    expect(autofixCall?.body).toEqual({ pushRequested: false })

    // 后端现在处于等待确认：流水线出现，监视器可打开
    state.waiting = true
    await page.getByRole('button', { name: '刷新' }).click()
    await expect(page.locator('.pipe')).toBeVisible()
    await expect(page.locator('.tile')).toContainText('等你确认方案')

    await page.locator('.tile').click()
    await expect(page.locator('.monitor')).toBeVisible()
    await expect(page.locator('.mo-tl li.waiting')).toContainText('方案确认')
    await expect(page.locator('.confirm-box')).toBeVisible()
    // 方案列表来自 checkpoint
    await expect(page.locator('.confirm-option')).toHaveCount(2)

    // 选 B + 勾选推送 + 确认
    await page.locator('.confirm-option').nth(1).click()
    await page.locator('.push-choice input').check()
    await page.getByRole('button', { name: '确认并继续' }).click()

    const confirmCall = state.calls.find(c => c.method === 'POST' && c.path.endsWith('/confirm'))
    expect(confirmCall?.body).toEqual({ optionId: 'B', pushRequested: true })
  })

  test('批量选择与立即聚合按钮', async ({ page }) => {
    const state: State = { calls: [], waiting: false }
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/workbench`)
    await expect(page.locator('tbody tr')).toHaveCount(1)

    // 勾选 → 批量按钮出现
    await page.locator('tbody input[type=checkbox]').check()
    await expect(page.getByRole('button', { name: '批量自动修复' })).toBeVisible()

    // 立即聚合 → POST sync
    await page.getByRole('button', { name: '立即聚合' }).click()
    expect(state.calls.some(c => c.method === 'POST' && c.path === '/api/workbench/items/sync')).toBe(true)
  })
})
