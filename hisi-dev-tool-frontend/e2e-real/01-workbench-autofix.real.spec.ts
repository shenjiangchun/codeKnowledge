import { expect, test } from '@playwright/test'

import { api, loginToken, openAsAdmin, TARGET_PATH } from './helpers/real'

/**
 * 技术债看板 + 自动修复 agent 流（真实后端、真实模型调用，被分析的项目是 java-spring-test）。
 *
 * 看板聚合 → 对该项目的待办点「自动修复」→ 流水线出现 → 打开监视器 → 在方案确认门选方案并确认
 * → 跟到终态，断言跑完（DONE）且 8 个阶段全部完成。
 *
 * 待办来自对该项目做过的日志分析（由 03-log-analysis 用例或手工提交：一条指向
 * UserServiceImpl.createUser 的 NullPointerException）。标题是模型/日志生成的、措辞会变，
 * 所以不硬编码标题，按「项目 + 日志来源 + NPE」定位；可用环境变量 WB_ITEM_TITLE 覆盖。
 * 被分析项目要有向量（影响面取证走混合检索），见 helpers/real.ts。
 */
const ITEM_TITLE = process.env.WB_ITEM_TITLE ?? ''
const GATE_TIMEOUT = 25 * 60_000
const RUN_TIMEOUT = 60 * 60_000

test('技术债看板聚合待办，自动修复流在监视器里确认方案并跑到终态', async ({ page, request }) => {
  await openAsAdmin(page, request, '/workbench')

  // ── 看板：总览与待办表 ──
  await expect(page.locator('.wb-title')).toHaveText('工作台')
  await expect(page.locator('.m-stat').first()).toContainText('技术债余额')
  await expect(page.locator('tbody tr').first()).toBeVisible()

  // 本项目的待办在看板上。标题取自异常日志行（如
  // 「NullPointerException：2026-11-27 10:00:00.000 ERROR [http-nio-8081-exec-5] c.example.controller.UserCo…」，
  // 页面会截断），所以用日志行里稳定出现的包名片段定位，不硬编码整条标题
  const TITLE_MARKER = 'c.example.controller.UserCo'
  const projectRows = page.locator('tbody tr', { hasText: TITLE_MARKER })
  const projectCount = await projectRows.count()
  expect(projectCount, '看板上应有被分析项目（java-spring-test）的待办').toBeGreaterThanOrEqual(1)

  const targetRow = ITEM_TITLE
    ? page.locator('tbody tr', { hasText: ITEM_TITLE }).first()
    : projectRows.filter({ hasText: '2026-11-27 10:00:00.000' }).first()
  await expect(targetRow).toBeVisible()

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

  // 运行记录：确认跑的是被分析项目、落在了哪个分支、改了什么
  const runs = await api(request, await loginToken(request), 'GET', '/api/workbench/runs')
  const run = (runs.body?.data ?? []).find((r: { projectPath: string }) => r.projectPath === TARGET_PATH)
  expect(run, '运行记录应指向被分析项目').toBeTruthy()
  test.info().annotations.push(
    { type: 'run', description: `runId=${run.id} status=${run.status} branch=${run.branchName ?? '-'} commit=${run.commitHash ?? '-'} error=${run.errorMessage ?? '-'}` },
    { type: 'worktree', description: String(run.worktreePath ?? '-') },
  )

  expect(finalStatus, `运行应跑完：${run.errorMessage ?? ''}`).toBe('DONE')
  // 8 个阶段全部完成
  await expect(monitor.locator('.mo-tl li')).toHaveCount(8)
  await expect(monitor.locator('.mo-tl li.done')).toHaveCount(8)
})
