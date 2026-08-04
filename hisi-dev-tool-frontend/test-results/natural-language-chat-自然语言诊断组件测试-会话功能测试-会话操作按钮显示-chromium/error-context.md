# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-language-chat.spec.ts >> 自然语言诊断组件测试 >> 会话功能测试 >> 会话操作按钮显示
- Location: e2e\natural-language-chat.spec.ts:153:5

# Error details

```
TimeoutError: locator.click: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('button:has-text("新建诊断会话")')

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
  24  |       await expect(sessionPanel).toBeVisible({ timeout: 10000 })
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
> 107 |       await newSessionButton.click()
      |                              ^ TimeoutError: locator.click: Timeout 10000ms exceeded.
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
  125 |       await expect(sendButton).toBeEnabled()
  126 |     })
  127 | 
  128 |     test('发送按钮初始禁用', async ({ page }) => {
  129 |       // 未输入内容时，发送按钮应该禁用
  130 |       const sendButton = page.locator('button:has-text("发送")')
  131 |       await expect(sendButton).toBeDisabled()
  132 |     })
  133 | 
  134 |     test('快捷建议按钮显示', async ({ page }) => {
  135 |       // 验证快捷建议区域
  136 |       const suggestions = page.locator('.intent-suggestions, text=快捷输入')
  137 |       if (await suggestions.isVisible()) {
  138 |         const suggestionButtons = suggestions.locator('button')
  139 |         const count = await suggestionButtons.count()
  140 |         expect(count).toBeGreaterThanOrEqual(0)
  141 |       }
  142 |     })
  143 | 
  144 |     test('Enter发送消息', async ({ page }) => {
  145 |       const inputArea = page.locator('textarea').first()
  146 |       await inputArea.fill('测试消息')
  147 |       await inputArea.press('Enter')
  148 |       await page.waitForTimeout(1000)
  149 | 
  150 |       // 验证消息已发送（检查消息历史或loading状态）
  151 |     })
  152 | 
  153 |     test('会话操作按钮显示', async ({ page }) => {
  154 |       // 验证导出、归档、删除按钮
  155 |       const exportButton = page.locator('button:has-text("导出")')
  156 |       const archiveButton = page.locator('button:has-text("归档")')
  157 |       const deleteButton = page.locator('button:has-text("删除")')
  158 | 
  159 |       if (await exportButton.isVisible()) {
  160 |         await expect(exportButton).toBeVisible()
  161 |       }
  162 |       if (await archiveButton.isVisible()) {
  163 |         await expect(archiveButton).toBeVisible()
  164 |       }
  165 |       if (await deleteButton.isVisible()) {
  166 |         await expect(deleteButton).toBeVisible()
  167 |       }
  168 |     })
  169 |   })
  170 | 
  171 |   /**
  172 |    * 多轮对话测试
  173 |    */
  174 |   test.describe('多轮对话测试', () => {
  175 | 
  176 |     test.beforeEach(async ({ page }) => {
  177 |       await page.goto(`${BASE_URL}/natural-language`)
  178 |       await page.waitForLoadState('networkidle')
  179 | 
  180 |       // 创建新会话
  181 |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
  182 |       await newSessionButton.click()
  183 |       await page.waitForTimeout(1500)
  184 |     })
  185 | 
  186 |     test('发送多条消息', async ({ page }) => {
  187 |       const inputArea = page.locator('textarea').first()
  188 | 
  189 |       // 第一轮
  190 |       await inputArea.fill('分析这个错误')
  191 |       await inputArea.press('Enter')
  192 |       await page.waitForTimeout(1500)
  193 | 
  194 |       // 第二轮
  195 |       await inputArea.fill('请详细解释')
  196 |       await inputArea.press('Enter')
  197 |       await page.waitForTimeout(1500)
  198 | 
  199 |       // 第三轮
  200 |       await inputArea.fill('给我修复建议')
  201 |       await inputArea.press('Enter')
  202 |       await page.waitForTimeout(1000)
  203 | 
  204 |       // 验证会话中有多轮对话
  205 |     })
  206 |   })
  207 | 
```