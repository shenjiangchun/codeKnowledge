# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: project-api.spec.ts >> 项目管理 API 测试 >> API-PROJ-03: 克隆远端项目 - 项目不存在
- Location: e2e\project-api.spec.ts:95:3

# Error details

```
Error: expect(received).toContain(expected) // indexOf

Expected value: 200
Received array: [400, 404, 500]
```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test'
  2   | 
  3   | /**
  4   |  * 项目管理 API 集成测试 (with JWT Authentication)
  5   |  *
  6   |  * 测试范围:
  7   |  * - Git仓库扫描
  8   |  * - 远端项目管理
  9   |  * - 项目分组管理
  10  |  *
  11  |  * Authentication: Uses JWT token (root/123456) with Authorization: Bearer header
  12  |  */
  13  | 
  14  | const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'
  15  | const API_BASE = `${BACKEND_URL}/api`
  16  | 
  17  | test.setTimeout(60000)
  18  | 
  19  | // Helper: Authenticate and get JWT token (root/123456)
  20  | async function getAuthToken(request: import('@playwright/test').APIRequestContext): Promise<string | null> {
  21  |   const loginResponse = await request.post(`${BACKEND_URL}/api/auth/login`, {
  22  |     data: { username: 'root', password: '123456' }
  23  |   })
  24  | 
  25  |   if (!loginResponse.ok()) {
  26  |     console.warn('[Auth] Login failed')
  27  |     return null
  28  |   }
  29  | 
  30  |   const loginData = await loginResponse.json()
  31  |   const token = loginData.data?.token || loginData.token
  32  |   console.log('[Auth] Got JWT token:', token ? 'yes' : 'no')
  33  |   return token
  34  | }
  35  | 
  36  | // Helper: Auth headers
  37  | function authHeaders(token: string | null): Record<string, string> {
  38  |   return token ? { 'Authorization': `Bearer ${token}` } : {}
  39  | }
  40  | 
  41  | const TEST_GROUP = {
  42  |   appId: `test-app-${Date.now()}`,
  43  |   appName: 'E2E Test Application',
  44  |   projectPaths: ['/tmp/test-project-1', '/tmp/test-project-2']
  45  | }
  46  | 
  47  | test.describe('项目管理 API 测试', () => {
  48  |   let authToken: string | null = null
  49  | 
  50  |   test.beforeAll(async ({ request }) => {
  51  |     authToken = await getAuthToken(request)
  52  |   })
  53  | 
  54  |   test.describe.configure({ mode: 'parallel' })
  55  | 
  56  |   // API-PROJ-01: Git仓库扫描
  57  |   test('API-PROJ-01: Git仓库扫描', async ({ request }) => {
  58  |     const response = await request.get(`${API_BASE}/projects/scan-git-repos`, {
  59  |       headers: authHeaders(authToken)
  60  |     })
  61  | 
  62  |     expect(response.ok()).toBeTruthy()
  63  |     const data = await response.json()
  64  |     expect(data.code).toBe(200)
  65  |     expect(Array.isArray(data.data)).toBeTruthy()
  66  | 
  67  |     for (const repo of data.data) {
  68  |       expect(repo).toHaveProperty('path')
  69  |       expect(repo).toHaveProperty('name')
  70  |       expect(repo).toHaveProperty('branch')
  71  |       expect(repo).toHaveProperty('clean')
  72  |     }
  73  |   })
  74  | 
  75  |   // API-PROJ-02: 远端项目列表
  76  |   test('API-PROJ-02: 远端项目列表', async ({ request }) => {
  77  |     const response = await request.get(`${API_BASE}/remote-projects`, {
  78  |       headers: authHeaders(authToken)
  79  |     })
  80  | 
  81  |     expect(response.ok()).toBeTruthy()
  82  |     const data = await response.json()
  83  |     expect(data.code).toBe(200)
  84  |     expect(Array.isArray(data.data)).toBeTruthy()
  85  | 
  86  |     for (const project of data.data) {
  87  |       expect(project).toHaveProperty('id')
  88  |       expect(project).toHaveProperty('name')
  89  |       expect(project).toHaveProperty('cloneStatus')
  90  |       expect(['PENDING', 'CLONING', 'CLONED', 'FAILED']).toContain(project.cloneStatus)
  91  |     }
  92  |   })
  93  | 
  94  |   // API-PROJ-03: 克隆远端项目
  95  |   test('API-PROJ-03: 克隆远端项目 - 项目不存在', async ({ request }) => {
  96  |     const response = await request.post(`${API_BASE}/remote-projects/999999/clone`, {
  97  |       headers: authHeaders(authToken)
  98  |     })
  99  | 
> 100 |     expect([400, 404, 500]).toContain(response.status())
      |                             ^ Error: expect(received).toContain(expected) // indexOf
  101 |   })
  102 | 
  103 |   // API-PROJ-04: 项目分组列表
  104 |   test('API-PROJ-04: 项目分组列表', async ({ request }) => {
  105 |     const response = await request.get(`${API_BASE}/project-group`, {
  106 |       headers: authHeaders(authToken)
  107 |     })
  108 | 
  109 |     expect(response.ok()).toBeTruthy()
  110 |     const data = await response.json()
  111 |     expect(data.code).toBe(200)
  112 |     expect(Array.isArray(data.data)).toBeTruthy()
  113 | 
  114 |     for (const group of data.data) {
  115 |       expect(group).toHaveProperty('appId')
  116 |       expect(group).toHaveProperty('appName')
  117 |       expect(Array.isArray(group.projectPaths)).toBeTruthy()
  118 |     }
  119 |   })
  120 | 
  121 |   // API-PROJ-05: 项目分组创建
  122 |   test('API-PROJ-05: 项目分组创建', async ({ request }) => {
  123 |     const createResponse = await request.post(`${API_BASE}/project-group`, {
  124 |       headers: authHeaders(authToken),
  125 |       data: {
  126 |         appId: TEST_GROUP.appId,
  127 |         appName: TEST_GROUP.appName,
  128 |         projectPaths: TEST_GROUP.projectPaths
  129 |       }
  130 |     })
  131 | 
  132 |     expect(createResponse.ok()).toBeTruthy()
  133 |     const createData = await createResponse.json()
  134 |     expect(createData.code).toBe(200)
  135 | 
  136 |     // 清理
  137 |     const deleteResponse = await request.delete(`${API_BASE}/project-group/${TEST_GROUP.appId}`, {
  138 |       headers: authHeaders(authToken)
  139 |     })
  140 |     expect([200, 204]).toContain(deleteResponse.status())
  141 |   })
  142 | 
  143 |   // API-PROJ-05: 项目分组创建 - 无效参数
  144 |   test('API-PROJ-05: 项目分组创建 - 无效参数', async ({ request }) => {
  145 |     const response = await request.post(`${API_BASE}/project-group`, {
  146 |       headers: authHeaders(authToken),
  147 |       data: { appName: 'Test App', projectPaths: [] }
  148 |     })
  149 | 
  150 |     expect([400, 500]).toContain(response.status())
  151 |   })
  152 | 
  153 |   // 错误边界: 无效的项目ID格式
  154 |   test('错误边界: 无效的项目ID格式', async ({ request }) => {
  155 |     const response = await request.get(`${API_BASE}/projects/status?name=`, {
  156 |       headers: authHeaders(authToken)
  157 |     })
  158 | 
  159 |     expect([200, 400, 404]).toContain(response.status())
  160 |   })
  161 | 
  162 |   // API响应格式验证
  163 |   test('API响应格式验证', async ({ request }) => {
  164 |     const response = await request.get(`${API_BASE}/projects/list`, {
  165 |       headers: authHeaders(authToken)
  166 |     })
  167 | 
  168 |     expect(response.ok()).toBeTruthy()
  169 |     const data = await response.json()
  170 |     expect(data).toHaveProperty('code')
  171 |     expect(data).toHaveProperty('message')
  172 |     expect(typeof data.code).toBe('number')
  173 |   })
  174 | })
  175 | 
  176 | test.describe('项目管理 API - 清理测试', () => {
  177 |   let authToken: string | null = null
  178 | 
  179 |   test.beforeAll(async ({ request }) => {
  180 |     authToken = await getAuthToken(request)
  181 |   })
  182 | 
  183 |   test('清理测试数据', async ({ request }) => {
  184 |     const appId = TEST_GROUP.appId
  185 | 
  186 |     const getResponse = await request.get(`${API_BASE}/project-group/${appId}`, {
  187 |       headers: authHeaders(authToken)
  188 |     })
  189 | 
  190 |     if (getResponse.status() === 200) {
  191 |       const deleteResponse = await request.delete(`${API_BASE}/project-group/${appId}`, {
  192 |         headers: authHeaders(authToken)
  193 |       })
  194 |       expect([200, 204, 404]).toContain(deleteResponse.status())
  195 |     }
  196 |   })
  197 | })
```