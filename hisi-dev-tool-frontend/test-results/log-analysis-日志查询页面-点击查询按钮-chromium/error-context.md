# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: log-analysis.spec.ts >> 日志查询页面 >> 点击查询按钮
- Location: e2e\log-analysis.spec.ts:45:3

# Error details

```
Error: locator.click: Error: strict mode violation: locator('button:has-text("查询")') resolved to 3 elements:
    1) <button type="button" data-v-7d8e7cce="" aria-disabled="false" class="el-button el-button--primary">…</button> aka getByRole('button', { name: '查询', exact: true })
    2) <button type="button" data-v-7d8e7cce="" aria-disabled="false" class="el-button el-button--default">…</button> aka getByRole('button', { name: '高级查询' })
    3) <button type="button" data-v-7d8e7cce="" aria-disabled="false" class="el-button el-button--primary">…</button> aka getByLabel('分析报告', { exact: true }).locator('button').filter({ hasText: '查询' })

Call log:
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
        - tablist [ref=e82]:
          - tab "日志查询" [selected] [ref=e83]
          - tab "定时任务配置" [ref=e84]
          - tab "分析报告" [ref=e85]
        - tabpanel "日志查询" [ref=e87]:
          - generic [ref=e88]:
            - generic [ref=e89]: 日志查询
            - generic [ref=e94]:
              - generic [ref=e96]:
                - generic [ref=e97]: DSL 查询配置
                - generic [ref=e98]:
                  - button "查询" [ref=e99] [cursor=pointer]:
                    - generic [ref=e100]:
                      - img [ref=e102]
                      - text: 查询
                  - button "重置" [ref=e104] [cursor=pointer]:
                    - generic [ref=e105]: 重置
                  - button "高级查询" [ref=e106] [cursor=pointer]:
                    - generic [ref=e107]: 高级查询
              - generic [ref=e108]:
                - separator [ref=e109]:
                  - generic [ref=e110]: 推荐查询
                - generic [ref=e111]:
                  - generic [ref=e113] [cursor=pointer]:
                    - generic [ref=e114]:
                      - img [ref=e116]
                      - generic [ref=e118]: 错误日志查询
                    - paragraph [ref=e119]: 查询最近 15 分钟的所有错误日志
                  - generic [ref=e121] [cursor=pointer]:
                    - generic [ref=e122]:
                      - img [ref=e124]
                      - generic [ref=e126]: NullPointerException
                    - paragraph [ref=e127]: 查询空指针异常日志
                  - generic [ref=e129] [cursor=pointer]:
                    - generic [ref=e130]:
                      - img [ref=e132]
                      - generic [ref=e134]: 数据库异常
                    - paragraph [ref=e135]: 查询数据库相关错误
                  - generic [ref=e137] [cursor=pointer]:
                    - generic [ref=e138]:
                      - img [ref=e140]
                      - generic [ref=e142]: Spring 异常
                    - paragraph [ref=e143]: 查询 Spring 框架相关错误
          - generic [ref=e144]:
            - generic [ref=e147]: 查询结果
            - generic [ref=e148]:
              - generic [ref=e150]:
                - table [ref=e152]:
                  - rowgroup [ref=e161]:
                    - row "级别 时间 服务 TraceID 消息 主机 操作" [ref=e162]:
                      - columnheader "级别" [ref=e163]:
                        - generic [ref=e164]: 级别
                      - columnheader "时间" [ref=e165]:
                        - generic [ref=e166]: 时间
                      - columnheader "服务" [ref=e167]:
                        - generic [ref=e168]: 服务
                      - columnheader "TraceID" [ref=e169]:
                        - generic [ref=e170]: TraceID
                      - columnheader "消息" [ref=e171]:
                        - generic [ref=e172]: 消息
                      - columnheader "主机" [ref=e173]:
                        - generic [ref=e174]: 主机
                      - columnheader "操作" [ref=e175]:
                        - generic [ref=e176]: 操作
                - generic [ref=e180]:
                  - table:
                    - rowgroup
                  - generic [ref=e182]: No Data
              - generic [ref=e183]:
                - generic [ref=e184]: Total 0
                - generic [ref=e187] [cursor=pointer]:
                  - generic:
                    - combobox [ref=e189]
                    - generic [ref=e190]: 20/page
                  - img [ref=e193]
                - button "Go to previous page" [disabled] [ref=e195]:
                  - generic:
                    - img
                - list [ref=e196]:
                  - listitem "page 1" [ref=e197]: "1"
                - button "Go to next page" [disabled] [ref=e198]:
                  - generic:
                    - img
```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test';
  2   | 
  3   | const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173';
  4   | const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
  5   | 
  6   | test.describe('日志查询页面', () => {
  7   | 
  8   |   test.beforeEach(async ({ page }) => {
  9   |     await page.goto(BASE_URL);
  10  |     // 导航到日志查询页面
  11  |     const logMenu = page.locator('.el-menu-item:has-text("日志查询"), .el-menu-item[index="/log-analysis"]');
  12  |     if (await logMenu.count() > 0) {
  13  |       await logMenu.first().click();
  14  |     } else {
  15  |       // 直接访问
  16  |       await page.goto(`${BASE_URL}/log-analysis`);
  17  |     }
  18  |   });
  19  | 
  20  |   test('页面加载 - 查询表单可见', async ({ page }) => {
  21  |     // 验证查询按钮存在
  22  |     const queryButton = page.locator('button:has-text("查询")');
  23  |     await expect(queryButton).toBeVisible({ timeout: 10000 });
  24  |   });
  25  | 
  26  |   test('查询表单 - 时间范围选择', async ({ page }) => {
  27  |     // 选择时间范围
  28  |     const timeSelect = page.locator('.el-select:has(.el-input__wrapper)').first();
  29  |     await timeSelect.click();
  30  | 
  31  |     // 验证下拉选项
  32  |     const option = page.locator('.el-select-dropdown__item:has-text("最近")').first();
  33  |     await expect(option).toBeVisible();
  34  |   });
  35  | 
  36  |   test('查询表单 - 输入关键字', async ({ page }) => {
  37  |     // 输入查询关键字
  38  |     const keywordInput = page.locator('input[placeholder*="关键字"], input[placeholder*="keyword"]').first();
  39  |     if (await keywordInput.count() > 0) {
  40  |       await keywordInput.fill('ERROR');
  41  |       await expect(keywordInput).toHaveValue('ERROR');
  42  |     }
  43  |   });
  44  | 
  45  |   test('点击查询按钮', async ({ page }) => {
  46  |     const queryButton = page.locator('button:has-text("查询")');
> 47  |     await queryButton.click();
      |                       ^ Error: locator.click: Error: strict mode violation: locator('button:has-text("查询")') resolved to 3 elements:
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
  143 |     expect(response.ok).toBeTruthy();
  144 |     const data = await response.json();
  145 | 
  146 |     // 2. 验证响应结构
  147 |     expect(data.code).toBe(200);
```