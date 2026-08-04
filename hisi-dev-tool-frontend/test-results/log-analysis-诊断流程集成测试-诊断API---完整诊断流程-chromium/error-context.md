# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: log-analysis.spec.ts >> 诊断流程集成测试 >> 诊断API - 完整诊断流程
- Location: e2e\log-analysis.spec.ts:125:3

# Error details

```
Error: expect(received).toBeTruthy()

Received: false
```

# Test source

```ts
  43  |   });
  44  | 
  45  |   test('点击查询按钮', async ({ page }) => {
  46  |     const queryButton = page.locator('button:has-text("查询")');
  47  |     await queryButton.click();
  48  | 
  49  |     // 等待加载状态或结果
  50  |     await page.waitForTimeout(1000);
  51  |   });
  52  | });
  53  | 
  54  | test.describe('日志分析按钮测试', () => {
  55  | 
  56  |   test.beforeEach(async ({ page }) => {
  57  |     // 验证后端服务可用
  58  |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
  59  |     if (!response.ok) {
  60  |       test.skip();
  61  |     }
  62  |   });
  63  | 
  64  |   test('日志列表 - 分析按钮存在', async ({ page }) => {
  65  |     await page.goto(`${BASE_URL}/log-analysis`);
  66  | 
  67  |     // 查询日志以显示列表
  68  |     const queryButton = page.locator('button:has-text("查询")');
  69  |     await queryButton.click();
  70  |     await page.waitForTimeout(2000);
  71  | 
  72  |     // 检查是否有日志数据
  73  |     const analyzeButton = page.locator('button:has-text("分析")').first();
  74  |     const tableRows = page.locator('.el-table__body-wrapper .el-table__row');
  75  | 
  76  |     // 如果有数据，验证分析按钮
  77  |     if (await tableRows.count() > 0) {
  78  |       await expect(analyzeButton).toBeVisible({ timeout: 5000 });
  79  |     }
  80  |   });
  81  | 
  82  |   test('点击分析按钮 - 打开分析对话框', async ({ page }) => {
  83  |     await page.goto(`${BASE_URL}/log-analysis`);
  84  | 
  85  |     // 查询日志
  86  |     const queryButton = page.locator('button:has-text("查询")');
  87  |     await queryButton.click();
  88  |     await page.waitForTimeout(2000);
  89  | 
  90  |     const tableRows = page.locator('.el-table__body-wrapper .el-table__row');
  91  | 
  92  |     if (await tableRows.count() > 0) {
  93  |       // 点击第一个分析按钮
  94  |       const analyzeButton = page.locator('button:has-text("分析")').first();
  95  |       await analyzeButton.click();
  96  | 
  97  |       // 验证分析对话框打开
  98  |       const dialog = page.locator('.el-dialog:has-text("分析"), .el-dialog:has-text("Claude")');
  99  |       await expect(dialog).toBeVisible({ timeout: 5000 });
  100 |     }
  101 |   });
  102 | });
  103 | 
  104 | test.describe('诊断流程集成测试', () => {
  105 | 
  106 |   test('诊断API - 健康检查', async () => {
  107 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
  108 |     expect(response.ok).toBeTruthy();
  109 | 
  110 |     const data = await response.json();
  111 |     expect(data.code).toBe(200);
  112 |     expect(data.data.status).toBe('UP');
  113 |   });
  114 | 
  115 |   test('诊断API - Agent列表', async () => {
  116 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/agents`);
  117 |     expect(response.ok).toBeTruthy();
  118 | 
  119 |     const data = await response.json();
  120 |     expect(data.code).toBe(200);
  121 |     expect(Array.isArray(data.data)).toBe(true);
  122 |     expect(data.data).toContain('STACK_TRACE');
  123 |   });
  124 | 
  125 |   test('诊断API - 完整诊断流程', async () => {
  126 |     // 1. 发送诊断请求
  127 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
  128 |       method: 'POST',
  129 |       headers: { 'Content-Type': 'application/json' },
  130 |       body: JSON.stringify({
  131 |         projectPath: '/test/project',
  132 |         errorMessage: 'NullPointerException in UserService.login',
  133 |         stackTrace: `java.lang.NullPointerException: Cannot invoke method on null object
  134 |         at com.example.service.UserService.login(UserService.java:150)
  135 |         at com.example.controller.AuthController.handleLogin(AuthController.java:45)
  136 |         at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)`,
  137 |         logContent: '2024-01-15 10:30:45 ERROR [main] UserService - Login failed: null user object',
  138 |         traceId: 'trace-e2e-test-001',
  139 |         entryPoint: 'POST /api/auth/login'
  140 |       })
  141 |     });
  142 | 
