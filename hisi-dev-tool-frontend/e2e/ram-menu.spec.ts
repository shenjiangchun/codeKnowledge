import { test, expect } from '@playwright/test'

/**
 * RAM (需求分析大师) 菜单测试
 * 验证菜单项可点击并能正确导航
 */

// 使用process.cwd()获取当前工作目录
const SCREENSHOT_DIR = process.cwd() + '/test-results'

test.describe('需求分析大师菜单测试', () => {
  test.slow() // 增加超时时间

  test.beforeEach(async ({ page }) => {
    // 导航到首页
    await page.goto('/')
    await page.waitForLoadState('networkidle')
  })

  test('需求分析大师菜单可点击并导航到/ram', async ({ page }) => {
    // 步骤1: 等待页面初始化
    await page.waitForTimeout(1000)

    // 检查是否有登录对话框弹出
    const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: '登录' })
    const dialogCount = await loginDialog.count()

    if (dialogCount > 0) {
      // 填写登录表单
      const usernameInput = page.locator('.el-dialog input[placeholder="请输入用户名"]').first()
      const passwordInput = page.locator('.el-dialog input[placeholder="请输入密码"]').first()

      await usernameInput.fill('root')
      await passwordInput.fill('123456')

      // 点击登录按钮
      await page.locator('.el-dialog button:has-text("登录")').first().click()

      // 等待登录成功，对话框关闭
      await expect(loginDialog).not.toBeVisible({ timeout: 10000 })

      // 等待页面状态稳定
      await page.waitForLoadState('networkidle')
      await page.waitForTimeout(1000)
    }

    // 截图：登录后状态
    await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-01-after-login.png`, fullPage: false })

    // 步骤2: 定位"需求分析大师"菜单项
    const ramMenuItem = page.locator('.el-menu-item').filter({ hasText: '需求分析大师' })

    // 步骤3: 验证菜单项存在且不是disabled状态
    await expect(ramMenuItem).toBeVisible({ timeout: 5000 })

    // 检查菜单项不是disabled状态（通过检查class）
    const isDisabled = await ramMenuItem.evaluate((el) => {
      return el.classList.contains('is-disabled')
    })
    expect(isDisabled).toBe(false)

    // 截图：点击前的菜单状态
    await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-02-menu-before-click.png`, fullPage: false })

    // 步骤4: 点击菜单项
    await ramMenuItem.click({ force: true })

    // 步骤5: 验证URL跳转到 /ram
    await expect(page).toHaveURL(/\/ram/, { timeout: 10000 })

    // 截图：点击后的页面
    await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-03-page-after-navigation.png`, fullPage: false })

    // 验证页面内容正确加载 - 使用宽松匹配并取第一个匹配项
    await expect(page.locator('text=/需求分析大师/').first()).toBeVisible({ timeout: 5000 })
  })

  test('需求分析大师菜单状态验证', async ({ page }) => {
    // 登录流程
    await page.waitForTimeout(1000)

    const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: '登录' })
    const dialogCount = await loginDialog.count()

    if (dialogCount > 0) {
      await page.locator('.el-dialog input[placeholder="请输入用户名"]').first().fill('root')
      await page.locator('.el-dialog input[placeholder="请输入密码"]').first().fill('123456')
      await page.locator('.el-dialog button:has-text("登录")').first().click()
      await expect(loginDialog).not.toBeVisible({ timeout: 10000 })
      await page.waitForLoadState('networkidle')
    }

    // 验证菜单项属性
    const ramMenuItem = page.locator('.el-menu-item').filter({ hasText: '需求分析大师' })

    // 检查菜单项可见
    await expect(ramMenuItem).toBeVisible()

    // 检查菜单项是否可交互
    const isEnabled = await ramMenuItem.isEnabled()
    expect(isEnabled).toBe(true)

    // 检查菜单项没有被禁用的样式
    const opacity = await ramMenuItem.evaluate((el) => {
      return parseFloat(window.getComputedStyle(el).opacity)
    })
    expect(opacity).toBeGreaterThan(0.5) // disabled状态opacity为0.5

    // 检查菜单项颜色
    const color = await ramMenuItem.evaluate((el) => {
      return window.getComputedStyle(el).color
    })
    console.log('Menu item color:', color)

    // 截图
    await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-04-menu-state-verification.png`, fullPage: false })
  })
})