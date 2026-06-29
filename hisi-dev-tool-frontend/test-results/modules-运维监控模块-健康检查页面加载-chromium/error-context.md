# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: modules.spec.ts >> 运维监控模块 >> 健康检查页面加载
- Location: e2e\modules.spec.ts:74:3

# Error details

```
Error: expect(page).toHaveTitle(expected) failed

Expected pattern: /运维监控/
Received string:  "hisi-dev-tool-frontend"
Timeout: 5000ms

Call log:
  - Expect "toHaveTitle" with timeout 5000ms
    12 × unexpected value "hisi-dev-tool-frontend"

```

```yaml
- complementary:
  - menubar:
    - menuitem "技能市场":
      - img
      - text: 技能市场
    - menuitem "KG Skills 套件":
      - img
      - text: KG Skills 套件
    - menuitem "Claude 终端":
      - img
      - text: Claude 终端
    - menuitem "APM 调试":
      - img
      - text: APM 调试
    - menuitem "增强检索":
      - img
      - text: 增强检索
    - menuitem "日志分析":
      - img
      - text: 日志分析
    - menuitem "知识图谱":
      - img
      - text: 知识图谱
    - menuitem "需求分析大师":
      - img
      - text: 需求分析大师
    - menuitem "项目现状分析":
      - img
      - text: 项目现状分析
    - menuitem "合入分析":
      - img
      - text: 合入分析
    - menuitem "项目管理":
      - img
      - text: 项目管理
    - menuitem "系统设置":
      - img
      - text: 系统设置
- heading "HiSi DevTool" [level=1]
- button "登录"
- main
```

# Test source

```ts
  1  | import { test, expect } from '@playwright/test'
  2  | 
  3  | /**
  4  |  * 日志分析模块测试
  5  |  */
  6  | test.describe('日志分析模块', () => {
  7  |   test.beforeEach(async ({ page }) => {
  8  |     await page.goto('/log-analysis')
  9  |     await page.waitForLoadState('networkidle')
  10 |   })
  11 | 
  12 |   test('页面加载', async ({ page }) => {
  13 |     // 检查标题
  14 |     await expect(page).toHaveTitle(/日志分析/)
  15 | 
  16 |     // 检查主要元素存在
  17 |     await expect(page.locator('text=日志分析')).toBeVisible()
  18 |   })
  19 | 
  20 |   test('查询表单', async ({ page }) => {
  21 |     // 检查查询按钮存在
  22 |     const queryButton = page.locator('button:has-text("查询")')
  23 |     await expect(queryButton).toBeVisible()
  24 |   })
  25 | })
  26 | 
  27 | /**
  28 |  * 调用链分析模块测试
  29 |  */
  30 | test.describe('调用链分析模块', () => {
  31 |   test.beforeEach(async ({ page }) => {
  32 |     await page.goto('/call-chain')
  33 |     await page.waitForLoadState('networkidle')
  34 |   })
  35 | 
  36 |   test('项目列表页面加载', async ({ page }) => {
  37 |     await expect(page).toHaveTitle(/调用链分析/)
  38 |     await expect(page.locator('text=调用链分析')).toBeVisible()
  39 |   })
  40 | 
  41 |   test('项目列表为空时显示提示', async ({ page }) => {
  42 |     // 等待数据加载
  43 |     await page.waitForTimeout(1000)
  44 | 
  45 |     // 检查是否显示空状态或项目列表
  46 |     const content = page.locator('body')
  47 |     await expect(content).toBeVisible()
  48 |   })
  49 | })
  50 | 
  51 | /**
  52 |  * 项目管理模块测试
  53 |  */
  54 | test.describe('项目管理模块', () => {
  55 |   test.beforeEach(async ({ page }) => {
  56 |     await page.goto('/project')
  57 |     await page.waitForLoadState('networkidle')
  58 |   })
  59 | 
  60 |   test('项目管理页面加载', async ({ page }) => {
  61 |     await expect(page).toHaveTitle(/项目管理/)
  62 |   })
  63 | })
  64 | 
  65 | /**
  66 |  * 运维监控模块测试
  67 |  */
  68 | test.describe('运维监控模块', () => {
  69 |   test.beforeEach(async ({ page }) => {
  70 |     await page.goto('/ops')
  71 |     await page.waitForLoadState('networkidle')
  72 |   })
  73 | 
  74 |   test('健康检查页面加载', async ({ page }) => {
> 75 |     await expect(page).toHaveTitle(/运维监控/)
     |                        ^ Error: expect(page).toHaveTitle(expected) failed
  76 |   })
  77 | })
```