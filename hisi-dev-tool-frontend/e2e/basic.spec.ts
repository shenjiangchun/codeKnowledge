import { test, expect } from '@playwright/test'

/**
 * 基础功能测试
 * 测试应用基本加载和导航
 */
test.describe('基础功能测试', () => {
  test('首页加载', async ({ page }) => {
    await page.goto('/')

    // 检查标题
    await expect(page).toHaveTitle(/HiSi Dev Tool/)

    // 检查默认跳转到日志分析页
    await expect(page).toHaveURL(/log-analysis/)
  })

  test('侧边栏导航', async ({ page }) => {
    await page.goto('/')

    // 测试所有导航项
    const navItems = [
      { text: '日志分析', href: '/log-analysis' },
      { text: '调用链分析', href: '/call-chain' },
      { text: '项目管理', href: '/project' },
      { text: '运维监控', href: '/ops' },
    ]

    for (const item of navItems) {
      // 点击导航项
      await page.click(`text=${item.text}`)

      // 验证 URL 变化
      await expect(page).toHaveURL(new RegExp(item.href))

      // 等待页面加载
      await page.waitForLoadState('networkidle')
    }
  })
})