# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-language-interaction.spec.ts >> 智能诊断自然语言交互测试 >> 用户输入自然语言问题描述
- Location: e2e\natural-language-interaction.spec.ts:324:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('.agent-diagnosis-panel')
Expected: visible
Timeout: 10000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 10000ms
  - waiting for locator('.agent-diagnosis-panel')

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
  227 |       // 滑动到不同位置（模拟拖动）
  228 |       const sliderBar = thresholdSlider.locator('.el-slider__bar')
  229 |       await expect(sliderBar).toBeVisible()
  230 |     }
  231 |   })
  232 | 
  233 |   /**
  234 |    * 测试场景8: 清空搜索
  235 |    */
  236 |   test('清空搜索输入', async ({ page }) => {
  237 |     await page.goto(`${BASE_URL}/search`)
  238 |     await page.waitForLoadState('networkidle')
  239 | 
  240 |     // 输入搜索内容
  241 |     const searchInput = page.locator('.search-box input').first()
  242 |     await searchInput.fill('测试搜索内容')
  243 | 
  244 |     // 点击清空按钮
  245 |     const clearButton = page.locator('.el-input__clear')
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
> 327 |     await expect(diagnosisPanel).toBeVisible({ timeout: 10000 })
      |                                  ^ Error: expect(locator).toBeVisible() failed
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
  346 |     await textarea.fill('StackOverflowError in recursive function')
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