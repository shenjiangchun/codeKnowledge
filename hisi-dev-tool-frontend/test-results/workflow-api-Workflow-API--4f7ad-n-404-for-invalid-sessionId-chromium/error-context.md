# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: workflow-api.spec.ts >> Workflow API Integration Tests >> API-WF-07: should return 404 for invalid sessionId
- Location: e2e\workflow-api.spec.ts:206:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 404
Received: 200
```

# Test source

```ts
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
  209 |       data: {}
  210 |     })
> 211 |     expect(response.status()).toBe(404)
      |                               ^ Error: expect(received).toBe(expected) // Object.is equality
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
  273 |       headers: authHeaders(authToken),
  274 |       data: {
  275 |         projectPath: '/tmp/status-test-project',
  276 |         mode: 'quick',
  277 |         question: 'What is the current architecture?'
  278 |       }
  279 |     })
  280 |     expect(response.ok()).toBeTruthy()
  281 | 
  282 |     const data = await response.json()
  283 |     expect(data.code).toBe(200)
  284 |     expect(data.data.sessionId).toBeTruthy()
  285 |     console.log('[API-WF-01] Status analysis:', data.data.sessionId)
  286 |   })
  287 | 
  288 |   // ═══════════════════════════════════════════════════════════════════════════════
  289 |   // API-WF-05: Submit Clarification
  290 |   // ═══════════════════════════════════════════════════════════════════════════════
  291 | 
  292 |   test('API-WF-05: should return 404 for invalid sessionId', async ({ request }) => {
  293 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions/non-existent-session-id/clarify`, {
  294 |       headers: authHeaders(authToken),
  295 |       data: { answers: { question1: 'Answer 1' } }
  296 |     })
  297 |     expect(response.status()).toBe(404)
  298 |   })
  299 | 
  300 |   test('API-WF-05: should accept clarification answers', async ({ request }) => {
  301 |     const sessionId = await createTestSession(request, authToken)
  302 |     if (!sessionId) {
  303 |       test.skip(true, 'Failed to create test session')
  304 |       return
  305 |     }
  306 | 
  307 |     await sleep(500)
  308 | 
  309 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/clarify`, {
  310 |       headers: authHeaders(authToken),
  311 |       data: { answers: { targetUsers: 'Developers', priority: 'High' } }
```