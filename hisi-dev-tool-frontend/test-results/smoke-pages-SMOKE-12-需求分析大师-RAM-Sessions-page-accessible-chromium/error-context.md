# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: smoke-pages.spec.ts >> SMOKE-12: 需求分析大师 (RAM Sessions) page accessible
- Location: e2e\smoke-pages.spec.ts:280:1

# Error details

```
Error: page.goto: net::ERR_NETWORK_CHANGED at http://localhost:5173/
Call log:
  - navigating to "http://localhost:5173/", waiting until "load"

```

# Test source

```ts
  1   | import { test, expect, Page } from '@playwright/test'
  2   | 
  3   | /**
  4   |  * Smoke Tests for HiSi DevTool Pages
  5   |  * Tests that each page renders without errors
  6   |  *
  7   |  * Test ID Convention: SMOKE-XX
  8   |  * Pages tested based on AppSidebar.vue and router/index.ts
  9   |  */
  10  | 
  11  | // Test configuration - run in parallel
  12  | test.describe.configure({ mode: 'parallel' })
  13  | 
  14  | /**
  15  |  * Helper function to perform login
  16  |  * Uses root/123456 admin credentials
  17  |  */
  18  | async function loginAsAdmin(page: Page) {
> 19  |   await page.goto('/')
      |              ^ Error: page.goto: net::ERR_NETWORK_CHANGED at http://localhost:5173/
  20  |   await page.waitForLoadState('domcontentloaded')
  21  | 
  22  |   // Check if login dialog appears or if already logged in
  23  |   const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: /登录|Login/ })
  24  |   const dialogCount = await loginDialog.count()
  25  | 
  26  |   if (dialogCount > 0) {
  27  |     // Fill login form
  28  |     const usernameInput = page.locator('.el-dialog input').first()
  29  |     const passwordInput = page.locator('.el-dialog input').nth(1)
  30  | 
  31  |     await usernameInput.fill('root')
  32  |     await passwordInput.fill('123456')
  33  | 
  34  |     // Submit login
  35  |     await page.locator('.el-dialog button[type="submit"], .el-dialog button:has-text("登录")').first().click()
  36  | 
  37  |     // Wait for dialog to close
  38  |     await expect(loginDialog).not.toBeVisible({ timeout: 15000 })
  39  |     await page.waitForTimeout(1000)
  40  |   }
  41  | 
  42  |   // Verify login success
  43  |   const userIndicator = page.locator('.user-dropdown, .username, text=root')
  44  |   await expect(userIndicator.first()).toBeVisible({ timeout: 10000 })
  45  | }
  46  | 
  47  | /**
  48  |  * Helper to collect console errors during page load
  49  |  */
  50  | async function collectConsoleErrors(page: Page): Promise<string[]> {
  51  |   const errors: string[] = []
  52  |   page.on('console', msg => {
  53  |     if (msg.type() === 'error') {
  54  |       errors.push(msg.text())
  55  |     }
  56  |   })
  57  |   return errors
  58  | }
  59  | 
  60  | /**
  61  |  * Helper to verify page loaded successfully
  62  |  */
  63  | async function verifyPageLoad(page: Page, route: string, titlePattern: RegExp | string) {
  64  |   await page.goto(route)
  65  |   await page.waitForLoadState('networkidle')
  66  | 
  67  |   // Verify page title
  68  |   await expect(page).toHaveTitle(titlePattern)
  69  | 
  70  |   // Verify no critical console errors (filter out favicon and minor warnings)
  71  |   const errors = await collectConsoleErrors(page)
  72  |   const criticalErrors = errors.filter(e =>
  73  |     !e.includes('favicon') &&
  74  |     !e.includes('net::ERR_') &&
  75  |     !e.includes('404') &&
  76  |     !e.includes('Warning:')
  77  |   )
  78  |   expect(criticalErrors).toHaveLength(0)
  79  | }
  80  | 
  81  | // =====================================================
  82  | // SMOKE-01: Skill Market - 技能市场
  83  | // =====================================================
  84  | test('SMOKE-01: 技能市场 (Skill Market) loads correctly', async ({ page }) => {
  85  |   await loginAsAdmin(page)
  86  |   const errors = await collectConsoleErrors(page)
  87  | 
  88  |   await page.goto('/skill-market')
  89  |   await page.waitForLoadState('networkidle')
  90  | 
  91  |   await expect(page).toHaveTitle(/技能市场|HiSi|DevTool/)
  92  |   await expect(page.locator('text=/技能市场|Skill Market/i')).toBeVisible({ timeout: 10000 })
  93  | 
  94  |   const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  95  |   expect(criticalErrors).toHaveLength(0)
  96  | })
  97  | 
  98  | // =====================================================
  99  | // SMOKE-02: KG Skills Kit - KG Skills套件
  100 | // =====================================================
  101 | test('SMOKE-02: KG Skills套件 loads correctly', async ({ page }) => {
  102 |   await loginAsAdmin(page)
  103 |   const errors = await collectConsoleErrors(page)
  104 | 
  105 |   await page.goto('/kg-skills-kit')
  106 |   await page.waitForLoadState('networkidle')
  107 | 
  108 |   await expect(page).toHaveTitle(/KG Skills|HiSi|DevTool/)
  109 |   await expect(page.locator('text=/KG Skills|套件/i')).toBeVisible({ timeout: 10000 })
  110 | 
  111 |   const criticalErrors = errors.filter(e => !e.includes('favicon') && !e.includes('net::ERR_'))
  112 |   expect(criticalErrors).toHaveLength(0)
  113 | })
  114 | 
  115 | // =====================================================
  116 | // SMOKE-03: Claude Terminal - Claude终端
  117 | // =====================================================
  118 | test('SMOKE-03: Claude终端 (Claude Terminal) loads correctly', async ({ page }) => {
  119 |   await loginAsAdmin(page)
```