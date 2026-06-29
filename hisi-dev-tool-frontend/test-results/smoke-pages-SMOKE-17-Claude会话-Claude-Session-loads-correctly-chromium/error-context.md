# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: smoke-pages.spec.ts >> SMOKE-17: Claude会话 (Claude Session) loads correctly
- Location: e2e\smoke-pages.spec.ts:403:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: .user-dropdown, .username, text=root >> nth=0
Expected: visible
Error: Unexpected token "=" while parsing css selector ".user-dropdown, .username, text=root". Did you mean to CSS.escape it?

Call log:
  - Expect "toBeVisible" with timeout 10000ms
  - waiting for .user-dropdown, .username, text=root >> nth=0

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
      - generic [ref=e77]:
        - generic [ref=e78]:
          - generic [ref=e80]:
            - generic [ref=e81]: 项目目录配置
            - generic [ref=e83]: 已配置
          - generic [ref=e85]:
            - generic [ref=e86]:
              - generic [ref=e87]: 项目目录
              - generic [ref=e89]:
                - textbox "项目目录" [ref=e91]:
                  - /placeholder: 请输入项目代码存放目录
                  - text: C:\Users\47583\projects\hisi_dev_tool v4.0
                - button "选择目录" [ref=e94] [cursor=pointer]:
                  - generic [ref=e95]: 选择目录
            - generic [ref=e97]:
              - button "保存配置" [ref=e98] [cursor=pointer]:
                - generic [ref=e99]: 保存配置
              - button "重置" [ref=e100] [cursor=pointer]:
                - generic [ref=e101]: 重置
        - alert [ref=e102]:
          - img [ref=e104]
          - generic [ref=e107]: 请在表格中勾选一个或多个项目以开始分析
        - generic [ref=e110]:
          - tablist [ref=e114]:
            - tab "本地项目" [selected] [ref=e116]
            - tab "远端项目" [ref=e117]
          - tabpanel "本地项目" [ref=e119]:
            - generic [ref=e120]:
              - generic [ref=e121]: 项目管理
              - generic [ref=e122]:
                - button "项目分组" [ref=e123] [cursor=pointer]:
                  - generic [ref=e124]:
                    - img [ref=e126]
                    - text: 项目分组
                - button "一键更新所有仓库" [ref=e128] [cursor=pointer]:
                  - generic [ref=e129]:
                    - img [ref=e131]
                    - text: 一键更新所有仓库
                - button "扫描仓库" [disabled]:
                  - generic:
                    - img
                  - generic:
                    - generic:
                      - img
                    - text: 扫描仓库
                - button "图谱屏蔽目录" [ref=e133] [cursor=pointer]:
                  - generic [ref=e134]:
                    - img [ref=e136]
                    - text: 图谱屏蔽目录
                - button "术语配置" [ref=e138] [cursor=pointer]:
                  - generic [ref=e139]:
                    - img [ref=e141]
                    - text: 术语配置
                - button "克隆项目" [ref=e143] [cursor=pointer]:
                  - generic [ref=e144]:
                    - img [ref=e146]
                    - text: 克隆项目
                - button "跨服务依赖构建 (0)" [disabled] [ref=e148]:
                  - generic [ref=e149]: 跨服务依赖构建 (0)
                - button "确认选择 (0)" [disabled] [ref=e150]:
                  - generic [ref=e151]:
                    - img [ref=e153]
                    - text: 确认选择 (0)
                - button "批量生成图谱 (0)" [disabled] [ref=e155]:
                  - generic [ref=e156]:
                    - img [ref=e158]
                    - text: 批量生成图谱 (0)
```

# Test source

```ts
  1   | import { test, expect, Page } from '@playwright/test'
  2   | 
  3   | /**
  4   |  * Smoke Tests for HiSi DevTool Pages
  5   |  * Tests that each page renders without errors
  6   |  *
  7   |  * Test ID Convention: SMOKE-XX
  8   |  * Pages tested based on AppSidebar.vue and router/index.ts
  9   |  */
  10  | 
  11  | // Test configuration - run in parallel
  12  | test.describe.configure({ mode: 'parallel' })
  13  | 
  14  | /**
  15  |  * Helper function to perform login
  16  |  * Uses root/123456 admin credentials
  17  |  */
  18  | async function loginAsAdmin(page: Page) {
  19  |   await page.goto('/')
  20  |   await page.waitForLoadState('domcontentloaded')
  21  | 
  22  |   // Check if login dialog appears or if already logged in
  23  |   const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: /登录|Login/ })
  24  |   const dialogCount = await loginDialog.count()
  25  | 
  26  |   if (dialogCount > 0) {
  27  |     // Fill login form
  28  |     const usernameInput = page.locator('.el-dialog input').first()
  29  |     const passwordInput = page.locator('.el-dialog input').nth(1)
  30  | 
  31  |     await usernameInput.fill('root')
  32  |     await passwordInput.fill('123456')
  33  | 
  34  |     // Submit login
  35  |     await page.locator('.el-dialog button[type="submit"], .el-dialog button:has-text("登录")').first().click()
  36  | 
  37  |     // Wait for dialog to close
  38  |     await expect(loginDialog).not.toBeVisible({ timeout: 15000 })
  39  |     await page.waitForTimeout(1000)
  40  |   }
  41  | 
  42  |   // Verify login success
  43  |   const userIndicator = page.locator('.user-dropdown, .username, text=root')
