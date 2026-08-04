# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: workflow-api.spec.ts >> Workflow API Integration Tests >> Rerun: should return 404 for invalid sessionId
- Location: e2e\workflow-api.spec.ts:466:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 404
Received: 200
```

# Test source

```ts
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
> 471 |     expect(response.status()).toBe(404)
      |                               ^ Error: expect(received).toBe(expected) // Object.is equality
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
  487 |     console.log('[Health] UP, startedAt:', data.data.startedAt)
  488 |   })
  489 | 
  490 |   // ═══════════════════════════════════════════════════════════════════════════════
  491 |   // Frontend Proxy
  492 |   // ═══════════════════════════════════════════════════════════════════════════════
  493 | 
  494 |   test('Proxy: frontend should forward API requests', async ({ request }) => {
  495 |     const response = await request.get(`${BASE_URL}/api/workflow/definitions`, {
  496 |       headers: authHeaders(authToken)
  497 |     })
  498 |     expect(response.ok()).toBeTruthy()
  499 | 
  500 |     const data = await response.json()
  501 |     expect(data.code).toBe(200)
  502 |     console.log('[Proxy] Verified')
  503 |   })
  504 | })
```