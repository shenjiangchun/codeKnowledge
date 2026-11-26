import { test, expect } from '@playwright/test';
import {
  TOKEN, REPORT_ID, SESSION, PROJECT, newState, callsOn, countCalls, installBackend,
} from './helpers/agent-flow-v2-backend';

/**
 * Agent 流 v2 的页面级端到端测试。后端与 WebSocket 由 helpers/agent-flow-v2-backend.ts 替换。
 * 每个用例都断言两件事：页面调的确实是 v2 接口（没有碰 v1），节点进度与报告能正确显示。
 */
const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173';

test.describe('Agent 流 v2 · 日志报告详情页', () => {
  test('v2 报告按结构渲染：摘要、根因正文、验证结果、证据、修复建议、关键堆栈帧', async ({ page }) => {
    const state = newState()
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/log-analysis/report/${REPORT_ID}`)

    // 读接口走 v2
    await expect.poll(() => callsOn(state, '/api/v2/log/')).toEqual([
      `GET /api/v2/log/report/${REPORT_ID}/status`,
      `GET /api/v2/log/report/${REPORT_ID}`,
    ])

    // 错误摘要按字段展示
    const summary = page.locator('.el-descriptions').filter({ hasText: '异常类型' })
    await expect(summary).toContainText('NullPointerException')
    await expect(summary).toContainText('UserController.java:20')

    // 根因正文是渲染后的 markdown，不是对象转储
    await expect(page.locator('.root-cause h2')).toHaveText('根因结论')
    await expect(page.locator('.root-cause')).toContainText('[E4]')
    await expect(page.locator('.root-cause')).not.toContainText('evidenceAnchors')
    await expect(page.locator('.section-title').filter({ hasText: '根本原因' })).toContainText('置信度: low')

    // 验证结果与证据表
    await expect(page.locator('.verify-reason')).toHaveText('根因落在异常抛出处，未见更上游的依据')
    await expect(page.locator('.el-table').filter({ hasText: '因果链' })).toContainText('根因落在抛出点')
    await expect(page.locator('.el-table').filter({ hasText: '编号' })).toContainText('堆栈帧')

    // 修复建议是带优先级的列表；关键堆栈帧
    await expect(page.locator('.fix-item')).toHaveCount(2)
    await expect(page.locator('.fix-item .el-tag')).toHaveText(['P0', 'P1'])
    await expect(page.locator('.fix-item').first()).toContainText('预期效果：消除第 20 行的空指针访问')
    await expect(page.locator('.frame-item')).toContainText('UserController.java:20')

    // 页面上不出现原始引用标记
    await expect(page.locator('body')).not.toContainText('[[')

    expect(callsOn(state, '/api/log/')).toEqual([])
  })

  test('重新分析：走 v2 接口，节点进度按后端顺序显示，结束后自动刷新报告', async ({ page }) => {
    const state = newState()
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/log-analysis/report/${REPORT_ID}`)
    await expect(page.locator('.root-cause h2')).toHaveText('根因结论')

    const reanalyze = page.getByRole('button', { name: '重新分析' })
    await reanalyze.click()

    // 进入分析中：先连上进度通道，此时还没有节点事件
    const timeline = page.locator('.node-timeline')
    await expect(timeline).toBeVisible()
    await expect(timeline).toContainText('等待节点事件')
    await expect(reanalyze).toBeDisabled()
    await expect.poll(() => state.logSockets.length).toBe(1)

    const socket = state.logSockets[0]
    const send = (event: Record<string, unknown>) =>
      socket.send(JSON.stringify({ reportId: Number(REPORT_ID), timestamp: Date.now(), ...event }))

    send({ type: 'NODE_START', nodeName: 'ParseNode' })
    send({ type: 'NODE_COMPLETE', nodeName: 'ParseNode', payload: { durationMs: 4, summary: [] } })
    send({ type: 'NODE_START', nodeName: 'KgEvidenceV2' })
    send({ type: 'NODE_COMPLETE', nodeName: 'KgEvidenceV2', payload: { durationMs: 521, summary: [] } })
    send({ type: 'NODE_START', nodeName: 'HypothesisV2' })

    await expect(timeline).toContainText('执行中')
    await expect(timeline.locator('.node-label')).toHaveText(['日志解析', '图谱取证', '根因假设'])
    await expect(timeline).toContainText('521ms')
    // 分析还没结束，不会提前显示「完成」
    await expect(timeline.locator('.timeline-header')).not.toContainText('完成')

    send({ type: 'NODE_COMPLETE', nodeName: 'HypothesisV2', payload: { durationMs: 48000, summary: [] } })
    for (const [name, durationMs] of [
      ['VerifyV2', 17],
      ['SynthesisV2', 44000],
      ['ReportNode', 1],
    ] as const) {
      send({ type: 'NODE_START', nodeName: name })
      send({ type: 'NODE_COMPLETE', nodeName: name, payload: { durationMs, summary: [] } })
    }
    send({ type: 'DAG_COMPLETE', nodeName: '_dag', payload: { totalDurationMs: 92543 } })
    await expect(timeline.locator('.timeline-header')).toContainText('完成')
    await expect(timeline.locator('.node-label')).toHaveText([
      '日志解析', '图谱取证', '根因假设', '验证', '综合成文', '报告生成',
    ])

    // 任务状态变为完成后：报告重新加载，进度条收起，按钮恢复
    state.reportStatus = 'completed'
    state.updatedAt = '2026-11-25T10:05:00'
    await expect(page.locator('.el-descriptions').filter({ hasText: '更新时间' })).toContainText('2026/11/25 10:05:00', { timeout: 15000 })
    await expect(timeline).toBeHidden()
    await expect(reanalyze).toBeEnabled()

    // 整个过程只调了一次 v2 的重新分析；报告正文取了两次（进入页面一次、分析结束后一次），状态接口被轮询过；
    // 没有碰 v1 的日志接口
    expect(countCalls(state, 'POST', `/api/v2/log/report/${REPORT_ID}/reanalyze`)).toBe(1)
    expect(countCalls(state, 'GET', `/api/v2/log/report/${REPORT_ID}`)).toBe(2)
    expect(countCalls(state, 'GET', `/api/v2/log/report/${REPORT_ID}/status`)).toBeGreaterThanOrEqual(2)
    expect(callsOn(state, '/api/log/')).toEqual([])
  })

  test('报告仍在分析中时打开页面：直接显示节点进度，不去取未完成的报告', async ({ page }) => {
    const state = { ...newState(), reportStatus: 'processing' as const }
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/log-analysis/report/${REPORT_ID}`)

    await expect(page.locator('.node-timeline')).toBeVisible()
    await expect(page.locator('.node-timeline')).toContainText('等待节点事件')
    await expect(page.getByRole('button', { name: '重新分析' })).toBeDisabled()
    await expect.poll(() => state.logSockets.length).toBe(1)
    expect(countCalls(state, 'GET', `/api/v2/log/report/${REPORT_ID}`)).toBe(0)

    // 分析结束：报告加载出来
    state.reportStatus = 'completed'
    await expect(page.locator('.root-cause h2')).toHaveText('根因结论', { timeout: 15000 })
    await expect(page.locator('.node-timeline')).toBeHidden()
  })

  test('分析失败：提示失败、进度条收起、按钮恢复，页面显示失败状态而不是上一轮的正文', async ({ page }) => {
    const state = newState()
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/log-analysis/report/${REPORT_ID}`)
    await expect(page.locator('.root-cause h2')).toHaveText('根因结论')

    await page.getByRole('button', { name: '重新分析' }).click()
    await expect(page.locator('.node-timeline')).toBeVisible()

    // 这一轮分析失败：任务状态接口返回 failed
    state.reportStatus = 'failed'
    state.updatedAt = '2026-11-25T10:06:00'

    await expect(page.locator('.el-message').filter({ hasText: '重新分析失败' })).toBeVisible()
    await expect(page.locator('.node-timeline')).toBeHidden()
    await expect(page.getByRole('button', { name: '重新分析' })).toBeEnabled()
    // 结果列在重新分析时已清空：显示失败状态与空结果，不保留上一轮的正文
    await expect(page.locator('.el-descriptions').filter({ hasText: '状态' })).toContainText('failed')
    await expect(page.getByText('暂无分析结果')).toBeVisible()
    await expect(page.locator('.root-cause')).toHaveCount(0)
  })
})

