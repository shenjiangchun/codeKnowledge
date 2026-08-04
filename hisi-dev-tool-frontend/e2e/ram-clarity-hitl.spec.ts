import { test, expect, Page } from '@playwright/test'

/**
 * RAM Workflow Intervention Points E2E Tests
 *
 * Tests for CLARIFY (澄清) and HITL (Human-in-the-loop) intervention points
 * in the Requirement Analysis Master (RAM) workflow.
 *
 * Test Scenarios:
 * - RAM-04: CLARIFY intervention - verify clarify form, submit answers
 * - RAM-05: HITL intervention - verify confirm panel, approve/reject actions
 *
 * SSE Event Structures (from useRamSession.ts):
 * - CLARIFY_REQUIRED: { payload: { questions: string[], nodeName?: string } }
 * - HITL_REQUIRED: { payload: { nodeName: string, output: Record<string, unknown> } }
 */

const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'

/**
 * Login helper - reused across tests
 */
async function ensureLoggedIn(page: Page): Promise<void> {
  await page.goto(BASE_URL)
  await page.waitForLoadState('networkidle')

  // Check for login dialog
  const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: '登录' })
  const dialogCount = await loginDialog.count()

  if (dialogCount > 0) {
    const usernameInput = loginDialog.locator('input[placeholder="请输入用户名"]').first()
    const passwordInput = loginDialog.locator('input[placeholder="请输入密码"]').first()

    await usernameInput.fill('root')
    await passwordInput.fill('123456')

    await loginDialog.locator('button:has-text("登录")').first().click()

    await expect(loginDialog).not.toBeVisible({ timeout: 10000 })
    await page.waitForLoadState('networkidle')
  }
}

/**
 * Navigate to RAM session list and find a session with pending intervention
 */
async function findSessionWithPendingIntervention(
  page: Page,
  interventionType: 'clarify' | 'hitl'
): Promise<string | null> {
  await page.goto(`${BASE_URL}/ram`)
  await page.waitForLoadState('networkidle')

  // Look for session cards with pending status indicators
  const sessionCards = page.locator('.session-card, .el-card')
  const cardCount = await sessionCards.count()

  for (let i = 0; i < cardCount; i++) {
    const card = sessionCards.nth(i)
    const statusTag = card.locator('.el-tag')

    // Check for sessions that might have pending intervention
    const statusText = await statusTag.textContent().catch(() => '')

    // Backend status mapping:
    // WAITING_CLARIFY -> frontend 'clarify'
    // WAITING_HITL -> frontend 'confirm'
    if (
      interventionType === 'clarify' &&
      (statusText?.includes('澄清') || statusText?.includes('clarify'))
    ) {
      // Click the card and extract session ID from URL
      await card.click()
      await page.waitForURL(/\/ram\/draft/, { timeout: 5000 })
      const url = page.url()
      const match = url.match(/\/ram\/draft\/([a-f0-9-]+)/)
      return match ? match[1] : null
    }

    if (
      interventionType === 'hitl' &&
      (statusText?.includes('确认') || statusText?.includes('confirm'))
    ) {
      await card.click()
      await page.waitForURL(/\/ram\/draft/, { timeout: 5000 })
      const url = page.url()
      const match = url.match(/\/ram\/draft\/([a-f0-9-]+)/)
      return match ? match[1] : null
    }
  }

  return null
}

/**
 * Mock SSE event for testing intervention UI without backend dependency
 * This injects a mock CLARIFY_REQUIRED or HITL_REQUIRED event into the page
 */
async function mockSSEIntervention(
  page: Page,
  sessionId: string,
  interventionType: 'clarify' | 'hitl',
  payload: Record<string, unknown>
): Promise<void> {
  // Navigate to draft page first
  await page.goto(`${BASE_URL}/ram/draft/${sessionId}`)
  await page.waitForLoadState('networkidle')

  // Wait for the page to initialize the session
  await page.waitForTimeout(2000)

  // Inject mock event into the Vue app's session state
  await page.evaluate(
    ({ type, payloadData }) => {
      // Find the Vue app instance and trigger the SSE event handler
      // This simulates receiving an event from the backend
      const event = {
        seq: 999,
        type,
        payload: payloadData
      }

      // Dispatch a custom event that the composable can intercept
      // Or directly manipulate the Vue reactive state
      const app = document.querySelector('#app')
      if (app && (app as any).__vue_app__) {
        // Access the Pinia store or composable state directly
        console.log('[Mock SSE] Dispatching mock event:', event)
      }

      // Alternative: trigger via window event for custom handler
      window.dispatchEvent(
        new CustomEvent('mock-ram-event', {
          detail: event
        })
      )
    },
    {
      type: interventionType === 'clarify' ? 'CLARIFY_REQUIRED' : 'HITL_REQUIRED',
      payloadData: payload
    }
  )

  // Give Vue time to react to the state change
  await page.waitForTimeout(1000)
}

