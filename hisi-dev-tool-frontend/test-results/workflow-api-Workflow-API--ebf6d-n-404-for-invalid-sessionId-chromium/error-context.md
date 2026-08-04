# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: workflow-api.spec.ts >> Workflow API Integration Tests >> API-WF-02: should return 404 for invalid sessionId
- Location: e2e\workflow-api.spec.ts:104:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 404
Received: 200
```

# Test source

```ts
  8   |  * - API-WF-02: Get workflow status (GET /api/workflow/sessions/:id/status)
  9   |  * - API-WF-03: Get workflow report (GET /api/workflow/sessions/:id/report)
  10  |  * - API-WF-04: Get workflow events (GET /api/workflow/sessions/:id/events)
  11  |  * - API-WF-05: Submit clarification (POST /api/ram/sessions/:id/clarify)
  12  |  * - API-WF-06: HITL confirmation (POST /api/ram/sessions/:id/confirm)
  13  |  * - API-WF-07: Abort workflow (POST /api/workflow/sessions/:id/abort)
  14  |  * - API-WF-08: Get workflow definitions (GET /api/workflow/definitions)
  15  |  *
  16  |  * Authentication: Uses JWT token (root/123456) with Authorization: Bearer header
  17  |  */
  18  | 
  19  | const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
  20  | const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'
  21  | 
  22  | test.setTimeout(60000)
  23  | 
  24  | // Helper: sleep
  25  | const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms))
  26  | 
  27  | // Helper: Authenticate and get JWT token (root/123456)
  28  | async function getAuthToken(request: import('@playwright/test').APIRequestContext): Promise<string | null> {
  29  |   const loginResponse = await request.post(`${BACKEND_URL}/api/auth/login`, {
  30  |     data: { username: 'root', password: '123456' }
  31  |   })
  32  | 
  33  |   if (!loginResponse.ok()) {
  34  |     console.warn('[Auth] Login failed')
  35  |     return null
  36  |   }
  37  | 
  38  |   const loginData = await loginResponse.json()
  39  |   const token = loginData.data?.token || loginData.token
  40  |   console.log('[Auth] Got JWT token:', token ? 'yes' : 'no')
  41  |   return token
  42  | }
  43  | 
  44  | // Helper: Auth headers
  45  | function authHeaders(token: string | null): Record<string, string> {
  46  |   return token ? { 'Authorization': `Bearer ${token}` } : {}
  47  | }
  48  | 
  49  | // Helper: Create test session
  50  | async function createTestSession(request: import('@playwright/test').APIRequestContext, token: string | null): Promise<string | null> {
  51  |   const response = await request.post(`${BACKEND_URL}/api/ram/sessions`, {
  52  |     headers: authHeaders(token),
  53  |     data: {
  54  |       rawInput: 'Test requirement for E2E workflow API testing',
  55  |       projectPath: '/tmp/test-project',
  56  |       userId: 'e2e-test'
  57  |     }
  58  |   })
  59  | 
  60  |   if (!response.ok()) {
  61  |     console.warn('[Setup] Failed to create test session, status:', response.status())
  62  |     return null
  63  |   }
  64  | 
  65  |   const data = await response.json()
  66  |   return data.data?.sessionId || data.sessionId
  67  | }
  68  | 
  69  | test.describe('Workflow API Integration Tests', () => {
  70  |   let authToken: string | null = null
  71  | 
  72  |   test.beforeAll(async ({ request }) => {
  73  |     authToken = await getAuthToken(request)
  74  |   })
  75  | 
  76  |   // ═══════════════════════════════════════════════════════════════════════════════
  77  |   // API-WF-08: Get Workflow Definitions
  78  |   // ═══════════════════════════════════════════════════════════════════════════════
  79  | 
  80  |   test('API-WF-08: should return list of workflow definitions', async ({ request }) => {
  81  |     const response = await request.get(`${BACKEND_URL}/api/workflow/definitions`, {
  82  |       headers: authHeaders(authToken)
  83  |     })
  84  |     expect(response.ok()).toBeTruthy()
  85  | 
  86  |     const data = await response.json()
  87  |     expect(data.code).toBe(200)
  88  |     expect(Array.isArray(data.data)).toBeTruthy()
  89  | 
  90  |     if (data.data?.length > 0) {
  91  |       for (const def of data.data) {
  92  |         expect(def.workflowType).toBeTruthy()
  93  |         expect(def.displayName).toBeTruthy()
  94  |         expect(Array.isArray(def.nodeNames)).toBeTruthy()
  95  |       }
  96  |     }
  97  |     console.log('[API-WF-08] Definitions:', data.data?.length || 0)
  98  |   })
  99  | 
  100 |   // ═══════════════════════════════════════════════════════════════════════════════
  101 |   // API-WF-02: Get Workflow Status
  102 |   // ═══════════════════════════════════════════════════════════════════════════════
  103 | 
  104 |   test('API-WF-02: should return 404 for invalid sessionId', async ({ request }) => {
  105 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/status`, {
  106 |       headers: authHeaders(authToken)
  107 |     })
> 108 |     expect(response.status()).toBe(404)
      |                               ^ Error: expect(received).toBe(expected) // Object.is equality
  109 |     const data = await response.json()
  110 |     expect(data.code).toBe(404)
  111 |   })
  112 | 
  113 |   test('API-WF-02: should return status for valid sessionId', async ({ request }) => {
  114 |     const sessionId = await createTestSession(request, authToken)
  115 |     if (!sessionId) {
  116 |       test.skip(true, 'Failed to create test session')
  117 |       return
  118 |     }
  119 | 
  120 |     await sleep(1000)
  121 | 
  122 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/status`, {
  123 |       headers: authHeaders(authToken)
  124 |     })
  125 |     expect(response.ok()).toBeTruthy()
  126 | 
  127 |     const data = await response.json()
  128 |     expect(data.code).toBe(200)
  129 |     expect(data.data).toBeTruthy()
  130 |     expect(['idle', 'RUNNING', 'DONE', 'FAILED', 'ABORTED', 'CLARIFY_REQ', 'HITL_REQ'].some(s => data.data.status?.includes(s))).toBeTruthy()
  131 |     console.log('[API-WF-02] Status:', data.data.status)
  132 |   })
  133 | 
  134 |   // ═══════════════════════════════════════════════════════════════════════════════
  135 |   // API-WF-03: Get Workflow Report
  136 |   // ═══════════════════════════════════════════════════════════════════════════════
  137 | 
  138 |   test('API-WF-03: should return 404 for invalid sessionId', async ({ request }) => {
  139 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/report`, {
  140 |       headers: authHeaders(authToken)
  141 |     })
  142 |     expect(response.status()).toBe(404)
  143 |   })
  144 | 
  145 |   test('API-WF-03: should return report structure', async ({ request }) => {
  146 |     const sessionId = await createTestSession(request, authToken)
  147 |     if (!sessionId) {
  148 |       test.skip(true, 'Failed to create test session')
  149 |       return
  150 |     }
  151 | 
  152 |     await sleep(1000)
  153 | 
  154 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/report`, {
  155 |       headers: authHeaders(authToken)
  156 |     })
  157 |     expect(response.ok()).toBeTruthy()
  158 | 
  159 |     const data = await response.json()
  160 |     expect(data.code).toBe(200)
  161 |     expect(data.data).toBeTruthy()
  162 |   })
  163 | 
  164 |   // ═══════════════════════════════════════════════════════════════════════════════
  165 |   // API-WF-04: Get Workflow Events
  166 |   // ═══════════════════════════════════════════════════════════════════════════════
  167 | 
  168 |   test('API-WF-04: should return 404 for invalid sessionId', async ({ request }) => {
  169 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/events`, {
  170 |       headers: authHeaders(authToken)
  171 |     })
  172 |     expect(response.status()).toBe(404)
  173 |   })
  174 | 
  175 |   test('API-WF-04: should return event list', async ({ request }) => {
  176 |     const sessionId = await createTestSession(request, authToken)
  177 |     if (!sessionId) {
  178 |       test.skip(true, 'Failed to create test session')
  179 |       return
  180 |     }
  181 | 
  182 |     await sleep(2000)
  183 | 
  184 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/events`, {
  185 |       headers: authHeaders(authToken)
  186 |     })
  187 |     expect(response.ok()).toBeTruthy()
  188 | 
  189 |     const data = await response.json()
  190 |     expect(data.code).toBe(200)
  191 |     expect(Array.isArray(data.data)).toBeTruthy()
  192 | 
  193 |     if (data.data?.length > 0) {
  194 |       for (const evt of data.data) {
  195 |         expect(evt.seq).toBeDefined()
  196 |         expect(evt.type).toBeTruthy()
  197 |       }
  198 |       console.log('[API-WF-04] Events:', data.data.length)
  199 |     }
  200 |   })
  201 | 
  202 |   // ═══════════════════════════════════════════════════════════════════════════════
  203 |   // API-WF-07: Abort Workflow
  204 |   // ═══════════════════════════════════════════════════════════════════════════════
  205 | 
  206 |   test('API-WF-07: should return 404 for invalid sessionId', async ({ request }) => {
  207 |     const response = await request.post(`${BACKEND_URL}/api/workflow/sessions/non-existent-session-id/abort`, {
  208 |       headers: authHeaders(authToken),
```