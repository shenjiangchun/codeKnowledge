# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: ram-clarity-hitl.spec.ts >> RAM Workflow Intervention Points >> RAM-04: CLARIFY intervention - verify clarify form and submit answers
- Location: e2e\ram-clarity-hitl.spec.ts:161:3

# Error details

```
TimeoutError: locator.fill: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('textarea[data-test="raw-input"], textarea').first()

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
  79  |       return match ? match[1] : null
  80  |     }
  81  | 
  82  |     if (
  83  |       interventionType === 'hitl' &&
  84  |       (statusText?.includes('确认') || statusText?.includes('confirm'))
  85  |     ) {
  86  |       await card.click()
  87  |       await page.waitForURL(/\/ram\/draft/, { timeout: 5000 })
  88  |       const url = page.url()
  89  |       const match = url.match(/\/ram\/draft\/([a-f0-9-]+)/)
  90  |       return match ? match[1] : null
  91  |     }
  92  |   }
  93  | 
  94  |   return null
  95  | }
  96  | 
  97  | /**
  98  |  * Mock SSE event for testing intervention UI without backend dependency
  99  |  * This injects a mock CLARIFY_REQUIRED or HITL_REQUIRED event into the page
  100 |  */
  101 | async function mockSSEIntervention(
  102 |   page: Page,
  103 |   sessionId: string,
  104 |   interventionType: 'clarify' | 'hitl',
  105 |   payload: Record<string, unknown>
  106 | ): Promise<void> {
  107 |   // Navigate to draft page first
  108 |   await page.goto(`${BASE_URL}/ram/draft/${sessionId}`)
  109 |   await page.waitForLoadState('networkidle')
  110 | 
  111 |   // Wait for the page to initialize the session
  112 |   await page.waitForTimeout(2000)
  113 | 
  114 |   // Inject mock event into the Vue app's session state
  115 |   await page.evaluate(
  116 |     ({ type, payloadData }) => {
  117 |       // Find the Vue app instance and trigger the SSE event handler
  118 |       // This simulates receiving an event from the backend
  119 |       const event = {
  120 |         seq: 999,
  121 |         type,
  122 |         payload: payloadData
  123 |       }
  124 | 
  125 |       // Dispatch a custom event that the composable can intercept
  126 |       // Or directly manipulate the Vue reactive state
  127 |       const app = document.querySelector('#app')
  128 |       if (app && (app as any).__vue_app__) {
  129 |         // Access the Pinia store or composable state directly
  130 |         console.log('[Mock SSE] Dispatching mock event:', event)
  131 |       }
  132 | 
  133 |       // Alternative: trigger via window event for custom handler
  134 |       window.dispatchEvent(
  135 |         new CustomEvent('mock-ram-event', {
  136 |           detail: event
  137 |         })
  138 |       )
  139 |     },
  140 |     {
  141 |       type: interventionType === 'clarify' ? 'CLARIFY_REQUIRED' : 'HITL_REQUIRED',
  142 |       payloadData: payload
  143 |     }
  144 |   )
  145 | 
  146 |   // Give Vue time to react to the state change
  147 |   await page.waitForTimeout(1000)
  148 | }
  149 | 
  150 | test.describe('RAM Workflow Intervention Points', () => {
  151 |   // Set longer timeout for long-running workflows
  152 |   test.setTimeout(90000)
  153 | 
  154 |   test.beforeEach(async ({ page }) => {
  155 |     await ensureLoggedIn(page)
  156 |   })
  157 | 
  158 |   // ============================================================
  159 |   // RAM-04: CLARIFY Intervention Test
  160 |   // ============================================================
  161 |   test('RAM-04: CLARIFY intervention - verify clarify form and submit answers', async ({ page }) => {
  162 |     // Step 1: Find or create a session with CLARIFY pending
  163 |     console.log('[RAM-04 Step 1] Looking for session with CLARIFY pending...')
  164 | 
  165 |     let sessionId = await findSessionWithPendingIntervention(page, 'clarify')
  166 | 
  167 |     if (!sessionId) {
  168 |       // No existing session with CLARIFY pending - create a new session
  169 |       // and navigate to its draft page
  170 |       console.log('[RAM-04 Step 1] No CLARIFY session found, creating new session...')
  171 | 
  172 |       // Navigate to RAM input page to start a new session
  173 |       await page.goto(`${BASE_URL}/ram/input`)
  174 |       await page.waitForLoadState('networkidle')
  175 |       await page.waitForTimeout(1000)
  176 | 
  177 |       // Fill in a minimal requirement to trigger a session
  178 |       const rawInputArea = page.locator('textarea[data-test="raw-input"], textarea').first()
> 179 |       await rawInputArea.fill('测试需求：优化代码性能')
      |                          ^ TimeoutError: locator.fill: Timeout 10000ms exceeded.
  180 | 
  181 |       // Click submit button
  182 |       const submitBtn = page.locator('button:has-text("开始分析"), button[type="submit"]').first()
  183 |       await submitBtn.click()
  184 | 
  185 |       // Wait for navigation to draft page
  186 |       await page.waitForURL(/\/ram\/draft/, { timeout: 30000 })
  187 |       const url = page.url()
  188 |       const match = url.match(/\/ram\/draft\/([a-f0-9-]+)/)
  189 |       sessionId = match ? match[1] : ''
  190 | 
  191 |       console.log(`[RAM-04 Step 1] Created new session: ${sessionId}`)
  192 |     }
  193 | 
  194 |     // Take screenshot of current state
  195 |     await page.screenshot({ path: 'test-results/ram-04-01-session-loaded.png' })
  196 | 
  197 |     // Step 2: Verify clarify alert bar appears (R-12: alert bar instead of auto-popup)
  198 |     console.log('[RAM-04 Step 2] Checking for clarify alert bar...')
  199 | 
  200 |     // Check for status tag showing 'clarify'
  201 |     const statusTag = page.locator('.ram-draft-view .el-tag').first()
  202 |     const statusText = await statusTag.textContent().catch(() => '')
  203 |     console.log(`[RAM-04 Step 2] Current status: "${statusText}"`)
  204 | 
  205 |     // Look for clarify alert bar (R-12 implementation)
  206 |     const clarifyAlert = page.locator('.clarify-alert, .el-alert:has-text("需要澄清")')
  207 | 
  208 |     // If clarify state is not showing, we need to wait for SSE event
  209 |     // The workflow might still be running before reaching CLARIFY_REQUIRED
  210 |     if (!await clarifyAlert.isVisible({ timeout: 5000 }).catch(() => false)) {
  211 |       console.log('[RAM-04 Step 2] Clarify alert not visible, waiting for SSE event...')
  212 | 
  213 |       // Wait up to 60 seconds for CLARIFY_REQUIRED event
  214 |       // The backend sends SSE events as the workflow progresses
  215 |       try {
  216 |         await expect(statusTag).toContainText('clarify', { timeout: 60000 })
  217 |       } catch {
  218 |         // If timeout, the session might not trigger CLARIFY in this workflow
  219 |         // This is expected for some workflows - mark as informational
  220 |         console.log('[RAM-04 Step 2] No CLARIFY event received within timeout')
  221 |         await page.screenshot({ path: 'test-results/ram-04-02-no-clarify.png' })
  222 |         test.skip(true, 'No CLARIFY_REQUIRED event received - workflow may not require clarification')
  223 |         return
  224 |       }
  225 |     }
  226 | 
  227 |     // Now verify clarify alert bar is visible
  228 |     await expect(clarifyAlert).toBeVisible({ timeout: 5000 })
  229 |     console.log('[RAM-04 Step 2] Clarify alert bar is visible')
  230 | 
  231 |     await page.screenshot({ path: 'test-results/ram-04-02-clarify-alert.png' })
  232 | 
  233 |     // Step 3: Open clarify modal by clicking the alert button
  234 |     console.log('[RAM-04 Step 3] Opening clarify modal...')
  235 | 
  236 |     const openClarifyBtn = clarifyAlert.locator('button:has-text("打开")')
  237 |     await openClarifyBtn.click()
  238 | 
  239 |     // Wait for ClarifyModal to appear
  240 |     const clarifyModal = page.locator('[data-test="clarify-modal"], .el-dialog:has-text("需要澄清")')
  241 |     await expect(clarifyModal).toBeVisible({ timeout: 5000 })
  242 | 
  243 |     console.log('[RAM-04 Step 3] ClarifyModal is visible')
  244 | 
  245 |     await page.screenshot({ path: 'test-results/ram-04-03-clarify-modal.png' })
  246 | 
  247 |     // Step 4: Verify clarify modal content
  248 |     console.log('[RAM-04 Step 4] Verifying clarify modal structure...')
  249 | 
  250 |     // Check for nodeName display (if available)
  251 |     const nodeNameSection = clarifyModal.locator('.clarify-node, p:has-text("节点")')
  252 |     const hasNodeName = await nodeNameSection.isVisible().catch(() => false)
  253 |     console.log(`[RAM-04 Step 4] NodeName visible: ${hasNodeName}`)
  254 | 
  255 |     // Check for question fields
  256 |     // The modal renders fields based on ClarifySchema.questions array
  257 |     const questionFields = clarifyModal.locator('[data-test^="clarify-field-"], .el-form-item')
  258 |     const fieldCount = await questionFields.count()
  259 |     console.log(`[RAM-04 Step 4] Found ${fieldCount} question fields`)
  260 | 
  261 |     // Verify there are at least some question fields
  262 |     expect(fieldCount).toBeGreaterThan(0)
  263 | 
  264 |     // Step 5: Fill in answers and submit
  265 |     console.log('[RAM-04 Step 5] Filling in answers...')
  266 | 
  267 |     // Get all textarea inputs in the clarify modal
  268 |     const answerInputs = clarifyModal.locator('textarea')
  269 |     const inputCount = await answerInputs.count()
  270 | 
  271 |     for (let i = 0; i < inputCount; i++) {
  272 |       const input = answerInputs.nth(i)
  273 |       await input.fill(`测试回答 ${i + 1}: 这是针对问题的澄清说明`)
  274 |     }
  275 | 
  276 |     await page.screenshot({ path: 'test-results/ram-04-04-answers-filled.png' })
  277 | 
  278 |     // Step 6: Submit clarify form
  279 |     console.log('[RAM-04 Step 6] Submitting clarify answers...')
```