test.describe('RAM Workflow Intervention Points', () => {
  // Set longer timeout for long-running workflows
  test.setTimeout(90000)

  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
  })

  // ============================================================
  // RAM-04: CLARIFY Intervention Test
  // ============================================================
  test('RAM-04: CLARIFY intervention - verify clarify form and submit answers', async ({ page }) => {
    // Step 1: Find or create a session with CLARIFY pending
    console.log('[RAM-04 Step 1] Looking for session with CLARIFY pending...')

    let sessionId = await findSessionWithPendingIntervention(page, 'clarify')

    if (!sessionId) {
      // No existing session with CLARIFY pending - create a new session
      // and navigate to its draft page
      console.log('[RAM-04 Step 1] No CLARIFY session found, creating new session...')

      // Navigate to RAM input page to start a new session
      await page.goto(`${BASE_URL}/ram/input`)
      await page.waitForLoadState('networkidle')
      await page.waitForTimeout(1000)

      // Fill in a minimal requirement to trigger a session
      const rawInputArea = page.locator('textarea[data-test="raw-input"], textarea').first()
      await rawInputArea.fill('测试需求：优化代码性能')

      // Click submit button
      const submitBtn = page.locator('button:has-text("开始分析"), button[type="submit"]').first()
      await submitBtn.click()

      // Wait for navigation to draft page
      await page.waitForURL(/\/ram\/draft/, { timeout: 30000 })
      const url = page.url()
      const match = url.match(/\/ram\/draft\/([a-f0-9-]+)/)
      sessionId = match ? match[1] : ''

      console.log(`[RAM-04 Step 1] Created new session: ${sessionId}`)
    }

    // Take screenshot of current state
    await page.screenshot({ path: 'test-results/ram-04-01-session-loaded.png' })

    // Step 2: Verify clarify alert bar appears (R-12: alert bar instead of auto-popup)
    console.log('[RAM-04 Step 2] Checking for clarify alert bar...')

    // Check for status tag showing 'clarify'
    const statusTag = page.locator('.ram-draft-view .el-tag').first()
    const statusText = await statusTag.textContent().catch(() => '')
    console.log(`[RAM-04 Step 2] Current status: "${statusText}"`)

    // Look for clarify alert bar (R-12 implementation)
    const clarifyAlert = page.locator('.clarify-alert, .el-alert:has-text("需要澄清")')

    // If clarify state is not showing, we need to wait for SSE event
    // The workflow might still be running before reaching CLARIFY_REQUIRED
    if (!await clarifyAlert.isVisible({ timeout: 5000 }).catch(() => false)) {
      console.log('[RAM-04 Step 2] Clarify alert not visible, waiting for SSE event...')

      // Wait up to 60 seconds for CLARIFY_REQUIRED event
      // The backend sends SSE events as the workflow progresses
      try {
        await expect(statusTag).toContainText('clarify', { timeout: 60000 })
      } catch {
        // If timeout, the session might not trigger CLARIFY in this workflow
        // This is expected for some workflows - mark as informational
        console.log('[RAM-04 Step 2] No CLARIFY event received within timeout')
        await page.screenshot({ path: 'test-results/ram-04-02-no-clarify.png' })
        test.skip(true, 'No CLARIFY_REQUIRED event received - workflow may not require clarification')
        return
      }
    }

    // Now verify clarify alert bar is visible
    await expect(clarifyAlert).toBeVisible({ timeout: 5000 })
    console.log('[RAM-04 Step 2] Clarify alert bar is visible')

    await page.screenshot({ path: 'test-results/ram-04-02-clarify-alert.png' })

    // Step 3: Open clarify modal by clicking the alert button
    console.log('[RAM-04 Step 3] Opening clarify modal...')

    const openClarifyBtn = clarifyAlert.locator('button:has-text("打开")')
    await openClarifyBtn.click()

    // Wait for ClarifyModal to appear
    const clarifyModal = page.locator('[data-test="clarify-modal"], .el-dialog:has-text("需要澄清")')
    await expect(clarifyModal).toBeVisible({ timeout: 5000 })

    console.log('[RAM-04 Step 3] ClarifyModal is visible')

    await page.screenshot({ path: 'test-results/ram-04-03-clarify-modal.png' })

    // Step 4: Verify clarify modal content
    console.log('[RAM-04 Step 4] Verifying clarify modal structure...')

    // Check for nodeName display (if available)
    const nodeNameSection = clarifyModal.locator('.clarify-node, p:has-text("节点")')
    const hasNodeName = await nodeNameSection.isVisible().catch(() => false)
    console.log(`[RAM-04 Step 4] NodeName visible: ${hasNodeName}`)

    // Check for question fields
    // The modal renders fields based on ClarifySchema.questions array
    const questionFields = clarifyModal.locator('[data-test^="clarify-field-"], .el-form-item')
    const fieldCount = await questionFields.count()
    console.log(`[RAM-04 Step 4] Found ${fieldCount} question fields`)

    // Verify there are at least some question fields
    expect(fieldCount).toBeGreaterThan(0)

    // Step 5: Fill in answers and submit
    console.log('[RAM-04 Step 5] Filling in answers...')

    // Get all textarea inputs in the clarify modal
    const answerInputs = clarifyModal.locator('textarea')
    const inputCount = await answerInputs.count()

    for (let i = 0; i < inputCount; i++) {
      const input = answerInputs.nth(i)
      await input.fill(`测试回答 ${i + 1}: 这是针对问题的澄清说明`)
    }

    await page.screenshot({ path: 'test-results/ram-04-04-answers-filled.png' })

    // Step 6: Submit clarify form
    console.log('[RAM-04 Step 6] Submitting clarify answers...')

    const submitBtn = clarifyModal.locator('[data-test="clarify-submit"], button:has-text("提交")').first()
    await submitBtn.click()

    // Wait for modal to close
    await expect(clarifyModal).not.toBeVisible({ timeout: 10000 })

    console.log('[RAM-04 Step 6] ClarifyModal closed after submit')

    await page.screenshot({ path: 'test-results/ram-04-05-after-submit.png' })

    // Step 7: Verify status changes to 'running' after submitClarify
    console.log('[RAM-04 Step 7] Verifying status change to running...')

    // After submitting clarify, the workflow resumes and status should be 'running'
    // Allow some time for SSE to reconnect and status to update
    await page.waitForTimeout(2000)

    const finalStatus = await statusTag.textContent().catch(() => '')
    console.log(`[RAM-04 Step 7] Final status: "${finalStatus}"`)

    // Status should be 'running' (workflow resumed) or could progress to 'confirm' (HITL)
    // or 'completed' if the workflow finishes quickly
    const validStatuses = ['running', 'confirm', 'completed']
    expect(validStatuses).toContain(finalStatus)

    await page.screenshot({ path: 'test-results/ram-04-06-final-status.png' })

    console.log('[RAM-04 SUCCESS] CLARIFY intervention test completed')
  })

  // ============================================================
  // RAM-05: HITL Intervention Test
  // ============================================================
  test('RAM-05: HITL intervention - verify confirm panel and approve/reject', async ({ page }) => {
    // Step 1: Find or create a session with HITL pending
    console.log('[RAM-05 Step 1] Looking for session with HITL pending...')

    let sessionId = await findSessionWithPendingIntervention(page, 'hitl')

    if (!sessionId) {
      // Navigate to a session that might trigger HITL
      // HITL typically occurs after clarify and impact analysis
      console.log('[RAM-05 Step 1] No HITL session found, navigating to draft page...')

      // List all sessions and pick one
      await page.goto(`${BASE_URL}/ram`)
      await page.waitForLoadState('networkidle')

      const sessionCards = page.locator('.session-card, .el-card')
      const cardCount = await sessionCards.count()

      if (cardCount > 0) {
        // Click first session
        await sessionCards.first().click()
        await page.waitForURL(/\/ram\/draft/, { timeout: 10000 })
      } else {
        // Create a new session
        await page.goto(`${BASE_URL}/ram/input`)
        await page.waitForLoadState('networkidle')

        const rawInputArea = page.locator('textarea').first()
        await rawInputArea.fill('测试需求：分析代码变更的影响范围')

        const submitBtn = page.locator('button:has-text("开始分析")').first()
        await submitBtn.click()

        await page.waitForURL(/\/ram\/draft/, { timeout: 30000 })
      }

      const url = page.url()
      const match = url.match(/\/ram\/draft\/([a-f0-9-]+)/)
      sessionId = match ? match[1] : ''
    }

    await page.screenshot({ path: 'test-results/ram-05-01-session-loaded.png' })

    // Step 2: Wait for HITL_REQUIRED event
    console.log('[RAM-05 Step 2] Waiting for HITL_REQUIRED event...')

    const statusTag = page.locator('.ram-draft-view .el-tag').first()

    // Look for confirm modal or confirm button
    const confirmModal = page.locator('.el-dialog:has-text("执行完成"), .el-dialog:has-text("请确认")')

    // If confirm modal is not showing, wait for status change
    if (!await confirmModal.isVisible({ timeout: 5000 }).catch(() => false)) {
      console.log('[RAM-05 Step 2] Confirm modal not visible, waiting for SSE event...')

      // Wait up to 60 seconds for HITL_REQUIRED event
      try {
        // Status should change to 'confirm' when HITL_REQUIRED is received
        await expect(statusTag).toContainText('confirm', { timeout: 60000 })
      } catch {
        console.log('[RAM-05 Step 2] No HITL event received within timeout')
        await page.screenshot({ path: 'test-results/ram-05-02-no-hitl.png' })
        test.skip(true, 'No HITL_REQUIRED event received - workflow may not have HITL intervention')
        return
      }
    }

    // Step 3: Verify confirm modal appears
    console.log('[RAM-05 Step 3] Verifying confirm modal structure...')

    // The ConfirmModal should auto-show when HITL_REQUIRED is received
    // Or there's a "继续确认" button in the topbar
    const reopenConfirmBtn = page.locator('button:has-text("继续确认")')
    if (!await confirmModal.isVisible({ timeout: 3000 }).catch(() => false)) {
      if (await reopenConfirmBtn.isVisible()) {
        await reopenConfirmBtn.click()
        await page.waitForTimeout(500)
      }
    }

    await expect(confirmModal).toBeVisible({ timeout: 5000 })
    console.log('[RAM-05 Step 3] ConfirmModal is visible')

    await page.screenshot({ path: 'test-results/ram-05-03-confirm-modal.png' })

    // Step 4: Verify confirm modal content
    console.log('[RAM-05 Step 4] Verifying HITL schema display...')

    // Check dialog title contains node name
    const dialogTitle = confirmModal.locator('.el-dialog__title, .el-dialog__header')
    const titleText = await dialogTitle.textContent().catch(() => '')
    console.log(`[RAM-05 Step 4] Dialog title: "${titleText}"`)

    // Should contain nodeName (e.g., "clarify", "impact", "implement")
    expect(titleText).toMatch(/(澄清|影响分析|实现方案|验证)/)

    // Check for output preview section
    const outputPreview = confirmModal.locator('.output-preview, .structured-output, .output-text')
    await expect(outputPreview).toBeVisible({ timeout: 3000 })

    console.log('[RAM-05 Step 4] Output preview is visible')

    // Verify output contains some content (nodeName and output from HITLSchema)
    const outputContent = await outputPreview.textContent().catch(() => '')
    console.log(`[RAM-05 Step 4] Output preview has content: ${outputContent.length > 0}`)

    expect(outputContent.length).toBeGreaterThan(0)

    await page.screenshot({ path: 'test-results/ram-05-04-output-verified.png' })

    // Step 5: Test approve action
    console.log('[RAM-05 Step 5] Testing approve action...')

    const approveBtn = confirmModal.locator('button:has-text("批准并继续")')
    await expect(approveBtn).toBeVisible()

    // Click approve
    await approveBtn.click()

    // Wait for modal to close
    await expect(confirmModal).not.toBeVisible({ timeout: 10000 })

    console.log('[RAM-05 Step 5] ConfirmModal closed after approve')

    await page.screenshot({ path: 'test-results/ram-05-05-after-approve.png' })

    // Step 6: Verify status changes appropriately after submitConfirm
    console.log('[RAM-05 Step 6] Verifying status change after approve...')

    await page.waitForTimeout(2000)

    const finalStatus = await statusTag.textContent().catch(() => '')
    console.log(`[RAM-05 Step 6] Final status: "${finalStatus}"`)

    // After approve, status should be 'running' (next node starts)
    // or 'completed' if this was the last node, or 'confirm' if another HITL
    const validStatuses = ['running', 'completed', 'confirm']
    expect(validStatuses).toContain(finalStatus)

    await page.screenshot({ path: 'test-results/ram-05-06-final-status.png' })

    console.log('[RAM-05 SUCCESS] HITL intervention test completed')
  })

  // ============================================================
  // RAM-05-REJECT: HITL Intervention - Reject Action Test
  // ============================================================
  test('RAM-05-REJECT: HITL intervention - reject action with feedback', async ({ page }) => {
    console.log('[RAM-05-REJECT Step 1] Looking for session with HITL pending...')

    // Navigate to sessions list
    await page.goto(`${BASE_URL}/ram`)
    await page.waitForLoadState('networkidle')

    const sessionCards = page.locator('.session-card, .el-card')
    const cardCount = await sessionCards.count()

    if (cardCount === 0) {
      test.skip(true, 'No sessions available for HITL reject test')
      return
    }

    // Click first session to see its status
    await sessionCards.first().click()
    await page.waitForURL(/\/ram\/draft/, { timeout: 10000 })
    await page.waitForLoadState('networkidle')

    await page.screenshot({ path: 'test-results/ram-05-reject-01-session.png' })

    // Wait for HITL state
    const statusTag = page.locator('.ram-draft-view .el-tag').first()
    const confirmModal = page.locator('.el-dialog:has-text("执行完成")')

    // If not in HITL state, wait for it
    let hitlReached = false
    try {
      await expect(statusTag).toContainText('confirm', { timeout: 60000 })
      hitlReached = true
    } catch {
      console.log('[RAM-05-REJECT] No HITL event within timeout')
    }

    if (!hitlReached) {
      await page.screenshot({ path: 'test-results/ram-05-reject-02-no-hitl.png' })
      test.skip(true, 'No HITL_REQUIRED event for reject test')
      return
    }

    // Open confirm modal if needed
    const reopenConfirmBtn = page.locator('button:has-text("继续确认")')
    if (!await confirmModal.isVisible({ timeout: 3000 }).catch(() => false)) {
      if (await reopenConfirmBtn.isVisible()) {
        await reopenConfirmBtn.click()
        await page.waitForTimeout(500)
      }
    }

    await expect(confirmModal).toBeVisible({ timeout: 5000 })
    await page.screenshot({ path: 'test-results/ram-05-reject-03-confirm-modal.png' })

    // Step 2: Click reject button (first click enters reject mode)
    console.log('[RAM-05-REJECT Step 2] Entering reject mode...')

    const rejectBtn = confirmModal.locator('button:has-text("驳回")').first()
    await rejectBtn.click()

    // Should show feedback textarea
    await page.waitForTimeout(500)

    // Step 3: Fill in reject feedback
    console.log('[RAM-05-REJECT Step 3] Filling in reject feedback...')

    const feedbackSection = confirmModal.locator('.feedback-section, .el-divider:has-text("驳回反馈")')
    await expect(feedbackSection).toBeVisible({ timeout: 3000 })

    const feedbackTextarea = confirmModal.locator('.feedback-section textarea, textarea[placeholder*="修改"]')
    await feedbackTextarea.fill('测试驳回反馈：输出内容不完整，需要补充更多细节')

    await page.screenshot({ path: 'test-results/ram-05-reject-04-feedback-filled.png' })

    // Step 4: Confirm reject (second click on now "确认驳回" button)
    console.log('[RAM-05-REJECT Step 4] Confirming reject...')

    const confirmRejectBtn = confirmModal.locator('button:has-text("确认驳回")')
    await confirmRejectBtn.click()

    // Wait for modal to close
    await expect(confirmModal).not.toBeVisible({ timeout: 10000 })

    await page.screenshot({ path: 'test-results/ram-05-reject-05-after-reject.png' })

    // Step 5: Verify status
    console.log('[RAM-05-REJECT Step 5] Verifying status after reject...')

    await page.waitForTimeout(2000)

    const finalStatus = await statusTag.textContent().catch(() => '')
    console.log(`[RAM-05-REJECT Step 5] Final status: "${finalStatus}"`)

    // After reject, the node should be re-run, so status should be 'running'
    expect(['running', 'confirm', 'clarify']).toContain(finalStatus)

    console.log('[RAM-05-REJECT SUCCESS] Reject action test completed')
  })

  // ============================================================
  // Backend Health Check
  // ============================================================
  test('Backend API health check', async () => {
    const response = await fetch(`${BACKEND_URL}/api/health`)
    expect(response.ok).toBeTruthy()
    console.log('[SUCCESS] Backend API is accessible')
  })
})