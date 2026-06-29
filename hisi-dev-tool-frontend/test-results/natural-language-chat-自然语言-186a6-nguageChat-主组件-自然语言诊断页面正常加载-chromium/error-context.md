# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-language-chat.spec.ts >> 自然语言诊断组件测试 >> NaturalLanguageChat 主组件 >> 自然语言诊断页面正常加载
- Location: e2e\natural-language-chat.spec.ts:21:5

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('.session-panel, [class*="session"]').first()
Expected: visible
Timeout: 10000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 10000ms
  - waiting for locator('.session-panel, [class*="session"]').first()

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
  1   | import { test, expect, Page } from '@playwright/test'
  2   | 
  3   | /**
  4   |  * 自然语言诊断组件 E2E 测试
  5   |  * 测试 NaturalLanguageChat.vue 组件（会话管理版本）
  6   |  */
  7   | test.describe('自然语言诊断组件测试', () => {
  8   | 
  9   |   const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
  10  | 
  11  |   /**
  12  |    * NaturalLanguageChat 组件测试
  13  |    */
  14  |   test.describe('NaturalLanguageChat 主组件', () => {
  15  | 
  16  |     test.beforeEach(async ({ page }) => {
  17  |       await page.goto(`${BASE_URL}/natural-language`)
  18  |       await page.waitForLoadState('networkidle')
  19  |     })
  20  | 
  21  |     test('自然语言诊断页面正常加载', async ({ page }) => {
  22  |       // 验证侧边栏存在
  23  |       const sessionPanel = page.locator('.session-panel, [class*="session"]').first()
> 24  |       await expect(sessionPanel).toBeVisible({ timeout: 10000 })
      |                                  ^ Error: expect(locator).toBeVisible() failed
  25  | 
  26  |       // 验证标题包含"自然语言诊断"
  27  |       const title = page.locator('text=自然语言诊断').first()
  28  |       await expect(title).toBeVisible()
  29  |     })
  30  | 
  31  |     test('新建诊断会话按钮存在', async ({ page }) => {
  32  |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
  33  |       await expect(newSessionButton).toBeVisible()
  34  |       await expect(newSessionButton).toBeEnabled()
  35  |     })
  36  | 
  37  |     test('会话分组显示', async ({ page }) => {
  38  |       // 验证"进行中"分组
  39  |       const activeGroup = page.locator('text=进行中').first()
  40  |       await expect(activeGroup).toBeVisible()
  41  | 
  42  |       // 验证"已归档"分组
  43  |       const archivedGroup = page.locator('text=已归档').first()
  44  |       await expect(archivedGroup).toBeVisible()
  45  |     })
  46  | 
  47  |     test('点击新建诊断会话', async ({ page }) => {
  48  |       // 点击新建会话
  49  |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
  50  |       await newSessionButton.click()
  51  |       await page.waitForTimeout(2000)
  52  | 
  53  |       // 验证会话创建（检查是否有消息提示或会话列表更新）
  54  |       const message = page.locator('.el-message')
  55  |       const sessionItem = page.locator('.session-item, [class*="session-item"]').first()
  56  | 
  57  |       // 应该有消息提示或新会话
  58  |       const hasMessage = await message.isVisible().catch(() => false)
  59  |       const hasSession = await sessionItem.isVisible().catch(() => false)
  60  | 
  61  |       expect(hasMessage || hasSession).toBeTruthy()
  62  |     })
  63  | 
  64  |     test('空状态提示显示', async ({ page }) => {
  65  |       // 验证空状态提示
  66  |       const emptyState = page.locator('text=选择一个诊断会话').first()
  67  |       await expect(emptyState).toBeVisible()
  68  | 
  69  |       // 验证开始按钮
  70  |       const startButton = page.locator('button:has-text("开始自然语言诊断")')
  71  |       await expect(startButton).toBeVisible()
  72  |     })
  73  | 
  74  |     test('点击开始自然语言诊断创建会话', async ({ page }) => {
  75  |       const startButton = page.locator('button:has-text("开始自然语言诊断")')
  76  |       await startButton.click()
  77  |       await page.waitForTimeout(1000)
  78  | 
  79  |       // 验证会话创建
  80  |     })
  81  | 
  82  |     test('会话列表项点击', async ({ page }) => {
  83  |       // 先创建一个会话
  84  |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
  85  |       await newSessionButton.click()
  86  |       await page.waitForTimeout(1000)
  87  | 
  88  |       // 尝试点击会话项
  89  |       const sessionItem = page.locator('[class*="session-item"]').first()
  90  |       if (await sessionItem.isVisible()) {
  91  |         await sessionItem.click()
  92  |       }
  93  |     })
  94  |   })
  95  | 
  96  |   /**
  97  |    * 会话创建后功能测试
  98  |    */
  99  |   test.describe('会话功能测试', () => {
  100 | 
  101 |     test.beforeEach(async ({ page }) => {
  102 |       await page.goto(`${BASE_URL}/natural-language`)
  103 |       await page.waitForLoadState('networkidle')
  104 | 
  105 |       // 创建新会话
  106 |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
  107 |       await newSessionButton.click()
  108 |       await page.waitForTimeout(1500)
  109 |     })
  110 | 
  111 |     test('输入区域显示', async ({ page }) => {
  112 |       // 验证输入框存在
  113 |       const inputArea = page.locator('textarea').first()
  114 |       await expect(inputArea).toBeVisible()
  115 |     })
  116 | 
  117 |     test('输入内容并发送', async ({ page }) => {
  118 |       // 输入内容
  119 |       const inputArea = page.locator('textarea').first()
  120 |       await inputArea.fill('帮我分析这个NullPointerException错误')
  121 |       await expect(inputArea).toHaveValue('帮我分析这个NullPointerException错误')
  122 | 
  123 |       // 发送按钮应该可用
  124 |       const sendButton = page.locator('button:has-text("发送")')
```