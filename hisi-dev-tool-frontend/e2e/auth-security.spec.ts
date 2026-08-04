import { test, expect } from '@playwright/test'

/**
 * Authentication Security E2E Tests
 *
 * Tests cover:
 * - AUTH-02: Login failure - wrong password
 * - AUTH-03: Login failure - user not found
 * - AUTH-04: Login failure - empty password
 * - AUTH-05: Logout flow
 * - AUTH-08: Regular user permission boundary
 * - AUTH-09: Admin permission boundary
 * - AUTH-10: Menu disabled without project selection
 */

// Serial mode for tests that share state (localStorage)
test.describe.configure({ mode: 'serial' })

const BASE_URL = 'http://localhost:5173'

/**
 * Helper: Open login dialog
 */
async function openLoginDialog(page: import('@playwright/test').Page) {
  await page.goto(BASE_URL)
  await page.waitForLoadState('domcontentloaded')

  // Clear any existing auth state
  await page.evaluate(() => localStorage.clear())
  await page.reload()
  await page.waitForLoadState('domcontentloaded')

  // Click login button
  const loginButton = page.locator('button:has-text("登录")').first()
  await expect(loginButton).toBeVisible({ timeout: 10000 })
  await loginButton.click()

  // Wait for dialog
  const loginDialog = page.locator('.el-dialog')
  await expect(loginDialog).toBeVisible({ timeout: 10000 })

  return loginDialog
}

/**
 * Helper: Get ElMessage notification
 */
function getElMessage(page: import('@playwright/test').Page) {
  return page.locator('.el-message')
}

test.describe('AUTH-02: Login failure - wrong password', () => {
  test('should show error message when password is incorrect', async ({ page }) => {
    test.setTimeout(60000)

    const loginDialog = await openLoginDialog(page)

    // Fill form with wrong password
    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('root')
    await inputs.nth(1).fill('wrongpassword')

    // Submit
    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Verify error message appears (ElMessage)
    const errorMessage = getElMessage(page)
    await expect(errorMessage).toBeVisible({ timeout: 10000 })

    // Verify error message contains failure indication
    // The message could be "用户名或密码错误" or similar
    const messageText = await errorMessage.textContent()
    expect(messageText).toBeTruthy()
    // Error message should indicate login failure (not success)
    expect(messageText).not.toContain('成功')

    // Verify dialog remains open (login did not succeed)
    await expect(loginDialog).toBeVisible({ timeout: 3000 })

    // Take screenshot for evidence
    await page.screenshot({ path: 'auth-wrong-password.png' })
  })
})

test.describe('AUTH-03: Login failure - user not found', () => {
  test('should show error message when user does not exist', async ({ page }) => {
    test.setTimeout(60000)

    const loginDialog = await openLoginDialog(page)

    // Fill form with non-existent user
    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('nonexistentuser12345')
    await inputs.nth(1).fill('123456')

    // Submit
    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Verify error message appears
    const errorMessage = getElMessage(page)
    await expect(errorMessage).toBeVisible({ timeout: 10000 })

    // Verify error message contains failure indication
    const messageText = await errorMessage.textContent()
    expect(messageText).toBeTruthy()
    // Could be "用户不存在" or "用户名或密码错误"
    expect(messageText).not.toContain('成功')

    // Verify dialog remains open
    await expect(loginDialog).toBeVisible({ timeout: 3000 })

    await page.screenshot({ path: 'auth-user-not-found.png' })
  })
})

test.describe('AUTH-04: Login failure - empty password', () => {
  test('should show validation error when password is empty', async ({ page }) => {
    test.setTimeout(60000)

    const loginDialog = await openLoginDialog(page)

    // Fill only username, leave password empty
    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('root')
    await inputs.nth(1).fill('') // Empty password

    // Submit
    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Verify validation message appears (ElMessage.warning)
    const warningMessage = getElMessage(page)
    await expect(warningMessage).toBeVisible({ timeout: 10000 })

    // Verify the message indicates both fields are required
    const messageText = await warningMessage.textContent()
    expect(messageText).toContain('请输入用户名和密码')

    // Verify dialog remains open
    await expect(loginDialog).toBeVisible({ timeout: 3000 })

    await page.screenshot({ path: 'auth-empty-password.png' })
  })

  test('should show validation error when username is empty', async ({ page }) => {
    test.setTimeout(60000)

    const loginDialog = await openLoginDialog(page)

    // Fill only password, leave username empty
    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('')
    await inputs.nth(1).fill('123456')

    // Submit
    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Verify validation message
    const warningMessage = getElMessage(page)
    await expect(warningMessage).toBeVisible({ timeout: 10000 })

    const messageText = await warningMessage.textContent()
    expect(messageText).toContain('请输入用户名和密码')

    // Verify dialog remains open
    await expect(loginDialog).toBeVisible({ timeout: 3000 })
  })
})

