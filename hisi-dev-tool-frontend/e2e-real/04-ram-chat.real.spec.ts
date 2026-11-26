import { expect, test } from '@playwright/test'
import { openAsAdmin } from './helpers/real'

/**
 * RAM 对话 agent 流（真实后端、真实模型调用）：以 v4.0 后端为项目新建对话，提一个与代码相关的问题，
 * 断言收到流式回复并且没有出错。
 */
test('RAM 对话：以 v4.0 后端为项目新建对话并得到回复', async ({ page, request }) => {
  await openAsAdmin(page, request, '/ram/chat')

  // 侧边栏和空态里各有一个「新建对话」
  await page.getByRole('button', { name: '新建对话' }).first().click()
  const dialog = page.locator('.el-dialog:visible')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-select').click()
  await page.getByRole('option', { name: 'hisi-dev-tool', exact: true }).click()
  await page.keyboard.press('Escape')
  await dialog.getByRole('button', { name: '创建' }).click()

  // 新会话的路由是 /ram/chat/:sid
  await expect(page).toHaveURL(/\/ram\/chat\/[^/]+/, { timeout: 60_000 })
  await expect(page.locator('.chat-header h2')).toContainText('hisi-dev-tool')

  const input = page.getByPlaceholder('输入问题，回车发送（Shift+Enter 换行）')
  await input.fill('这个项目里 SkillMarketController 的技能列表是怎么构建的？按分类筛选时有什么风险？')
  await input.press('Enter')

  // 回复是流式的，中间会展示工具调用步骤；等这一轮真正结束（工具步骤不再转圈、「停止」按钮消失）且助手消息有正文
  await page.locator('.assistant-row').first().waitFor({ timeout: 10 * 60_000 })
  const stopButton = page.getByRole('button', { name: /停止|中断/ })
  await expect
    .poll(
      async () => {
        const text = await page.locator('.assistant-row').last().innerText({ timeout: 3_000 }).catch(() => '')
        const toolStepsRunning = (await page.locator('.step-card .loading-icon').count()) > 0
        return !toolStepsRunning && (await stopButton.count()) === 0 && text.trim().length > 20
      },
      { timeout: 10 * 60_000, intervals: [3_000] },
    )
    .toBe(true)
  await expect(page.locator('.error-row')).toHaveCount(0)
  const text = await page.locator('.assistant-row').last().innerText()
  test.info().annotations.push({ type: 'answer', description: text.slice(0, 300) })
  await page.screenshot({ path: test.info().outputPath('ram-chat.png'), fullPage: true })
})
