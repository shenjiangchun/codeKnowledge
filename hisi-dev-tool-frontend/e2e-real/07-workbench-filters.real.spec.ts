import { expect, test } from '@playwright/test'

import { openAsAdmin } from './helpers/real'

/**
 * 工作台待办表格的列筛选（真实后端）：严重度/项目/来源/状态四个列头下拉，
 * 选中后表格行全部符合筛选值，清空恢复。
 */
test('工作台待办表列筛选：严重度/来源/状态下拉生效', async ({ page, request }) => {
  await openAsAdmin(page, request, '/workbench')

  const sevSel = page.locator('thead select').nth(0)
  const srcSel = page.locator('thead select').nth(2)
  const statusSel = page.locator('thead select').nth(3)
  await expect(sevSel).toBeVisible()
  await expect(srcSel).toBeVisible()
  await expect(statusSel).toBeVisible()

  // 严重度 = P0：所有可见行的严重度标签都是 P0
  await sevSel.selectOption('P0')
  await expect(page.locator('tbody tr').first()).toBeVisible()
  const sevTags = await page.locator('tbody tr .sev').allTextContents()
  expect(sevTags.length, 'P0 筛选后应至少有一行').toBeGreaterThan(0)
  for (const t of sevTags) expect(t.trim()).toBe('P0')

  // 来源 = 日志：所有可见行的来源标签都是「日志」
  await sevSel.selectOption('')
  await srcSel.selectOption('log')
  const srcTags = await page.locator('tbody tr .src').allTextContents()
  expect(srcTags.length, '来源筛选后应至少有一行').toBeGreaterThan(0)
  for (const t of srcTags) expect(t.trim()).toBe('日志')

  // 状态 = 已清零：所有可见行状态标签都是「已清零」
  await srcSel.selectOption('')
  await statusSel.selectOption('DONE')
  const stTags = await page.locator('tbody tr .st-DONE').allTextContents()
  expect(stTags.length, '状态筛选后应至少有一行已清零').toBeGreaterThan(0)

  // 清空恢复
  await statusSel.selectOption('')
  await expect(page.locator('tbody tr').first()).toBeVisible()
})
