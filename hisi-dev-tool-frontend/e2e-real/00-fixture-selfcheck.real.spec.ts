import { expect, test } from '@playwright/test'

import { openAsAdmin } from './helpers/real'

/**
 * 不是业务用例，是「E2E 夹具自检」：openAsAdmin 往 localStorage 种了被分析项目（java-spring-test），
 * 所有 real spec 都靠它让页面「已选项目」。2026-11-27 把需求/现状页的 projectPaths 改成
 * 「下拉能解析到的 + 手动输入的」之后，这里验证种子项目是否仍然真的被选中。
 * 不调模型，秒级完成。
 */
test('夹具自检：openAsAdmin 种下的被分析项目在需求分析页确实处于已选状态', async ({ page, request }) => {
  const picked: string[] = []
  page.on('request', (req) => {
    const m = req.url().match(/\/api\/ram\/sessions$/)
    if (m && req.method() === 'POST') {
      try {
        picked.push(...(req.postDataJSON()?.projectPaths ?? []))
      } catch { /* ignore */ }
    }
  })

  await openAsAdmin(page, request, '/ram/new')

  const select = page.locator('[data-test="ram-project-select"]')
  await expect(select).toBeVisible({ timeout: 30_000 })
  // 等项目列表加载完（下拉 loading 结束）
  await page.waitForTimeout(2_500)

  const hintText = await page.locator('.selected-project-hint').innerText().catch(() => '<no hint element>')
  const tags = await select.locator('.el-select__tags-text, .el-tag').allInnerTexts()
  console.log('[fixture-check] hint =', JSON.stringify(hintText))
  console.log('[fixture-check] visible tags =', JSON.stringify(tags.slice(0, 6)))

  // 提交一次，看后端实际收到哪些 projectPaths（这就是业务真正会用的）
  await page.getByRole('textbox', { name: '*需求原文' }).fill('夹具自检：列出项目里的 Controller。')
  await page.locator('[data-test="ram-submit"]').click()
  await page.waitForTimeout(3_000)
  console.log('[fixture-check] submitted projectPaths =', JSON.stringify(picked))

  expect(hintText, '种子项目应让页面显示「已选 N 个项目」').toContain('已选')
  expect(picked.flat().length, '提交时应带上至少一个项目路径').toBeGreaterThan(0)
})
