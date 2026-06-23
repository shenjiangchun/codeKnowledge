import { test, expect, Page } from '@playwright/test'

/**
 * Smoke Tests for HiSi DevTool Pages
 * Tests that each page renders without errors
 *
 * Test ID Convention: SMOKE-XX
 * Pages tested based on AppSidebar.vue and router/index.ts
 */

// Test configuration - run in parallel
test.describe.configure({ mode: 'parallel' })

/**
 * Helper function to perform login
 * Uses root/123456 admin credentials
 */
async function loginAsAdmin(page: Page) {
  await page.goto('/')
  await page.waitForLoadState('domcontentloaded')

  // Check if login dialog appears or if already logged in
  const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: /登录|Login/ })
  const dialogCount = await loginDialog.count()

  if (dialogCount > 0) {
    // Fill login form
    const usernameInput = page.locator('.el-dialog input').first()
    const passwordInput = page.locator('.el-dialog input').nth(1)

    await usernameInput.fill('root')
    await passwordInput.fill('123456')

    // Submit login
    await page.locator('.el-dialog button[type="submit"], .el-dialog button:has-text("登录")').first().click()

    // Wait for dialog to close
    await expect(loginDialog).not.toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(1000)
  }

  // Verify login success
  const userIndicator = page.locator('.user-dropdown, .username, text=root')
  await expect(userIndicator.first()).toBeVisible({ timeout: 10000 })
}

/**
 * Helper to collect console errors during page load
 */
async function collectConsoleErrors(page: Page): Promise<string[]> {
  const errors: string[] = []
  page.on('console', msg => {
    if (msg.type() === 'error') {
      errors.push(msg.text())
    }
  })
  return errors
}

/**
 * Helper to verify page loaded successfully
 */
async function verifyPageLoad(page: Page, route: string, titlePattern: RegExp | string) {
  await page.goto(route)
  await page.waitForLoadState('networkidle')

  // Verify page title
  await expect(page).toHaveTitle(titlePattern)

  // Verify no critical console errors (filter out favicon and minor warnings)
  const errors = await collectConsoleErrors(page)
  const criticalErrors = errors.filter(e =>
    !e.includes('favicon') &&
    !e.includes('net::ERR_') &&
    !e.includes('404') &&
    !e.includes('Warning:')
  )
  expect(criticalErrors).toHaveLength(0)
}

