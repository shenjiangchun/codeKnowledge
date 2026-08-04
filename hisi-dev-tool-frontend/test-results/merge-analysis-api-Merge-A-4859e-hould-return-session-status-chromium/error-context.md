# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: merge-analysis-api.spec.ts >> Merge Analysis API Tests >> API-MERGE-03: should return session status
- Location: e2e\merge-analysis-api.spec.ts:178:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: "number"
Received: "string"
```

# Test source

```ts
  109 |     expect(response.ok()).toBeTruthy()
  110 |     const data = await response.json()
  111 |     expect(data.code).toBe(200)
  112 |     expect(data.data).toBeDefined()
  113 | 
  114 |     expect(data.data.sourceBranch).toBeDefined()
  115 |     expect(data.data.targetBranch).toBeDefined()
  116 |     expect(typeof data.data.totalFiles).toBe('number')
  117 |     expect(typeof data.data.totalAdditions).toBe('number')
  118 |     expect(typeof data.data.totalDeletions).toBe('number')
  119 |     expect(Array.isArray(data.data.files)).toBeTruthy()
  120 |   })
  121 | 
  122 |   test('API-MERGE-02: should return empty diff for same branch', async ({ request }) => {
  123 |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
  124 |       headers: authHeaders(authToken),
  125 |       data: {
  126 |         projectPath: TEST_PROJECT_PATH,
  127 |         sourceBranch: TEST_SOURCE_BRANCH,
  128 |         targetBranch: TEST_SOURCE_BRANCH
  129 |       }
  130 |     })
  131 | 
  132 |     expect(response.ok()).toBeTruthy()
  133 |     const data = await response.json()
  134 |     expect(data.code).toBe(200)
  135 |     expect(data.data.totalFiles).toBe(0)
  136 |     expect(data.data.files).toHaveLength(0)
  137 |   })
  138 | 
  139 |   test('API-MERGE-02: should return error for invalid project path', async ({ request }) => {
  140 |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
  141 |       headers: authHeaders(authToken),
  142 |       data: {
  143 |         projectPath: '/non/existent/path/xyz123',
  144 |         sourceBranch: TEST_SOURCE_BRANCH,
  145 |         targetBranch: TEST_TARGET_BRANCH
  146 |       }
  147 |     })
  148 | 
  149 |     expect(response.ok()).toBeFalsy()
  150 |   })
  151 | 
  152 |   test('API-MERGE-02: file diff should have correct structure', async ({ request }) => {
  153 |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
  154 |       headers: authHeaders(authToken),
  155 |       data: {
  156 |         projectPath: TEST_PROJECT_PATH,
  157 |         sourceBranch: TEST_SOURCE_BRANCH,
  158 |         targetBranch: TEST_TARGET_BRANCH
  159 |       }
  160 |     })
  161 | 
  162 |     expect(response.ok()).toBeTruthy()
  163 |     const data = await response.json()
  164 | 
  165 |     if (data.data.files && data.data.files.length > 0) {
  166 |       const fileDiff = data.data.files[0]
  167 |       expect(fileDiff.filePath).toBeDefined()
  168 |       expect(['ADD', 'MODIFY', 'DELETE', 'RENAME']).toContain(fileDiff.changeType)
  169 |       expect(typeof fileDiff.additions).toBe('number')
  170 |       expect(typeof fileDiff.deletions).toBe('number')
  171 |     }
  172 |   })
  173 | 
  174 |   // ═══════════════════════════════════════════════════════════════════════════════
  175 |   // API-MERGE-03: Get session status
  176 |   // ═══════════════════════════════════════════════════════════════════════════════
  177 | 
  178 |   test('API-MERGE-03: should return session status', async ({ request }) => {
  179 |     // Create session first
  180 |     const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  181 |       headers: authHeaders(authToken),
  182 |       data: {
  183 |         projectPath: TEST_PROJECT_PATH,
  184 |         sourceBranch: TEST_SOURCE_BRANCH,
  185 |         targetBranch: TEST_TARGET_BRANCH
  186 |       }
  187 |     })
  188 | 
  189 |     if (!createResponse.ok()) {
  190 |       test.skip(true, 'Failed to create session')
  191 |       return
  192 |     }
  193 | 
  194 |     const createData = await createResponse.json()
  195 |     const sessionId = createData.data?.sessionHandle
  196 | 
  197 |     await sleep(1000)
  198 | 
  199 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}`, {
  200 |       headers: authHeaders(authToken)
  201 |     })
  202 | 
  203 |     expect(response.ok()).toBeTruthy()
  204 |     const data = await response.json()
  205 |     expect(data.code).toBe(200)
  206 |     expect(data.data).toBeDefined()
  207 | 
  208 |     expect(['idle', 'running', 'DONE', 'FAILED', 'completed', 'error']).toContain(data.data.status)
> 209 |     expect(typeof data.data.lastSeq).toBe('number')
      |                                      ^ Error: expect(received).toBe(expected) // Object.is equality
  210 |   })
  211 | 
  212 |   test('API-MERGE-03: should return 404 for invalid sessionId', async ({ request }) => {
  213 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/invalid-session-id-xyz`, {
  214 |       headers: authHeaders(authToken)
  215 |     })
  216 | 
  217 |     expect(response.status()).toBe(404)
  218 |   })
  219 | 
  220 |   // ═══════════════════════════════════════════════════════════════════════════════
  221 |   // API-MERGE-04: List sessions
  222 |   // ═══════════════════════════════════════════════════════════════════════════════
  223 | 
  224 |   test('API-MERGE-04: should return list of sessions', async ({ request }) => {
  225 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  226 |       headers: authHeaders(authToken)
  227 |     })
  228 | 
  229 |     expect(response.ok()).toBeTruthy()
  230 |     const data = await response.json()
  231 |     expect(data.code).toBe(200)
  232 |     expect(Array.isArray(data.data)).toBeTruthy()
  233 |   })
  234 | 
  235 |   test('API-MERGE-04: should respect limit parameter', async ({ request }) => {
  236 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions?limit=5`, {
  237 |       headers: authHeaders(authToken)
  238 |     })
  239 | 
  240 |     expect(response.ok()).toBeTruthy()
  241 |     const data = await response.json()
  242 |     expect(data.code).toBe(200)
  243 |     expect(Array.isArray(data.data)).toBeTruthy()
  244 |     expect(data.data.length).toBeLessThanOrEqual(5)
  245 |   })
  246 | 
  247 |   // ═══════════════════════════════════════════════════════════════════════════════
  248 |   // API-MERGE-05: Get session events
  249 |   // ═══════════════════════════════════════════════════════════════════════════════
  250 | 
  251 |   test('API-MERGE-05: should return session events', async ({ request }) => {
  252 |     // Create session first
  253 |     const createResponse = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  254 |       headers: authHeaders(authToken),
  255 |       data: {
  256 |         projectPath: TEST_PROJECT_PATH,
  257 |         sourceBranch: TEST_SOURCE_BRANCH,
  258 |         targetBranch: TEST_TARGET_BRANCH
  259 |       }
  260 |     })
  261 | 
  262 |     if (!createResponse.ok()) {
  263 |       test.skip(true, 'Failed to create session')
  264 |       return
  265 |     }
  266 | 
  267 |     const createData = await createResponse.json()
  268 |     const sessionId = createData.data?.sessionHandle
  269 | 
  270 |     await sleep(2000)
  271 | 
  272 |     const response = await request.get(`${BACKEND_URL}/api/merge-analysis/sessions/${sessionId}/events`, {
  273 |       headers: authHeaders(authToken)
  274 |     })
  275 | 
  276 |     expect(response.ok()).toBeTruthy()
  277 |     const data = await response.json()
  278 |     expect(data.code).toBe(200)
  279 |     expect(Array.isArray(data.data)).toBeTruthy()
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
```