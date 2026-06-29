# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: merge-analysis-api.spec.ts >> Merge Analysis API Tests >> Error: should return 400 for empty request body
- Location: e2e\merge-analysis-api.spec.ts:374:3

# Error details

```
Error: expect(received).toContain(expected) // indexOf

Expected value: 200
Received array: [400, 500, 422]
```

# Test source

```ts
  280 |   })
  281 | 
  282 |   test('API-MERGE-05: should return 404 for invalid sessionId', async ({ request }) => {
  283 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/invalid-session-id/events`, {
  284 |       headers: authHeaders(authToken)
  285 |     })
  286 | 
  287 |     expect(response.status()).toBe(404)
  288 |   })
  289 | 
  290 |   // ═══════════════════════════════════════════════════════════════════════════════
  291 |   // API-MERGE-06: List branches
  292 |   // ═══════════════════════════════════════════════════════════════════════════════
  293 | 
  294 |   test('API-MERGE-06: should return list of branches', async ({ request }) => {
  295 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/branches?projectPath=${encodeURIComponent(TEST_PROJECT_PATH)}`, {
  296 |       headers: authHeaders(authToken)
  297 |     })
  298 | 
  299 |     expect(response.ok()).toBeTruthy()
  300 |     const data = await response.json()
  301 |     expect(data.code).toBe(200)
  302 |     expect(Array.isArray(data.data)).toBeTruthy()
  303 |   })
  304 | 
  305 |   test('API-MERGE-06: should contain common branches', async ({ request }) => {
  306 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/branches?projectPath=${encodeURIComponent(TEST_PROJECT_PATH)}`, {
  307 |       headers: authHeaders(authToken)
  308 |     })
  309 | 
  310 |     expect(response.ok()).toBeTruthy()
  311 |     const data = await response.json()
  312 | 
  313 |     const branches = data.data as string[]
  314 |     expect(branches.some(b => b === 'main' || b === 'master')).toBeTruthy()
  315 |   })
  316 | 
  317 |   test('API-MERGE-06: should return error for missing projectPath', async ({ request }) => {
  318 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/branches`, {
  319 |       headers: authHeaders(authToken)
  320 |     })
  321 | 
  322 |     expect(response.ok()).toBeFalsy()
  323 |   })
  324 | 
  325 |   // ═══════════════════════════════════════════════════════════════════════════════
  326 |   // API-MERGE-07: Rerun from node
  327 |   // ═══════════════════════════════════════════════════════════════════════════════
  328 | 
  329 |   test('API-MERGE-07: should rerun from valid node', async ({ request }) => {
  330 |     // Create session first
  331 |     const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  332 |       headers: authHeaders(authToken),
  333 |       data: {
  334 |         projectPath: TEST_PROJECT_PATH,
  335 |         sourceBranch: TEST_SOURCE_BRANCH,
  336 |         targetBranch: TEST_TARGET_BRANCH
  337 |       }
  338 |     })
  339 | 
  340 |     if (!createResponse.ok()) {
  341 |       test.skip(true, 'Failed to create session')
  342 |       return
  343 |     }
  344 | 
  345 |     const createData = await createResponse.json()
  346 |     const sessionId = createData.data?.sessionHandle
  347 | 
  348 |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}/rerun-from/diff_extract`, {
  349 |       headers: authHeaders(authToken),
  350 |       data: {}
  351 |     })
  352 | 
  353 |     if (response.ok()) {
  354 |       const data = await response.json()
  355 |       expect(data.code).toBe(200)
  356 |       expect(data.data.rerunFromNode).toBe('diff_extract')
  357 |       expect(data.data.dispatched).toBe(true)
  358 |     }
  359 |   })
  360 | 
  361 |   test('API-MERGE-07: should return 404 for invalid sessionId', async ({ request }) => {
  362 |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions/invalid-session-id/rerun-from/diff_extract`, {
  363 |       headers: authHeaders(authToken),
  364 |       data: {}
  365 |     })
  366 | 
  367 |     expect(response.status()).toBe(404)
  368 |   })
  369 | 
  370 |   // ═══════════════════════════════════════════════════════════════════════════════
  371 |   // Error boundary tests
  372 |   // ═══════════════════════════════════════════════════════════════════════════════
  373 | 
  374 |   test('Error: should return 400 for empty request body', async ({ request }) => {
  375 |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  376 |       headers: authHeaders(authToken),
  377 |       data: {}
  378 |     })
  379 | 
> 380 |     expect([400, 500, 422]).toContain(response.status())
      |                             ^ Error: expect(received).toContain(expected) // indexOf
  381 |   })
  382 | 
  383 |   test('Error: should return 404 for non-existent session', async ({ request }) => {
  384 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/00000000-0000-0000-0000-000000000000`, {
  385 |       headers: authHeaders(authToken)
  386 |     })
  387 | 
  388 |     expect(response.status()).toBe(404)
  389 |   })
  390 | 
  391 |   // ═══════════════════════════════════════════════════════════════════════════════
  392 |   // SSE Stream endpoint
  393 |   // ═══════════════════════════════════════════════════════════════════════════════
  394 | 
  395 |   test('SSE: should accept SSE connection', async ({ request }) => {
  396 |     // Create session first
  397 |     const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  398 |       headers: authHeaders(authToken),
  399 |       data: {
  400 |         projectPath: TEST_PROJECT_PATH,
  401 |         sourceBranch: TEST_SOURCE_BRANCH,
  402 |         targetBranch: TEST_TARGET_BRANCH
  403 |       }
  404 |     })
  405 | 
  406 |     if (!createResponse.ok()) {
  407 |       test.skip(true, 'Failed to create session')
  408 |       return
  409 |     }
  410 | 
  411 |     const createData = await createResponse.json()
  412 |     const sessionId = createData.data?.sessionHandle
  413 | 
  414 |     const streamResponse = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}/stream`, {
  415 |       headers: { ...authHeaders(authToken) },
  416 |       timeout: 5000
  417 |     })
  418 | 
  419 |     expect([200, 404, 503]).toContain(streamResponse.status())
  420 |   })
  421 | })
```