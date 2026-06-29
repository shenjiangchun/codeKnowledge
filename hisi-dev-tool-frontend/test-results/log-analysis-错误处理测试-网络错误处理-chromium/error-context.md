# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: log-analysis.spec.ts >> 错误处理测试 >> 网络错误处理
- Location: e2e\log-analysis.spec.ts:347:3

# Error details

```
TimeoutError: locator.click: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('button:has-text("查询")')

```

# Test source

```ts
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
  335 |     // 设置桌面视口
  336 |     await page.setViewportSize({ width: 1920, height: 1080 });
  337 |     await page.goto(BASE_URL);
  338 | 
  339 |     // 验证侧边栏可见
  340 |     const sidebar = page.locator('.el-menu, .sidebar, nav');
  341 |     await expect(sidebar.first()).toBeVisible({ timeout: 10000 });
  342 |   });
  343 | });
  344 | 
  345 | test.describe('错误处理测试', () => {
  346 | 
  347 |   test('网络错误处理', async ({ page }) => {
  348 |     // 模拟网络错误
  349 |     await page.route('**/api/**', route => route.abort('failed'));
  350 | 
  351 |     await page.goto(`${BASE_URL}/log-analysis`);
  352 | 
  353 |     // 点击查询
  354 |     const queryButton = page.locator('button:has-text("查询")');
> 355 |     await queryButton.click();
      |                       ^ TimeoutError: locator.click: Timeout 10000ms exceeded.
  356 | 
  357 |     // 等待错误处理
  358 |     await page.waitForTimeout(2000);
  359 | 
  360 |     // 验证错误提示（如果有）
  361 |     const errorMessage = page.locator('.el-message--error, .el-notification__content:has-text("失败")');
  362 |     // 不强制要求错误提示，仅验证页面不崩溃
  363 |   });
  364 | 
  365 |   test('后端服务不可用提示', async ({ page }) => {
  366 |     // 模拟 500 错误
  367 |     await page.route('**/api/diagnosis/**', route =>
  368 |       route.fulfill({ status: 500, body: JSON.stringify({ message: 'Internal Server Error' }) })
  369 |     );
  370 | 
  371 |     await page.goto(`${BASE_URL}/diagnostic`);
  372 | 
  373 |     // 填写并提交
  374 |     const errorTextarea = page.locator('textarea').first();
  375 |     await errorTextarea.fill('Test error');
  376 | 
  377 |     const diagnoseButton = page.locator('button:has-text("诊断")').first();
  378 |     if (await diagnoseButton.count() > 0) {
  379 |       await diagnoseButton.click();
  380 |       await page.waitForTimeout(2000);
  381 |     }
  382 |   });
  383 | });
```