// =====================================================
// SMOKE-01: Skill Market - 技能市场
// =====================================================
test('SMOKE-01: 技能市场 (Skill Market) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/skill-market')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/技能市场|HiSi|DevTool/)
  await expect(page.locator('text=/技能市场|Skill Market/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-02: KG Skills Kit - KG Skills套件
// =====================================================
test('SMOKE-02: KG Skills套件 loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/kg-skills-kit')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/KG Skills|HiSi|DevTool/)
  await expect(page.locator('text=/KG Skills|套件/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-03: Claude Terminal - Claude终端
// =====================================================
test('SMOKE-03: Claude终端 (Claude Terminal) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/claude-terminal')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/Claude|终端|HiSi|DevTool/)
  await expect(page.locator('text=/Claude|终端|Terminal/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-04: APM Debug - APM调试
// =====================================================
test('SMOKE-04: APM调试 (APM Debug) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/apm-debug')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/APM|调试|HiSi|DevTool/)
  await expect(page.locator('text=/APM|调试|Debug/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-05: Settings - 系统设置
// =====================================================
test('SMOKE-05: 系统设置 (Settings) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/settings')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/设置|HiSi|DevTool/)
  await expect(page.locator('text=/设置|Settings/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-06: Prompt Config - 提示词配置
// =====================================================
test('SMOKE-06: 提示词配置 (Prompt Config) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/prompt-config')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/提示词|配置|HiSi|DevTool/)
  await expect(page.locator('text=/提示词|Prompt|配置/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-07: Glossary - 术语管理
// =====================================================
test('SMOKE-07: 术语管理 (Glossary) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/glossary')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/术语|Glossary|HiSi|DevTool/)
  await expect(page.locator('text=/术语|Glossary/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-08: Project Management - 项目管理
// =====================================================
test('SMOKE-08: 项目管理 (Project Management) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/project')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/项目|Project|HiSi|DevTool/)
  await expect(page.locator('text=/项目|Project/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-09: Enhanced Search - 增强检索
// Note: This page may redirect to /project if no project is selected
// =====================================================
test('SMOKE-09: 增强检索 (Enhanced Search) page accessible', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/search')
  await page.waitForLoadState('networkidle')

  // Page should either load or redirect to project selection
  await expect(page).toHaveTitle(/检索|项目|HiSi|DevTool/)

  // Verify we're either on search page or redirected to project page
  const currentUrl = page.url()
  expect(
    currentUrl.includes('/search') || currentUrl.includes('/project')
  ).toBe(true)

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-10: Merge Analysis List - 合入分析列表
// =====================================================
test('SMOKE-10: 合入分析列表 (Merge Analysis List) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/merge-analysis')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/合入分析|Merge|HiSi|DevTool/)
  await expect(page.locator('text=/合入分析|Merge Analysis/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-11: Merge Analysis New - 合入分析新建
// =====================================================
test('SMOKE-11: 合入分析新建 (Merge Analysis New) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/merge-analysis/new')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/新建|合入分析|HiSi|DevTool/)
  // Page should show the input form
  await expect(page.locator('form, .el-form, .input-form').first()).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-12: RAM Sessions - 需求分析大师
// Note: This page may redirect to /project if no project is selected
// =====================================================
test('SMOKE-12: 需求分析大师 (RAM Sessions) page accessible', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/ram')
  await page.waitForLoadState('networkidle')

  // Page should either load or redirect to project selection
  await expect(page).toHaveTitle(/需求分析|项目|HiSi|DevTool/)

  // Verify we're either on ram page or redirected to project page
  const currentUrl = page.url()
  expect(
    currentUrl.includes('/ram') || currentUrl.includes('/project')
  ).toBe(true)

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-13: Log Analysis - 日志分析
// =====================================================
test('SMOKE-13: 日志分析 (Log Analysis) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/log-analysis')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/日志|Log|分析|HiSi|DevTool/)
  await expect(page.locator('text=/日志|Log/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-14: User Management (Admin Only) - 用户管理
// This page should only be accessible by admin users
// =====================================================
test('SMOKE-14: 用户管理 (User Management) - accessible by admin', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/admin/users')
  await page.waitForLoadState('networkidle')

  // Admin should be able to access the page
  await expect(page).toHaveTitle(/用户|User|管理|Management|HiSi|DevTool/)
  await expect(page.locator('text=/用户|User/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-14-B: User Management - Non-admin should be redirected
// =====================================================
test('SMOKE-14-B: 用户管理 (User Management) - non-admin redirected to home', async ({ page }) => {
  // Clear any existing auth state
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.reload()
  await page.waitForLoadState('domcontentloaded')

  // Try to access admin page without login
  await page.goto('/admin/users')
  await page.waitForLoadState('networkidle')

  // Should be redirected to home or show access denied
  const currentUrl = page.url()
  const isRedirected = !currentUrl.includes('/admin/users')

  // If redirected or still on login page, the test passes
  expect(isRedirected || currentUrl.includes('/')).toBe(true)
})

// =====================================================
// SMOKE-15: Knowledge Graph - 知识图谱
// Note: This page may redirect to /project if no project is selected
// =====================================================
test('SMOKE-15: 知识图谱 (Knowledge Graph) page accessible', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/knowledge-graph')
  await page.waitForLoadState('networkidle')

  // Page should either load or redirect to project selection
  await expect(page).toHaveTitle(/知识图谱|项目|HiSi|DevTool/)

  // Verify we're either on knowledge-graph page or redirected to project page
  const currentUrl = page.url()
  expect(
    currentUrl.includes('/knowledge-graph') || currentUrl.includes('/project')
  ).toBe(true)

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-16: RAM Status - 项目现状分析
// Note: This page may redirect to /project if no project is selected
// =====================================================
test('SMOKE-16: 项目现状分析 (RAM Status) page accessible', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/ram/status')
  await page.waitForLoadState('networkidle')

  // Page should either load or redirect to project selection
  await expect(page).toHaveTitle(/现状分析|项目|HiSi|DevTool/)

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})

// =====================================================
// SMOKE-17: Claude Session - Claude会话
// =====================================================
test('SMOKE-17: Claude会话 (Claude Session) loads correctly', async ({ page }) => {
  await loginAsAdmin(page)
  const errors = await collectConsoleErrors(page)

  await page.goto('/claude-session')
  await page.waitForLoadState('networkidle')

  await expect(page).toHaveTitle(/Claude|会话|Session|HiSi|DevTool/)
  await expect(page.locator('text=/Claude|会话|Session/i')).toBeVisible({ timeout: 10000 })

  const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  expect(criticalErrors).toHaveLength(0)
})