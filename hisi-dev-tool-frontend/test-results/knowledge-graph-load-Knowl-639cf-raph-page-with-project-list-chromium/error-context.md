# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: knowledge-graph-load.spec.ts >> Knowledge Graph Page Load Test >> should login and load knowledge graph page with project list
- Location: e2e\knowledge-graph-load.spec.ts:20:3

# Error details

```
Test timeout of 30000ms exceeded.
```

```
Error: page.waitForLoadState: Test timeout of 30000ms exceeded.
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
      - menuitem "用户管理" [ref=e63] [cursor=pointer]:
        - img [ref=e65]
        - generic [ref=e67]: 用户管理
      - menuitem "系统设置" [ref=e68] [cursor=pointer]:
        - img [ref=e70]
        - generic [ref=e72]: 系统设置
  - generic [ref=e73]:
    - generic [ref=e75]:
      - heading "HiSi DevTool" [level=1] [ref=e76]
      - button "root 管理员" [ref=e80] [cursor=pointer]:
        - generic [ref=e81]: root
        - generic [ref=e83]: 管理员
    - main [ref=e84]:
      - generic [ref=e85]:
        - generic [ref=e86]:
          - generic [ref=e88]:
            - generic [ref=e89]: 项目目录配置
            - generic [ref=e91]: 已配置
          - generic [ref=e93]:
            - generic [ref=e94]:
              - generic [ref=e95]: 项目目录
              - generic [ref=e97]:
                - textbox "项目目录" [ref=e99]:
                  - /placeholder: 请输入项目代码存放目录
                  - text: C:\Users\47583\projects\hisi_dev_tool v4.0
                - button "选择目录" [ref=e102] [cursor=pointer]:
                  - generic [ref=e103]: 选择目录
            - generic [ref=e105]:
              - button "保存配置" [ref=e106] [cursor=pointer]:
                - generic [ref=e107]: 保存配置
              - button "重置" [ref=e108] [cursor=pointer]:
                - generic [ref=e109]: 重置
        - alert [ref=e110]:
          - img [ref=e112]
          - generic [ref=e115]: 请在表格中勾选一个或多个项目以开始分析
        - generic [ref=e118]:
          - tablist [ref=e122]:
            - tab "本地项目" [selected] [ref=e124]
            - tab "远端项目" [ref=e125]
          - tabpanel "本地项目" [ref=e127]:
            - generic [ref=e128]:
              - generic [ref=e129]: 项目管理
              - generic [ref=e130]:
                - button "项目分组" [ref=e131] [cursor=pointer]:
                  - generic [ref=e132]:
                    - img [ref=e134]
                    - text: 项目分组
                - button "一键更新所有仓库" [ref=e136] [cursor=pointer]:
                  - generic [ref=e137]:
                    - img [ref=e139]
                    - text: 一键更新所有仓库
                - button "扫描仓库" [disabled]:
                  - generic:
                    - img
                  - generic:
                    - generic:
                      - img
                    - text: 扫描仓库
                - button "图谱屏蔽目录" [ref=e141] [cursor=pointer]:
                  - generic [ref=e142]:
                    - img [ref=e144]
                    - text: 图谱屏蔽目录
                - button "术语配置" [ref=e146] [cursor=pointer]:
                  - generic [ref=e147]:
                    - img [ref=e149]
                    - text: 术语配置
                - button "克隆项目" [ref=e151] [cursor=pointer]:
                  - generic [ref=e152]:
                    - img [ref=e154]
                    - text: 克隆项目
                - button "跨服务依赖构建 (0)" [disabled] [ref=e156]:
                  - generic [ref=e157]: 跨服务依赖构建 (0)
                - button "确认选择 (0)" [disabled] [ref=e158]:
                  - generic [ref=e159]:
                    - img [ref=e161]
                    - text: 确认选择 (0)
                - button "批量生成图谱 (0)" [disabled] [ref=e163]:
                  - generic [ref=e164]:
                    - img [ref=e166]
                    - text: 批量生成图谱 (0)
```

# Test source

