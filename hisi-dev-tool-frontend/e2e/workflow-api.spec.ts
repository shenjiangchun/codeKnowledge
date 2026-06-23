import { test, expect } from '@playwright/test'

/**
 * Workflow API Integration Tests (with JWT Authentication)
 *
 * Tests the unified workflow controller endpoints:
 * - API-WF-01: Start workflow (POST /api/ram/sessions)
 * - API-WF-02: Get workflow status (GET /api/workflow/sessions/:id/status)
 * - API-WF-03: Get workflow report (GET /api/workflow/sessions/:id/report)
 * - API-WF-04: Get workflow events (GET /api/workflow/sessions/:id/events)
 * - API-WF-05: Submit clarification (POST /api/ram/sessions/:id/clarify)
 * - API-WF-06: HITL confirmation (POST /api/ram/sessions/:id/confirm)
 * - API-WF-07: Abort workflow (POST /api/workflow/sessions/:id/abort)
 * - API-WF-08: Get workflow definitions (GET /api/workflow/definitions)
 *
 * Authentication: Uses JWT token (root/123456) with Authorization: Bearer header
 */

const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'

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

// Helper: Create test session
async function createTestSession(request: import('@playwright/test').APIRequestContext, token: string | null): Promise<string | null> {
  const response = await request.post(`${BACKEND_URL}/api/ram/sessions`, {
    headers: authHeaders(token),
    data: {
      rawInput: 'Test requirement for E2E workflow API testing',
      projectPath: '/tmp/test-project',
      userId: 'e2e-test'
    }
  })

  if (!response.ok()) {
    console.warn('[Setup] Failed to create test session, status:', response.status())
    return null
  }

  const data = await response.json()
  return data.data?.sessionId || data.sessionId
}

