# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: ram-status-session.spec.ts >> RAM Status - Historical Session Loading >> should verify backend API is accessible
- Location: e2e\ram-status-session.spec.ts:163:3

# Error details

```
Error: expect(received).toBeTruthy()

Received: false
```

# Test source

```ts
  66  | 
  67  |       // Verify login success (look for user info or logout option)
  68  |       await page.waitForTimeout(1000)
  69  |     }
  70  | 
  71  |     await page.screenshot({ path: 'test-results/02-after-login.png' })
  72  | 
  73  |     // Step 3: Navigate to /ram/status
  74  |     console.log('[Step 3] Navigating to /ram/status...')
  75  |     await page.goto(`${BASE_URL}/ram/status`)
  76  |     await page.waitForLoadState('networkidle')
  77  |     await page.screenshot({ path: 'test-results/03-status-list.png' })
  78  | 
  79  |     // Verify we're on the status session list page
  80  |     const listPageHeader = page.locator('.status-session-list, .el-card').first()
  81  |     await expect(listPageHeader).toBeVisible({ timeout: 10000 })
  82  | 
  83  |     // Step 4: Click first session card
  84  |     console.log('[Step 4] Clicking first session card...')
  85  |     const sessionCards = page.locator('.session-card')
  86  |     const cardCount = await sessionCards.count()
  87  | 
  88  |     console.log(`[Step 4] Found ${cardCount} session cards`)
  89  | 
  90  |     if (cardCount === 0) {
  91  |       console.log('[Step 4] No session cards found - skipping test')
  92  |       test.skip(true, 'No historical sessions available for testing')
  93  |       return
  94  |     }
  95  | 
  96  |     // Click first session card
  97  |     const firstCard = sessionCards.first()
  98  |     await firstCard.click()
  99  | 
  100 |     // Wait for navigation to status detail page
  101 |     await page.waitForURL(/\/ram\/status\//, { timeout: 10000 })
  102 |     await page.waitForLoadState('networkidle')
  103 |     await page.screenshot({ path: 'test-results/04-status-detail.png' })
  104 | 
  105 |     // Step 5: Verify completed status and report content
  106 |     console.log('[Step 5] Verifying report display...')
  107 | 
  108 |     // Wait for the status page to load (not showing loading animation)
  109 |     const statusPage = page.locator('.status-page').first()
  110 |     await expect(statusPage).toBeVisible({ timeout: 15000 })
  111 | 
  112 |     // Check for status tag - should show "已完成" for completed sessions
  113 |     const statusTag = page.locator('.status-page .el-tag').first()
  114 | 
  115 |     // Wait a bit for the report to load
  116 |     await page.waitForTimeout(2000)
  117 | 
  118 |     const tagText = await statusTag.textContent()
  119 |     console.log(`[Step 5] Status tag text: "${tagText}"`)
  120 | 
  121 |     // Verify we're not in loading state (no loading animation)
  122 |     const loadingIndicator = page.locator('.status-page .is-loading, .status-page .loading-container')
  123 |     const isLoading = await loadingIndicator.isVisible().catch(() => false)
  124 | 
  125 |     if (isLoading) {
  126 |       console.log('[Step 5] Report is still loading, waiting...')
  127 |       await page.waitForTimeout(5000)
  128 |       await page.screenshot({ path: 'test-results/05-after-wait.png' })
  129 |     }
  130 | 
  131 |     // Final verification: check for "已完成" tag or report content
  132 |     const finalTagText = await statusTag.textContent()
  133 |     const hasReport = await page.locator('.status-page .markdown-content, .status-page .report-container').isVisible().catch(() => false)
  134 |     const hasError = await page.locator('.status-page .error-container').isVisible().catch(() => false)
  135 | 
  136 |     console.log(`[Step 5] Final status: "${finalTagText}", hasReport: ${hasReport}, hasError: ${hasError}`)
  137 | 
  138 |     await page.screenshot({ path: 'test-results/06-final-state.png' })
  139 | 
  140 |     // Assertions
  141 |     if (finalTagText?.includes('已完成')) {
  142 |       // Success case: completed session with report
  143 |       expect(finalTagText).toContain('已完成')
  144 |       console.log('[SUCCESS] Session loaded successfully with "已完成" status')
  145 |     } else if (finalTagText?.includes('运行中')) {
  146 |       // Session is still running - this is expected for some sessions
  147 |       console.log('[INFO] Session is still running')
  148 |       expect(['运行中', '已完成', '失败']).toContain(finalTagText)
  149 |     } else if (finalTagText?.includes('失败')) {
  150 |       // Session failed
  151 |       console.log('[INFO] Session failed')
  152 |       expect(hasError).toBe(true)
  153 |     } else {
  154 |       // Unknown status - log and still pass if we have some content
  155 |       console.log(`[INFO] Unknown status tag: "${finalTagText}"`)
  156 |     }
  157 | 
  158 |     // Verify we have content (either report or error message, not just loading)
  159 |     const hasContent = hasReport || hasError || !isLoading
  160 |     expect(hasContent).toBe(true)
  161 |   })
  162 | 
  163 |   test('should verify backend API is accessible', async () => {
  164 |     // Health check
  165 |     const response = await fetch(`${BACKEND_URL}/api/health`)
> 166 |     expect(response.ok).toBeTruthy()
      |                         ^ Error: expect(received).toBeTruthy()
  167 |     console.log('[SUCCESS] Backend API is accessible')
  168 |   })
  169 | })
```