# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: workflow-api.spec.ts >> Workflow API Integration Tests >> API-WF-04: should return 404 for invalid sessionId
- Location: e2e\workflow-api.spec.ts:168:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 404
Received: 200
```

# Test source

```ts
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
  108 |     expect(response.status()).toBe(404)
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
> 172 |     expect(response.status()).toBe(404)
      |                               ^ Error: expect(received).toBe(expected) // Object.is equality
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
  209 |       data: {}
  210 |     })
  211 |     expect(response.status()).toBe(404)
  212 |   })
  213 | 
  214 |   test('API-WF-07: should abort running session', async ({ request }) => {
  215 |     const sessionId = await createTestSession(request, authToken)
  216 |     if (!sessionId) {
  217 |       test.skip(true, 'Failed to create test session')
  218 |       return
  219 |     }
  220 | 
  221 |     await sleep(500)
  222 | 
  223 |     const abortResponse = await request.post(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/abort`, {
  224 |       headers: authHeaders(authToken),
  225 |       data: {}
  226 |     })
  227 |     expect(abortResponse.ok()).toBeTruthy()
  228 | 
  229 |     await sleep(500)
  230 | 
  231 |     const statusResponse = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/status`, {
  232 |       headers: authHeaders(authToken)
  233 |     })
  234 |     expect(statusResponse.ok()).toBeTruthy()
  235 | 
  236 |     const statusData = await statusResponse.json()
  237 |     expect(statusData.data.status).toBe('ABORTED')
  238 |     console.log('[API-WF-07] Aborted successfully')
  239 |   })
  240 | 
  241 |   // ═══════════════════════════════════════════════════════════════════════════════
  242 |   // API-WF-01: Start Workflow
  243 |   // ═══════════════════════════════════════════════════════════════════════════════
  244 | 
  245 |   test('API-WF-01: should start demand analysis workflow', async ({ request }) => {
  246 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions`, {
  247 |       headers: authHeaders(authToken),
  248 |       data: {
  249 |         rawInput: 'E2E test: Implement user authentication feature',
  250 |         projectPath: '/tmp/test-auth-project',
  251 |         userId: 'e2e-test-user'
  252 |       }
  253 |     })
  254 |     expect(response.ok()).toBeTruthy()
  255 | 
  256 |     const data = await response.json()
  257 |     expect(data.code).toBe(200)
  258 |     expect(data.data.sessionId).toBeTruthy()
  259 |     expect(data.data.sessionId).toMatch(/^[a-f0-9-]+$/)
  260 |     console.log('[API-WF-01] Session:', data.data.sessionId)
  261 |   })
  262 | 
  263 |   test('API-WF-01: should return 400 for empty rawInput', async ({ request }) => {
  264 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions`, {
  265 |       headers: authHeaders(authToken),
  266 |       data: { rawInput: '', projectPath: '/tmp/test' }
  267 |     })
  268 |     expect(response.status()).toBe(400)
  269 |   })
  270 | 
  271 |   test('API-WF-01: should start status analysis workflow', async ({ request }) => {
  272 |     const response = await request.post(`${BACKEND_URL}/api/ram/status/start`, {
```