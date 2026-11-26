import { expect, test } from '@playwright/test'

import { openAsAdmin } from './helpers/real'

/**
 * 需求分析大师完整流程（真实后端、真实模型调用），项目是 v4.0 后端：
 * 填需求 → 澄清（回答分析器的问题）→ 确认（批准）→ 影响面 → 实现 → 验证 → 生成技术方案 → 完成。
 * 人工介入的两类弹窗由测试代为处理：澄清就给一句明确的回答，确认就批准。
 */
const FLOW_TIMEOUT = 30 * 60_000

// 项目里有两个技能列表接口，分析器看过代码后一定会问改哪个、非法取值怎么处理。
// 回答要把这些决定说清楚——答非所问它会一直追问（产品侧已有澄清轮数上限之外的两处修复，见后端测试）。
const CLARIFY_ANSWER = [
  'A：仅修改 SkillMarketController 的 GET /api/skill-market/list；SkillController 的 /api/skills/list 不在本次范围内。',
  '非法 riskLevel（空串、high1、大小写不符）返回 400；与 category / search 同时出现时按 AND 组合。',
  '存量技能全部回填为 low；riskLevel 是技能定义上的持久化字段，新增技能必填。',
].join('')

test('需求分析大师：以 v4.0 后端为项目走完澄清、确认、影响面直到技术方案', async ({ page, request }) => {
  await openAsAdmin(page, request, '/ram/new')

  // 选项目（可多选下拉：选完按 Esc 收起）
  await page.locator('[data-test="ram-project-select"]').click()
  await page.locator('.el-select-dropdown__item', { hasText: 'hisi-dev-tool' })
    .filter({ hasNotText: 'frontend' })
    .filter({ hasNotText: 'mcp' })
    .first()
    .click()
  await page.keyboard.press('Escape')
  await expect(page.locator('.selected-project-hint')).toContainText('已选 1 个项目')

  const requirement = [
    '为技能市场的技能列表接口增加按风险等级过滤的能力：',
    '1. 每个技能增加 riskLevel 字段，取值 low / medium / high，已有技能默认为 low；',
    '2. 技能列表接口支持按 riskLevel 过滤，不传时返回全部；',
    '3. 不改变现有接口的其它参数与返回结构。',
    '请分析影响面并给出技术方案。',
  ].join('\n')
  // Element Plus 把 data-test 落在 <textarea> 自己身上，按角色定位更稳
  await page.getByRole('textbox', { name: '*需求原文' }).fill(requirement)
  await page.locator('[data-test="ram-submit"]').click()

  await expect(page).toHaveURL(/\/ram\/draft\/[^/]+/, { timeout: 60_000 })

  // 人工介入循环：澄清弹窗、确认弹窗、澄清提示条、最后一步的「生成技术方案」
  const deadline = Date.now() + FLOW_TIMEOUT
  // 页面顶栏里那个状态标签（应用外壳的顶栏里也有标签，不能只写 header .el-tag）
  const statusTag = page.locator('[data-test="ram-session-status"]')
  let techPlanTriggered = false
  let lastLog = 0
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
    // 终态的写法以失败提示为准：已完成 / 失败 / 已中止
    if (/^(已完成|失败|已中止)$/.test(status)) break

    if (Date.now() - lastLog > 60_000) {
      lastLog = Date.now()
      console.log(`[ram-demand] status=${status} techPlanTriggered=${techPlanTriggered}`)
    }
    await page.waitForTimeout(5_000)
  }

  let finalStatus = (await statusTag.innerText()).trim()
  // 四节点 DAG（澄清→影响面→实现→验证）跑完，页面状态就是 completed；
  // 技术方案是在这之后点「生成技术方案」单独触发的，不算在 DAG 的完成状态里
  if (!techPlanTriggered && finalStatus === '已完成') {
    const techPlan = page.getByRole('button', { name: '生成技术方案' }).first()
    await expect(techPlan).toBeVisible({ timeout: 60_000 })
    await techPlan.click()
    techPlanTriggered = true
  }

  // 技术方案点完按钮后由模型单独生成、经 SSE 回来，以分钟计。点开「技术方案」节点等它有输出
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
  await page.screenshot({ path: test.info().outputPath('ram-demand-techplan.png'), fullPage: true })

  test.info().annotations.push({ type: 'status', description: `最终状态 ${finalStatus}，技术方案${techPlanTriggered ? '已生成' : '未触发生成'}` })
  await page.screenshot({ path: test.info().outputPath('ram-demand-final.png'), fullPage: true })
  expect(finalStatus, '需求分析应正常完成').toBe('已完成')
  expect(techPlanTriggered, '应出现并触发「生成技术方案」').toBe(true)
  await expect(techPlanView).not.toBeEmpty()
})