> 143 |     expect(response.ok).toBeTruthy();
      |                         ^ Error: expect(received).toBeTruthy()
  144 |     const data = await response.json();
  145 | 
  146 |     // 2. 验证响应结构
  147 |     expect(data.code).toBe(200);
  148 |     expect(data.data.requestId).toBeTruthy();
  149 |     expect(data.data.confidence).toBeGreaterThanOrEqual(0);
  150 |     expect(data.data.confidence).toBeLessThanOrEqual(1);
  151 | 
  152 |     // 3. 验证诊断结果
  153 |     expect(data.data.conclusion).toBeTruthy();
  154 |     expect(data.data.agents).toBeDefined();
  155 |     expect(Array.isArray(data.data.agents)).toBe(true);
  156 |     expect(data.data.agents.length).toBeGreaterThan(0);
  157 | 
  158 |     // 4. 验证 Agent 结果
  159 |     const stackTraceAgent = data.data.agents.find((a: any) => a.type === 'STACK_TRACE');
  160 |     expect(stackTraceAgent).toBeDefined();
  161 |     expect(stackTraceAgent.status).toBe('SUCCESS');
  162 |     expect(stackTraceAgent.confidence).toBeGreaterThan(0);
  163 |   });
  164 | 
  165 |   test('诊断API - 高置信度结果', async () => {
  166 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
  167 |       method: 'POST',
  168 |       headers: { 'Content-Type': 'application/json' },
  169 |       body: JSON.stringify({
  170 |         projectPath: '/test/project',
  171 |         errorMessage: 'Database connection failed',
  172 |         stackTrace: `java.sql.SQLException: Connection refused
  173 |         at com.example.db.ConnectionPool.getConnection(ConnectionPool.java:80)
  174 |         at com.example.repository.UserRepository.findById(UserRepository.java:45)`,
  175 |         logContent: '2024-01-15 ERROR Connection refused to database'
  176 |       })
  177 |     });
  178 | 
  179 |     const data = await response.json();
  180 | 
  181 |     // 验证包含修复建议
  182 |     expect(data.data.fixSuggestions).toBeDefined();
  183 |     expect(Array.isArray(data.data.fixSuggestions)).toBe(true);
  184 | 
  185 |     // 验证受影响代码
  186 |     expect(data.data.affectedCode).toBeDefined();
  187 |     expect(Array.isArray(data.data.affectedCode)).toBe(true);
  188 |   });
  189 | 
  190 |   test('诊断API - 异步诊断', async () => {
  191 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze/async`, {
  192 |       method: 'POST',
  193 |       headers: { 'Content-Type': 'application/json' },
  194 |       body: JSON.stringify({
  195 |         projectPath: '/test/project',
  196 |         errorMessage: 'Async test error',
  197 |         stackTrace: 'java.lang.Exception: async test'
  198 |       })
  199 |     });
  200 | 
  201 |     expect(response.ok).toBeTruthy();
  202 |     const data = await response.json();
  203 |     expect(data.code).toBe(200);
  204 |     expect(data.data).toContain('requestId');
  205 |   });
  206 | 
  207 |   test('诊断API - 边界情况：空堆栈', async () => {
  208 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
  209 |       method: 'POST',
  210 |       headers: { 'Content-Type': 'application/json' },
  211 |       body: JSON.stringify({
  212 |         projectPath: '/test',
  213 |         errorMessage: 'Unknown error'
  214 |       })
  215 |     });
  216 | 
  217 |     expect(response.ok).toBeTruthy();
  218 |     const data = await response.json();
  219 |     expect(data.code).toBe(200);
  220 |     expect(data.data.confidence).toBe(0);
  221 |   });
  222 | 
  223 |   test('诊断API - 验证失败：缺少errorMessage', async () => {
  224 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
  225 |       method: 'POST',
  226 |       headers: { 'Content-Type': 'application/json' },
  227 |       body: JSON.stringify({
  228 |         projectPath: '/test',
  229 |         stackTrace: 'java.lang.Exception: test'
  230 |       })
  231 |     });
  232 | 
  233 |     // 应该返回 400 或验证错误
  234 |     expect(response.status).toBe(400);
  235 |   });
  236 | });
  237 | 
  238 | test.describe('诊断页面 UI 测试', () => {
  239 | 
  240 |   test('诊断页面 - 表单元素', async ({ page }) => {
  241 |     await page.goto(`${BASE_URL}/diagnostic`);
  242 | 
  243 |     // 验证页面标题
```