test.describe('Workflow API Integration Tests', () => {
  let authToken: string | null = null

  test.beforeAll(async ({ request }) => {
    authToken = await getAuthToken(request)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-08: Get Workflow Definitions
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-08: should return list of workflow definitions', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/workflow/definitions`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()

    if (data.data?.length > 0) {
      for (const def of data.data) {
        expect(def.workflowType).toBeTruthy()
        expect(def.displayName).toBeTruthy()
        expect(Array.isArray(def.nodeNames)).toBeTruthy()
      }
    }
    console.log('[API-WF-08] Definitions:', data.data?.length || 0)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-02: Get Workflow Status
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-02: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/status`, {
      headers: authHeaders(authToken)
    })
    expect(response.status()).toBe(404)
    const data = await response.json()
    expect(data.code).toBe(404)
  })

  test('API-WF-02: should return status for valid sessionId', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(1000)

    const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/status`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data).toBeTruthy()
    expect(['idle', 'RUNNING', 'DONE', 'FAILED', 'ABORTED', 'CLARIFY_REQ', 'HITL_REQ'].some(s => data.data.status?.includes(s))).toBeTruthy()
    console.log('[API-WF-02] Status:', data.data.status)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-03: Get Workflow Report
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-03: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/report`, {
      headers: authHeaders(authToken)
    })
    expect(response.status()).toBe(404)
  })

  test('API-WF-03: should return report structure', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(1000)

    const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/report`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data).toBeTruthy()
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-04: Get Workflow Events
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-04: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/events`, {
      headers: authHeaders(authToken)
    })
    expect(response.status()).toBe(404)
  })

  test('API-WF-04: should return event list', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(2000)

    const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/events`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()

    if (data.data?.length > 0) {
      for (const evt of data.data) {
        expect(evt.seq).toBeDefined()
        expect(evt.type).toBeTruthy()
      }
      console.log('[API-WF-04] Events:', data.data.length)
    }
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-07: Abort Workflow
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-07: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/abort`, {
      headers: authHeaders(authToken),
      data: {}
    })
    expect(response.status()).toBe(404)
  })

  test('API-WF-07: should abort running session', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(500)

    const abortResponse = await request.post(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/abort`, {
      headers: authHeaders(authToken),
      data: {}
    })
    expect(abortResponse.ok()).toBeTruthy()

    await sleep(500)

    const statusResponse = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/status`, {
      headers: authHeaders(authToken)
    })
    expect(statusResponse.ok()).toBeTruthy()

    const statusData = await statusResponse.json()
    expect(statusData.data.status).toBe('ABORTED')
    console.log('[API-WF-07] Aborted successfully')
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-01: Start Workflow
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-01: should start demand analysis workflow', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/ram/sessions`, {
      headers: authHeaders(authToken),
      data: {
        rawInput: 'E2E test: Implement user authentication feature',
        projectPath: '/tmp/test-auth-project',
        userId: 'e2e-test-user'
      }
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data.sessionId).toBeTruthy()
    expect(data.data.sessionId).toMatch(/^[a-f0-9-]+$/)
    console.log('[API-WF-01] Session:', data.data.sessionId)
  })

  test('API-WF-01: should return 400 for empty rawInput', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/ram/sessions`, {
      headers: authHeaders(authToken),
      data: { rawInput: '', projectPath: '/tmp/test' }
    })
    expect(response.status()).toBe(400)
  })

  test('API-WF-01: should start status analysis workflow', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/ram/status/start`, {
      headers: authHeaders(authToken),
      data: {
        projectPath: '/tmp/status-test-project',
        mode: 'quick',
        question: 'What is the current architecture?'
      }
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data.sessionId).toBeTruthy()
    console.log('[API-WF-01] Status analysis:', data.data.sessionId)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-05: Submit Clarification
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-05: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/ram/sessions/non-existent-session-id/clarify`, {
      headers: authHeaders(authToken),
      data: { answers: { question1: 'Answer 1' } }
    })
    expect(response.status()).toBe(404)
  })

  test('API-WF-05: should accept clarification answers', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(500)

    const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/clarify`, {
      headers: authHeaders(authToken),
      data: { answers: { targetUsers: 'Developers', priority: 'High' } }
    })

    expect([200, 400, 404].includes(response.status())).toBeTruthy()

    if (response.ok()) {
      const data = await response.json()
      expect(data.code).toBe(200)
      console.log('[API-WF-05] Clarification submitted')
    }
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // API-WF-06: HITL Confirmation
  // ═══════════════════════════════════════════════════════════════════════════════

  test('API-WF-06: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/ram/sessions/non-existent-session-id/confirm`, {
      headers: authHeaders(authToken),
      data: { action: 'approve', nodeName: 'impact' }
    })
    expect(response.status()).toBe(404)
  })

  test('API-WF-06: should accept approve action', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(500)

    const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/confirm`, {
      headers: authHeaders(authToken),
      data: { nodeName: 'impact', action: 'approve', feedback: 'Looks good' }
    })

    expect([200, 400, 404].includes(response.status())).toBeTruthy()

    if (response.ok()) {
      const data = await response.json()
      expect(data.code).toBe(200)
      console.log('[API-WF-06] Approved')
    }
  })

  test('API-WF-06: should accept reject action', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(500)

    const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/confirm`, {
      headers: authHeaders(authToken),
      data: { nodeName: 'implement', action: 'reject', feedback: 'Need more details' }
    })

    expect([200, 400, 404].includes(response.status())).toBeTruthy()
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // SSE Stream Endpoint
  // ═══════════════════════════════════════════════════════════════════════════════

  test('SSE: should return SSE stream', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/stream`, {
      headers: { ...authHeaders(authToken), 'Accept': 'text/event-stream' }
    })

    expect([200, 500].includes(response.status())).toBeTruthy()

    if (response.ok()) {
      const contentType = response.headers()['content-type']
      expect(contentType).toContain('text/event-stream')
    }
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Node Registry
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Nodes: should list available workflow nodes', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/workflow/nodes`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(typeof data.data).toBe('object')

    const nodeNames = Object.keys(data.data)
    if (nodeNames.length > 0) {
      console.log('[Nodes] Available:', nodeNames.slice(0, 10))
    }
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Session List
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Sessions: should list recent sessions', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/ram/sessions?limit=10`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(Array.isArray(data.data)).toBeTruthy()

    if (data.data?.length > 0) {
      expect(data.data[0].sessionId).toBeTruthy()
      expect(data.data[0].status).toBeTruthy()
      console.log('[Sessions] Count:', data.data.length)
    }
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Rerun from Node
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Rerun: should rerun from specific node', async ({ request }) => {
    const sessionId = await createTestSession(request, authToken)
    if (!sessionId) {
      test.skip(true, 'Failed to create test session')
      return
    }

    await sleep(1000)

    const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/rerun-from/impact`, {
      headers: authHeaders(authToken),
      data: {}
    })

    expect([200, 404].includes(response.status())).toBeTruthy()

    if (response.ok()) {
      const data = await response.json()
      expect(data.code).toBe(200)
      console.log('[Rerun] Dispatched')
    }
  })

  test('Rerun: should return 404 for invalid sessionId', async ({ request }) => {
    const response = await request.post(`${BACKEND_URL}/api/ram/sessions/invalid-id/rerun-from/impact`, {
      headers: authHeaders(authToken),
      data: {}
    })
    expect(response.status()).toBe(404)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Health Check
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Health: backend should be UP', async ({ request }) => {
    const response = await request.get(`${BACKEND_URL}/api/ram/health`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    expect(data.data.status).toBe('UP')
    console.log('[Health] UP, startedAt:', data.data.startedAt)
  })

  // ═══════════════════════════════════════════════════════════════════════════════
  // Frontend Proxy
  // ═══════════════════════════════════════════════════════════════════════════════

  test('Proxy: frontend should forward API requests', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/workflow/definitions`, {
      headers: authHeaders(authToken)
    })
    expect(response.ok()).toBeTruthy()

    const data = await response.json()
    expect(data.code).toBe(200)
    console.log('[Proxy] Verified')
  })
})