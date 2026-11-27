import { expect, test } from '@playwright/test'

import { loginToken, openAsAdmin, TARGET_PATH, BACKEND } from './helpers/real'

/**
 * RAM 对话 agent 流（真实后端、真实模型调用）：以 java-spring-test 为项目新建对话，提一个必须查代码才能回答的问题，
 * 断言收到流式回复、确实调用了工具、并且没有出错。
 *
 * 被分析项目换成有向量的 java-spring-test（v4.0 后端 0 向量，见 helpers/real.ts）。
 * 问题只描述「想要什么」、不透露符号名：答案里出现项目里真实存在的方法名，才说明模型真的查了图谱/代码。
 *
 * 会话经接口创建（不带首问——带了会让 POST 同步等完整回合），再经消息接口异步发起第一问，与页面真实交互一致，
 * 最后按 URL 打开会话页等待回答。原因：页面的「新建对话」选择器只列后端扫描到的 git 仓库，
 * test-projects/ 下的 java-spring-test 不在其中，页面上选不到它。
 */
test('RAM 对话：以 java-spring-test 为项目新建对话，模型查代码后回答', async ({ page, request }) => {
  const token = await loginToken(request)
  const created = await request.post(`${BACKEND}/api/ram/chat/sessions`, {
    data: { projectPaths: [TARGET_PATH], projectName: 'java-spring-test' },
    headers: { Authorization: `Bearer ${token}` },
    timeout: 60_000,
  })
  const body = await created.json().catch(() => undefined)
  const sessionId = String(body?.data?.sessionId ?? '')
  expect(sessionId, `创建对话会话失败：${JSON.stringify(body)?.slice(0, 200)}`).toBeTruthy()

  const sent = await request.post(`${BACKEND}/api/ram/chat/${sessionId}/messages`, {
    data: { text: '这个项目对外提供了哪些 HTTP 接口？按创建用户、删除用户、查询统计这几类分别说出对应的 Java 方法名。' },
    headers: { Authorization: `Bearer ${token}` },
    timeout: 60_000,
  })
  expect(sent.status(), `发消息失败：${JSON.stringify(await sent.json().catch(() => undefined))?.slice(0, 200)}`).toBe(200)

  await openAsAdmin(page, request, `/ram/chat/${sessionId}`)
  await expect(page.locator('.chat-header h2')).toContainText('java-spring-test')
  await page.locator('.assistant-row').first().waitFor({ timeout: 10 * 60_000 })

  // 等这一轮真正结束：正文不再变化、工具步骤不再转圈、「停止」按钮消失；「思考中...」不算答案
  let previous = ''
  let lastProgress = Date.now()
  let reloaded = false
  const deadline = Date.now() + 10 * 60_000
  const stopButton = page.getByRole('button', { name: /停止|中断/ })
  for (;;) {
    const text = await page.locator('.assistant-row').last().innerText({ timeout: 3_000 }).catch(() => '')
    const toolStepsRunning = (await page.locator('.step-card .loading-icon').count()) > 0
    const settled = text.length > 20 && !/思考中/.test(text) && text === previous
      && !toolStepsRunning && (await stopButton.count()) === 0
    if (settled) break
    if (text !== previous) {
      previous = text
      lastProgress = Date.now()
    }
    // 长时间没有进展：刷新页面重拉消息（断线期间后端不补发事件，页面挂载时会从 REST 重载）
    if (!reloaded && Date.now() - lastProgress > 2 * 60_000) {
      reloaded = true
      await page.reload()
      await page.locator('.assistant-row').first().waitFor({ timeout: 30_000 })
      lastProgress = Date.now()
      continue
    }
    if (Date.now() > deadline) {
      throw new Error(`回答未在 10 分钟内稳定，最后文本：${text.slice(0, 120)}`)
    }
    await page.waitForTimeout(3_000)
  }

  await expect(page.locator('.error-row')).toHaveCount(0)
  const text = await page.locator('.assistant-row').last().innerText()
  // 真查了代码才会出现的项目真实方法名（不是模型凭空能知道的）
  expect(text).toMatch(/createUser|deleteUser|getStats|getUserById/)
  // 确实走了工具，而不是直接作答
  const toolSteps = await page.locator('.step-card').count()
  expect(toolSteps, '应出现工具调用步骤').toBeGreaterThan(0)
  test.info().annotations.push({ type: 'answer', description: text.slice(0, 300) })
  test.info().annotations.push({ type: 'tool-steps', description: `工具步骤卡片 ${toolSteps} 个` })
  await page.screenshot({ path: test.info().outputPath('ram-chat.png'), fullPage: true })
})