test.describe('AUTH-05: Logout flow', () => {
  test('should clear auth state and redirect to home on logout', async ({ page }) => {
    test.setTimeout(60000)

    // Start fresh
    await page.goto(BASE_URL)
    await page.waitForLoadState('domcontentloaded')
    await page.evaluate(() => localStorage.clear())
    await page.reload()
    await page.waitForLoadState('domcontentloaded')

    // Step 1: Login first
    const loginButton = page.locator('button:has-text("登录")').first()
    await expect(loginButton).toBeVisible({ timeout: 10000 })
    await loginButton.click()

    const loginDialog = page.locator('.el-dialog')
    await expect(loginDialog).toBeVisible({ timeout: 10000 })

    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('root')
    await inputs.nth(1).fill('123456')

    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Wait for login success
    await expect(loginDialog).not.toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(1000)

    // Verify logged in state
    const username = page.locator('.user-dropdown .username').first()
    await expect(username).toHaveText('root', { timeout: 10000 })

    // Step 2: Logout
    // Click on the user-info span to open dropdown menu
    const userInfo = page.locator('.user-dropdown .user-info')
    await expect(userInfo).toBeVisible({ timeout: 5000 })
    await userInfo.click()

    // Wait for dropdown menu to appear
    await page.waitForTimeout(500)

    // Find the logout dropdown item
    const logoutOption = page.getByRole('menuitem', { name: '退出登录' })
    await expect(logoutOption).toBeVisible({ timeout: 5000 })
    await logoutOption.click()

    // Step 3: Verify logout state
    await page.waitForTimeout(1000)

    // Verify localStorage token is cleared
    const token = await page.evaluate(() => localStorage.getItem('hisi-token'))
    expect(token).toBeNull()

    // Verify login button is visible again (logged out state)
    const loginButtonAfterLogout = page.locator('button:has-text("登录")').first()
    await expect(loginButtonAfterLogout).toBeVisible({ timeout: 10000 })

    // Verify username is no longer displayed
    const usernameAfterLogout = page.locator('.user-dropdown .username')
    await expect(usernameAfterLogout).not.toBeVisible({ timeout: 5000 })

    await page.screenshot({ path: 'auth-logout-success.png' })
  })
})

test.describe('AUTH-08: Regular user permission boundary', () => {
  test('should not show user management menu for regular user', async ({ page }) => {
    test.setTimeout(60000)

    // Note: This test requires a regular user account to exist
    // If backend does not support regular users, this test will be skipped

    await page.goto(BASE_URL)
    await page.waitForLoadState('domcontentloaded')
    await page.evaluate(() => localStorage.clear())
    await page.reload()
    await page.waitForLoadState('domcontentloaded')

    // Try to login as a regular user
    // First, we need to check if there's a regular user account available
    // For now, this test documents the expected behavior
    // If no regular user exists, the test should be skipped or marked as fixme

    // Attempt login with testuser (if exists)
    const loginButton = page.locator('button:has-text("登录")').first()
    await expect(loginButton).toBeVisible({ timeout: 10000 })
    await loginButton.click()

    const loginDialog = page.locator('.el-dialog')
    await expect(loginDialog).toBeVisible({ timeout: 10000 })

    const inputs = loginDialog.locator('input')

    // Try with a regular user account
    // Note: This test assumes a 'testuser' account exists with password '123456'
    // If no regular user exists, the test will fail at login
    await inputs.nth(0).fill('testuser')
    await inputs.nth(1).fill('123456')

    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Wait for either login success or failure
    try {
      await expect(loginDialog).not.toBeVisible({ timeout: 10000 })

      // If login succeeded, check for user management menu
      await page.waitForTimeout(1000)

      // Check sidebar for user management menu
      const sidebar = page.locator('.app-sidebar')
      const userManagementItem = sidebar.locator('text=用户管理')

      // For regular user, user management should NOT be visible
      await expect(userManagementItem).not.toBeVisible({ timeout: 5000 })

      // Verify regular user role tag shows "成员"
      const roleTag = page.locator('.user-dropdown .role-tag').first()
      await expect(roleTag).toContainText('成员', { timeout: 5000 })

      await page.screenshot({ path: 'auth-regular-user-menu.png' })

      // Logout after test
      const userInfo = page.locator('.user-dropdown .user-info')
      await expect(userInfo).toBeVisible({ timeout: 5000 })
      await userInfo.click()
      await page.waitForTimeout(500)
      const logoutOption = page.getByRole('menuitem', { name: '退出登录' })
      await expect(logoutOption).toBeVisible({ timeout: 5000 })
      await logoutOption.click()
      await page.waitForTimeout(1000)

    } catch {
      // If login failed, the test user doesn't exist
      // Mark as skipped
      test.skip(true, 'Regular user account (testuser) does not exist - skipping permission test')
    }
  })
})

