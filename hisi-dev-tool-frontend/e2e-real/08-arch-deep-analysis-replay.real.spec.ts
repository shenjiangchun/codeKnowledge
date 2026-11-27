import { expect, test, type Page } from '@playwright/test'

import { loginToken } from './helpers/real'

/**
 * 回归：架构「AI 深度分析」面板无法回放历史分析。
 *
 * 历史会话 id 存在 localStorage，键里拼了项目路径；面板挂载时自己的勾选恒为空，
 * 以前只用面板勾选拼键 → 读到空串键 → 永远回放不了，只能重新跑。
 * 现在回放时回退到页面全局已选项目来拼键。
 *
 * 用数据库里**已完成**的深度分析会话做验证：把它的 handle 写进对应键、打开页面，
 * 面板应直接展示那份报告，且**不得**发起新的分析（没碰 /start）。
 * 需要环境变量：ARCH_HISTORY_HANDLE、ARCH_HISTORY_PROJECT_PATH（一条已完成会话的 handle 与它分析的项目路径）。
 */
const HANDLE = process.env.ARCH_HISTORY_HANDLE ?? ''
const PROJECT_PATH = process.env.ARCH_HISTORY_PROJECT_PATH ?? ''

test.skip(!HANDLE || !PROJECT_PATH, '需要 ARCH_HISTORY_HANDLE / ARCH_HISTORY_PROJECT_PATH 指向一条已完成的深度分析会话')

test('架构 AI 深度分析：刷新后回放历史分析，不重新发起', async ({ page, request }) => {
  const token = await loginToken(request)
  const storageKey = `arch-deep-analysis-session:${PROJECT_PATH}`

  // 模拟「上次分析过这个项目」：全局只选中这一个项目（键 = 这一个项目的路径），历史 id 已写入
  await page.addInitScript(
    ([token, project, key, handle]) => {
      localStorage.setItem('hisi-token', token as string)
      localStorage.setItem('hisi-selected-projects', JSON.stringify([{ name: 'p', path: project }]))
      localStorage.setItem(key as string, handle as string)
    },
    [token, PROJECT_PATH, storageKey, HANDLE] as const,
  )

  const starts: string[] = []
  page.on('request', (req) => {
    if (req.method() === 'POST' && /\/v2\/workflow\/start/.test(req.url())) starts.push(req.url())
  })

  await page.goto('/architecture')
  // 面板在「AI 深度分析」标签页里，默认不激活（组件照常挂载并回放，只是不可见）：先切过去
  await page.getByRole('tab', { name: 'AI 深度分析' }).click({ timeout: 30_000 })

  // 回放成功：面板出现报告操作栏（v-if="markdownReport"）与报告正文
  const panel = page.locator('.ai-deep-analysis')
  await expect(panel).toBeVisible({ timeout: 30_000 })
  await expect(panel.locator('.report-actions')).toBeVisible({ timeout: 30_000 })
  await expect(panel.locator('.markdown-content, .report-section').first()).toBeVisible()

  const text = await panel.innerText()
  expect(text.length, '回放的报告应有实质内容').toBeGreaterThan(200)
  // 空状态提示（未回放时的「开始分析」引导）不应再出现
  await expect(panel.getByText(/开始深度分析|点击.*开始.*分析/)).toHaveCount(0)

  // 关键：是回放，不是悄悄重新跑了一遍
  expect(starts, '回放历史时不得发起新的分析').toStrictEqual([])

  await page.screenshot({ path: test.info().outputPath('arch-deep-analysis-replayed.png'), fullPage: true })
})

test('架构 AI 深度分析：没有历史时保持空状态，不凭空请求', async ({ page, request }) => {
  const token = await loginToken(request)
  await page.addInitScript(
    ([token, project]) => {
      localStorage.setItem('hisi-token', token as string)
      localStorage.setItem('hisi-selected-projects', JSON.stringify([{ name: 'p', path: project }]))
      // 不写任何 arch-deep-analysis-session:* 历史记录
      Object.keys(localStorage)
        .filter((k) => k.startsWith('arch-deep-analysis-session:'))
        .forEach((k) => localStorage.removeItem(k))
    },
    [token, PROJECT_PATH] as const,
  )
  const sessionReads: string[] = []
  page.on('request', (req) => {
    if (/\/v2\/workflow\/sessions\/[^/]+\/(status|report|events)/.test(req.url())) sessionReads.push(req.url())
  })

  await page.goto('/architecture')
  await page.getByRole('tab', { name: 'AI 深度分析' }).click({ timeout: 30_000 })
  await expect(page.locator('.ai-deep-analysis')).toBeVisible({ timeout: 30_000 })
  await page.waitForTimeout(2_000)

  await expect(page.locator('.ai-deep-analysis .report-actions')).toHaveCount(0)
  expect(sessionReads, '没有历史时不应去读任何会话').toStrictEqual([])
})
