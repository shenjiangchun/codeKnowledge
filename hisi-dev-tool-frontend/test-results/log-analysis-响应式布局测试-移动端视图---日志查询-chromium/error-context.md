# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: log-analysis.spec.ts >> 响应式布局测试 >> 移动端视图 - 日志查询
- Location: e2e\log-analysis.spec.ts:315:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('button:has-text("查询")')
Expected: visible
Error: strict mode violation: locator('button:has-text("查询")') resolved to 3 elements:
    1) <button type="button" data-v-7d8e7cce="" aria-disabled="false" class="el-button el-button--primary">…</button> aka getByRole('button', { name: '查询', exact: true })
    2) <button type="button" data-v-7d8e7cce="" aria-disabled="false" class="el-button el-button--default">…</button> aka getByRole('button', { name: '高级查询' })
    3) <button type="button" data-v-7d8e7cce="" aria-disabled="false" class="el-button el-button--primary">…</button> aka getByLabel('分析报告', { exact: true }).locator('button').filter({ hasText: '查询' })

Call log:
  - Expect "toBeVisible" with timeout 10000ms
  - waiting for locator('button:has-text("查询")')

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
    - main [ref=e76]:
      - generic [ref=e78]:
        - generic [ref=e80]:
          - img [ref=e83]
          - img [ref=e87] [cursor=pointer]
          - tablist [ref=e90]:
            - tab "日志查询" [selected] [ref=e91]
            - tab "定时任务配置" [ref=e92]
            - tab "分析报告" [ref=e93]
        - tabpanel "日志查询" [ref=e95]:
          - generic [ref=e96]:
            - generic [ref=e97]: 日志查询
            - generic [ref=e102]:
              - generic [ref=e103]:
                - generic:
                  - generic [ref=e104]: DSL 查询配置
                  - generic [ref=e105]:
                    - button "查询" [ref=e106] [cursor=pointer]:
                      - generic [ref=e107]:
                        - img [ref=e109]
                        - text: 查询
                    - button "重置" [ref=e111] [cursor=pointer]:
                      - generic [ref=e112]: 重置
                    - button "高级查询" [ref=e113] [cursor=pointer]:
                      - generic [ref=e114]: 高级查询
              - generic [ref=e115]:
                - separator:
                  - generic [ref=e116]: 推荐查询
                - generic:
                  - generic [ref=e118] [cursor=pointer]:
                    - generic:
                      - img [ref=e120]
                      - generic [ref=e122]: 错误日志查询
                    - paragraph: 查询最近 15 分钟的所有错误日志
                  - generic [ref=e124] [cursor=pointer]:
                    - generic:
                      - img [ref=e126]
                      - generic [ref=e128]: NullPointerException
                    - paragraph: 查询空指针异常日志
                  - generic [ref=e130] [cursor=pointer]:
                    - generic:
                      - img [ref=e132]
                      - generic [ref=e134]: 数据库异常
                    - paragraph: 查询数据库相关错误
                  - generic [ref=e136] [cursor=pointer]:
                    - generic:
                      - img [ref=e138]
                      - generic [ref=e140]: Spring 异常
                    - paragraph: 查询 Spring 框架相关错误
          - generic [ref=e141]:
            - generic [ref=e144]: 查询结果
            - generic [ref=e145]:
              - generic [ref=e147]:
                - table [ref=e149]:
                  - rowgroup [ref=e158]:
                    - row "级别 时间 服务 TraceID 消息 主机 操作" [ref=e159]:
                      - columnheader "级别" [ref=e160]:
                        - generic [ref=e161]: 级别
                      - columnheader "时间" [ref=e162]:
                        - generic [ref=e163]: 时间
                      - columnheader "服务" [ref=e164]:
                        - generic [ref=e165]: 服务
                      - columnheader "TraceID" [ref=e166]:
                        - generic [ref=e167]: TraceID
                      - columnheader "消息" [ref=e168]:
                        - generic [ref=e169]: 消息
                      - columnheader "主机" [ref=e170]:
                        - generic [ref=e171]: 主机
                      - columnheader "操作" [ref=e172]:
                        - generic [ref=e173]: 操作
                - generic [ref=e177]:
                  - table:
                    - rowgroup
                  - generic [ref=e179]: No Data
              - generic [ref=e180]:
                - generic [ref=e181]: Total 0
                - generic [ref=e184] [cursor=pointer]:
                  - generic:
                    - combobox [ref=e186]
                    - generic [ref=e187]: 20/page
                  - img [ref=e190]
                - button "Go to previous page" [disabled] [ref=e192]:
                  - generic:
                    - img
                - list [ref=e193]:
                  - listitem "page 1" [ref=e194]: "1"
                - button "Go to next page" [disabled] [ref=e195]:
                  - generic:
                    - img
```

# Test source

```ts
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
> 321 |     await expect(page.locator('button:has-text("查询")')).toBeVisible({ timeout: 10000 });
      |                                                         ^ Error: expect(locator).toBeVisible() failed
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