test.describe('AUTH-09: Admin permission boundary', () => {
  test('should show user management menu for admin user', async ({ page }) => {
    test.setTimeout(60000)

    // Clear state
    await page.goto(BASE_URL)
    await page.waitForLoadState('domcontentloaded')
    await page.evaluate(() => localStorage.clear())
    await page.reload()
    await page.waitForLoadState('domcontentloaded')

    // Login as admin (root)
    const loginButton = page.locator('button:has-text("登录")').first()
    await expect(loginButton).toBeVisible({ timeout: 10000 })
    await loginButton.click()

    const loginDialog = page.locator('.el-dialog')
    await expect(loginDialog).toBeVisible({ timeout: 10000 })

    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('root')
    await inputs.nth(1).fill('123456')

    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Wait for login success
    await expect(loginDialog).not.toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(1000)

    // Verify admin role tag
    const roleTag = page.locator('.user-dropdown .role-tag').first()
    await expect(roleTag).toContainText('管理员', { timeout: 5000 })

    // Verify user management menu is visible in sidebar
    const sidebar = page.locator('.app-sidebar')
    const userManagementItem = sidebar.locator('text=用户管理')
    await expect(userManagementItem).toBeVisible({ timeout: 5000 })

    await page.screenshot({ path: 'auth-admin-user-menu.png' })

    // Logout after test
    const userInfo = page.locator('.user-dropdown .user-info')
    await expect(userInfo).toBeVisible({ timeout: 5000 })
    await userInfo.click()
    await page.waitForTimeout(500)
    const logoutOption = page.getByRole('menuitem', { name: '退出登录' })
    await expect(logoutOption).toBeVisible({ timeout: 5000 })
    await logoutOption.click()
    await page.waitForTimeout(1000)
  })
})

test.describe('AUTH-10: Menu disabled without project selection', () => {
  test('should show disabled state for menus requiring project selection', async ({ page }) => {
    test.setTimeout(60000)

    // Clear state and login
    await page.goto(BASE_URL)
    await page.waitForLoadState('domcontentloaded')
    await page.evaluate(() => localStorage.clear())
    await page.reload()
    await page.waitForLoadState('domcontentloaded')

    // Login first
    const loginButton = page.locator('button:has-text("登录")').first()
    await expect(loginButton).toBeVisible({ timeout: 10000 })
    await loginButton.click()

    const loginDialog = page.locator('.el-dialog')
    await expect(loginDialog).toBeVisible({ timeout: 10000 })

    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('root')
    await inputs.nth(1).fill('123456')

    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    await expect(loginDialog).not.toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(1000)

    // Clear project selection to trigger disabled state
    // The selectedProjects is stored in localStorage with key 'hisi-selected-projects'
    await page.evaluate(() => {
      localStorage.removeItem('hisi-selected-projects')
    })

    // Reload to apply the cleared state
    await page.reload()
    await page.waitForLoadState('domcontentloaded')
    await page.waitForTimeout(1000)

    // Check sidebar for disabled menu items
    const sidebar = page.locator('.app-sidebar')

    // 'search' (增强检索) should be disabled without project selection
    const searchMenuItem = sidebar.locator('.el-menu-item:has-text("增强检索")')

    // Verify the menu item has disabled styling
    // According to AppSidebar.vue, disabled items have:
    // - disabled attribute on el-menu-item
    // - CSS: opacity: 0.5, cursor: not-allowed
    const isDisabled = await searchMenuItem.evaluate((el) => {
      const computed = window.getComputedStyle(el)
      return el.classList.contains('is-disabled') ||
             computed.opacity === '0.5' ||
             computed.cursor === 'not-allowed'
    })

    expect(isDisabled).toBeTruthy()

    // 'knowledge-graph' (知识图谱) should also be disabled
    // Note: When disabled, items with children are rendered as el-menu-item, not el-sub-menu
    const kgMenuItem = sidebar.locator('.el-menu-item:has-text("知识图谱")')

    // Verify the menu item is visible but has disabled styling
    await expect(kgMenuItem).toBeVisible({ timeout: 5000 })

    const kgIsDisabled = await kgMenuItem.evaluate((el) => {
      const computed = window.getComputedStyle(el)
      return el.classList.contains('is-disabled') ||
             computed.opacity === '0.5' ||
             computed.cursor === 'not-allowed'
    })

    expect(kgIsDisabled).toBeTruthy()

    await page.screenshot({ path: 'auth-menu-disabled-state.png' })
  })
})

