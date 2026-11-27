import { expect, test, type Page } from '@playwright/test'

import { loginToken, openAsAdmin } from './helpers/real'

/**
 * 用有向量的小项目验证迁移后的 pi + 进程内 MCP 的页面级全链路：
 * java-spring-test（20 个方法、向量完整，UserController + UserService）。
 * v4.0 没有向量，影响面节点依赖的混合检索对任何查询都返回 0——那两条需求/现状流程在 v4.0 上必然失败，
 * 不能作为回归对象；本文件专跑小项目。
 *
 * 注意：java-spring-test 不在后端 Git 仓库扫描范围（它不是 projects/ 的直接子目录、也没有 .git），
 * 所以下拉里没有它——需求/现状页面用「更多选择方式」手动输入路径；对话会话经接口创建后按 URL 打开。
 */
const PROJECT_PATH = 'C:/Users/47583/projects/hisi_dev_tool v5.0/test-projects/java-spring-test'
const FLOW_TIMEOUT = 30 * 60_000

async function pickProject(page: Page) {
  // 折叠面板的标题里那个「更多选择方式」span 带 @click.stop，点它会挡住折叠开合：
  // 先点 span 让 showAdvanced=true（内容渲染），再点箭头把面板展开
  await page.locator('.advanced-toggle').first().click()
  await page.locator('.el-collapse-item__arrow').first().click()
  const manual = page.locator('[data-test="ram-project-manual"]')
  await expect(manual).toBeVisible({ timeout: 10_000 })
  await manual.fill(PROJECT_PATH)
  await manual.press('Enter')
  await expect(page.locator('.selected-project-hint')).toContainText('已选 1 个项目')
}

test('RAM 对话：小项目上问答，经 pi 调用进程内 MCP 工具', async ({ page, request }) => {
  const token = await loginToken(request)
  // 先建会话（不带 initialQuestion：带问题会让 POST 同步等完整回合，几分钟不返回——页面侧从不这么调），
  // 再用消息接口异步发起第一问，与页面真实交互一致
  const created = await request.post('http://localhost:8080/api/ram/chat/sessions', {
    data: { projectPaths: [PROJECT_PATH], projectName: 'java-spring-test' },
    headers: { Authorization: `Bearer ${token}` },
    timeout: 60_000,
  })
  const body = await created.json().catch(() => undefined)
  const sessionId = String(body?.data?.sessionId ?? '')
  expect(sessionId, `创建对话会话失败：${JSON.stringify(body)?.slice(0, 200)}`).toBeTruthy()

  const sent = await request.post(`http://localhost:8080/api/ram/chat/${sessionId}/messages`, {
    data: { text: '这个项目里有哪些 HTTP 接口？列出接口路径和对应的方法名。' },
    headers: { Authorization: `Bearer ${token}` },
    timeout: 60_000,
  })
  expect(sent.status(), `发消息失败：${JSON.stringify(await sent.json().catch(() => undefined))?.slice(0, 200)}`).toBe(200)

  await openAsAdmin(page, request, `/ram/chat/${sessionId}`)
  await page.locator('.assistant-row').first().waitFor({ timeout: 10 * 60_000 })

  // 等回答稳定：文本不再变化且没有还在执行的工具步骤。
  // 「思考中...」不算稳定答案——回合可能还在后台执行；WS 断线期间后端不补发事件，只能重拉消息
  let previous = ''
  let lastProgress = Date.now()
  let reloaded = false
  const deadline = Date.now() + 10 * 60_000
  for (;;) {
    const text = await page.locator('.assistant-row').last().innerText({ timeout: 3_000 }).catch(() => '')
    const toolStepsRunning = (await page.locator('.step-card .loading-icon').count()) > 0
    const settled = text && !/思考中/.test(text) && text === previous && !toolStepsRunning
    if (settled) break
    if (text !== previous) {
      previous = text
      lastProgress = Date.now()
    }
    // 长时间没有进展：刷新页面重拉消息（模拟用户 F5，页面挂载时会从 REST 重载事件）
    if (!reloaded && Date.now() - lastProgress > 2 * 60_000) {
      reloaded = true
      await page.reload()
      await page.locator('.assistant-row').first().waitFor({ timeout: 30_000 })
      continue
    }
    if (Date.now() > deadline) {
      throw new Error(`回答未在 10 分钟内稳定，最后文本：${text.slice(0, 120)}`)
    }
    await page.waitForTimeout(3_000)
  }
  const text = await page.locator('.assistant-row').last().innerText()
  expect(text.length, '对话应有回答').toBeGreaterThan(20)
  const toolSteps = await page.locator('.step-card').count()
  test.info().annotations.push({ type: 'tool-steps', description: `工具步骤卡片 ${toolSteps} 个` })
  if (reloaded) {
    test.info().annotations.push({ type: 'page-reload', description: '等待期间刷新过一次页面（WS 断线后通过重拉消息恢复）' })
  }
  await page.screenshot({ path: test.info().outputPath('mcp-chat.png'), fullPage: true })
})

test('需求分析：影响面节点在小项目上跑通（v4.0 无向量时该节点必挂）', async ({ page, request }) => {
  await openAsAdmin(page, request, '/ram/new')
  await pickProject(page)

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
      expect(status, '小项目有向量，影响面与后续节点不应失败').toBe('已完成')
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

test('现状分析：小项目上生成报告（v4.0 上曾因取证慢触发 5 分钟超时）', async ({ page, request }) => {
  await openAsAdmin(page, request, '/ram/status/new')
  await pickProject(page)

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
