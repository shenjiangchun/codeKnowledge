# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: knowledge-graph.spec.ts >> 知识图谱 API 集成测试 >> GET /api/knowledge-graph/callees >> 返回方法调用的其他方法列表
- Location: e2e\knowledge-graph.spec.ts:101:5

# Error details

```
Error: expect(received).toBeTruthy()

Received: false
```

# Test source

```ts
  10  |  */
  11  | 
  12  | import { test, expect } from '@playwright/test'
  13  | 
  14  | test.describe('知识图谱 API 集成测试', () => {
  15  |   const backendUrl = 'http://localhost:8080'
  16  | 
  17  |   test.describe('POST /api/knowledge-graph/generate', () => {
  18  |     test('成功生成知识图谱', async ({ page }) => {
  19  |       const response = await page.request.post(`${backendUrl}/api/knowledge-graph/generate`, {
  20  |         data: { projectPath: '/tmp/test-project' }
  21  |       })
  22  | 
  23  |       // 检查响应状态码（可能是200成功或404项目不存在）
  24  |       expect([200, 404, 500]).toContain(response.status())
  25  | 
  26  |       if (response.status() === 200) {
  27  |         const data = await response.json()
  28  |         expect(data).toHaveProperty('methodNodeCount')
  29  |         expect(data).toHaveProperty('callRelationCount')
  30  |         expect(data).toHaveProperty('entryPointCount')
  31  |         expect(typeof data.methodNodeCount).toBe('number')
  32  |         expect(typeof data.callRelationCount).toBe('number')
  33  |         expect(typeof data.entryPointCount).toBe('number')
  34  |       }
  35  |     })
  36  | 
  37  |     test('项目路径为空返回400错误', async ({ page }) => {
  38  |       const response = await page.request.post(`${backendUrl}/api/knowledge-graph/generate`, {
  39  |         data: { projectPath: '' }
  40  |       })
  41  | 
  42  |       expect(response.status()).toBe(400)
  43  |     })
  44  | 
  45  |     test('请求体缺少projectPath字段返回400错误', async ({ page }) => {
  46  |       const response = await page.request.post(`${backendUrl}/api/knowledge-graph/generate`, {
  47  |         data: {}
  48  |       })
  49  | 
  50  |       expect(response.status()).toBe(400)
  51  |     })
  52  |   })
  53  | 
  54  |   test.describe('GET /api/knowledge-graph/status', () => {
  55  |     test('返回知识图谱生成状态', async ({ page }) => {
  56  |       const response = await page.request.get(`${backendUrl}/api/knowledge-graph/status`, {
  57  |         params: { projectPath: '/tmp/test-project' }
  58  |       })
  59  | 
  60  |       expect(response.ok()).toBeTruthy()
  61  | 
  62  |       const data = await response.json()
  63  |       expect(data).toHaveProperty('projectPath')
  64  |       expect(data).toHaveProperty('status')
  65  |       expect(['not_generated', 'generated']).toContain(data.status)
  66  |     })
  67  | 
  68  |     test('缺少projectPath参数返回400错误', async ({ page }) => {
  69  |       const response = await page.request.get(`${backendUrl}/api/knowledge-graph/status`)
  70  | 
  71  |       expect(response.status()).toBe(400)
  72  |     })
  73  |   })
  74  | 
  75  |   test.describe('GET /api/knowledge-graph/callers', () => {
  76  |     test('返回方法的调用者列表', async ({ page }) => {
  77  |       const response = await page.request.get(`${backendUrl}/api/knowledge-graph/callers`, {
  78  |         params: {
  79  |           className: 'com.example.Service',
  80  |           methodName: 'process',
  81  |           projectPath: '/tmp/test-project'
  82  |         }
  83  |       })
  84  | 
  85  |       // 检查响应状态码
  86  |       expect(response.ok()).toBeTruthy()
  87  | 
  88  |       const data = await response.json()
  89  |       expect(Array.isArray(data)).toBeTruthy()
  90  | 
  91  |       // 如果有调用者数据，验证数据结构
  92  |       if (data.length > 0) {
  93  |         expect(data[0]).toHaveProperty('callerId')
  94  |         expect(data[0]).toHaveProperty('callType')
  95  |         expect(data[0]).toHaveProperty('callLine')
  96  |       }
  97  |     })
  98  |   })
  99  | 
  100 |   test.describe('GET /api/knowledge-graph/callees', () => {
  101 |     test('返回方法调用的其他方法列表', async ({ page }) => {
  102 |       const response = await page.request.get(`${backendUrl}/api/knowledge-graph/callees`, {
  103 |         params: {
  104 |           className: 'com.example.Service',
  105 |           methodName: 'process',
  106 |           projectPath: '/tmp/test-project'
  107 |         }
  108 |       })
  109 | 
> 110 |       expect(response.ok()).toBeTruthy()
      |                             ^ Error: expect(received).toBeTruthy()
  111 | 
  112 |       const data = await response.json()
  113 |       expect(Array.isArray(data)).toBeTruthy()
  114 | 
  115 |       // 如果有被调用者数据，验证数据结构
  116 |       if (data.length > 0) {
  117 |         expect(data[0]).toHaveProperty('calleeId')
  118 |         expect(data[0]).toHaveProperty('callType')
  119 |         expect(data[0]).toHaveProperty('callLine')
  120 |       }
  121 |     })
  122 |   })
  123 | 
  124 |   test.describe('GET /api/knowledge-graph/entry-points', () => {
  125 |     test('返回项目入口点列表', async ({ page }) => {
  126 |       const response = await page.request.get(`${backendUrl}/api/knowledge-graph/entry-points`, {
  127 |         params: { projectPath: '/tmp/test-project' }
  128 |       })
  129 | 
  130 |       expect(response.ok()).toBeTruthy()
  131 | 
  132 |       const data = await response.json()
  133 |       expect(Array.isArray(data)).toBeTruthy()
  134 | 
  135 |       // 如果有入口点数据，验证数据结构
  136 |       if (data.length > 0) {
  137 |         expect(data[0]).toHaveProperty('nodeId')
  138 |         expect(data[0]).toHaveProperty('entryType')
  139 |         expect(data[0]).toHaveProperty('entryKey')
  140 |         expect(data[0]).toHaveProperty('projectPath')
  141 |       }
  142 |     })
  143 | 
  144 |     test('按入口类型筛选入口点 - HTTP', async ({ page }) => {
  145 |       const response = await page.request.get(`${backendUrl}/api/knowledge-graph/entry-points`, {
  146 |         params: {
  147 |           projectPath: '/tmp/test-project',
  148 |           entryType: 'HTTP'
  149 |         }
  150 |       })
  151 | 
  152 |       expect(response.ok()).toBeTruthy()
  153 | 
  154 |       const data = await response.json()
  155 |       expect(Array.isArray(data)).toBeTruthy()
  156 | 
  157 |       // 所有返回的入口点都应该是 HTTP 类型
  158 |       data.forEach((entryPoint: any) => {
  159 |         expect(entryPoint.entryType).toBe('HTTP')
  160 |       })
  161 |     })
  162 | 
  163 |     test('按入口类型筛选入口点 - SCHEDULED', async ({ page }) => {
  164 |       const response = await page.request.get(`${backendUrl}/api/knowledge-graph/entry-points`, {
  165 |         params: {
  166 |           projectPath: '/tmp/test-project',
  167 |           entryType: 'SCHEDULED'
  168 |         }
  169 |       })
  170 | 
  171 |       expect(response.ok()).toBeTruthy()
  172 | 
  173 |       const data = await response.json()
  174 |       expect(Array.isArray(data)).toBeTruthy()
  175 | 
  176 |       // 所有返回的入口点都应该是 SCHEDULED 类型
  177 |       data.forEach((entryPoint: any) => {
  178 |         expect(entryPoint.entryType).toBe('SCHEDULED')
  179 |       })
  180 |     })
  181 |   })
  182 | })
  183 | 
  184 | test.describe('知识图谱前端页面测试', () => {
  185 |   test.beforeEach(async ({ page }) => {
  186 |     // 导航到项目管理页面
  187 |     await page.goto('/project')
  188 |   })
  189 | 
  190 |   test('项目管理页面显示"生成图谱"按钮', async ({ page }) => {
  191 |     // 等待页面加载完成
  192 |     await page.waitForLoadState('networkidle')
  193 | 
  194 |     // 检查是否存在生成图谱按钮（可能在表格操作列中）
  195 |     const generateGraphButton = page.getByRole('button', { name: /生成图谱|知识图谱/ })
  196 |     // 按钮可能因为项目列表为空而不可见，检查按钮是否存在
  197 |     const buttonCount = await generateGraphButton.count()
  198 |     // 如果有项目，按钮应该可见
  199 |     if (buttonCount > 0) {
  200 |       await expect(generateGraphButton.first()).toBeVisible()
  201 |     }
  202 |   })
  203 | 
  204 |   test('点击按钮触发知识图谱生成', async ({ page }) => {
  205 |     await page.waitForLoadState('networkidle')
  206 | 
  207 |     // 查找生成图谱按钮
  208 |     const generateGraphButton = page.getByRole('button', { name: /生成图谱/ })
  209 | 
  210 |     // 如果按钮存在且可点击
```