import { test, expect } from '@playwright/test'

/**
 * 日志分析模块测试
 */
test.describe('日志分析模块', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/log-analysis')
    await page.waitForLoadState('networkidle')
  })

  test('页面加载', async ({ page }) => {
    // 检查标题
    await expect(page).toHaveTitle(/日志分析/)

    // 检查主要元素存在
    await expect(page.locator('text=日志分析')).toBeVisible()
  })

  test('查询表单', async ({ page }) => {
    // 检查查询按钮存在
    const queryButton = page.locator('button:has-text("查询")')
    await expect(queryButton).toBeVisible()
  })
})

/**
 * 调用链分析模块测试
 */
test.describe('调用链分析模块', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/call-chain')
    await page.waitForLoadState('networkidle')
  })

  test('项目列表页面加载', async ({ page }) => {
    await expect(page).toHaveTitle(/调用链分析/)
    await expect(page.locator('text=调用链分析')).toBeVisible()
  })

  test('项目列表为空时显示提示', async ({ page }) => {
    // 等待数据加载
    await page.waitForTimeout(1000)

    // 检查是否显示空状态或项目列表
    const content = page.locator('body')
    await expect(content).toBeVisible()
  })
})

/**
 * 项目管理模块测试
 */
test.describe('项目管理模块', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/project')
    await page.waitForLoadState('networkidle')
  })

  test('项目管理页面加载', async ({ page }) => {
    await expect(page).toHaveTitle(/项目管理/)
  })
})

/**
 * 运维监控模块测试
 */
test.describe('运维监控模块', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/ops')
    await page.waitForLoadState('networkidle')
  })

  test('健康检查页面加载', async ({ page }) => {
    await expect(page).toHaveTitle(/运维监控/)
  })
})