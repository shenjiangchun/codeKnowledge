import { test, expect } from '@playwright/test'

/**
 * 项目现状分析 - 历史会话加载测试
 */

const BASE_URL = 'http://localhost:5173'

test('历史会话加载测试', async ({ page }) => {
  test.setTimeout(90000)

  // Step 1: Navigate to homepage
  console.log('[Step 1] 导航到首页...')
  await page.goto(BASE_URL)
  await page.waitForLoadState('domcontentloaded')
  await page.waitForTimeout(2000) // Give Vue app time to render

  // Step 2: Login
  console.log('[Step 2] 登录...')
  const loginBtn = page.locator('button:has-text("登录")').first()
  if (await loginBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
    await loginBtn.click()
    await page.waitForTimeout(500)

    // Wait for login dialog
    const dialog = page.locator('.el-dialog').first()
    await expect(dialog).toBeVisible({ timeout: 5000 })

    // Fill credentials - use specific selectors for login tab
    const loginTab = dialog.locator('.el-tab-pane').first() // Login is the first tab
    await loginTab.locator('input[placeholder="请输入用户名"]').fill('root')
    await loginTab.locator('input[placeholder="请输入密码"]').fill('123456')

    // Submit - click the login button in the login tab
    const submitBtn = loginTab.locator('button:has-text("登录")')
    await submitBtn.click()

    // Wait for dialog to close
    await expect(dialog).not.toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(1000)
  }

  // Step 3: Navigate to /ram/status
  console.log('[Step 3] 导航到项目现状分析历史会话列表...')
  await page.goto(`${BASE_URL}/ram/status`)
  await page.waitForLoadState('domcontentloaded')
  await page.waitForTimeout(2000)

  // Verify list page
  const cardHeader = page.locator('.el-card__header').first()
  await expect(cardHeader).toContainText('历史会话')

  // Take screenshot of list page
  await page.screenshot({ path: 'test-results/ram-history-list.png', fullPage: true })
  console.log('[Screenshot] test-results/ram-history-list.png')

  // Step 4: Click first session card
  console.log('[Step 4] 点击第一个历史会话卡片...')
  const sessionCards = page.locator('.session-card')
  const cardCount = await sessionCards.count()
  console.log(`[Step 4] 找到 ${cardCount} 个历史会话卡片`)

  expect(cardCount).toBeGreaterThan(0)

  await sessionCards.first().click()

  // Wait for navigation
  await page.waitForURL(/\/ram\/status\/[^/]+$/, { timeout: 10000 })
  await page.waitForLoadState('domcontentloaded')
  await page.waitForTimeout(2000)

  // Step 5: Verify report display
  console.log('[Step 5] 验证报告显示...')

  // Wait for status page to load
  const statusPage = page.locator('.status-page')
  await expect(statusPage).toBeVisible({ timeout: 15000 })

  // Wait for report to load (not showing loading spinner)
  const loadingSpinner = page.locator('.loading-container')
  const isLoading = await loadingSpinner.isVisible({ timeout: 3000 }).catch(() => false)

  if (isLoading) {
    console.log('[Step 5] 报告正在加载，等待...')
    await loadingSpinner.waitFor({ state: 'hidden', timeout: 60000 })
  }

  // Take screenshot of detail page
  await page.screenshot({ path: 'test-results/ram-history-detail.png', fullPage: true })
  console.log('[Screenshot] test-results/ram-history-detail.png')

  // Verify status tag shows "已完成"
  const statusTag = page.locator('.status-page .el-tag').first()
  const tagText = await statusTag.textContent()
  console.log(`[Step 5] 状态标签: "${tagText}"`)

  // Success: tag should show "已完成"
  expect(tagText).toContain('已完成')

  // Verify report content is visible (not empty)
  const reportContent = page.locator('.report-container, .markdown-content').first()
  await expect(reportContent).toBeVisible({ timeout: 5000 })

  console.log('[SUCCESS] 测试通过: 历史会话加载成功，显示"已完成"状态和报告内容')
})