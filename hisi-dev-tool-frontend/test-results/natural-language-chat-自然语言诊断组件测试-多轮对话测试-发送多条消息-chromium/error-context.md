# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-language-chat.spec.ts >> 自然语言诊断组件测试 >> 多轮对话测试 >> 发送多条消息
- Location: e2e\natural-language-chat.spec.ts:186:5

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
> 182 |       await newSessionButton.click()
      |                              ^ TimeoutError: locator.click: Timeout 10000ms exceeded.
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
  208 |   /**
  209 |    * 完整用户流程测试
  210 |    */
  211 |   test.describe('完整用户流程', () => {
  212 | 
  213 |     test('完整诊断流程', async ({ page }) => {
  214 |       // 1. 访问页面
  215 |       await page.goto(`${BASE_URL}/natural-language`)
  216 |       await page.waitForLoadState('networkidle')
  217 | 
  218 |       // 2. 验证页面加载
  219 |       const title = page.locator('text=自然语言诊断').first()
  220 |       await expect(title).toBeVisible()
  221 | 
  222 |       // 3. 创建新会话
  223 |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
  224 |       await newSessionButton.click()
  225 |       await page.waitForTimeout(1500)
  226 | 
  227 |       // 4. 输入问题
  228 |       const inputArea = page.locator('textarea').first()
  229 |       await inputArea.fill('帮我分析NullPointerException错误')
  230 |       await inputArea.press('Enter')
  231 |       await page.waitForTimeout(2000)
  232 | 
  233 |       // 5. 验证消息历史更新
  234 |     })
  235 | 
  236 |     test('使用快捷建议', async ({ page }) => {
  237 |       await page.goto(`${BASE_URL}/natural-language`)
  238 |       await page.waitForLoadState('networkidle')
  239 | 
  240 |       // 创建会话
  241 |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
  242 |       await newSessionButton.click()
  243 |       await page.waitForTimeout(1500)
  244 | 
  245 |       // 点击快捷建议（如果有）
  246 |       const suggestionButton = page.locator('.intent-suggestions button, button:has-text("分析")').first()
  247 |       if (await suggestionButton.isVisible()) {
  248 |         await suggestionButton.click()
  249 |         await page.waitForTimeout(500)
  250 | 
  251 |         // 验证输入框被填充
  252 |         const inputArea = page.locator('textarea').first()
  253 |         const inputValue = await inputArea.inputValue()
  254 |         expect(inputValue.length).toBeGreaterThan(0)
  255 |       }
  256 |     })
  257 |   })
  258 | 
  259 |   /**
  260 |    * 侧边栏导航测试
  261 |    */
  262 |   test.describe('侧边栏导航', () => {
  263 | 
  264 |     test('自然语言诊断子菜单', async ({ page }) => {
  265 |       await page.goto(`${BASE_URL}/natural-language`)
  266 |       await page.waitForLoadState('networkidle')
  267 | 
  268 |       // 验证子菜单项
  269 |       const simplifiedOption = page.locator('text=简化版')
  270 |       const advancedOption = page.locator('text=高级版')
  271 | 
  272 |       // 子菜单可能默认展开或需要点击
  273 |       const menuToggle = page.locator('[class*="menu"]:has-text("自然语言诊断")').first()
  274 |       if (await menuToggle.isVisible()) {
  275 |         await menuToggle.click()
  276 |         await page.waitForTimeout(500)
  277 |       }
  278 |     })
  279 |   })
  280 | 
  281 |   /**
  282 |    * 错误处理测试
```