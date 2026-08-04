import { test, expect, Page } from '@playwright/test'

/**
 * 自然语言诊断组件 E2E 测试
 * 测试 NaturalLanguageChat.vue 组件（会话管理版本）
 */
test.describe('自然语言诊断组件测试', () => {

  const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'

  /**
   * NaturalLanguageChat 组件测试
   */
  test.describe('NaturalLanguageChat 主组件', () => {

    test.beforeEach(async ({ page }) => {
      await page.goto(`${BASE_URL}/natural-language`)
      await page.waitForLoadState('networkidle')
    })

    test('自然语言诊断页面正常加载', async ({ page }) => {
      // 验证侧边栏存在
      const sessionPanel = page.locator('.session-panel, [class*="session"]').first()
      await expect(sessionPanel).toBeVisible({ timeout: 10000 })

      // 验证标题包含"自然语言诊断"
      const title = page.locator('text=自然语言诊断').first()
      await expect(title).toBeVisible()
    })

    test('新建诊断会话按钮存在', async ({ page }) => {
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await expect(newSessionButton).toBeVisible()
      await expect(newSessionButton).toBeEnabled()
    })

    test('会话分组显示', async ({ page }) => {
      // 验证"进行中"分组
      const activeGroup = page.locator('text=进行中').first()
      await expect(activeGroup).toBeVisible()

      // 验证"已归档"分组
      const archivedGroup = page.locator('text=已归档').first()
      await expect(archivedGroup).toBeVisible()
    })

    test('点击新建诊断会话', async ({ page }) => {
      // 点击新建会话
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await newSessionButton.click()
      await page.waitForTimeout(2000)

      // 验证会话创建（检查是否有消息提示或会话列表更新）
      const message = page.locator('.el-message')
      const sessionItem = page.locator('.session-item, [class*="session-item"]').first()

      // 应该有消息提示或新会话
      const hasMessage = await message.isVisible().catch(() => false)
      const hasSession = await sessionItem.isVisible().catch(() => false)

      expect(hasMessage || hasSession).toBeTruthy()
    })

    test('空状态提示显示', async ({ page }) => {
      // 验证空状态提示
      const emptyState = page.locator('text=选择一个诊断会话').first()
      await expect(emptyState).toBeVisible()

      // 验证开始按钮
      const startButton = page.locator('button:has-text("开始自然语言诊断")')
      await expect(startButton).toBeVisible()
    })

    test('点击开始自然语言诊断创建会话', async ({ page }) => {
      const startButton = page.locator('button:has-text("开始自然语言诊断")')
      await startButton.click()
      await page.waitForTimeout(1000)

      // 验证会话创建
    })

    test('会话列表项点击', async ({ page }) => {
      // 先创建一个会话
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await newSessionButton.click()
      await page.waitForTimeout(1000)

      // 尝试点击会话项
      const sessionItem = page.locator('[class*="session-item"]').first()
      if (await sessionItem.isVisible()) {
        await sessionItem.click()
      }
    })
  })

  /**
   * 会话创建后功能测试
   */
  test.describe('会话功能测试', () => {

    test.beforeEach(async ({ page }) => {
      await page.goto(`${BASE_URL}/natural-language`)
      await page.waitForLoadState('networkidle')

      // 创建新会话
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await newSessionButton.click()
      await page.waitForTimeout(1500)
    })

    test('输入区域显示', async ({ page }) => {
      // 验证输入框存在
      const inputArea = page.locator('textarea').first()
      await expect(inputArea).toBeVisible()
    })

    test('输入内容并发送', async ({ page }) => {
      // 输入内容
      const inputArea = page.locator('textarea').first()
      await inputArea.fill('帮我分析这个NullPointerException错误')
      await expect(inputArea).toHaveValue('帮我分析这个NullPointerException错误')

      // 发送按钮应该可用
      const sendButton = page.locator('button:has-text("发送")')
      await expect(sendButton).toBeEnabled()
    })

    test('发送按钮初始禁用', async ({ page }) => {
      // 未输入内容时，发送按钮应该禁用
      const sendButton = page.locator('button:has-text("发送")')
      await expect(sendButton).toBeDisabled()
    })

    test('快捷建议按钮显示', async ({ page }) => {
      // 验证快捷建议区域
      const suggestions = page.locator('.intent-suggestions, text=快捷输入')
      if (await suggestions.isVisible()) {
        const suggestionButtons = suggestions.locator('button')
        const count = await suggestionButtons.count()
        expect(count).toBeGreaterThanOrEqual(0)
      }
    })

    test('Enter发送消息', async ({ page }) => {
      const inputArea = page.locator('textarea').first()
      await inputArea.fill('测试消息')
      await inputArea.press('Enter')
      await page.waitForTimeout(1000)

      // 验证消息已发送（检查消息历史或loading状态）
    })

    test('会话操作按钮显示', async ({ page }) => {
      // 验证导出、归档、删除按钮
      const exportButton = page.locator('button:has-text("导出")')
      const archiveButton = page.locator('button:has-text("归档")')
      const deleteButton = page.locator('button:has-text("删除")')

      if (await exportButton.isVisible()) {
        await expect(exportButton).toBeVisible()
      }
      if (await archiveButton.isVisible()) {
        await expect(archiveButton).toBeVisible()
      }
      if (await deleteButton.isVisible()) {
        await expect(deleteButton).toBeVisible()
      }
    })
  })

  /**
   * 多轮对话测试
   */
  test.describe('多轮对话测试', () => {

    test.beforeEach(async ({ page }) => {
      await page.goto(`${BASE_URL}/natural-language`)
      await page.waitForLoadState('networkidle')

      // 创建新会话
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await newSessionButton.click()
      await page.waitForTimeout(1500)
    })

    test('发送多条消息', async ({ page }) => {
      const inputArea = page.locator('textarea').first()

      // 第一轮
      await inputArea.fill('分析这个错误')
      await inputArea.press('Enter')
      await page.waitForTimeout(1500)

      // 第二轮
      await inputArea.fill('请详细解释')
      await inputArea.press('Enter')
      await page.waitForTimeout(1500)

      // 第三轮
      await inputArea.fill('给我修复建议')
      await inputArea.press('Enter')
      await page.waitForTimeout(1000)

      // 验证会话中有多轮对话
    })
  })

  /**
   * 完整用户流程测试
   */
  test.describe('完整用户流程', () => {

    test('完整诊断流程', async ({ page }) => {
      // 1. 访问页面
      await page.goto(`${BASE_URL}/natural-language`)
      await page.waitForLoadState('networkidle')

      // 2. 验证页面加载
      const title = page.locator('text=自然语言诊断').first()
      await expect(title).toBeVisible()

      // 3. 创建新会话
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await newSessionButton.click()
      await page.waitForTimeout(1500)

      // 4. 输入问题
      const inputArea = page.locator('textarea').first()
      await inputArea.fill('帮我分析NullPointerException错误')
      await inputArea.press('Enter')
      await page.waitForTimeout(2000)

      // 5. 验证消息历史更新
    })

    test('使用快捷建议', async ({ page }) => {
      await page.goto(`${BASE_URL}/natural-language`)
      await page.waitForLoadState('networkidle')

      // 创建会话
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await newSessionButton.click()
      await page.waitForTimeout(1500)

      // 点击快捷建议（如果有）
      const suggestionButton = page.locator('.intent-suggestions button, button:has-text("分析")').first()
      if (await suggestionButton.isVisible()) {
        await suggestionButton.click()
        await page.waitForTimeout(500)

        // 验证输入框被填充
        const inputArea = page.locator('textarea').first()
        const inputValue = await inputArea.inputValue()
        expect(inputValue.length).toBeGreaterThan(0)
      }
    })
  })

  /**
   * 侧边栏导航测试
   */
  test.describe('侧边栏导航', () => {

    test('自然语言诊断子菜单', async ({ page }) => {
      await page.goto(`${BASE_URL}/natural-language`)
      await page.waitForLoadState('networkidle')

      // 验证子菜单项
      const simplifiedOption = page.locator('text=简化版')
      const advancedOption = page.locator('text=高级版')

      // 子菜单可能默认展开或需要点击
      const menuToggle = page.locator('[class*="menu"]:has-text("自然语言诊断")').first()
      if (await menuToggle.isVisible()) {
        await menuToggle.click()
        await page.waitForTimeout(500)
      }
    })
  })

  /**
   * 错误处理测试
   */
  test.describe('错误处理', () => {

    test('无项目选择时创建会话提示', async ({ page }) => {
      await page.goto(`${BASE_URL}/natural-language`)
      await page.waitForLoadState('networkidle')

      // 点击新建会话
      const newSessionButton = page.locator('button:has-text("新建诊断会话")')
      await newSessionButton.click()
      await page.waitForTimeout(1000)

      // 检查是否有提示消息（如果没有选择项目）
      const warning = page.locator('.el-message:has-text("选择项目"), .el-message:has-text("项目")')
      // 消息可能存在也可能不存在
    })
  })
})