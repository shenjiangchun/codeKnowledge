import { expect, test } from '@playwright/test'
import { api, loginToken, openAsAdmin, V4_BACKEND } from './helpers/real'

/**
 * 技术债看板 + 自动修复 agent 流（真实后端、真实模型调用，被分析的项目是 v4.0 后端）。
 *
 * 看板聚合 → 对一条 v4.0 的待办点「自动修复」→ 流水线出现 → 打开监视器 → 在方案确认门选方案并确认
 * → 跟到终态，断言跑完（DONE）且 8 个阶段全部完成。
 * 待办标题用环境变量 WB_ITEM_TITLE 指定。默认是一条日志分析的 NPE 待办：局部可验证的缺陷自动修复能收敛；
 * 「拆包循环」这类结构性重构在 3 轮迭代内不收敛（run 16 实证），不适合作为这条用例的对象。
 */
const ITEM_TITLE = process.env.WB_ITEM_TITLE ?? 'NullPointerException：2026-11-26 00:10:00.123'
const GATE_TIMEOUT = 25 * 60_000
const RUN_TIMEOUT = 60 * 60_000

test('技术债看板聚合 v4.0 待办，自动修复流在监视器里确认方案并跑到终态', async ({ page, request }) => {
  await openAsAdmin(page, request, '/workbench')

  // ── 看板：总览与待办表 ──
  await expect(page.locator('.wb-title')).toHaveText('工作台')
  await expect(page.locator('.m-stat').first()).toContainText('技术债余额')
  await expect(page.locator('tbody tr').first()).toBeVisible()

  // v4.0 的待办在看板上：架构（来源标成「架构·AI」）+ 日志分析产生的待办。
  // 标题是模型生成的、措辞每次都可能变，只断言来源标记
  const v4Rows = page.locator('tbody tr', { hasText: 'hisi-dev-tool' })
  const v4Count = await v4Rows.count()
  expect(v4Count, '看板上应有 v4.0 后端的待办').toBeGreaterThanOrEqual(10)
  await expect(v4Rows.filter({ hasText: '架构·AI' }).first()).toBeVisible()
  const targetRow = page.locator('tbody tr', { hasText: ITEM_TITLE }).first()
  await expect(targetRow).toBeVisible()

  // 按项目筛选：只留 v4.0 后端的待办
  const v4Filter = page.locator('.m-proj', { hasText: 'hisi-dev-tool' }).first()
  await v4Filter.click()
  await expect(page.locator('tbody tr')).toHaveCount(v4Count)
  await v4Filter.click()

  // ── 对这条待办点自动修复 ──
  const action = targetRow.locator('.c-actions .el-button').first()
  await expect(action).toHaveText(/自动修复|重试/)
  await action.click()

  // 流水线条出现在底部；点它的条目打开监视器（刚发起时列表还没刷新，这一步以前会让页面崩掉）
  await expect(page.locator('.pipe')).toBeVisible()
  await page.locator('.tile').first().click()
  const monitor = page.locator('.monitor')
  await expect(monitor).toBeVisible()

  // ── 方案确认门 ──
  const confirmBox = monitor.locator('.confirm-box')
  await expect(confirmBox).toBeVisible({ timeout: GATE_TIMEOUT })
  const options = confirmBox.locator('.confirm-option')
  const optionCount = await options.count()
  expect(optionCount, '至少给出一个候选方案').toBeGreaterThan(0)
  await monitor.screenshot({ path: test.info().outputPath('01-proposal.png') })

  // 选第二个方案（没有就选第一个），不推送
  await options.nth(Math.min(1, optionCount - 1)).click()
  await expect(confirmBox.locator('.push-choice input')).not.toBeChecked()
  await confirmBox.getByRole('button', { name: '确认并继续' }).click()
  await expect(confirmBox).toBeHidden()

  // ── 跟到终态 ──
  const statusBadge = monitor.locator('.mo-status')
  await expect(statusBadge).toHaveText(/^(DONE|FAILED|ABORTED)$/, { timeout: RUN_TIMEOUT })
  const finalStatus = (await statusBadge.innerText()).trim()
  await monitor.screenshot({ path: test.info().outputPath('02-final.png') })

  // 运行记录：确认跑的是 v4.0、落在了哪个分支、改了什么
  const runs = await api(request, await loginToken(request), 'GET', '/api/workbench/runs')
  const run = (runs.body?.data ?? []).find((r: { projectPath: string }) => r.projectPath === V4_BACKEND)
  expect(run, '运行记录应指向 v4.0 后端').toBeTruthy()
  test.info().annotations.push(
    { type: 'run', description: `runId=${run.id} status=${run.status} branch=${run.branchName ?? '-'} commit=${run.commitHash ?? '-'} error=${run.errorMessage ?? '-'}` },
    { type: 'worktree', description: String(run.worktreePath ?? '-') },
  )

  expect(finalStatus, `运行应跑完：${run.errorMessage ?? ''}`).toBe('DONE')
  // 8 个阶段全部完成
  await expect(monitor.locator('.mo-tl li')).toHaveCount(8)
  await expect(monitor.locator('.mo-tl li.done')).toHaveCount(8)
})
