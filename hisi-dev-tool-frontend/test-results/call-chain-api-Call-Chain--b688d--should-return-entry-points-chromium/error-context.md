# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: call-chain-api.spec.ts >> Call Chain API Integration Tests >> API-CHAIN-05: should return entry points
- Location: e2e\call-chain-api.spec.ts:194:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: "number"
Received: "string"
```

# Test source

```ts
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
> 216 |     expect(typeof data.data?.total).toBe('number')
      |                                     ^ Error: expect(received).toBe(expected) // Object.is equality
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
  268 | 
  269 |     const projectPath = projectsData.data[0]
  270 | 
  271 |     const res = await request.get(`${API_BASE}/classes`, {
  272 |       headers: authHeaders(authToken),
  273 |       params: { projectPaths: projectPath, page: 1, pageSize: 20 }
  274 |     })
  275 | 
  276 |     expect(res.ok()).toBeTruthy()
  277 |     const data = await res.json()
  278 |     expect(data.code).toBe(200)
  279 |     expect(Array.isArray(data.data?.items)).toBeTruthy()
  280 |   })
  281 | 
  282 |   test('Helper: should search methods', async ({ request }) => {
  283 |     const projectsRes = await request.get(`${API_BASE}/projects`, {
  284 |       headers: authHeaders(authToken)
  285 |     })
  286 |     const projectsData = await projectsRes.json()
  287 | 
  288 |     if (!projectsData.data?.length) {
  289 |       test.skip(true, 'No projects')
  290 |       return
  291 |     }
  292 | 
  293 |     const projectPath = projectsData.data[0]
  294 | 
  295 |     const res = await request.get(`${API_BASE}/method/search`, {
  296 |       headers: authHeaders(authToken),
  297 |       params: { keyword: 'Service', projectPaths: projectPath, limit: 10 }
  298 |     })
  299 | 
  300 |     expect(res.ok()).toBeTruthy()
  301 |     const data = await res.json()
  302 |     expect(data.code).toBe(200)
  303 |     expect(Array.isArray(data.data)).toBeTruthy()
  304 |   })
  305 | 
  306 |   // ═══════════════════════════════════════════════════════════════════════════════
  307 |   // Error Boundary
  308 |   // ═══════════════════════════════════════════════════════════════════════════════
  309 | 
  310 |   test('Error: should handle invalid project path', async ({ request }) => {
  311 |     const res = await request.get(`${API_BASE}/entry-points`, {
  312 |       headers: authHeaders(authToken),
  313 |       params: { projectPaths: '/nonexistent/path', pageSize: 10 }
  314 |     })
  315 | 
  316 |     expect(res.ok()).toBeTruthy()
```