# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: call-chain-api.spec.ts >> Call Chain API Integration Tests >> API-CHAIN-01: should return call chain graph
- Location: e2e\call-chain-api.spec.ts:49:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 200
Received: 404
```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test'
  2   | 
  3   | /**
  4   |  * Call Chain API E2E Tests (with JWT Authentication)
  5   |  *
  6   |  * Authentication: Uses JWT token (root/123456) with Authorization: Bearer header
  7   |  */
  8   | 
  9   | const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'
  10  | const API_BASE = `${BACKEND_URL}/api/v2/knowledge-graph`
  11  | 
  12  | const TEST_PROJECT_PATH = process.env.TEST_PROJECT_PATH || 'C:/Users/47583/projects/hisi_dev_tool v5.0/hisi-dev-tool'
  13  | 
  14  | test.setTimeout(60000)
  15  | 
  16  | // Helper: Authenticate and get JWT token (root/123456)
  17  | async function getAuthToken(request: import('@playwright/test').APIRequestContext): Promise<string | null> {
  18  |   const loginResponse = await request.post(`${BACKEND_URL}/api/auth/login`, {
  19  |     data: { username: 'root', password: '123456' }
  20  |   })
  21  | 
  22  |   if (!loginResponse.ok()) {
  23  |     console.warn('[Auth] Login failed')
  24  |     return null
  25  |   }
  26  | 
  27  |   const loginData = await loginResponse.json()
  28  |   const token = loginData.data?.token || loginData.token
  29  |   console.log('[Auth] Got JWT token:', token ? 'yes' : 'no')
  30  |   return token
  31  | }
  32  | 
  33  | // Helper: Auth headers
  34  | function authHeaders(token: string | null): Record<string, string> {
  35  |   return token ? { 'Authorization': `Bearer ${token}` } : {}
  36  | }
  37  | 
  38  | test.describe('Call Chain API Integration Tests', () => {
  39  |   let authToken: string | null = null
  40  | 
  41  |   test.beforeAll(async ({ request }) => {
  42  |     authToken = await getAuthToken(request)
  43  |   })
  44  | 
  45  |   // ═══════════════════════════════════════════════════════════════════════════════
  46  |   // API-CHAIN-01: URI Call Chain
  47  |   // ═══════════════════════════════════════════════════════════════════════════════
  48  | 
  49  |   test('API-CHAIN-01: should return call chain graph', async ({ request }) => {
  50  |     const projectsRes = await request.get(`${API_BASE}/projects`, {
  51  |       headers: authHeaders(authToken)
  52  |     })
  53  |     expect(projectsRes.ok()).toBeTruthy()
  54  | 
  55  |     const projectsData = await projectsRes.json()
  56  |     if (!projectsData.data?.length) {
  57  |       test.skip(true, 'No projects available')
  58  |       return
  59  |     }
  60  | 
  61  |     const projectPath = projectsData.data[0]
  62  | 
  63  |     const entryRes = await request.get(`${API_BASE}/entry-points`, {
  64  |       headers: authHeaders(authToken),
  65  |       params: { projectPaths: projectPath, pageSize: 5 }
  66  |     })
  67  | 
  68  |     if (!entryRes.ok()) {
  69  |       test.skip(true, 'Failed to get entry points')
  70  |       return
  71  |     }
  72  | 
  73  |     const entryData = await entryRes.json()
  74  |     if (!entryData.data?.items?.length) {
  75  |       test.skip(true, 'No entry points available')
  76  |       return
  77  |     }
  78  | 
  79  |     const entryKey = entryData.data.items[0].entryKey
  80  | 
  81  |     const chainRes = await request.get(`${API_BASE}/call-chain/graph`, {
  82  |       headers: authHeaders(authToken),
  83  |       params: { entryKey, projectPaths: projectPath, includeCycles: true, maxDepth: 10 }
  84  |     })
  85  | 
  86  |     expect(chainRes.ok()).toBeTruthy()
  87  |     const chainData = await chainRes.json()
> 88  |     expect(chainData.code).toBe(200)
      |                            ^ Error: expect(received).toBe(expected) // Object.is equality
  89  |     expect(chainData.data).toBeDefined()
  90  |     expect(Array.isArray(chainData.data.nodes)).toBeTruthy()
  91  |     expect(Array.isArray(chainData.data.edges)).toBeTruthy()
  92  |   })
  93  | 
  94  |   test('API-CHAIN-01: should handle empty URI', async ({ request }) => {
  95  |     const res = await request.get(`${API_BASE}/call-chain/graph`, {
  96  |       headers: authHeaders(authToken),
  97  |       params: { entryKey: '', projectPaths: TEST_PROJECT_PATH }
  98  |     })
  99  | 
  100 |     expect([200, 400, 404]).toContain(res.status())
  101 |   })
  102 | 
  103 |   test('API-CHAIN-01: should return empty for non-existent URI', async ({ request }) => {
  104 |     const res = await request.get(`${API_BASE}/call-chain/graph`, {
  105 |       headers: authHeaders(authToken),
  106 |       params: { entryKey: 'NON_EXISTENT_URI_/api/fake/endpoint', projectPaths: TEST_PROJECT_PATH }
  107 |     })
  108 | 
  109 |     expect(res.ok()).toBeTruthy()
  110 |     const data = await res.json()
  111 |     expect(data.data?.nodes?.length ?? 0).toBeLessThanOrEqual(1)
  112 |   })
  113 | 
  114 |   // ═══════════════════════════════════════════════════════════════════════════════
  115 |   // API-CHAIN-02: Method Reference Query
  116 |   // ═══════════════════════════════════════════════════════════════════════════════
  117 | 
  118 |   test('API-CHAIN-02: should return root entries', async ({ request }) => {
  119 |     const projectsRes = await request.get(`${API_BASE}/projects`, {
  120 |       headers: authHeaders(authToken)
  121 |     })
  122 |     const projectsData = await projectsRes.json()
  123 | 
  124 |     if (!projectsData.data?.length) {
  125 |       test.skip(true, 'No projects')
  126 |       return
  127 |     }
  128 | 
  129 |     const projectPath = projectsData.data[0]
  130 | 
  131 |     const classesRes = await request.get(`${API_BASE}/classes`, {
  132 |       headers: authHeaders(authToken),
  133 |       params: { projectPaths: projectPath, pageSize: 10 }
  134 |     })
  135 | 
  136 |     if (!classesRes.ok()) {
  137 |       test.skip(true, 'No classes')
  138 |       return
  139 |     }
  140 | 
  141 |     const classesData = await classesRes.json()
  142 |     const className = classesData.data?.items?.[0]
  143 | 
  144 |     if (!className) {
  145 |       test.skip(true, 'No class')
  146 |       return
  147 |     }
  148 | 
  149 |     const methodsRes = await request.get(`${API_BASE}/method/by-class`, {
  150 |       headers: authHeaders(authToken),
  151 |       params: { className, projectPaths: projectPath }
  152 |     })
  153 | 
  154 |     if (!methodsRes.ok()) {
  155 |       test.skip(true, 'No methods')
  156 |       return
  157 |     }
  158 | 
  159 |     const methodsData = await methodsRes.json()
  160 |     const methodName = methodsData.data?.[0]?.methodName
  161 | 
  162 |     if (!methodName) {
  163 |       test.skip(true, 'No method')
  164 |       return
  165 |     }
  166 | 
  167 |     const res = await request.get(`${API_BASE}/root-entries`, {
  168 |       headers: authHeaders(authToken),
  169 |       params: { className, methodName, projectPaths: projectPath }
  170 |     })
  171 | 
  172 |     expect(res.ok()).toBeTruthy()
  173 |     const data = await res.json()
  174 |     expect(data.code).toBe(200)
  175 |     expect(Array.isArray(data.data?.rootEntries)).toBeTruthy()
  176 |     expect(Array.isArray(data.data?.directCallers)).toBeTruthy()
  177 |   })
  178 | 
  179 |   test('API-CHAIN-02: should handle invalid method', async ({ request }) => {
  180 |     const res = await request.get(`${API_BASE}/root-entries`, {
  181 |       headers: authHeaders(authToken),
  182 |       params: { className: 'com.nonexistent.FakeClass', methodName: 'nonexistentMethod', projectPaths: TEST_PROJECT_PATH }
  183 |     })
  184 | 
  185 |     expect(res.ok()).toBeTruthy()
  186 |     const data = await res.json()
  187 |     expect(data.data?.rootEntries?.length ?? 0).toBe(0)
  188 |   })
```