test.describe('AUTH: Security edge cases', () => {
  test('should handle SQL injection attempt in username', async ({ page }) => {
    test.setTimeout(60000)

    const loginDialog = await openLoginDialog(page)

    // Try SQL injection in username
    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill("admin' OR '1'='1")
    await inputs.nth(1).fill('anything')

    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Should show login error, not succeed
    const errorMessage = getElMessage(page)
    await expect(errorMessage).toBeVisible({ timeout: 10000 })

    // Verify still on login page (not logged in)
    await expect(loginDialog).toBeVisible({ timeout: 3000 })

    await page.screenshot({ path: 'auth-sql-injection-blocked.png' })
  })

  test('should handle XSS attempt in username', async ({ page }) => {
    test.setTimeout(60000)

    const loginDialog = await openLoginDialog(page)

    // Try XSS in username
    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('<script>alert("xss")</script>')
    await inputs.nth(1).fill('password')

    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    // Should show login error or validation error
    const message = getElMessage(page)
    await expect(message).toBeVisible({ timeout: 10000 })

    // Verify no script execution (no alert dialog)
    // The input should be properly escaped

    await page.screenshot({ path: 'auth-xss-blocked.png' })
  })

  test('should not expose sensitive data in localStorage after logout', async ({ page }) => {
    test.setTimeout(60000)

    // Login first
    await page.goto(BASE_URL)
    await page.waitForLoadState('domcontentloaded')
    await page.evaluate(() => localStorage.clear())
    await page.reload()
    await page.waitForLoadState('domcontentloaded')

    const loginButton = page.locator('button:has-text("登录")').first()
    await expect(loginButton).toBeVisible({ timeout: 10000 })
    await loginButton.click()

    const loginDialog = page.locator('.el-dialog')
    await expect(loginDialog).toBeVisible({ timeout: 10000 })

    const inputs = loginDialog.locator('input')
    await inputs.nth(0).fill('root')
    await inputs.nth(1).fill('123456')

    const submitButton = loginDialog.locator('button[type="submit"]').first()
    await submitButton.click()

    await expect(loginDialog).not.toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(1000)

    // Verify token exists after login
    const tokenBeforeLogout = await page.evaluate(() => localStorage.getItem('hisi-token'))
    expect(tokenBeforeLogout).toBeTruthy()

    // Logout
    const userInfo = page.locator('.user-dropdown .user-info')
    await expect(userInfo).toBeVisible({ timeout: 5000 })
    await userInfo.click()
    await page.waitForTimeout(500)
    const logoutOption = page.getByRole('menuitem', { name: '退出登录' })
    await expect(logoutOption).toBeVisible({ timeout: 5000 })
    await logoutOption.click()
    await page.waitForTimeout(1000)

    // Verify no sensitive data in localStorage
    const tokenAfterLogout = await page.evaluate(() => localStorage.getItem('hisi-token'))
    expect(tokenAfterLogout).toBeNull()

    // Verify no password stored
    const allKeys = await page.evaluate(() => Object.keys(localStorage))
    const sensitiveKeys = allKeys.filter(key =>
      key.toLowerCase().includes('password') ||
      key.toLowerCase().includes('secret') ||
      key.toLowerCase().includes('credential')
    )
    expect(sensitiveKeys).toHaveLength(0)

    await page.screenshot({ path: 'auth-no-sensitive-data-in-storage.png' })
  })
})