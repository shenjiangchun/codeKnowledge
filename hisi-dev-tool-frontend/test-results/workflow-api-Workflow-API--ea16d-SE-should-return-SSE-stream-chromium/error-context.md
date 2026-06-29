# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: workflow-api.spec.ts >> Workflow API Integration Tests >> SSE: should return SSE stream
- Location: e2e\workflow-api.spec.ts:379:3

# Error details

```
TimeoutError: apiRequestContext.get: Timeout 10000ms exceeded.
Call log:
  - → GET http://localhost:8080/api/workflow/sessions/e071e754-562f-4fad-a79b-e4849dd8dcca/stream
    - user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.7827.55 Safari/537.36
    - accept: text/event-stream
    - accept-encoding: gzip,deflate,br
    - Authorization: Bearer eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJyb290Iiwicm9sZSI6IkFETUlOIiwiaWF0IjoxNzgyMTgzOTQ2LCJleHAiOjE3ODIyNzAzNDZ9.cs7eH6MbC3wqeYMisJway-d20b8af62LLv31scXaoR-L4Qo60TrLuGcFF3LdWSiM
  - ← 200
    - vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers
    - cache-control: no-cache, no-transform
    - x-accel-buffering: no
    - content-type: text/event-stream
    - transfer-encoding: chunked
    - date: Tue, 23 Jun 2026 03:05:48 GMT
    - keep-alive: timeout=60
    - connection: keep-alive

```

# Test source

```ts
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
  332 |     expect(response.status()).toBe(404)
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
> 386 |     const response = await request.get(`${BACKEND_URL}/api/workflow/sessions/${sessionId}/stream`, {
      |                                    ^ TimeoutError: apiRequestContext.get: Timeout 10000ms exceeded.
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
  433 |       expect(data.data[0].sessionId).toBeTruthy()
  434 |       expect(data.data[0].status).toBeTruthy()
  435 |       console.log('[Sessions] Count:', data.data.length)
  436 |     }
  437 |   })
  438 | 
  439 |   // ═══════════════════════════════════════════════════════════════════════════════
  440 |   // Rerun from Node
  441 |   // ═══════════════════════════════════════════════════════════════════════════════
  442 | 
  443 |   test('Rerun: should rerun from specific node', async ({ request }) => {
  444 |     const sessionId = await createTestSession(request, authToken)
  445 |     if (!sessionId) {
  446 |       test.skip(true, 'Failed to create test session')
  447 |       return
  448 |     }
  449 | 
  450 |     await sleep(1000)
  451 | 
  452 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions/${sessionId}/rerun-from/impact`, {
  453 |       headers: authHeaders(authToken),
  454 |       data: {}
  455 |     })
  456 | 
  457 |     expect([200, 404].includes(response.status())).toBeTruthy()
  458 | 
  459 |     if (response.ok()) {
  460 |       const data = await response.json()
  461 |       expect(data.code).toBe(200)
  462 |       console.log('[Rerun] Dispatched')
  463 |     }
  464 |   })
  465 | 
  466 |   test('Rerun: should return 404 for invalid sessionId', async ({ request }) => {
  467 |     const response = await request.post(`${BACKEND_URL}/api/ram/sessions/invalid-id/rerun-from/impact`, {
  468 |       headers: authHeaders(authToken),
  469 |       data: {}
  470 |     })
  471 |     expect(response.status()).toBe(404)
  472 |   })
  473 | 
  474 |   // ═══════════════════════════════════════════════════════════════════════════════
  475 |   // Health Check
  476 |   // ═══════════════════════════════════════════════════════════════════════════════
  477 | 
  478 |   test('Health: backend should be UP', async ({ request }) => {
  479 |     const response = await request.get(`${BACKEND_URL}/api/ram/health`, {
  480 |       headers: authHeaders(authToken)
  481 |     })
  482 |     expect(response.ok()).toBeTruthy()
  483 | 
  484 |     const data = await response.json()
  485 |     expect(data.code).toBe(200)
  486 |     expect(data.data.status).toBe('UP')
```