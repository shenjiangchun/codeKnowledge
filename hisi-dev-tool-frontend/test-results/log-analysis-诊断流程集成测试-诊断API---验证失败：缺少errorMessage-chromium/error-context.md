# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: log-analysis.spec.ts >> 诊断流程集成测试 >> 诊断API - 验证失败：缺少errorMessage
- Location: e2e\log-analysis.spec.ts:223:3

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: 400
Received: 401
```

# Test source

```ts
  134 |         at com.example.service.UserService.login(UserService.java:150)
  135 |         at com.example.controller.AuthController.handleLogin(AuthController.java:45)
  136 |         at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)`,
  137 |         logContent: '2024-01-15 10:30:45 ERROR [main] UserService - Login failed: null user object',
  138 |         traceId: 'trace-e2e-test-001',
  139 |         entryPoint: 'POST /api/auth/login'
  140 |       })
  141 |     });
  142 | 
  143 |     expect(response.ok).toBeTruthy();
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
> 234 |     expect(response.status).toBe(400);
      |                             ^ Error: expect(received).toBe(expected) // Object.is equality
  235 |   });
  236 | });
  237 | 
  238 | test.describe('诊断页面 UI 测试', () => {
  239 | 
  240 |   test('诊断页面 - 表单元素', async ({ page }) => {
  241 |     await page.goto(`${BASE_URL}/diagnostic`);
  242 | 
  243 |     // 验证页面标题
  244 |     await expect(page.locator('h2, h3, .el-card__header').filter({ hasText: /诊断|智能/ }).first()).toBeVisible({ timeout: 10000 });
  245 | 
  246 |     // 验证输入表单
  247 |     const textareas = page.locator('textarea');
  248 |     await expect(textareas.first()).toBeVisible({ timeout: 10000 });
  249 |   });
  250 | 
  251 |   test('诊断页面 - 输入错误信息', async ({ page }) => {
  252 |     await page.goto(`${BASE_URL}/diagnostic`);
  253 | 
  254 |     // 输入错误消息
  255 |     const errorTextarea = page.locator('textarea').first();
  256 |     await errorTextarea.fill('NullPointerException in UserService.login() method at line 150');
  257 |     await expect(errorTextarea).toContainText('NullPointerException');
  258 |   });
  259 | 
  260 |   test('诊断页面 - 输入堆栈信息', async ({ page }) => {
  261 |     await page.goto(`${BASE_URL}/diagnostic`);
  262 | 
  263 |     const stackTrace = `java.lang.NullPointerException: null
  264 |     at com.example.service.UserService.login(UserService.java:150)
  265 |     at com.example.controller.AuthController.handleLogin(AuthController.java:45)`;
  266 | 
  267 |     // 查找堆栈输入框
  268 |     const stackTextarea = page.locator('textarea').nth(1);
  269 |     if (await stackTextarea.count() > 0) {
  270 |       await stackTextarea.fill(stackTrace);
  271 |     }
  272 |   });
  273 | 
  274 |   test('诊断页面 - 提交诊断', async ({ page }) => {
  275 |     await page.goto(`${BASE_URL}/diagnostic`);
  276 | 
  277 |     // 填写表单
  278 |     const errorTextarea = page.locator('textarea').first();
  279 |     await errorTextarea.fill('Test error message');
  280 | 
  281 |     // 点击诊断按钮
  282 |     const diagnoseButton = page.locator('button:has-text("诊断"), button:has-text("开始")').first();
  283 |     if (await diagnoseButton.count() > 0) {
  284 |       await diagnoseButton.click();
  285 | 
  286 |       // 等待响应
  287 |       await page.waitForTimeout(3000);
  288 |     }
  289 |   });
  290 | 
  291 |   test('诊断页面 - 结果展示', async ({ page }) => {
  292 |     await page.goto(`${BASE_URL}/diagnostic`);
  293 | 
  294 |     // 填写表单并提交
  295 |     const errorTextarea = page.locator('textarea').first();
  296 |     await errorTextarea.fill('NullPointerException in test method');
  297 | 
  298 |     const diagnoseButton = page.locator('button:has-text("诊断"), button:has-text("开始")').first();
  299 |     if (await diagnoseButton.count() > 0) {
  300 |       await diagnoseButton.click();
  301 | 
  302 |       // 等待结果显示
  303 |       const resultSection = page.locator('.diagnosis-result, .analysis-result, .el-card:has-text("结论")');
  304 |       try {
  305 |         await expect(resultSection.first()).toBeVisible({ timeout: 10000 });
  306 |       } catch {
  307 |         // 结果可能还在加载
  308 |       }
  309 |     }
  310 |   });
  311 | });
  312 | 
  313 | test.describe('响应式布局测试', () => {
  314 | 
  315 |   test('移动端视图 - 日志查询', async ({ page }) => {
  316 |     // 设置移动端视口
  317 |     await page.setViewportSize({ width: 375, height: 667 });
  318 |     await page.goto(`${BASE_URL}/log-analysis`);
  319 | 
  320 |     // 验证页面可访问
  321 |     await expect(page.locator('button:has-text("查询")')).toBeVisible({ timeout: 10000 });
  322 |   });
  323 | 
  324 |   test('平板视图 - 诊断页面', async ({ page }) => {
  325 |     // 设置平板视口
  326 |     await page.setViewportSize({ width: 768, height: 1024 });
  327 |     await page.goto(`${BASE_URL}/diagnostic`);
  328 | 
  329 |     // 验证页面可访问
  330 |     const formElement = page.locator('textarea, input').first();
  331 |     await expect(formElement).toBeVisible({ timeout: 10000 });
  332 |   });
  333 | 
  334 |   test('桌面视图 - 完整布局', async ({ page }) => {
```