# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-language-chat.spec.ts >> 自然语言诊断组件测试 >> 错误处理 >> 无项目选择时创建会话提示
- Location: e2e\natural-language-chat.spec.ts:286:5

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
  283 |    */
  284 |   test.describe('错误处理', () => {
  285 | 
  286 |     test('无项目选择时创建会话提示', async ({ page }) => {
  287 |       await page.goto(`${BASE_URL}/natural-language`)
  288 |       await page.waitForLoadState('networkidle')
  289 | 
  290 |       // 点击新建会话
  291 |       const newSessionButton = page.locator('button:has-text("新建诊断会话")')
> 292 |       await newSessionButton.click()
      |                              ^ TimeoutError: locator.click: Timeout 10000ms exceeded.
  293 |       await page.waitForTimeout(1000)
  294 | 
  295 |       // 检查是否有提示消息（如果没有选择项目）
  296 |       const warning = page.locator('.el-message:has-text("选择项目"), .el-message:has-text("项目")')
  297 |       // 消息可能存在也可能不存在
  298 |     })
  299 |   })
  300 | })
```