import { expect, test } from '@playwright/test'

import { openAsAdmin } from './helpers/real'

/**
 * 项目现状分析（真实后端、真实模型调用），项目是 v4.0 后端：提一个关于项目现状的问题，等报告生成。
 */
test('项目现状分析：对 v4.0 后端生成现状分析报告', async ({ page, request }) => {
  await openAsAdmin(page, request, '/ram/status/new')

  await page.locator('[data-test="ram-project-select"]').click()
  await page.locator('.el-select-dropdown__item', { hasText: 'hisi-dev-tool' })
    .filter({ hasNotText: 'frontend' })
    .filter({ hasNotText: 'mcp' })
    .first()
    .click()
  await page.keyboard.press('Escape')
  await expect(page.locator('.selected-project-hint')).toContainText('已选 1 个项目')

  await page.getByPlaceholder(/输入你想了解的问题/).fill(
    '知识图谱域与语义搜索域之间的依赖关系是怎样的？是否存在循环依赖，主要涉及哪些类？',
  )
  await page.locator('[data-test="ram-submit"]').click()

  await expect(page).toHaveURL(/\/ram\/status\/[^/]+/, { timeout: 60_000 })
  const report = page.locator('.report-container .markdown-content')
  await expect(report.first()).toBeVisible({ timeout: 25 * 60_000 })
  // 报告是流式补全的，等它稳定
  let previous = -1
  for (let i = 0; i < 60; i++) {
    const length = (await report.first().innerText()).length
    if (length > 200 && length === previous) break
    previous = length
    await page.waitForTimeout(5_000)
  }
  expect(previous, '现状分析报告应有实质内容').toBeGreaterThan(200)
  await expect(page.locator('.error-detail')).toHaveCount(0)
  await page.screenshot({ path: test.info().outputPath('ram-status.png'), fullPage: true })
})
