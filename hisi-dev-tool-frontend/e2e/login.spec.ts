import { test, expect } from '@playwright/test'

/**
 * 登录流程 E2E 测试
 * 测试步骤:
 * 1. 导航到首页
 * 2. 点击"登录"按钮
 * 3. 输入用户名: root，密码: 123456
 * 4. 点击登录按钮
 * 5. 验证成功：页面显示"root 管理员"
 */

// 串行执行测试，避免并行导致的localStorage状态冲突
test.describe.configure({ mode: 'serial' })

test.describe('登录流程测试', () => {
  test('完整的登录流程', async ({ page }) => {
    // 设置更长的测试超时时间
    test.setTimeout(60000)

    // 1. 导航到首页
    await page.goto('/')
    await page.waitForLoadState('domcontentloaded')

    // 2. 验证页面标题
    await expect(page).toHaveTitle(/HiSi|DevTool/)

    // 3. 清除可能的已登录状态（清除localStorage）
    await page.evaluate(() => localStorage.clear())
    await page.reload()
    await page.waitForLoadState('domcontentloaded')

    // 4. 查找并点击"登录"按钮
    const loginButton = page.locator('button:has-text("登录")').first()
    await expect(loginButton).toBeVisible({ timeout: 10000 })
    await loginButton.click()

    // 5. 等待登录对话框出现
    const loginDialog = page.locator('.el-dialog')
    await expect(loginDialog).toBeVisible({ timeout: 10000 })

    // 6. 填写登录表单
    // 在对话框内找到输入框
    const inputs = loginDialog.locator('input')
    const usernameInput = inputs.nth(0)
    const passwordInput = inputs.nth(1)

    await usernameInput.fill('root')
    await passwordInput.fill('123456')

    // 7. 点击登录按钮提交（对话框内的按钮）
    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // 8. 等待登录成功，对话框关闭
    await expect(loginDialog).not.toBeVisible({ timeout: 15000 })

    // 9. 验证用户信息显示：用户名显示为 "root"
    await page.waitForTimeout(2000) // 等待UI更新
    const username = page.locator('.user-dropdown .username')
    await expect(username.first()).toHaveText('root', { timeout: 10000 })

    // 10. 验证显示管理员标签
    const roleTag = page.locator('.user-dropdown .role-tag').first()
    await expect(roleTag).toContainText('管理员', { timeout: 5000 })

    // 11. 截图记录成功状态
    // 使用相对路径，Playwright会在test-results目录下创建
    await page.screenshot({ path: 'login-success.png', fullPage: true })

    console.log('登录测试成功：页面显示 "root 管理员"')
  })
})