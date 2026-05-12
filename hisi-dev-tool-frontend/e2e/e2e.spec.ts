import { test, expect, Page } from '@playwright/test'

/**
 * 端到端流程测试
 * 测试完整的用户操作流程
 */
test.describe('端到端流程测试', () => {
  test('完整的页面导航流程', async ({ page }) => {
    // 1. 访问首页
    await page.goto('/')
    await expect(page).toHaveURL(/log-analysis/)

    // 2. 导航到调用链分析
    await page.click('text=调用链分析')
    await expect(page).toHaveURL(/call-chain/)

    // 3. 导航到项目管理
    await page.click('text=项目管理')
    await expect(page).toHaveURL(/project/)

    // 4. 导航到运维监控
    await page.click('text=运维监控')
    await expect(page).toHaveURL(/ops/)

    // 5. 返回日志分析
    await page.click('text=日志分析')
    await expect(page).toHaveURL(/log-analysis/)
  })

  test('响应式布局测试', async ({ page }) => {
    // 测试不同屏幕尺寸
    const viewports = [
      { width: 1920, height: 1080, name: 'Desktop' },
      { width: 1366, height: 768, name: 'Laptop' },
      { width: 768, height: 1024, name: 'Tablet' },
      { width: 375, height: 667, name: 'Mobile' },
    ]

    for (const viewport of viewports) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.goto('/')
      await page.waitForLoadState('networkidle')

      // 检查页面是否正常渲染
      await expect(page.locator('body')).toBeVisible()
    }
  })
})