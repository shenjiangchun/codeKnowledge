import { test, expect } from '@playwright/test'

/**
 * 项目管理 API 集成测试 (with JWT Authentication)
 *
 * 测试范围:
 * - Git仓库扫描
 * - 远端项目管理
 * - 项目分组管理
 *
 * Authentication: Uses JWT token (root/123456) with Authorization: Bearer header
 */

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'
const API_BASE = `${BACKEND_URL}/api`

test.setTimeout(60000)

// Helper: Authenticate and get JWT token (root/123456)
async function getAuthToken(request: import('@playwright/test').APIRequestContext): Promise<string | null> {
  const loginResponse = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { username: 'root', password: '123456' }
  })

  if (!loginResponse.ok()) {
    console.warn('[Auth] Login failed')
    return null
  }

  const loginData = await loginResponse.json()
  const token = loginData.data?.token || loginData.token
  console.log('[Auth] Got JWT token:', token ? 'yes' : 'no')
  return token
}

// Helper: Auth headers
function authHeaders(token: string | null): Record<string, string> {
  return token ? { 'Authorization': `Bearer ${token}` } : {}
}

const TEST_GROUP = {
  appId: `test-app-${Date.now()}`,
  appName: 'E2E Test Application',
  projectPaths: ['/tmp/test-project-1', '/tmp/test-project-2']
}

test.describe('项目管理 API 测试', () => {
  let authToken: string | null = null

  test.beforeAll(async ({ request }) => {
    authToken = await getAuthToken(request)
  })

  test.describe.configure({ mode: 'parallel' })

  // API-PROJ-01: Git仓库扫描
  test('API-PROJ-01: Git仓库扫描', async ({ request }) => {
    const response = await request.get(`${API_BASE}/projects/scan-git-repos`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()

    for (const repo of data.data) {
      expect(repo).toHaveProperty('path')
      expect(repo).toHaveProperty('name')
      expect(repo).toHaveProperty('branch')
      expect(repo).toHaveProperty('clean')
    }
  })

  // API-PROJ-02: 远端项目列表
  test('API-PROJ-02: 远端项目列表', async ({ request }) => {
    const response = await request.get(`${API_BASE}/remote-projects`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()

    for (const project of data.data) {
      expect(project).toHaveProperty('id')
      expect(project).toHaveProperty('name')
      expect(project).toHaveProperty('cloneStatus')
      expect(['PENDING', 'CLONING', 'CLONED', 'FAILED']).toContain(project.cloneStatus)
    }
  })

  // API-PROJ-03: 克隆远端项目
  test('API-PROJ-03: 克隆远端项目 - 项目不存在', async ({ request }) => {
    const response = await request.post(`${API_BASE}/remote-projects/999999/clone`, {
      headers: authHeaders(authToken)
    })

    expect([400, 404, 500]).toContain(response.status())
  })

  // API-PROJ-04: 项目分组列表
  test('API-PROJ-04: 项目分组列表', async ({ request }) => {
    const response = await request.get(`${API_BASE}/project-group`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()

    for (const group of data.data) {
      expect(group).toHaveProperty('appId')
      expect(group).toHaveProperty('appName')
      expect(Array.isArray(group.projectPaths)).toBeTruthy()
    }
  })

  // API-PROJ-05: 项目分组创建
  test('API-PROJ-05: 项目分组创建', async ({ request }) => {
    const createResponse = await request.post(`${API_BASE}/project-group`, {
      headers: authHeaders(authToken),
      data: {
        appId: TEST_GROUP.appId,
        appName: TEST_GROUP.appName,
        projectPaths: TEST_GROUP.projectPaths
      }
    })

    expect(createResponse.ok()).toBeTruthy()
    const createData = await createResponse.json()
    expect(createData.code).toBe(200)

    // 清理
    const deleteResponse = await request.delete(`${API_BASE}/project-group/${TEST_GROUP.appId}`, {
      headers: authHeaders(authToken)
    })
    expect([200, 204]).toContain(deleteResponse.status())
  })

  // API-PROJ-05: 项目分组创建 - 无效参数
  test('API-PROJ-05: 项目分组创建 - 无效参数', async ({ request }) => {
    const response = await request.post(`${API_BASE}/project-group`, {
      headers: authHeaders(authToken),
      data: { appName: 'Test App', projectPaths: [] }
    })

    expect([400, 500]).toContain(response.status())
  })

  // 错误边界: 无效的项目ID格式
  test('错误边界: 无效的项目ID格式', async ({ request }) => {
    const response = await request.get(`${API_BASE}/projects/status?name=`, {
      headers: authHeaders(authToken)
    })

    expect([200, 400, 404]).toContain(response.status())
  })

  // API响应格式验证
  test('API响应格式验证', async ({ request }) => {
    const response = await request.get(`${API_BASE}/projects/list`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data).toHaveProperty('code')
    expect(data).toHaveProperty('message')
    expect(typeof data.code).toBe('number')
  })
})

test.describe('项目管理 API - 清理测试', () => {
  let authToken: string | null = null

  test.beforeAll(async ({ request }) => {
    authToken = await getAuthToken(request)
  })

  test('清理测试数据', async ({ request }) => {
    const appId = TEST_GROUP.appId

    const getResponse = await request.get(`${API_BASE}/project-group/${appId}`, {
      headers: authHeaders(authToken)
    })

    if (getResponse.status() === 200) {
      const deleteResponse = await request.delete(`${API_BASE}/project-group/${appId}`, {
        headers: authHeaders(authToken)
      })
      expect([200, 204, 404]).toContain(deleteResponse.status())
    }
  })
})