```ts
  1   | /**
  2   |  * Knowledge Graph Page Load E2E Test
  3   |  *
  4   |  * Test steps:
  5   |  * 1. Navigate to http://localhost:5173
  6   |  * 2. Login (username: root, password: 123456)
  7   |  * 3. Click sidebar "知识图谱"
  8   |  * 4. Verify: Page shows project list (at least demo-django)
  9   |  */
  10  | 
  11  | import { test, expect } from '@playwright/test'
  12  | 
  13  | test.describe('Knowledge Graph Page Load Test', () => {
  14  |   test.beforeEach(async ({ page }) => {
  15  |     // Navigate to the application
  16  |     await page.goto('/')
  17  |     await page.waitForLoadState('networkidle')
  18  |   })
  19  | 
  20  |   test('should login and load knowledge graph page with project list', async ({ page }) => {
  21  |     // Step 1: Check if we're on the app (should redirect to /project)
  22  |     await expect(page).toHaveURL(/\/project/)
  23  | 
  24  |     // Step 2: Login flow
  25  |     // Find the login button in the header (UserDropdown component)
  26  |     const loginButton = page.getByRole('button', { name: '登录' })
  27  | 
  28  |     // Check if we need to login (button might not exist if already logged in)
  29  |     const loginButtonCount = await loginButton.count()
  30  | 
  31  |     if (loginButtonCount > 0) {
  32  |       // Click login button to open dialog
  33  |       await loginButton.click()
  34  | 
  35  |       // Wait for login dialog to appear
  36  |       const loginDialog = page.getByRole('dialog', { name: /登录/ })
  37  |       await expect(loginDialog).toBeVisible()
  38  | 
  39  |       // The dialog has tabs, ensure "登录" tab is active
  40  |       const loginTab = page.getByRole('tab', { name: '登录' })
  41  |       await expect(loginTab).toBeVisible()
  42  | 
  43  |       // Fill login form
  44  |       const usernameInput = loginDialog.getByPlaceholder('请输入用户名')
  45  |       const passwordInput = loginDialog.getByPlaceholder('请输入密码')
  46  | 
  47  |       await usernameInput.fill('root')
  48  |       await passwordInput.fill('123456')
  49  | 
  50  |       // Click login button inside dialog
  51  |       const submitLoginBtn = loginDialog.getByRole('button', { name: '登录' })
  52  |       await submitLoginBtn.click()
  53  | 
  54  |       // Wait for login success (dialog should close)
  55  |       await expect(loginDialog).not.toBeVisible({ timeout: 10000 })
  56  | 
  57  |       // Verify login success - username should appear in header
  58  |       const userDropdown = page.locator('.user-dropdown')
  59  |       await expect(userDropdown.getByText('root')).toBeVisible({ timeout: 5000 })
  60  |     }
  61  | 
  62  |     // Step 3: Navigate to Knowledge Graph via sidebar
  63  |     // First, ensure a project is selected (required for knowledge-graph to be enabled)
  64  |     // Go to project management page
  65  |     await page.goto('/project')
> 66  |     await page.waitForLoadState('networkidle')
      |                ^ Error: page.waitForLoadState: Test timeout of 30000ms exceeded.
  67  | 
  68  |     // Wait for the "本地项目" tab to be active (default)
  69  |     const localTab = page.getByRole('tab', { name: '本地项目' })
  70  |     await expect(localTab).toBeVisible({ timeout: 10000 })
  71  | 
  72  |     // Wait for collapse items (project groups) to load
  73  |     const collapseItems = page.locator('.el-collapse-item')
  74  |     await expect(collapseItems.first()).toBeVisible({ timeout: 15000 })
  75  | 
  76  |     // Take screenshot of project list
  77  |     await page.screenshot({ path: 'test-results/project-list.png', fullPage: false })
  78  | 
  79  |     // Expand the first collapse item if it's collapsed
  80  |     const firstCollapseHeader = collapseItems.first().locator('.el-collapse-item__header')
  81  |     const isExpanded = await collapseItems.first().locator('.el-collapse-item__content').isVisible()
  82  |     if (!isExpanded) {
  83  |       await firstCollapseHeader.click()
  84  |       await page.waitForTimeout(500)
  85  |     }
  86  | 
  87  |     // Check if there are projects in the table
  88  |     // Use more specific selector: table inside collapse-item content
  89  |     const projectTable = page.locator('.el-collapse-item__content .el-table').first()
  90  |     await expect(projectTable).toBeVisible({ timeout: 10000 })
  91  | 
  92  |     const projectRows = projectTable.locator('.el-table__row')
  93  |     const rowCount = await projectRows.count()
  94  | 
  95  |     console.log(`Found ${rowCount} projects in first group`)
  96  | 
  97  |     if (rowCount > 0) {
  98  |       // Look for demo-django project or select first available
  99  |       const allRows = await projectRows.all()
  100 | 
  101 |       // Find demo-django row
  102 |       let foundDemo = false
  103 |       for (const row of allRows) {
  104 |         const projectName = await row.locator('.project-name-cell span').first().textContent()
  105 |         if (projectName && projectName.includes('demo-django')) {
  106 |           // Click the "选择" button on this row
  107 |           const selectBtn = row.getByRole('button', { name: '选择' })
  108 |           await selectBtn.click()
  109 |           foundDemo = true
  110 |           console.log('Selected demo-django project')
  111 |           break
  112 |         }
  113 |       }
  114 | 
  115 |       if (!foundDemo && rowCount > 0) {
  116 |         // Select the first available project
  117 |         const firstRow = projectRows.first()
  118 |         const selectBtn = firstRow.getByRole('button', { name: '选择' })
  119 |         await selectBtn.click()
  120 |         console.log('Selected first available project')
  121 |       }
  122 | 
  123 |       // Wait for selection to be applied - success alert should appear
  124 |       const successAlert = page.locator('.el-alert--success')
  125 |       await expect(successAlert).toBeVisible({ timeout: 5000 })
  126 |     }
  127 | 
  128 |     // Now navigate to knowledge graph
  129 |     // Click on "知识图谱" in sidebar - it's a submenu
  130 |     const sidebarMenu = page.locator('.sidebar-menu')
  131 |     await expect(sidebarMenu).toBeVisible()
  132 | 
  133 |     // Find the knowledge graph submenu
  134 |     const kgSubMenu = sidebarMenu.locator('.el-sub-menu').filter({ hasText: '知识图谱' })
  135 |     await expect(kgSubMenu).toBeVisible({ timeout: 5000 })
  136 | 
  137 |     // Click to expand submenu
  138 |     await kgSubMenu.click()
  139 |     await page.waitForTimeout(500)
  140 | 
  141 |     // Click on "图谱总览" submenu item
  142 |     const graphOverview = page.getByRole('menuitem', { name: '图谱总览' })
  143 |     await graphOverview.click()
  144 | 
  145 |     // Wait for navigation
  146 |     await page.waitForURL(/\/knowledge-graph/, { timeout: 10000 })
  147 | 
  148 |     // Step 4: Verify knowledge graph page loaded
  149 |     // Check page title/header
  150 |     const pageHeader = page.locator('.knowledge-graph-view')
  151 |     await expect(pageHeader).toBeVisible({ timeout: 10000 })
  152 | 
  153 |     // Check for "知识图谱分析" card header
  154 |     const cardHeader = page.locator('.card-header').filter({ hasText: '知识图谱分析' })
  155 |     await expect(cardHeader).toBeVisible({ timeout: 5000 })
  156 | 
  157 |     // Take screenshot of knowledge graph page
  158 |     await page.screenshot({ path: 'test-results/knowledge-graph-page.png', fullPage: true })
  159 | 
  160 |     // Check for project selector - it has placeholder "选择项目"
  161 |     // The selector might already have a project selected, so we look for the el-select itself
  162 |     const projectSelector = page.locator('.header-actions .el-select').first()
  163 |     const selectorCount = await projectSelector.count()
  164 | 
  165 |     // Verify stats overview is visible (even if empty)
  166 |     const statsOverview = page.locator('.stats-overview')
```