> 44  |   await expect(userIndicator.first()).toBeVisible({ timeout: 10000 })
      |                                       ^ Error: expect(locator).toBeVisible() failed
  45  | }
  46  | 
  47  | /**
  48  |  * Helper to collect console errors during page load
  49  |  */
  50  | async function collectConsoleErrors(page: Page): Promise<string[]> {
  51  |   const errors: string[] = []
  52  |   page.on('console', msg => {
  53  |     if (msg.type() === 'error') {
  54  |       errors.push(msg.text())
  55  |     }
  56  |   })
  57  |   return errors
  58  | }
  59  | 
  60  | /**
  61  |  * Helper to verify page loaded successfully
  62  |  */
  63  | async function verifyPageLoad(page: Page, route: string, titlePattern: RegExp | string) {
  64  |   await page.goto(route)
  65  |   await page.waitForLoadState('networkidle')
  66  | 
  67  |   // Verify page title
  68  |   await expect(page).toHaveTitle(titlePattern)
  69  | 
  70  |   // Verify no critical console errors (filter out favicon and minor warnings)
  71  |   const errors = await collectConsoleErrors(page)
  72  |   const criticalErrors = errors.filter(e =>
  73  |     !e.includes('favicon') &&
  74  |     !e.includes('net::ERR_') &&
  75  |     !e.includes('404') &&
  76  |     !e.includes('Warning:')
  77  |   )
  78  |   expect(criticalErrors).toHaveLength(0)
  79  | }
  80  | 
  81  | // =====================================================
  82  | // SMOKE-01: Skill Market - 技能市场
  83  | // =====================================================
  84  | test('SMOKE-01: 技能市场 (Skill Market) loads correctly', async ({ page }) => {
  85  |   await loginAsAdmin(page)
  86  |   const errors = await collectConsoleErrors(page)
  87  | 
  88  |   await page.goto('/skill-market')
  89  |   await page.waitForLoadState('networkidle')
  90  | 
  91  |   await expect(page).toHaveTitle(/技能市场|HiSi|DevTool/)
  92  |   await expect(page.locator('text=/技能市场|Skill Market/i')).toBeVisible({ timeout: 10000 })
  93  | 
  94  |   const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  95  |   expect(criticalErrors).toHaveLength(0)
  96  | })
  97  | 
  98  | // =====================================================
  99  | // SMOKE-02: KG Skills Kit - KG Skills套件
  100 | // =====================================================
  101 | test('SMOKE-02: KG Skills套件 loads correctly', async ({ page }) => {
  102 |   await loginAsAdmin(page)
  103 |   const errors = await collectConsoleErrors(page)
  104 | 
  105 |   await page.goto('/kg-skills-kit')
  106 |   await page.waitForLoadState('networkidle')
  107 | 
  108 |   await expect(page).toHaveTitle(/KG Skills|HiSi|DevTool/)
  109 |   await expect(page.locator('text=/KG Skills|套件/i')).toBeVisible({ timeout: 10000 })
  110 | 
  111 |   const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  112 |   expect(criticalErrors).toHaveLength(0)
  113 | })
  114 | 
  115 | // =====================================================
  116 | // SMOKE-03: Claude Terminal - Claude终端
  117 | // =====================================================
  118 | test('SMOKE-03: Claude终端 (Claude Terminal) loads correctly', async ({ page }) => {
  119 |   await loginAsAdmin(page)
  120 |   const errors = await collectConsoleErrors(page)
  121 | 
  122 |   await page.goto('/claude-terminal')
  123 |   await page.waitForLoadState('networkidle')
  124 | 
  125 |   await expect(page).toHaveTitle(/Claude|终端|HiSi|DevTool/)
  126 |   await expect(page.locator('text=/Claude|终端|Terminal/i')).toBeVisible({ timeout: 10000 })
  127 | 
  128 |   const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  129 |   expect(criticalErrors).toHaveLength(0)
  130 | })
  131 | 
  132 | // =====================================================
  133 | // SMOKE-04: APM Debug - APM调试
  134 | // =====================================================
  135 | test('SMOKE-04: APM调试 (APM Debug) loads correctly', async ({ page }) => {
  136 |   await loginAsAdmin(page)
  137 |   const errors = await collectConsoleErrors(page)
  138 | 
  139 |   await page.goto('/apm-debug')
  140 |   await page.waitForLoadState('networkidle')
  141 | 
  142 |   await expect(page).toHaveTitle(/APM|调试|HiSi|DevTool/)
  143 |   await expect(page.locator('text=/APM|调试|Debug/i')).toBeVisible({ timeout: 10000 })
  144 | 
```