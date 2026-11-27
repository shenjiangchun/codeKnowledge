import { expect, test } from '@playwright/test'

import { openAsAdmin, TARGET_NAME } from './helpers/real'

/**
 * 架构诊断 agent 流（真实后端、真实模型调用）：对 java-spring-test 先生成实时诊断报告，再跑 AI 深度分析，
 * 断言深度分析跑完并给出结构化结果（问题卡片含标题/根因/建议，或明确给出「未发现问题」）。
 *
 * 被分析项目换成有向量的 java-spring-test（v4.0 后端 0 向量，见 helpers/real.ts）。
 */
test('架构诊断：对 java-spring-test 生成实时报告并完成 AI 深度分析', async ({ page, request }) => {
  await openAsAdmin(page, request, '/architecture')

  // 选中被分析项目（按完整名称精确匹配）
  await page.locator('.control-card .el-select').first().click()
  await page.getByRole('option', { name: TARGET_NAME, exact: true }).click()

  // 实时诊断报告（规则与图谱统计，不走模型）
  await page.getByRole('button', { name: /生成报告|刷新报告/ }).click()
  await expect(page.locator('.health-score')).toBeVisible({ timeout: 10 * 60_000 })
  await expect(page.locator('.issues-card')).toBeVisible()

  // AI 深度分析（agent 流：取证 → ReAct 查证 → 报告）
  await page.getByRole('button', { name: 'AI 深度分析' }).click()
  const panel = page.locator('.ai-deep-analysis')
  await expect(panel).toBeVisible()

  // 深度分析进行中会逐段渲染：等两节点都完成，且问题数量 10 秒内不再变化
  const doneTags = panel.locator('.node-tag, .el-tag').filter({ hasText: /^完成$/ })
  await expect(doneTags.first()).toBeVisible({ timeout: 40 * 60_000 })
  let previous = -1
  for (let i = 0; i < 90; i++) {
    const count = await panel.locator('.issue-card').count()
    const running = await panel.locator('.el-tag').filter({ hasText: /^运行中$/ }).count()
    if (running === 0 && count === previous) break
    previous = count
    await page.waitForTimeout(10_000)
  }

  const issues = panel.locator('.issue-card')
  const count = await issues.count()
  test.info().annotations.push({ type: 'issues', description: `深度分析给出 ${count} 个问题` })
  if (count > 0) {
    await expect(issues.first().locator('.issue-title')).not.toBeEmpty()
    await expect(panel).toContainText('根因')
    await expect(panel).toContainText('建议')
  } else {
    // 0 个问题时必须是分析给出的明确结论，而不是面板空着或中途失败
    await expect(panel).not.toContainText('失败')
    await expect(panel.locator('.markdown-content, .report-section').first()).toBeVisible()
  }
  await page.screenshot({ path: test.info().outputPath('architecture-deep-analysis.png'), fullPage: true })
})
