import { expect, test } from '@playwright/test'

import { loginToken } from './helpers/real'

/**
 * Phase2 V2 页面验证：用一条已完成（DONE）的 v2 精确位置分析会话，打开 /ram/phase2/:sid，
 * 断言分层报告渲染出来（领域概览、关键发现、链路详情），而不是「分析失败」。
 * 需要环境变量：PHASE2_V2_HANDLE（一条 DONE 的 v2 会话 handle）。
 */
const HANDLE = process.env.PHASE2_V2_HANDLE ?? ''

test.skip(!HANDLE, '需要 PHASE2_V2_HANDLE 指向一条 DONE 的 v2 会话')

test('Phase2 V2：页面渲染分层报告（领域概览 + 链路详情）', async ({ page, request }) => {
  const token = await loginToken(request)
  await page.addInitScript(
    ([token]) => localStorage.setItem('hisi-token', token as string),
    [token] as const,
  )

  await page.goto(`/ram/phase2/${HANDLE}`)

  // 报告渲染成功：出现「第一层：领域概览」与「第二层：链路详情」两个区块
  await expect(page.getByText('第一层：领域概览')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('第二层：链路详情')).toBeVisible({ timeout: 30_000 })

  // 状态标签应是「已完成」而不是「失败」
  await expect(page.getByText('已完成')).toBeVisible()

  // 概览内容非空
  const overview = page.locator('.overview-text')
  await expect(overview).not.toBeEmpty()

  // 链路详情有至少一条链
  await expect(page.locator('.el-collapse-item').first()).toBeVisible()

  await page.screenshot({ path: test.info().outputPath('phase2-v2-report.png'), fullPage: true })
})
