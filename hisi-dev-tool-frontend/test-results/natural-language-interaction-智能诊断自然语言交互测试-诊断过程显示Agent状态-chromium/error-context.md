# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-language-interaction.spec.ts >> 智能诊断自然语言交互测试 >> 诊断过程显示Agent状态
- Location: e2e\natural-language-interaction.spec.ts:344:3

# Error details

```
TimeoutError: locator.fill: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('.input-section textarea').first()

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
  246 |     if (await clearButton.isVisible()) {
  247 |       await clearButton.click()
  248 | 
  249 |       // 验证输入被清空
  250 |       await expect(searchInput).toHaveValue('')
  251 |     }
  252 |   })
  253 | 
  254 |   /**
  255 |    * 测试场景9: Enter键触发搜索
  256 |    */
  257 |   test('Enter键触发搜索', async ({ page }) => {
  258 |     await page.goto(`${BASE_URL}/search`)
  259 |     await page.waitForLoadState('networkidle')
  260 | 
  261 |     // 输入搜索内容
  262 |     const searchInput = page.locator('.search-box input').first()
  263 |     await searchInput.fill('配置文件读取')
  264 | 
  265 |     // 按Enter键
  266 |     await searchInput.press('Enter')
  267 | 
  268 |     // 等待搜索响应
  269 |     await page.waitForTimeout(2000)
  270 | 
  271 |     // 验证搜索已触发（加载状态或结果）
  272 |     const searchButton = page.locator('button:has-text("搜索")')
  273 |     const isLoading = await searchButton.getAttribute('loading')
  274 | 
  275 |     // 或者检查结果面板状态
  276 |     const resultsPanel = page.locator('.search-results-panel')
  277 |     await expect(resultsPanel).toBeVisible({ timeout: 10000 })
  278 |   })
  279 | 
  280 |   /**
  281 |    * 测试场景10: 加载更多结果
  282 |    */
  283 |   test('加载更多搜索结果', async ({ page }) => {
  284 |     await page.goto(`${BASE_URL}/search`)
  285 |     await page.waitForLoadState('networkidle')
  286 | 
  287 |     // 执行搜索
  288 |     const searchInput = page.locator('.search-box input').first()
  289 |     await searchInput.fill('服务调用')
  290 |     await searchInput.press('Enter')
  291 | 
  292 |     await page.waitForTimeout(3000)
  293 | 
  294 |     // 检查是否有"加载更多"按钮
  295 |     const loadMoreButton = page.locator('.load-more button:has-text("加载更多")')
  296 | 
  297 |     if (await loadMoreButton.isVisible()) {
  298 |       await loadMoreButton.click()
  299 |       await page.waitForTimeout(2000)
  300 | 
  301 |       // 验证结果数量增加
  302 |       const resultItems = page.locator('.result-item')
  303 |       const itemCount = await resultItems.count()
  304 |       expect(itemCount).toBeGreaterThan(0)
  305 |     }
  306 |   })
  307 | })
  308 | 
  309 | /**
  310 |  * 智能诊断自然语言交互测试
  311 |  */
  312 | test.describe('智能诊断自然语言交互测试', () => {
  313 | 
  314 |   const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
  315 | 
  316 |   test.beforeEach(async ({ page }) => {
  317 |     await page.goto(`${BASE_URL}/diagnostic`)
  318 |     await page.waitForLoadState('networkidle')
  319 |   })
  320 | 
  321 |   /**
  322 |    * 测试场景: 用户输入问题描述进行诊断
  323 |    */
  324 |   test('用户输入自然语言问题描述', async ({ page }) => {
  325 |     // 验证诊断页面加载
  326 |     const diagnosisPanel = page.locator('.agent-diagnosis-panel')
  327 |     await expect(diagnosisPanel).toBeVisible({ timeout: 10000 })
  328 | 
  329 |     // 输入问题描述
  330 |     const textarea = page.locator('.input-section textarea, textarea').first()
  331 |     await textarea.fill('NullPointerException at UserService.login() line 123')
  332 | 
  333 |     // 验证输入值
  334 |     await expect(textarea).toHaveValue('NullPointerException at UserService.login() line 123')
  335 | 
  336 |     // 点击开始诊断按钮
  337 |     const startButton = page.locator('button:has-text("开始诊断")')
  338 |     await expect(startButton).toBeVisible()
  339 |   })
  340 | 
  341 |   /**
  342 |    * 测试场景: 诊断过程显示Agent执行状态
  343 |    */
  344 |   test('诊断过程显示Agent状态', async ({ page }) => {
  345 |     const textarea = page.locator('.input-section textarea').first()
> 346 |     await textarea.fill('StackOverflowError in recursive function')
      |                    ^ TimeoutError: locator.fill: Timeout 10000ms exceeded.
  347 | 
  348 |     // 开始诊断
  349 |     const startButton = page.locator('button:has-text("开始诊断")')
  350 |     await startButton.click()
  351 | 
  352 |     // 等待诊断开始
  353 |     await page.waitForTimeout(2000)
  354 | 
  355 |     // 验证进度区域
  356 |     const progressSection = page.locator('.progress-section')
  357 |     if (await progressSection.isVisible()) {
  358 |       // 验证进度显示
  359 |       const progressValue = progressSection.locator('.progress-value')
  360 |       await expect(progressValue).toBeVisible()
  361 |     }
  362 | 
  363 |     // 验证Agent列表
  364 |     const agentList = page.locator('.agent-list')
  365 |     if (await agentList.isVisible()) {
  366 |       const agentItems = agentList.locator('.agent-item')
  367 |       const agentCount = await agentItems.count()
  368 |       expect(agentCount).toBeGreaterThan(0)
  369 |     }
  370 |   })
  371 | })
```