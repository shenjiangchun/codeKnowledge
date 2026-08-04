# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: merge-analysis-api.spec.ts >> Merge Analysis API Tests >> API-MERGE-02: should return empty diff for same branch
- Location: e2e\merge-analysis-api.spec.ts:122:3

# Error details

```
Error: expect(received).toBeTruthy()

Received: false
```

# Test source

```ts
  32  | 
  33  |   const loginData = await loginResponse.json()
  34  |   const token = loginData.data?.token || loginData.token
  35  |   console.log('[Auth] Got JWT token:', token ? 'yes' : 'no')
  36  |   return token
  37  | }
  38  | 
  39  | // Helper: Auth headers
  40  | function authHeaders(token: string | null): Record<string, string> {
  41  |   return token ? { 'Authorization': `Bearer ${token}` } : {}
  42  | }
  43  | 
  44  | test.describe('Merge Analysis API Tests', () => {
  45  |   let authToken: string | null = null
  46  | 
  47  |   test.beforeAll(async ({ request }) => {
  48  |     authToken = await getAuthToken(request)
  49  |   })
  50  | 
  51  |   // ═══════════════════════════════════════════════════════════════════════════════
  52  |   // API-MERGE-01: Create merge analysis session
  53  |   // ═══════════════════════════════════════════════════════════════════════════════
  54  | 
  55  |   test('API-MERGE-01: should create a new session', async ({ request }) => {
  56  |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  57  |       headers: authHeaders(authToken),
  58  |       data: {
  59  |         projectPath: TEST_PROJECT_PATH,
  60  |         sourceBranch: TEST_SOURCE_BRANCH,
  61  |         targetBranch: TEST_TARGET_BRANCH
  62  |       }
  63  |     })
  64  | 
  65  |     expect(response.ok()).toBeTruthy()
  66  |     const data = await response.json()
  67  |     expect(data.code).toBe(200)
  68  |     expect(data.data).toBeDefined()
  69  |     expect(data.data.sessionHandle).toBeDefined()
  70  |     expect(typeof data.data.sessionHandle).toBe('string')
  71  |   })
  72  | 
  73  |   test('API-MERGE-01: should return error for missing required fields', async ({ request }) => {
  74  |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  75  |       headers: authHeaders(authToken),
  76  |       data: {}
  77  |     })
  78  | 
  79  |     expect([400, 500, 422]).toContain(response.status())
  80  |   })
  81  | 
  82  |   test('API-MERGE-01: should return error for empty projectPath', async ({ request }) => {
  83  |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/sessions`, {
  84  |       headers: authHeaders(authToken),
  85  |       data: {
  86  |         projectPath: '',
  87  |         sourceBranch: TEST_SOURCE_BRANCH,
  88  |         targetBranch: TEST_TARGET_BRANCH
  89  |       }
  90  |     })
  91  | 
  92  |     expect(response.ok()).toBeFalsy()
  93  |   })
  94  | 
  95  |   // ═══════════════════════════════════════════════════════════════════════════════
  96  |   // API-MERGE-02: Get merge diff
  97  |   // ═══════════════════════════════════════════════════════════════════════════════
  98  | 
  99  |   test('API-MERGE-02: should return diff result', async ({ request }) => {
  100 |     const response = await request.post(`${BACKEND_URL}/api/merge-analysis/diff`, {
  101 |       headers: authHeaders(authToken),
  102 |       data: {
  103 |         projectPath: TEST_PROJECT_PATH,
  104 |         sourceBranch: TEST_SOURCE_BRANCH,
  105 |         targetBranch: TEST_TARGET_BRANCH
  106 |       }
  107 |     })
  108 | 
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
> 132 |     expect(response.ok()).toBeTruthy()
      |                           ^ Error: expect(received).toBeTruthy()
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
  209 |     expect(typeof data.data.lastSeq).toBe('number')
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
```