# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: workflow-api.spec.ts >> Workflow API Integration Tests >> API-WF-06: should return 404 for invalid sessionId
- Location: e2e\workflow-api.spec.ts:327:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 404
Received: 200
```

# Test source

```ts
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
  312 |     })
  313 | 
  314 |     expect([200, 400, 404].includes(response.status())).toBeTruthy()
  315 | 
  316 |     if (response.ok()) {
  317 |       const data = await response.json()
  318 |       expect(data.code).toBe(200)
  319 |       console.log('[API-WF-05] Clarification submitted')
  320 |     }
  321 |   })
  322 | 
  323 |   // ═══════════════════════════════════════════════════════════════════════════════
  324 |   // API-WF-06: HITL Confirmation
  325 |   // ═══════════════════════════════════════════════════════════════════════════════
  326 | 
  327 |   test('API-WF-06: should return 404 for invalid sessionId', async ({ request }) => {
  328 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions/non-existent-session-id/confirm`, {
  329 |       headers: authHeaders(authToken),
  330 |       data: { action: 'approve', nodeName: 'impact' }
  331 |     })
> 332 |     expect(response.status()).toBe(404)
      |                               ^ Error: expect(received).toBe(expected) // Object.is equality
  333 |   })
  334 | 
  335 |   test('API-WF-06: should accept approve action', async ({ request }) => {
  336 |     const sessionId = await createTestSession(request, authToken)
  337 |     if (!sessionId) {
  338 |       test.skip(true, 'Failed to create test session')
  339 |       return
  340 |     }
  341 | 
  342 |     await sleep(500)
  343 | 
  344 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/confirm`, {
  345 |       headers: authHeaders(authToken),
  346 |       data: { nodeName: 'impact', action: 'approve', feedback: 'Looks good' }
  347 |     })
  348 | 
  349 |     expect([200, 400, 404].includes(response.status())).toBeTruthy()
  350 | 
  351 |     if (response.ok()) {
  352 |       const data = await response.json()
  353 |       expect(data.code).toBe(200)
  354 |       console.log('[API-WF-06] Approved')
  355 |     }
  356 |   })
  357 | 
  358 |   test('API-WF-06: should accept reject action', async ({ request }) => {
  359 |     const sessionId = await createTestSession(request, authToken)
  360 |     if (!sessionId) {
  361 |       test.skip(true, 'Failed to create test session')
  362 |       return
  363 |     }
  364 | 
  365 |     await sleep(500)
  366 | 
  367 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/confirm`, {
  368 |       headers: authHeaders(authToken),
  369 |       data: { nodeName: 'implement', action: 'reject', feedback: 'Need more details' }
  370 |     })
  371 | 
  372 |     expect([200, 400, 404].includes(response.status())).toBeTruthy()
  373 |   })
  374 | 
  375 |   // ═══════════════════════════════════════════════════════════════════════════════
  376 |   // SSE Stream Endpoint
  377 |   // ═══════════════════════════════════════════════════════════════════════════════
  378 | 
  379 |   test('SSE: should return SSE stream', async ({ request }) => {
  380 |     const sessionId = await createTestSession(request, authToken)
  381 |     if (!sessionId) {
  382 |       test.skip(true, 'Failed to create test session')
  383 |       return
  384 |     }
  385 | 
  386 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/stream`, {
  387 |       headers: { ...authHeaders(authToken), 'Accept': 'text/event-stream' }
  388 |     })
  389 | 
  390 |     expect([200, 500].includes(response.status())).toBeTruthy()
  391 | 
  392 |     if (response.ok()) {
  393 |       const contentType = response.headers()['content-type']
  394 |       expect(contentType).toContain('text/event-stream')
  395 |     }
  396 |   })
  397 | 
  398 |   // ═══════════════════════════════════════════════════════════════════════════════
  399 |   // Node Registry
  400 |   // ═══════════════════════════════════════════════════════════════════════════════
  401 | 
  402 |   test('Nodes: should list available workflow nodes', async ({ request }) => {
  403 |     const response = await request.get(`${BACKEND_URL}/api/workflow/nodes`, {
  404 |       headers: authHeaders(authToken)
  405 |     })
  406 |     expect(response.ok()).toBeTruthy()
  407 | 
  408 |     const data = await response.json()
  409 |     expect(data.code).toBe(200)
  410 |     expect(typeof data.data).toBe('object')
  411 | 
  412 |     const nodeNames = Object.keys(data.data)
  413 |     if (nodeNames.length > 0) {
  414 |       console.log('[Nodes] Available:', nodeNames.slice(0, 10))
  415 |     }
  416 |   })
  417 | 
  418 |   // ═══════════════════════════════════════════════════════════════════════════════
  419 |   // Session List
  420 |   // ═══════════════════════════════════════════════════════════════════════════════
  421 | 
  422 |   test('Sessions: should list recent sessions', async ({ request }) => {
  423 |     const response = await request.get(`${BACKEND_URL}/api/ram/sessions?limit=10`, {
  424 |       headers: authHeaders(authToken)
  425 |     })
  426 |     expect(response.ok()).toBeTruthy()
  427 | 
  428 |     const data = await response.json()
  429 |     expect(data.code).toBe(200)
  430 |     expect(Array.isArray(data.data)).toBeTruthy()
  431 | 
  432 |     if (data.data?.length > 0) {
```