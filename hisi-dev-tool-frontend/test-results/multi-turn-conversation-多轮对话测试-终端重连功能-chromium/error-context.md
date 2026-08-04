# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: multi-turn-conversation.spec.ts >> 多轮对话测试 >> 终端重连功能
- Location: e2e\multi-turn-conversation.spec.ts:187:3

# Error details

```
TimeoutError: locator.textContent: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('.terminal-status .el-tag')

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
          - generic [ref=e79]:
            - generic [ref=e80]: 会话列表
            - button [ref=e81] [cursor=pointer]:
              - img [ref=e84]
          - generic [ref=e86]:
            - generic [ref=e88]:
              - img [ref=e91]
              - textbox "搜索会话" [ref=e93]
            - button "新建会话" [ref=e95] [cursor=pointer]:
              - generic [ref=e96]:
                - img [ref=e98]
                - text: 新建会话
          - generic [ref=e100]:
            - generic [ref=e101]:
              - generic [ref=e102] [cursor=pointer]:
                - img [ref=e104]
                - generic [ref=e106]: 进行中 (2)
              - generic [ref=e107]:
                - generic [ref=e109] [cursor=pointer]:
                  - generic [ref=e110]: 新会话
                  - generic [ref=e111]:
                    - generic "C:/Users/47583/projects/hisi_dev_tool v4.0/hisi-dev-tool" [ref=e112]: .../hisi_dev_tool v4.0/hisi-dev-tool
                    - generic [ref=e113]: 5月21日 09:18
                - generic [ref=e115] [cursor=pointer]:
                  - generic [ref=e116]: 新会话
                  - generic [ref=e118]: 4月24日 09:53
            - generic [ref=e120] [cursor=pointer]:
              - img [ref=e122]
              - generic [ref=e124]: 已归档 (0)
        - generic [ref=e125]:
          - generic [ref=e127]:
            - img [ref=e129]
            - generic [ref=e131]: Claude CLI Terminal
            - generic [ref=e132]: .../hisi_dev_tool v4.0/hisi-dev-tool
          - generic [ref=e136]:
            - generic:
              - textbox "Terminal input"
        - generic [ref=e137]:
          - generic [ref=e138]:
            - generic [ref=e139]:
              - img [ref=e141]
              - generic [ref=e143]: 会话信息
            - generic [ref=e144]:
              - generic [ref=e145]:
                - generic [ref=e146]: 标题
                - generic [ref=e147]: 新会话
              - generic [ref=e148]:
                - generic [ref=e149]: Session ID
                - generic [ref=e150]: "-"
              - generic [ref=e151]:
                - generic [ref=e152]: 工作目录
                - generic [ref=e153]: C:/Users/47583/projects/hisi_dev_tool v4.0/hisi-dev-tool
              - generic [ref=e154]:
                - generic [ref=e155]:
                  - img [ref=e157]
                  - text: Git 状态
                - generic [ref=e159]:
                  - generic [ref=e160]: v4.4
                  - generic [ref=e161]: 干净
          - generic [ref=e162]:
            - generic [ref=e163]:
              - img [ref=e165]
              - generic [ref=e167]: 终端状态
            - generic [ref=e168]:
              - generic [ref=e169]:
                - generic [ref=e170]: 连接状态
                - generic [ref=e172]: 已断开
              - generic [ref=e173]:
                - generic [ref=e174]: 终端尺寸
                - generic [ref=e175]: 80 x 22
              - generic [ref=e176]:
                - generic [ref=e177]: 运行时长
                - generic [ref=e178]: 00:00
              - generic [ref=e179]:
                - button "重连" [ref=e180] [cursor=pointer]:
                  - generic [ref=e181]:
                    - img [ref=e183]
                    - text: 重连
                - button "清屏" [ref=e185] [cursor=pointer]:
                  - generic [ref=e186]:
                    - img [ref=e188]
                    - text: 清屏
          - generic [ref=e190]:
            - generic [ref=e191]:
              - img [ref=e193]
              - generic [ref=e195]: 快捷命令
            - generic [ref=e196]:
              - button "/help" [ref=e197] [cursor=pointer]:
                - generic [ref=e198]: /help
              - button "/plugin" [ref=e199] [cursor=pointer]:
                - generic [ref=e200]: /plugin
              - button "/config" [ref=e201] [cursor=pointer]:
                - generic [ref=e202]: /config
              - button "/clear" [ref=e203] [cursor=pointer]:
                - generic [ref=e204]: /clear
              - button "分析日志错误" [ref=e205] [cursor=pointer]:
                - generic [ref=e206]: 分析日志错误
              - button "查询代码实现" [ref=e207] [cursor=pointer]:
                - generic [ref=e208]: 查询代码实现
              - button "解释错误原因" [ref=e209] [cursor=pointer]:
                - generic [ref=e210]: 解释错误原因
              - button "深入分析" [ref=e211] [cursor=pointer]:
                - generic [ref=e212]: 深入分析
          - generic [ref=e213]:
            - generic [ref=e214]:
              - img [ref=e216]
              - generic [ref=e218]: 会话统计
            - generic [ref=e219]:
              - generic [ref=e220]:
                - generic [ref=e221]: "2"
                - generic [ref=e222]: 活跃会话
              - generic [ref=e223]:
                - generic [ref=e224]: "0"
                - generic [ref=e225]: 归档会话
          - generic [ref=e226]:
            - generic [ref=e227]:
              - img [ref=e229]
              - generic [ref=e231]: 主题设置
            - generic [ref=e232]:
              - generic [ref=e233]:
                - generic [ref=e237] [cursor=pointer]: 深色科技
                - generic [ref=e241] [cursor=pointer]: Monokai 经典
                - generic [ref=e245] [cursor=pointer]: Dracula
                - generic [ref=e249] [cursor=pointer]: 浅色简约
                - generic [ref=e253] [cursor=pointer]: 护眼暖色
                - generic [ref=e257] [cursor=pointer]: 护眼绿色
              - generic [ref=e258]:
                - generic [ref=e259]: 自定义主色调
                - button "color picker" [ref=e260]:
                  - img [ref=e265] [cursor=pointer]
              - button "重置" [ref=e268] [cursor=pointer]:
                - generic [ref=e269]: 重置
```

