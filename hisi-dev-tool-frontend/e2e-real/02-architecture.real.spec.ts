import { expect, test } from '@playwright/test'
import { openAsAdmin } from './helpers/real'

/**
 * 架构诊断 agent 流（真实后端、真实模型调用）：对 v4.0 后端先生成实时诊断报告，再跑 AI 深度分析，
 * 断言深度分析给出问题清单（标题、根因、建议）与治理路线图。
 */
test('架构诊断：对 v4.0 后端生成实时报告并完成 AI 深度分析', async ({ page, request }) => {
  await openAsAdmin(page, request, '/architecture')

  // 选中 v4.0 后端（选中项目里有三个同名前缀的工程，按完整名称精确匹配）
  await page.locator('.control-card .el-select').first().click()
  await page.getByRole('option', { name: 'hisi-dev-tool', exact: true }).click()

  // 实时诊断报告（规则与图谱统计，不走模型）
  await page.getByRole('button', { name: /生成报告|刷新报告/ }).click()
  await expect(page.locator('.health-score')).toBeVisible({ timeout: 10 * 60_000 })
  await expect(page.locator('.issues-card')).toBeVisible()

  // AI 深度分析（agent 流：取证 → ReAct 查证 → 报告）
  await page.getByRole('button', { name: 'AI 深度分析' }).click()
  const panel = page.locator('.ai-deep-analysis')
  await expect(panel).toBeVisible()
  const issues = panel.locator('.issue-card')
  await expect(issues.first()).toBeVisible({ timeout: 40 * 60_000 })
  // 深度分析进行中会逐段渲染，等它稳定下来（issue 数量 10 秒内不再变化）
  let previous = -1
  for (let i = 0; i < 90; i++) {
    const count = await issues.count()
    if (count > 0 && count === previous) break
    previous = count
    await page.waitForTimeout(10_000)
  }
  const count = await issues.count()
  expect(count, '深度分析应给出问题清单').toBeGreaterThan(0)
  await expect(issues.first().locator('.issue-title')).not.toBeEmpty()
  await expect(panel).toContainText('根因')
  await expect(panel).toContainText('建议')
  await page.screenshot({ path: test.info().outputPath('architecture-deep-analysis.png'), fullPage: true })
  test.info().annotations.push({ type: 'issues', description: `深度分析给出 ${count} 个问题` })
})
