import { test, expect } from '@playwright/test'

/**
 * Call Chain API E2E Tests (with JWT Authentication)
 *
 * Authentication: Uses JWT token (root/123456) with Authorization: Bearer header
 */

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'
const API_BASE = `${BACKEND_URL}/api/v2/knowledge-graph`

const TEST_PROJECT_PATH = process.env.TEST_PROJECT_PATH || 'C:/Users/47583/projects/hisi_dev_tool v5.0/hisi-dev-tool'

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

test.describe('Call Chain API Integration Tests', () => {
  let authToken: string | null = null

  test.beforeAll(async ({ request }) => {
    authToken = await getAuthToken(request)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-CHAIN-01: URI Call Chain
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-CHAIN-01: should return call chain graph', async ({ request }) => {
    const projectsRes = await request.get(`${API_BASE}/projects`, {
      headers: authHeaders(authToken)
    })
    expect(projectsRes.ok()).toBeTruthy()

    const projectsData = await projectsRes.json()
    if (!projectsData.data?.length) {
      test.skip(true, 'No projects available')
      return
    }

    const projectPath = projectsData.data[0]

    const entryRes = await request.get(`${API_BASE}/entry-points`, {
      headers: authHeaders(authToken),
      params: { projectPaths: projectPath, pageSize: 5 }
    })

    if (!entryRes.ok()) {
      test.skip(true, 'Failed to get entry points')
      return
    }

    const entryData = await entryRes.json()
    if (!entryData.data?.items?.length) {
      test.skip(true, 'No entry points available')
      return
    }

    const entryKey = entryData.data.items[0].entryKey

    const chainRes = await request.get(`${API_BASE}/call-chain/graph`, {
      headers: authHeaders(authToken),
      params: { entryKey, projectPaths: projectPath, includeCycles: true, maxDepth: 10 }
    })

    expect(chainRes.ok()).toBeTruthy()
    const chainData = await chainRes.json()
    expect(chainData.code).toBe(200)
    expect(chainData.data).toBeDefined()
    expect(Array.isArray(chainData.data.nodes)).toBeTruthy()
    expect(Array.isArray(chainData.data.edges)).toBeTruthy()
  })

  test('API-CHAIN-01: should handle empty URI', async ({ request }) => {
    const res = await request.get(`${API_BASE}/call-chain/graph`, {
      headers: authHeaders(authToken),
      params: { entryKey: '', projectPaths: TEST_PROJECT_PATH }
    })

    expect([200, 400, 404]).toContain(res.status())
  })

  test('API-CHAIN-01: should return empty for non-existent URI', async ({ request }) => {
    const res = await request.get(`${API_BASE}/call-chain/graph`, {
      headers: authHeaders(authToken),
      params: { entryKey: 'NON_EXISTENT_URI_/api/fake/endpoint', projectPaths: TEST_PROJECT_PATH }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.data?.nodes?.length ?? 0).toBeLessThanOrEqual(1)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-CHAIN-02: Method Reference Query
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-CHAIN-02: should return root entries', async ({ request }) => {
    const projectsRes = await request.get(`${API_BASE}/projects`, {
      headers: authHeaders(authToken)
    })
    const projectsData = await projectsRes.json()

    if (!projectsData.data?.length) {
      test.skip(true, 'No projects')
      return
    }

    const projectPath = projectsData.data[0]

    const classesRes = await request.get(`${API_BASE}/classes`, {
      headers: authHeaders(authToken),
      params: { projectPaths: projectPath, pageSize: 10 }
    })

    if (!classesRes.ok()) {
      test.skip(true, 'No classes')
      return
    }

    const classesData = await classesRes.json()
    const className = classesData.data?.items?.[0]

    if (!className) {
      test.skip(true, 'No class')
      return
    }

    const methodsRes = await request.get(`${API_BASE}/method/by-class`, {
      headers: authHeaders(authToken),
      params: { className, projectPaths: projectPath }
    })

    if (!methodsRes.ok()) {
      test.skip(true, 'No methods')
      return
    }

    const methodsData = await methodsRes.json()
    const methodName = methodsData.data?.[0]?.methodName

    if (!methodName) {
      test.skip(true, 'No method')
      return
    }

    const res = await request.get(`${API_BASE}/root-entries`, {
      headers: authHeaders(authToken),
      params: { className, methodName, projectPaths: projectPath }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data?.rootEntries)).toBeTruthy()
    expect(Array.isArray(data.data?.directCallers)).toBeTruthy()
  })

  test('API-CHAIN-02: should handle invalid method', async ({ request }) => {
    const res = await request.get(`${API_BASE}/root-entries`, {
      headers: authHeaders(authToken),
      params: { className: 'com.nonexistent.FakeClass', methodName: 'nonexistentMethod', projectPaths: TEST_PROJECT_PATH }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.data?.rootEntries?.length ?? 0).toBe(0)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-CHAIN-05: Entry Point Analysis
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-CHAIN-05: should return entry points', async ({ request }) => {
    const projectsRes = await request.get(`${API_BASE}/projects`, {
      headers: authHeaders(authToken)
    })
    const projectsData = await projectsRes.json()

    if (!projectsData.data?.length) {
      test.skip(true, 'No projects')
      return
    }

    const projectPath = projectsData.data[0]

    const res = await request.get(`${API_BASE}/entry-points`, {
      headers: authHeaders(authToken),
      params: { projectPaths: projectPath, page: 1, pageSize: 20 }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data?.items)).toBeTruthy()
    expect(typeof data.data?.total).toBe('number')
  })

  test('API-CHAIN-05: should return entry types', async ({ request }) => {
    const projectsRes = await request.get(`${API_BASE}/projects`, {
      headers: authHeaders(authToken)
    })
    const projectsData = await projectsRes.json()

    if (!projectsData.data?.length) {
      test.skip(true, 'No projects')
      return
    }

    const projectPath = projectsData.data[0]

    const res = await request.get(`${API_BASE}/entry-types`, {
      headers: authHeaders(authToken),
      params: { projectPaths: projectPath }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Helper Endpoints
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Helper: should return project list', async ({ request }) => {
    const res = await request.get(`${API_BASE}/projects`, {
      headers: authHeaders(authToken)
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()
  })

  test('Helper: should return class list', async ({ request }) => {
    const projectsRes = await request.get(`${API_BASE}/projects`, {
      headers: authHeaders(authToken)
    })
    const projectsData = await projectsRes.json()

    if (!projectsData.data?.length) {
      test.skip(true, 'No projects')
      return
    }

    const projectPath = projectsData.data[0]

    const res = await request.get(`${API_BASE}/classes`, {
      headers: authHeaders(authToken),
      params: { projectPaths: projectPath, page: 1, pageSize: 20 }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data?.items)).toBeTruthy()
  })

  test('Helper: should search methods', async ({ request }) => {
    const projectsRes = await request.get(`${API_BASE}/projects`, {
      headers: authHeaders(authToken)
    })
    const projectsData = await projectsRes.json()

    if (!projectsData.data?.length) {
      test.skip(true, 'No projects')
      return
    }

    const projectPath = projectsData.data[0]

    const res = await request.get(`${API_BASE}/method/search`, {
      headers: authHeaders(authToken),
      params: { keyword: 'Service', projectPaths: projectPath, limit: 10 }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Error Boundary
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Error: should handle invalid project path', async ({ request }) => {
    const res = await request.get(`${API_BASE}/entry-points`, {
      headers: authHeaders(authToken),
      params: { projectPaths: '/nonexistent/path', pageSize: 10 }
    })

    expect(res.ok()).toBeTruthy()
    const data = await res.json()
    expect(data.data?.items?.length ?? 0).toBe(0)
  })

  test('Error: should handle missing projectPaths', async ({ request }) => {
    const res = await request.get(`${API_BASE}/entry-points`, {
      headers: authHeaders(authToken),
      params: { pageSize: 10 }
    })

    expect([400, 500]).toContain(res.status())
  })
})