# Test source

```ts
  105 |       // 验证会话选中状态
  106 |       await expect(sessionItems.nth(1)).toHaveClass(/active|selected/)
  107 | 
  108 |       // 再点击第一个会话切换回去
  109 |       await sessionItems.first().click()
  110 |       await page.waitForTimeout(1000)
  111 | 
  112 |       // 验证切换成功
  113 |       await expect(sessionItems.first()).toHaveClass(/active|selected/)
  114 |     }
  115 |   })
  116 | 
  117 |   /**
  118 |    * 测试场景5: 终端连接状态
  119 |    */
  120 |   test('终端WebSocket连接状态', async ({ page }) => {
  121 |     await page.goto(`${BASE_URL}/claude-terminal`)
  122 |     await page.waitForLoadState('networkidle')
  123 | 
  124 |     // 检查连接状态标签
  125 |     const statusTag = page.locator('.terminal-status .el-tag')
  126 |     await expect(statusTag).toBeVisible({ timeout: 15000 })
  127 | 
  128 |     // 获取状态文本
  129 |     const statusText = await statusTag.textContent()
  130 | 
  131 |     // 验证状态文本合理
  132 |     const validStates = ['已连接', '连接中...', '已断开', '连接错误']
  133 |     expect(validStates.some(state => statusText?.includes(state))).toBeTruthy()
  134 |   })
  135 | 
  136 |   /**
  137 |    * 测试场景6: 快捷命令功能
  138 |    */
  139 |   test('快捷命令按钮功能', async ({ page }) => {
  140 |     await page.goto(`${BASE_URL}/claude-terminal`)
  141 |     await page.waitForLoadState('networkidle')
  142 | 
  143 |     // 检查快捷命令区域
  144 |     const quickActions = page.locator('.quick-actions')
  145 |     await expect(quickActions).toBeVisible()
  146 | 
  147 |     // 验证快捷命令按钮
  148 |     const actionButtons = quickActions.locator('button')
  149 |     const buttonCount = await actionButtons.count()
  150 |     expect(buttonCount).toBeGreaterThan(0)
  151 | 
  152 |     // 检查具体命令按钮
  153 |     const helpButton = quickActions.locator('button:has-text("/help")')
  154 |     if (await helpButton.isVisible()) {
  155 |       await expect(helpButton).toBeEnabled()
  156 |     }
  157 | 
  158 |     const pluginButton = quickActions.locator('button:has-text("/plugin")')
  159 |     if (await pluginButton.isVisible()) {
  160 |       await expect(pluginButton).toBeEnabled()
  161 |     }
  162 |   })
  163 | 
  164 |   /**
  165 |    * 测试场景7: 终端清屏功能
  166 |    */
  167 |   test('终端清屏功能', async ({ page }) => {
  168 |     await page.goto(`${BASE_URL}/claude-terminal`)
  169 |     await page.waitForLoadState('networkidle')
  170 | 
  171 |     // 找到清屏按钮
  172 |     const clearButton = page.locator('button:has-text("清屏")')
  173 |     await expect(clearButton).toBeVisible()
  174 | 
  175 |     // 点击清屏
  176 |     await clearButton.click()
  177 |     await page.waitForTimeout(500)
  178 | 
  179 |     // 验证终端容器仍然可见
  180 |     const terminalContainer = page.locator('.terminal-container')
  181 |     await expect(terminalContainer).toBeVisible()
  182 |   })
  183 | 
  184 |   /**
  185 |    * 测试场景8: 重连功能
  186 |    */
  187 |   test('终端重连功能', async ({ page }) => {
  188 |     await page.goto(`${BASE_URL}/claude-terminal`)
  189 |     await page.waitForLoadState('networkidle')
  190 | 
  191 |     // 找到重连按钮
  192 |     const reconnectButton = page.locator('button:has-text("重连")')
  193 |     await expect(reconnectButton).toBeVisible()
  194 | 
  195 |     // 如果已连接，按钮应该禁用；如果断开，可以点击
  196 |     const isConnected = await page.locator('.terminal-status .el-tag:has-text("已连接")').isVisible()
  197 | 
  198 |     if (!isConnected) {
  199 |       // 断开状态可以重连
  200 |       await reconnectButton.click()
  201 |       await page.waitForTimeout(3000)
  202 | 
  203 |       // 验证状态变为连接中或已连接
  204 |       const statusTag = page.locator('.terminal-status .el-tag')
> 205 |       const statusText = await statusTag.textContent()
      |                                          ^ TimeoutError: locator.textContent: Timeout 10000ms exceeded.
  206 |       expect(['连接中...', '已连接'].some(s => statusText?.includes(s))).toBeTruthy()
  207 |     }
  208 |   })
  209 | 
  210 |   /**
  211 |    * 测试场景9: 多轮对话上下文保持
  212 |    */
  213 |   test('多轮对话上下文保持', async ({ page }) => {
  214 |     await page.goto(`${BASE_URL}/claude-terminal`)
  215 |     await page.waitForTimeout(5000)
  216 | 
  217 |     // 等待连接
  218 |     const connectedTag = page.locator('.terminal-status .el-tag:has-text("已连接")')
  219 |     const isConnected = await connectedTag.isVisible({ timeout: 15000 })
  220 | 
  221 |     if (isConnected) {
  222 |       // 模拟第一轮对话
  223 |       const terminalContainer = page.locator('.terminal-container')
  224 |       await expect(terminalContainer).toBeVisible()
  225 | 
  226 |       // 使用快捷命令模拟输入
  227 |       const helpButton = page.locator('.quick-actions button:has-text("/help")')
  228 |       if (await helpButton.isVisible()) {
  229 |         await helpButton.click()
  230 |         await page.waitForTimeout(2000)
  231 | 
  232 |         // 验证终端有输出（xterm内容）
  233 |         const terminalContent = await terminalContainer.locator('.xterm-rows, canvas').count()
  234 |         expect(terminalContent).toBeGreaterThan(0)
  235 |       }
  236 | 
  237 |       // 模拟第二轮对话
  238 |       const clearButton = page.locator('button:has-text("/clear")')
  239 |       if (await clearButton.isVisible()) {
  240 |         await clearButton.click()
  241 |         await page.waitForTimeout(1000)
  242 |       }
  243 |     }
  244 |   })
  245 | 
  246 |   /**
  247 |    * 测试场景10: 会话删除功能
  248 |    */
  249 |   test('删除会话功能', async ({ page }) => {
  250 |     await page.goto(`${BASE_URL}/claude-terminal`)
  251 |     await page.waitForLoadState('networkidle')
  252 | 
  253 |     // 获取初始会话数量
  254 |     const sessionItems = page.locator('.session-item')
  255 |     const initialCount = await sessionItems.count()
  256 | 
  257 |     if (initialCount > 1) {
  258 |       // 找到删除按钮（通常在会话项上）
  259 |       const deleteButton = sessionItems.first().locator('button:has-text("删除"), .delete-btn')
  260 |       if (await deleteButton.isVisible()) {
  261 |         await deleteButton.click()
  262 | 
  263 |         // 等待确认对话框或直接删除
  264 |         await page.waitForTimeout(1000)
  265 | 
  266 |         // 检查是否有确认对话框
  267 |         const confirmButton = page.locator('.el-message-box button:has-text("确认"), .el-dialog button:has-text("确定")')
  268 |         if (await confirmButton.isVisible()) {
  269 |           await confirmButton.click()
  270 |           await page.waitForTimeout(1000)
  271 |         }
  272 | 
  273 |         // 验证会话数量减少
  274 |         const newCount = await sessionItems.count()
  275 |         expect(newCount).toBeLessThanOrEqual(initialCount)
  276 |       }
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
```