test.describe('Agent 流 v2 · 架构深度分析', () => {
  test('开始分析走 v2 工作流接口，事件流以 token 查询参数鉴权，进度与报告正常显示', async ({ page }) => {
    const state = newState()
    await installBackend(page, state)
    await page.goto(`${BASE_URL}/architecture`)

    await page.getByRole('button', { name: /AI 深度分析/ }).click()
    // 页头的项目切换框与面板里的项目选择框下拉后选项同名，只点可见的那个
    await page.locator('.project-bar .el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: PROJECT.name }).click()
    await page.getByRole('button', { name: '开始分析' }).click()

    const panel = page.locator('.ai-deep-analysis')
    // 两个节点的名字与 v1 一致（页面写死了它们）
    await expect(panel.locator('.node-progress .node-label')).toHaveText(['证据收集', 'ReAct 分析'])
    await expect(panel.locator('.node-progress .node-dot .done')).toHaveCount(2)
    await expect(panel.locator('.process-log')).toContainText('推理（第 1 轮）…')
    // 报告按结构化小节渲染：执行摘要、视图、问题分析、路线图
    await expect(panel.locator('.report-section h3.section-title').first()).toHaveText('执行摘要')
    await expect(panel.locator('.summary-text').first()).toHaveText('项目架构健康度 10 分。')
    await expect(panel.locator('.issue-card .issue-title')).toHaveText(['知识图谱域与语义搜索域存在循环依赖'])
    await expect(panel.locator('.roadmap-action')).toHaveText(['拆除知识图谱域两个循环依赖'])

    const start = state.calls.find((c) => c.path === '/api/v2/workflow/start')
    expect(start?.method).toBe('POST')
    expect(start?.body).toEqual({
      workflowType: 'arch-deep-analysis',
      input: { projectPaths: [PROJECT.path] },
    })

    // 浏览器的 EventSource 带不了请求头，token 必须放在查询参数里
    const stream = state.calls.find((c) => c.path.endsWith('/stream'))
    expect(stream?.path).toBe(`/api/v2/workflow/sessions/${SESSION}/stream`)
    const query = new URLSearchParams(stream?.search ?? '')
    expect(query.get('token')).toBe(TOKEN)
    expect(query.get('afterSeq')).toBe('0')

    // 没有碰 v1 的工作流接口
    expect(callsOn(state, '/api/workflow/')).toEqual([])
  })
})
