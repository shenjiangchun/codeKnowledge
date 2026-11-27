import { expect, test } from '@playwright/test'

import { openAsAdmin } from './helpers/real'

/**
 * RAM 需求分析大师 + 项目现状分析两条页面级全链路（真实后端、真实模型）。RAM 对话见 04。
 *
 * 被分析项目是 java-spring-test（有向量：20 个方法的 desc + code 向量齐全，见 helpers/real.ts）。
 * 需求分析的影响面节点依赖混合检索，被分析项目必须有向量。
 * 覆盖「需求分析大师」与「项目现状分析」两个页面（原 05/06 两个 v4.0 版用例已并入这里）。
 *
 * 技术方案断言排除「LLM不可用」占位输出：模型流失败时后端会回退成占位结果，
 * 以前只断言「非空」，占位输出也算通过——那是一次假通过。
 */
const FLOW_TIMEOUT = 30 * 60_000

test('需求分析大师：澄清 → 确认 → 影响面 → 实现 → 验证 → 技术方案（真实输出，非占位）', async ({ page, request }) => {
  await openAsAdmin(page, request, '/ram/new')
  // openAsAdmin 已把被分析项目种进全局选中项；需求分析页会从那里恢复（不必在下拉里手动选）
  await expect(page.locator('.selected-project-hint')).toContainText('已选 1 个项目', { timeout: 30_000 })

  const requirement = [
    '给用户接口增加一个按邮箱查询用户的方法：',
    '1. 新增 GET /users/by-email 接口，入参 email 必填，格式非法返回 400；',
    '2. 查询复用 UserService；',
    '3. 不改动其它接口与数据模型。',
    '请分析影响面并给出技术方案。',
  ].join('\n')
  await page.getByRole('textbox', { name: '*需求原文' }).fill(requirement)
  await page.locator('[data-test="ram-submit"]').click()
  await expect(page).toHaveURL(/\/ram\/draft\/[^/]+/, { timeout: 60_000 })

  const CLARIFY_ANSWER = [
    'A：仅修改 UserController 与 UserServiceImpl，新增 byEmail 查询方法；',
    '邮箱为空或格式不符返回 400，不吞异常；',
    '不改动其它接口与数据模型。',
  ].join('')

  const statusTag = page.locator('[data-test="ram-session-status"]')
  let techPlanTriggered = false
  let lastLog = 0
  const deadline = Date.now() + FLOW_TIMEOUT
  for (;;) {
    if (Date.now() > deadline) {
      throw new Error(`需求分析未在 ${FLOW_TIMEOUT / 60000} 分钟内完成，最后状态：${await statusTag.innerText().catch(() => '?')}`)
    }

    const clarify = page.locator('[data-test="clarify-modal"]')
    if (await clarify.isVisible().catch(() => false)) {
      const fields = clarify.locator('textarea')
      const count = await fields.count()
      for (let i = 0; i < count; i++) {
        await fields.nth(i).fill(CLARIFY_ANSWER)
      }
      await clarify.locator('[data-test="clarify-submit"]').click()
      await page.waitForTimeout(2_000)
      continue
    }

    const confirm = page.locator('.el-dialog', { has: page.getByRole('button', { name: '批准并继续' }) })
    if (await confirm.isVisible().catch(() => false)) {
      await confirm.getByRole('button', { name: '批准并继续' }).click()
      await page.waitForTimeout(2_000)
      continue
    }

    const clarifyAlert = page.locator('.clarify-alert')
    if (await clarifyAlert.isVisible().catch(() => false)) {
      await clarifyAlert.getByRole('button', { name: '打开' }).click()
      await page.waitForTimeout(1_000)
      continue
    }

    const techPlan = page.getByRole('button', { name: '生成技术方案' }).first()
    if (!techPlanTriggered && await techPlan.isVisible().catch(() => false)) {
      await techPlan.click()
      techPlanTriggered = true
      continue
    }

    const status = (await statusTag.innerText().catch(() => '')).trim()
    if (/^(已完成|失败|已中止)$/.test(status)) {
      expect(status, '被分析项目有向量，影响面与后续节点不应失败').toBe('已完成')
      break
    }

    if (Date.now() - lastLog > 60_000) {
      lastLog = Date.now()
      console.log(`[ram-demand-jst] status=${status} techPlanTriggered=${techPlanTriggered}`)
    }
    await page.waitForTimeout(5_000)
  }

  const finalStatus = (await statusTag.innerText()).trim()
  if (!techPlanTriggered && finalStatus === '已完成') {
    const techPlan = page.getByRole('button', { name: '生成技术方案' }).first()
    await expect(techPlan).toBeVisible({ timeout: 60_000 })
    await techPlan.click()
    techPlanTriggered = true
  }

  await page.locator('.dag-card-group').filter({ hasText: '技术方案' }).first().click()
  const techPlanView = page.locator('.detail-techplan-view, .detail-md').first()
  const planDeadline = Date.now() + 15 * 60_000
  for (;;) {
    if (await techPlanView.isVisible().catch(() => false)) break
    const status = (await statusTag.innerText().catch(() => '')).trim()
    if (/^(失败|已中止)$/.test(status)) {
      throw new Error(`技术方案生成失败，会话状态：${status}`)
    }
    if (Date.now() > planDeadline) {
      throw new Error(`技术方案未在 15 分钟内生成，最后状态：${status}`)
    }
    await page.waitForTimeout(5_000)
  }
  await page.screenshot({ path: test.info().outputPath('mcp-demand-techplan.png'), fullPage: true })

  test.info().annotations.push({ type: 'status', description: `最终状态 ${finalStatus}，技术方案${techPlanTriggered ? '已生成' : '未触发生成'}` })
  expect(finalStatus, '需求分析应正常完成').toBe('已完成')
  expect(techPlanTriggered, '应出现并触发「生成技术方案」').toBe(true)
  await expect(techPlanView).not.toBeEmpty()
  // 技术方案必须是真实生成的结果：模型流失败时后端回退到占位输出
  // （reasoning 固定为「LLM不可用，无法生成技术方案」，见 ClaudeTechPlanLlmClient.minimalOutput），不得把占位当成功
  await expect(techPlanView, '技术方案不应是模型不可用时的占位输出').not.toContainText('LLM不可用')
})

test('项目现状分析：生成有实质内容的报告', async ({ page, request }) => {
  await openAsAdmin(page, request, '/ram/status/new')
  await expect(page.locator('.selected-project-hint')).toContainText('已选 1 个项目', { timeout: 30_000 })

  await page.getByPlaceholder(/输入你想了解的问题/).fill(
    '这个项目的接口层与业务层是怎么划分的？主要涉及哪些类？',
  )
  await page.locator('[data-test="ram-submit"]').click()

  await expect(page).toHaveURL(/\/ram\/status\/[^/]+/, { timeout: 60_000 })
  const report = page.locator('.report-container .markdown-content')
  await expect(report.first()).toBeVisible({ timeout: 25 * 60_000 })
  let previous = -1
  for (let i = 0; i < 60; i++) {
    const length = (await report.first().innerText()).length
    if (length > 200 && length === previous) break
    previous = length
    await page.waitForTimeout(5_000)
  }
  expect(previous, '现状分析报告应有实质内容').toBeGreaterThan(200)
  await expect(page.locator('.error-detail')).toHaveCount(0)
  await page.screenshot({ path: test.info().outputPath('mcp-status.png'), fullPage: true })
})
