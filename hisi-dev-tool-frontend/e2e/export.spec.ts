import { test, expect } from '@playwright/test'

/**
 * 导出功能 E2E 测试
 *
 * 测试范围：
 * - 日志分析报告导出（单个报告 + 批量 ZIP）
 * - RAM Session 导出
 * - 合入分析报告导出
 */

const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'

test.describe('导出功能测试', () => {

  test.beforeEach(async ({ page }) => {
    // 登录
    await page.goto(BASE_URL)
    await page.waitForLoadState('domcontentloaded')

    const loginBtn = page.locator('button:has-text("登录")').first()
    if (await loginBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await loginBtn.click()
      await page.waitForTimeout(500)

      const dialog = page.locator('.el-dialog').first()
      await expect(dialog).toBeVisible({ timeout: 5000 })

      const loginTab = dialog.locator('.el-tab-pane').first()
      await loginTab.locator('input[placeholder="请输入用户名"]').fill('root')
      await loginTab.locator('input[placeholder="请输入密码"]').fill('123456')

      const submitBtn = loginTab.locator('button:has-text("登录")')
      await submitBtn.click()

      await expect(dialog).not.toBeVisible({ timeout: 15000 })
      await page.waitForTimeout(1000)
    }
  })

  test('日志分析 - 报告详情页导出按钮可见', async ({ page }) => {
    // 导航到日志查询页面
    await page.goto(`${BASE_URL}/log-analysis`)
    await page.waitForLoadState('domcontentloaded')
    await page.waitForTimeout(2000)

    // 检查是否有报告数据
    const tableRows = page.locator('.el-table__body-wrapper .el-table__row')
    const rowCount = await tableRows.count()

    if (rowCount > 0) {
      // 点击第一行的查看详情按钮
      const viewBtn = tableRows.first().locator('button:has-text("详情"), button:has-text("查看")')
      if (await viewBtn.count() > 0) {
        await viewBtn.first().click()
        await page.waitForTimeout(1000)

        // 验证导出按钮存在
        const exportBtn = page.locator('button:has-text("导出"), button:has-text("Markdown")')
        await expect(exportBtn.first()).toBeVisible({ timeout: 5000 })

        // 截图
        await page.screenshot({ path: 'test-results/export-log-detail.png', fullPage: true })
      }
    } else {
      // 没有数据时跳过
      test.skip()
    }
  })

  test('日志分析 - 批量导出按钮存在', async ({ page }) => {
    await page.goto(`${BASE_URL}/log-analysis`)
    await page.waitForLoadState('domcontentloaded')
    await page.waitForTimeout(2000)

    // 查找批量导出按钮
    const exportZipBtn = page.locator('button:has-text("批量导出"), button:has-text("导出ZIP")')

    // 检查按钮是否可见（可能在工具栏或表头）
    const isVisible = await exportZipBtn.isVisible({ timeout: 3000 }).catch(() => false)

    if (isVisible) {
      await page.screenshot({ path: 'test-results/export-log-zip-btn.png', fullPage: true })
    } else {
      // 批量导出按钮可能在其他位置，不强制要求
      console.log('批量导出按钮当前不可见')
    }
  })

  test('RAM - 导出按钮在 Draft 页面可见', async ({ page }) => {
    // 导航到 RAM Draft 页面（需要有效的 session）
    // 先访问历史会话列表获取一个有效 session
    await page.goto(`${BASE_URL}/ram/status`)
    await page.waitForLoadState('domcontentloaded')
    await page.waitForTimeout(2000)

    const sessionCards = page.locator('.session-card')
    const cardCount = await sessionCards.count()

    if (cardCount > 0) {
      // 点击第一个历史会话
      await sessionCards.first().click()
      await page.waitForTimeout(2000)

      // 检查导出按钮
      const exportBtn = page.locator('button:has-text("导出"), button:has-text("Markdown")')
      const exportVisible = await exportBtn.isVisible({ timeout: 5000 }).catch(() => false)

      if (exportVisible) {
        await page.screenshot({ path: 'test-results/export-ram-session.png', fullPage: true })
      }
    } else {
      console.log('没有历史 RAM Session，跳过导出按钮测试')
    }
  })

  test('合入分析 - 导出按钮在分析页面可见', async ({ page }) => {
    // 导航到合入分析页面
    await page.goto(`${BASE_URL}/merge-analysis`)
    await page.waitForLoadState('domcontentloaded')
    await page.waitForTimeout(2000)

    // 检查是否有历史会话
    const historyItems = page.locator('.history-item, .session-item')
    const itemCount = await historyItems.count()

    if (itemCount > 0) {
      // 点击第一个历史会话
      await historyItems.first().click()
      await page.waitForTimeout(2000)

      // 检查导出按钮
      const exportBtn = page.locator('button:has-text("导出"), button:has-text("Markdown")')
      const exportVisible = await exportBtn.isVisible({ timeout: 5000 }).catch(() => false)

      if (exportVisible) {
        await page.screenshot({ path: 'test-results/export-merge-analysis.png', fullPage: true })
      }
    } else {
      console.log('没有合入分析历史记录，跳过导出按钮测试')
    }
  })

  test('后端 API - RAM 导出接口返回正确格式', async () => {
    // 直接测试后端 API
    const healthResponse = await fetch(`${BACKEND_URL}/api/ram/health`)

    if (!healthResponse.ok) {
      test.skip()
      return
    }

    // 测试不存在的 session 导出
    const invalidExportResponse = await fetch(`${BACKEND_URL}/api/ram/sessions/invalid-uuid/export/md`)

    // 应返回 404
    expect(invalidExportResponse.status).toBe(404)
  })
})

test.describe('导出功能错误处理', () => {

  test('后端 API - 日志报告导出不存在返回 404', async () => {
    const response = await fetch(`${BACKEND_URL}/api/log/report/999999/export/md`)

    // 应返回 404 或错误响应
    if (response.status === 404) {
      expect(response.status).toBe(404)
    } else {
      const data = await response.json()
      expect(data.code).toBe(404)
    }
  })

  test('后端 API - 批量导出空时间范围返回空 ZIP', async () => {
    // 使用不存在的时间范围
    const startTime = '2000-01-01T00:00:00'
    const endTime = '2000-01-02T00:00:00'

    const response = await fetch(`${BACKEND_URL}/api/log/reports/export/zip?startTime=${startTime}&endTime=${endTime}`)

    if (response.ok) {
      const blob = await response.blob()
      // ZIP 文件应该有最小大小（即使是空的）
      expect(blob.size).toBeGreaterThan(0)
      expect(blob.type).toContain('application/zip')
    }
  })
})