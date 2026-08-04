import { test, expect } from '@playwright/test'

/**
 * Regression test: StatusPage session-not-found handling
 *
 * Verifies that navigating to /ram/status/{invalid-sid} shows a user-friendly
 * error message and blocks downstream 404 cascade (SSE rejoin + fallback poll).
 *
 * Background: Previously, the catch block only console.warn'd, causing Steps 2/3
 * (SSE rejoin + retry REST) to fire with the same invalid sid, producing 3+ failed
 * requests and console spam.
 */
test.describe('StatusPage — session-not-found regression', () => {

  test('shows user-friendly error for invalid sid and blocks cascade', async ({ page }) => {
    const invalidSid = crypto.randomUUID()

    // Collect console warnings — we expect NO "Failed to load report via REST"
    const consoleWarnings: string[] = []
    page.on('console', msg => {
      if (msg.type() === 'warning') {
        consoleWarnings.push(msg.text())
      }
    })

    // Count /api/ram/status/{sid}/report requests — should be exactly 1
    const reportRequests: string[] = []
    page.on('request', req => {
      const url = req.url()
      if (url.includes('/api/ram/status/') && url.includes('/report')) {
        reportRequests.push(url)
      }
    })

    await page.goto(`/ram/status/${invalidSid}`)

    // Expect the error message to appear
    await expect(page.getByText('会话不存在或已失效')).toBeVisible({ timeout: 10000 })

    // Loading should be off
    await expect(page.locator('.el-loading-mask')).not.toBeVisible()

    // No old-style console.warn about REST failure
    const restWarn = consoleWarnings.filter(w => w.includes('Failed to load report via REST'))
    expect(restWarn).toHaveLength(0)

    // Only 1 report request (no SSE rejoin, no fallback poll retry)
    expect(reportRequests.length).toBeLessThanOrEqual(1)
  })
})
