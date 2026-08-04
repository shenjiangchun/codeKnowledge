# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: multi-turn-conversation.spec.ts >> 用户干预响应测试 >> 用户切换会话作为干预
- Location: e2e\multi-turn-conversation.spec.ts:366:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('.terminal-status .el-tag')
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for locator('.terminal-status .el-tag')

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
- main:
  - text: 会话列表
  - button:
    - img
  - img
  - textbox "搜索会话"
  - button "新建会话":
    - img
    - text: 新建会话
  - img
  - text: 进行中 (2) 新会话 .../hisi_dev_tool v4.0/hisi-dev-tool 5月21日 09:18 新会话 4月24日 09:53
  - button "归档":
    - img
  - button "删除":
    - img
  - img
  - text: 已归档 (0)
  - img
  - text: Claude CLI Terminal
  - textbox "Terminal input"
  - img
  - text: 会话信息 标题 新会话 Session ID - 工作目录 -
  - img
  - text: 终端状态 连接状态 已连接 终端尺寸 80 x 22 运行时长 00:04
  - button "重连" [disabled]:
    - img
    - text: 重连
  - button "清屏":
    - img
    - text: 清屏
  - img
  - text: 快捷命令
  - button "/help"
  - button "/plugin"
  - button "/config"
  - button "/clear"
  - button "分析日志错误"
  - button "查询代码实现"
  - button "解释错误原因"
  - button "深入分析"
  - img
  - text: 会话统计 2 活跃会话 0 归档会话
  - img
  - text: 主题设置 深色科技 Monokai 经典 Dracula 浅色简约 护眼暖色 护眼绿色 自定义主色调
  - button "color picker":
    - img
  - button "重置"
- alert:
  - img
  - paragraph: "???????????"
  - img
```

# Test source

```ts
  277 |     }
  278 |   })
  279 | 
  280 |   /**
  281 |    * 测试场景11: 工作目录显示
  282 |    */
  283 |   test('工作目录显示', async ({ page }) => {
  284 |     await page.goto(`${BASE_URL}/claude-terminal`)
  285 |     await page.waitForLoadState('networkidle')
  286 | 
  287 |     // 检查工作目录显示
  288 |     const workingDir = page.locator('.working-directory')
  289 |     if (await workingDir.isVisible()) {
  290 |       const dirText = await workingDir.textContent()
  291 |       // 工作目录应该有内容
  292 |       expect(dirText?.length).toBeGreaterThan(0)
  293 |     }
  294 |   })
  295 | 
  296 |   /**
  297 |    * 测试场景12: 终端标题显示
  298 |    */
  299 |   test('终端标题和状态显示', async ({ page }) => {
  300 |     await page.goto(`${BASE_URL}/claude-terminal`)
  301 |     await page.waitForLoadState('networkidle')
  302 | 
  303 |     // 验证终端标题
  304 |     const terminalTitle = page.locator('.terminal-title')
  305 |     await expect(terminalTitle).toBeVisible()
  306 | 
  307 |     // 验证标题包含图标和文本
  308 |     const titleIcon = terminalTitle.locator('.el-icon')
  309 |     await expect(titleIcon).toBeVisible()
  310 | 
  311 |     const titleText = terminalTitle.locator('span')
  312 |     const titleContent = await titleText.textContent()
  313 |     expect(titleContent?.length).toBeGreaterThan(0)
  314 |   })
  315 | })
  316 | 
  317 | /**
  318 |  * 用户干预响应测试
  319 |  */
  320 | test.describe('用户干预响应测试', () => {
  321 | 
  322 |   const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
  323 | 
  324 |   test.beforeEach(async ({ page }) => {
  325 |     await page.goto(`${BASE_URL}/claude-terminal`)
  326 |     await page.waitForTimeout(3000)
  327 |   })
  328 | 
  329 |   /**
  330 |    * 测试场景: 用户中断当前操作
  331 |    */
  332 |   test('用户可以中断当前操作', async ({ page }) => {
  333 |     // 检查取消按钮（在诊断场景中）
  334 |     const cancelButton = page.locator('button:has-text("取消")')
  335 |     if (await cancelButton.isVisible()) {
  336 |       // 如果有取消按钮，验证可以点击
  337 |       const isEnabled = await cancelButton.isEnabled()
  338 |       expect(typeof isEnabled).toBe('boolean')
  339 |     }
  340 |   })
  341 | 
  342 |   /**
  343 |    * 测试场景: 用户输入干预命令
  344 |    */
  345 |   test('用户可以输入干预命令', async ({ page }) => {
  346 |     const terminalContainer = page.locator('.terminal-container')
  347 |     await expect(terminalContainer).toBeVisible()
  348 | 
  349 |     // 验证快捷命令区域可用
  350 |     const quickActions = page.locator('.quick-actions')
  351 |     await expect(quickActions).toBeVisible()
  352 | 
  353 |     // 所有快捷命令应该可点击
  354 |     const actionButtons = quickActions.locator('button')
  355 |     const count = await actionButtons.count()
  356 | 
  357 |     for (let i = 0; i < count; i++) {
  358 |       const button = actionButtons.nth(i)
  359 |       await expect(button).toBeEnabled()
  360 |     }
  361 |   })
  362 | 
  363 |   /**
  364 |    * 测试场景: 用户切换会话干预
  365 |    */
  366 |   test('用户切换会话作为干预', async ({ page }) => {
  367 |     const sessionItems = page.locator('.session-item')
  368 |     const count = await sessionItems.count()
  369 | 
  370 |     if (count >= 2) {
  371 |       // 切换会话是一种干预行为
  372 |       await sessionItems.nth(1).click()
  373 |       await page.waitForTimeout(500)
  374 | 
  375 |       // 验证状态更新
  376 |       const statusTag = page.locator('.terminal-status .el-tag')
> 377 |       await expect(statusTag).toBeVisible()
      |                               ^ Error: expect(locator).toBeVisible() failed
  378 |     }
  379 |   })
  380 | })
  381 | 
  382 | /**
  383 |  * WebSocket 连接测试
  384 |  */
  385 | test.describe('WebSocket连接测试', () => {
  386 | 
  387 |   const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
  388 |   const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'
  389 | 
  390 |   test('WebSocket终端连接', async ({ page }) => {
  391 |     await page.goto(`${BASE_URL}/claude-terminal`)
  392 |     await page.waitForTimeout(5000)
  393 | 
  394 |     // 在浏览器环境中测试WebSocket
  395 |     const wsResult = await page.evaluate(async () => {
  396 |       return new Promise((resolve) => {
  397 |         try {
  398 |           const ws = new WebSocket(`ws://localhost:8080/ws/terminal`)
  399 |           let connected = false
  400 | 
  401 |           ws.onopen = () => {
  402 |             connected = true
  403 |             ws.close()
  404 |           }
  405 | 
  406 |           ws.onclose = () => {
  407 |             resolve({ connected })
  408 |           }
  409 | 
  410 |           ws.onerror = () => {
  411 |             resolve({ connected: false })
  412 |           }
  413 | 
  414 |           // 10秒超时
  415 |           setTimeout(() => {
  416 |             if (!connected) {
  417 |               ws.close()
  418 |               resolve({ connected: false })
  419 |             }
  420 |           }, 10000)
  421 |         } catch (e: any) {
  422 |           resolve({ connected: false, error: e.message })
  423 |         }
  424 |       })
  425 |     })
  426 | 
  427 |     // WebSocket连接可能成功或失败，取决于后端状态
  428 |     expect(typeof wsResult.connected).toBe('boolean')
  429 |   })
  430 | })
```