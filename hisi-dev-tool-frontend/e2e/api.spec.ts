import { test, expect } from '@playwright/test'

/**
 * API 集成测试
 * 测试前后端 API 连接
 */
test.describe('API 集成测试', () => {
  test('后端健康检查', async ({ page }) => {
    // 直接访问后端 health 端点
    const response = await page.request.get('http://localhost:8080/actuator/health')
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.status).toBe('UP')
  })

  test('项目列表 API', async ({ page }) => {
    const response = await page.request.get('http://localhost:8080/api/projects/list')
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.message).toBe('success')
    expect(Array.isArray(data.data)).toBeTruthy()
  })

  test('知识图谱项目列表 API', async ({ page }) => {
    const response = await page.request.get('http://localhost:8080/api/knowledge-graph/projects')
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
  })

  test('前端代理转发', async ({ page }) => {
    // 通过前端代理访问后端 API
    const response = await page.request.get('/api/projects/list')
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
  })
})