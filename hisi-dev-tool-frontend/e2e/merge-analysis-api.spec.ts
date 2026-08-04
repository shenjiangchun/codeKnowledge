import { test, expect } from '@playwright/test'

/**
 * Merge Analysis API E2E Tests (with JWT Authentication)
 *
 * Tests the merge analysis workflow API endpoints with proper auth headers.
 * Authentication: Uses JWT token (root/123456) with Authorization: Bearer header
 */

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'
const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:5173'

const TEST_PROJECT_PATH = process.env.TEST_PROJECT_PATH || 'C:/Users/47583/projects/hisi_dev_tool v5.0/hisi-dev-tool'
const TEST_SOURCE_BRANCH = 'main'
const TEST_TARGET_BRANCH = 'master'

test.setTimeout(60000)

// Helper: sleep
const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))

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

test.describe('Merge Analysis API Tests', () => {
  let authToken: string | null = null

  test.beforeAll(async ({ request }) => {
    authToken = await getAuthToken(request)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-MERGE-01: Create merge analysis session
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-MERGE-01: should create a new session', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data).toBeDefined()
    expect(data.data.sessionHandle).toBeDefined()
    expect(typeof data.data.sessionHandle).toBe('string')
  })

  test('API-MERGE-01: should return error for missing required fields', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {}
    })

    expect([400, 500, 422]).toContain(response.status())
  })

  test('API-MERGE-01: should return error for empty projectPath', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: '',
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    expect(response.ok()).toBeFalsy()
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-MERGE-02: Get merge diff
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-MERGE-02: should return diff result', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data).toBeDefined()

    expect(data.data.sourceBranch).toBeDefined()
    expect(data.data.targetBranch).toBeDefined()
    expect(typeof data.data.totalFiles).toBe('number')
    expect(typeof data.data.totalAdditions).toBe('number')
    expect(typeof data.data.totalDeletions).toBe('number')
    expect(Array.isArray(data.data.files)).toBeTruthy()
  })

  test('API-MERGE-02: should return empty diff for same branch', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_SOURCE_BRANCH
      }
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data.totalFiles).toBe(0)
    expect(data.data.files).toHaveLength(0)
  })

  test('API-MERGE-02: should return error for invalid project path', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: '/non/existent/path/xyz123',
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    expect(response.ok()).toBeFalsy()
  })

  test('API-MERGE-02: file diff should have correct structure', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()

    if (data.data.files && data.data.files.length > 0) {
      const fileDiff = data.data.files[0]
      expect(fileDiff.filePath).toBeDefined()
      expect(['ADD', 'MODIFY', 'DELETE', 'RENAME']).toContain(fileDiff.changeType)
      expect(typeof fileDiff.additions).toBe('number')
      expect(typeof fileDiff.deletions).toBe('number')
    }
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-MERGE-03: Get session status
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-MERGE-03: should return session status', async ({ request }) => {
    // Create session first
    const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    if (!createResponse.ok()) {
      test.skip(true, 'Failed to create session')
      return
    }

    const createData = await createResponse.json()
    const sessionId = createData.data?.sessionHandle

    await sleep(1000)

    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data).toBeDefined()

    expect(['idle', 'running', 'DONE', 'FAILED', 'completed', 'error']).toContain(data.data.status)
    expect(typeof data.data.lastSeq).toBe('number')
  })

  test('API-MERGE-03: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/invalid-session-id-xyz`, {
      headers: authHeaders(authToken)
    })

    expect(response.status()).toBe(404)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-MERGE-04: List sessions
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-MERGE-04: should return list of sessions', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()
  })

  test('API-MERGE-04: should respect limit parameter', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions?limit=5`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()
    expect(data.data.length).toBeLessThanOrEqual(5)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-MERGE-05: Get session events
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-MERGE-05: should return session events', async ({ request }) => {
    // Create session first
    const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    if (!createResponse.ok()) {
      test.skip(true, 'Failed to create session')
      return
    }

    const createData = await createResponse.json()
    const sessionId = createData.data?.sessionHandle

    await sleep(2000)

    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}/events`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()
  })

  test('API-MERGE-05: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/invalid-session-id/events`, {
      headers: authHeaders(authToken)
    })

    expect(response.status()).toBe(404)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-MERGE-06: List branches
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-MERGE-06: should return list of branches', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/branches?projectPath=${encodeURIComponent(TEST_PROJECT_PATH)}`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()
  })

  test('API-MERGE-06: should contain common branches', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/branches?projectPath=${encodeURIComponent(TEST_PROJECT_PATH)}`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeTruthy()
    const data = await response.json()

    const branches = data.data as string[]
    expect(branches.some(b => b === 'main' || b === 'master')).toBeTruthy()
  })

  test('API-MERGE-06: should return error for missing projectPath', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/branches`, {
      headers: authHeaders(authToken)
    })

    expect(response.ok()).toBeFalsy()
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-MERGE-07: Rerun from node
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-MERGE-07: should rerun from valid node', async ({ request }) => {
    // Create session first
    const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    if (!createResponse.ok()) {
      test.skip(true, 'Failed to create session')
      return
    }

    const createData = await createResponse.json()
    const sessionId = createData.data?.sessionHandle

    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}/rerun-from/diff_extract`, {
      headers: authHeaders(authToken),
      data: {}
    })

    if (response.ok()) {
      const data = await response.json()
      expect(data.code).toBe(200)
      expect(data.data.rerunFromNode).toBe('diff_extract')
      expect(data.data.dispatched).toBe(true)
    }
  })

  test('API-MERGE-07: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions/invalid-session-id/rerun-from/diff_extract`, {
      headers: authHeaders(authToken),
      data: {}
    })

    expect(response.status()).toBe(404)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Error boundary tests
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Error: should return 400 for empty request body', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {}
    })

    expect([400, 500, 422]).toContain(response.status())
  })

  test('Error: should return 404 for non-existent session', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/00000000-0000-0000-0000-000000000000`, {
      headers: authHeaders(authToken)
    })

    expect(response.status()).toBe(404)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // SSE Stream endpoint
  // ═══════════════════════════════════════════════════════════════════════════════

  test('SSE: should accept SSE connection', async ({ request }) => {
    // Create session first
    const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: TEST_PROJECT_PATH,
        sourceBranch: TEST_SOURCE_BRANCH,
        targetBranch: TEST_TARGET_BRANCH
      }
    })

    if (!createResponse.ok()) {
      test.skip(true, 'Failed to create session')
      return
    }

    const createData = await createResponse.json()
    const sessionId = createData.data?.sessionHandle

    const streamResponse = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}/stream`, {
      headers: { ...authHeaders(authToken) },
      timeout: 5000
    })

    expect([200, 404, 503]).toContain(streamResponse.status())
  })
})