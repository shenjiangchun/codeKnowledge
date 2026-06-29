# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: call-chain-api.spec.ts >> Call Chain API Integration Tests >> API-CHAIN-02: should return root entries
- Location: e2e\call-chain-api.spec.ts:118:3

# Error details

```
TimeoutError: apiRequestContext.get: Timeout 10000ms exceeded.
Call log:
  - → GET http://localhost:8080/api/v2/knowledge-graph/root-entries?className=com.huawei.hisi.DevToolApplication&methodName=main&projectPaths=C%3A%2FUsers%2F47583%2Fprojects%2Fhisi-dev-tool
    - user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.7827.55 Safari/537.36
    - accept: */*
    - accept-encoding: gzip,deflate,br
    - Authorization: Bearer eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJyb290Iiwicm9sZSI6IkFETUlOIiwiaWF0IjoxNzgyMTgzNDIyLCJleHAiOjE3ODIyNjk4MjJ9.BXsiG8OJQ5diTvVDTxmfMC3hftSMDIGrqx_jOYNgEsBLzGR9731ia0Ud2KV1PnqF

```

# Test source

```ts
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
  88  |     expect(chainData.code).toBe(200)
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
> 167 |     const res = await request.get(`${API_BASE}/root-entries`, {
      |                               ^ TimeoutError: apiRequestContext.get: Timeout 10000ms exceeded.
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
  189 | 
  190 |   // ═══════════════════════════════════════════════════════════════════════════════
  191 |   // API-CHAIN-05: Entry Point Analysis
  192 |   // ═══════════════════════════════════════════════════════════════════════════════
  193 | 
  194 |   test('API-CHAIN-05: should return entry points', async ({ request }) => {
  195 |     const projectsRes = await request.get(`${API_BASE}/projects`, {
  196 |       headers: authHeaders(authToken)
  197 |     })
  198 |     const projectsData = await projectsRes.json()
  199 | 
  200 |     if (!projectsData.data?.length) {
  201 |       test.skip(true, 'No projects')
  202 |       return
  203 |     }
  204 | 
  205 |     const projectPath = projectsData.data[0]
  206 | 
  207 |     const res = await request.get(`${API_BASE}/entry-points`, {
  208 |       headers: authHeaders(authToken),
  209 |       params: { projectPaths: projectPath, page: 1, pageSize: 20 }
  210 |     })
  211 | 
  212 |     expect(res.ok()).toBeTruthy()
  213 |     const data = await res.json()
  214 |     expect(data.code).toBe(200)
  215 |     expect(Array.isArray(data.data?.items)).toBeTruthy()
  216 |     expect(typeof data.data?.total).toBe('number')
  217 |   })
  218 | 
  219 |   test('API-CHAIN-05: should return entry types', async ({ request }) => {
  220 |     const projectsRes = await request.get(`${API_BASE}/projects`, {
  221 |       headers: authHeaders(authToken)
  222 |     })
  223 |     const projectsData = await projectsRes.json()
  224 | 
  225 |     if (!projectsData.data?.length) {
  226 |       test.skip(true, 'No projects')
  227 |       return
  228 |     }
  229 | 
  230 |     const projectPath = projectsData.data[0]
  231 | 
  232 |     const res = await request.get(`${API_BASE}/entry-types`, {
  233 |       headers: authHeaders(authToken),
  234 |       params: { projectPaths: projectPath }
  235 |     })
  236 | 
  237 |     expect(res.ok()).toBeTruthy()
  238 |     const data = await res.json()
  239 |     expect(data.code).toBe(200)
  240 |     expect(Array.isArray(data.data)).toBeTruthy()
  241 |   })
  242 | 
  243 |   // ═══════════════════════════════════════════════════════════════════════════════
  244 |   // Helper Endpoints
  245 |   // ═══════════════════════════════════════════════════════════════════════════════
  246 | 
  247 |   test('Helper: should return project list', async ({ request }) => {
  248 |     const res = await request.get(`${API_BASE}/projects`, {
  249 |       headers: authHeaders(authToken)
  250 |     })
  251 | 
  252 |     expect(res.ok()).toBeTruthy()
  253 |     const data = await res.json()
  254 |     expect(data.code).toBe(200)
  255 |     expect(Array.isArray(data.data)).toBeTruthy()
  256 |   })
  257 | 
  258 |   test('Helper: should return class list', async ({ request }) => {
  259 |     const projectsRes = await request.get(`${API_BASE}/projects`, {
  260 |       headers: authHeaders(authToken)
  261 |     })
  262 |     const projectsData = await projectsRes.json()
  263 | 
  264 |     if (!projectsData.data?.length) {
  265 |       test.skip(true, 'No projects')
  266 |       return
  267 |     }
```