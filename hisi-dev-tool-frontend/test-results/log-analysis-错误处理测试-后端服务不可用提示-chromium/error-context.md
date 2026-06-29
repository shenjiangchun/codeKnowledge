# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: log-analysis.spec.ts >> 错误处理测试 >> 后端服务不可用提示
- Location: e2e\log-analysis.spec.ts:365:3

# Error details

```
TimeoutError: locator.fill: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('textarea').first()

```

# Page snapshot

```yaml
- generic [ref=e3]:
  - complementary [ref=e4]:
    - menubar [ref=e5]:
      - menuitem "技能市场" [ref=e6] [cursor=pointer]:
        - img [ref=e8]
        - generic [ref=e10]: 技能市场
      - menuitem "KG Skills 套件" [ref=e11]:
        - img [ref=e13]
        - generic [ref=e15]: KG Skills 套件
      - menuitem "Claude 终端" [ref=e16] [cursor=pointer]:
        - img [ref=e18]
        - generic [ref=e20]: Claude 终端
      - menuitem "APM 调试" [ref=e21] [cursor=pointer]:
        - img [ref=e23]
        - generic [ref=e26]: APM 调试
      - menuitem "增强检索" [ref=e27]:
        - img [ref=e29]
        - generic [ref=e31]: 增强检索
      - menuitem "日志分析" [ref=e32] [cursor=pointer]:
        - img [ref=e34]
        - generic [ref=e36]: 日志分析
      - menuitem "知识图谱" [ref=e37]:
        - img [ref=e39]
        - generic [ref=e41]: 知识图谱
      - menuitem "需求分析大师" [ref=e42] [cursor=pointer]:
        - img [ref=e44]
        - generic [ref=e46]: 需求分析大师
      - menuitem "项目现状分析" [ref=e47] [cursor=pointer]:
        - img [ref=e49]
        - generic [ref=e51]: 项目现状分析
      - menuitem "合入分析" [ref=e52] [cursor=pointer]:
        - img [ref=e54]
        - generic [ref=e57]: 合入分析
      - menuitem "项目管理" [ref=e58] [cursor=pointer]:
        - img [ref=e60]
        - generic [ref=e62]: 项目管理
      - menuitem "系统设置" [ref=e63] [cursor=pointer]:
        - img [ref=e65]
        - generic [ref=e67]: 系统设置
  - generic [ref=e68]:
    - generic [ref=e70]:
      - heading "HiSi DevTool" [level=1] [ref=e71]
      - button "登录" [ref=e74] [cursor=pointer]:
        - generic [ref=e75]: 登录
    - main [ref=e76]
```

# Test source

```ts
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
  355 |     await queryButton.click();
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
> 375 |     await errorTextarea.fill('Test error');
      |                         ^ TimeoutError: locator.fill: Timeout 10000ms exceeded.
  376 | 
  377 |     const diagnoseButton = page.locator('button:has-text("诊断")').first();
  378 |     if (await diagnoseButton.count() > 0) {
  379 |       await diagnoseButton.click();
  380 |       await page.waitForTimeout(2000);
  381 |     }
  382 |   });
  383 | });
```