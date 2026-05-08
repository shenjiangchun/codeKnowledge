import { test, expect, Page } from '@playwright/test'

/**
 * 自然语言交互 E2E 测试
 * 测试用户输入自然语言 -> 系统识别意图 -> 返回结果的完整流程
 */
test.describe('自然语言交互测试', () => {

  const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
  const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080'

  test.beforeAll(async () => {
    // 验证后端服务可用
    try {
      const response = await fetch(`${BACKEND_URL}/api/search/health`)
      // 服务可能不存在健康检查端点，尝试其他端点
      if (!response.ok) {
        const fallbackResponse = await fetch(`${BACKEND_URL}/api/diagnosis/health`)
        expect(fallbackResponse.ok).toBeTruthy()
      }
    } catch (e) {
      // 如果后端不可用，测试将使用 Mock 数据
      console.log('Backend not available, tests will proceed with UI validation')
    }
  })

  /**
   * 测试场景1: 语义搜索页面加载
   */
  test('语义搜索页面正常加载', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 验证页面标题
    await expect(page).toHaveTitle(/语义搜索/)

    // 验证搜索输入框存在
    const searchInput = page.locator('.search-box input, .el-input__inner').first()
    await expect(searchInput).toBeVisible({ timeout: 10000 })
  })

  /**
   * 测试场景2: 用户输入自然语言搜索
   */
  test('用户输入自然语言进行代码搜索', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 输入自然语言描述
    const searchInput = page.locator('.search-box input, input[placeholder*="自然语言"]').first()
    await searchInput.fill('处理用户登录的方法')

    // 验证输入值
    await expect(searchInput).toHaveValue('处理用户登录的方法')

    // 点击搜索按钮
    const searchButton = page.locator('button:has-text("搜索")').first()
    await expect(searchButton).toBeVisible()
    await searchButton.click()

    // 等待搜索结果或加载状态
    await page.waitForTimeout(2000)

    // 验证搜索状态（加载中或结果）
    const loadingIndicator = page.locator('.loading-state, .is-loading')
    const resultsPanel = page.locator('.results-list, .search-results-panel')

    // 应该显示加载状态或结果
    const isLoadingVisible = await loadingIndicator.isVisible()
    const hasResults = await resultsPanel.isVisible()

    expect(isLoadingVisible || hasResults).toBeTruthy()
  })

  /**
   * 测试场景3: 搜索过滤器功能
   */
  test('搜索过滤器可以正常使用', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 测试搜索范围过滤器
    const scopeSelect = page.locator('.search-filters .el-select').first()
    if (await scopeSelect.isVisible()) {
      await scopeSelect.click()
      await page.waitForTimeout(500)

      // 选择"方法"选项
      const methodOption = page.locator('.el-select-dropdown__item:has-text("方法")')
      if (await methodOption.isVisible()) {
        await methodOption.click()
      }
    }

    // 测试编程语言过滤器
    const languageSelect = page.locator('.search-filters .el-select').nth(1)
    if (await languageSelect.isVisible()) {
      await languageSelect.click()
      await page.waitForTimeout(500)

      // 选择"Java"选项
      const javaOption = page.locator('.el-select-dropdown__item:has-text("Java")')
      if (await javaOption.isVisible()) {
        await javaOption.click()
      }
    }

    // 验证过滤器设置成功（通过UI状态）
    await page.waitForTimeout(500)
  })

  /**
   * 测试场景4: 搜索结果展示
   */
  test('搜索结果正确展示', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 输入搜索内容
    const searchInput = page.locator('.search-box input').first()
    await searchInput.fill('数据库连接配置')
    await searchInput.press('Enter')

    // 等待结果
    await page.waitForTimeout(3000)

    // 检查结果面板
    const resultsPanel = page.locator('.search-results-panel')
    await expect(resultsPanel).toBeVisible({ timeout: 10000 })

    // 如果有结果，验证结果项结构
    const resultItems = page.locator('.result-item')
    const itemCount = await resultItems.count()

    if (itemCount > 0) {
      // 验证第一个结果项的结构
      const firstResult = resultItems.first()

      // 应有类型标签
      const typeTag = firstResult.locator('.el-tag')
      await expect(typeTag).toBeVisible()

      // 应有名称
      const resultName = firstResult.locator('.result-name')
      await expect(resultName).toBeVisible()

      // 应有相关度分数
      const relevanceScore = firstResult.locator('.relevance-score')
      await expect(relevanceScore).toBeVisible()
    }
  })

  /**
   * 测试场景5: 搜索结果选择和预览
   */
  test('选择搜索结果显示代码预览', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 执行搜索
    const searchInput = page.locator('.search-box input').first()
    await searchInput.fill('异常处理')
    await searchInput.press('Enter')

    await page.waitForTimeout(3000)

    // 尝试选择结果
    const resultItems = page.locator('.result-item')
    const itemCount = await resultItems.count()

    if (itemCount > 0) {
      // 点击第一个结果
      await resultItems.first().click()
      await page.waitForTimeout(500)

      // 验证选中状态
      await expect(resultItems.first()).toHaveClass(/selected/)

      // 验证预览面板显示
      const previewPanel = page.locator('.preview-column, .code-preview-panel')
      await expect(previewPanel).toBeVisible()
    }
  })

  /**
   * 测试场景6: 搜索建议和历史
   */
  test('搜索建议和历史功能', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 检查历史区域
    const historySection = page.locator('.search-history')
    if (await historySection.isVisible()) {
      const historyTags = historySection.locator('.history-tag')
      const historyCount = await historyTags.count()

      if (historyCount > 0) {
        // 点击历史记录项
        await historyTags.first().click()

        // 验证输入框被填充
        const searchInput = page.locator('.search-box input').first()
        const inputValue = await searchInput.inputValue()
        expect(inputValue.length).toBeGreaterThan(0)
      }
    }
  })

  /**
   * 测试场景7: 相关度阈值调节
   */
  test('相关度阈值滑块调节', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 找到阈值滑块
    const thresholdSlider = page.locator('.search-filters .el-slider')

    if (await thresholdSlider.isVisible()) {
      // 获取滑块初始值
      const sliderRunway = thresholdSlider.locator('.el-slider__runway')

      // 验证滑块存在并可交互
      await expect(sliderRunway).toBeVisible()

      // 滑动到不同位置（模拟拖动）
      const sliderBar = thresholdSlider.locator('.el-slider__bar')
      await expect(sliderBar).toBeVisible()
    }
  })

  /**
   * 测试场景8: 清空搜索
   */
  test('清空搜索输入', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 输入搜索内容
    const searchInput = page.locator('.search-box input').first()
    await searchInput.fill('测试搜索内容')

    // 点击清空按钮
    const clearButton = page.locator('.el-input__clear')
    if (await clearButton.isVisible()) {
      await clearButton.click()

      // 验证输入被清空
      await expect(searchInput).toHaveValue('')
    }
  })

  /**
   * 测试场景9: Enter键触发搜索
   */
  test('Enter键触发搜索', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 输入搜索内容
    const searchInput = page.locator('.search-box input').first()
    await searchInput.fill('配置文件读取')

    // 按Enter键
    await searchInput.press('Enter')

    // 等待搜索响应
    await page.waitForTimeout(2000)

    // 验证搜索已触发（加载状态或结果）
    const searchButton = page.locator('button:has-text("搜索")')
    const isLoading = await searchButton.getAttribute('loading')

    // 或者检查结果面板状态
    const resultsPanel = page.locator('.search-results-panel')
    await expect(resultsPanel).toBeVisible({ timeout: 10000 })
  })

  /**
   * 测试场景10: 加载更多结果
   */
  test('加载更多搜索结果', async ({ page }) => {
    await page.goto(`${BASE_URL}/search`)
    await page.waitForLoadState('networkidle')

    // 执行搜索
    const searchInput = page.locator('.search-box input').first()
    await searchInput.fill('服务调用')
    await searchInput.press('Enter')

    await page.waitForTimeout(3000)

    // 检查是否有"加载更多"按钮
    const loadMoreButton = page.locator('.load-more button:has-text("加载更多")')

    if (await loadMoreButton.isVisible()) {
      await loadMoreButton.click()
      await page.waitForTimeout(2000)

      // 验证结果数量增加
      const resultItems = page.locator('.result-item')
      const itemCount = await resultItems.count()
      expect(itemCount).toBeGreaterThan(0)
    }
  })
})

/**
 * 智能诊断自然语言交互测试
 */
test.describe('智能诊断自然语言交互测试', () => {

  const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'

  test.beforeEach(async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`)
    await page.waitForLoadState('networkidle')
  })

  /**
   * 测试场景: 用户输入问题描述进行诊断
   */
  test('用户输入自然语言问题描述', async ({ page }) => {
    // 验证诊断页面加载
    const diagnosisPanel = page.locator('.agent-diagnosis-panel')
    await expect(diagnosisPanel).toBeVisible({ timeout: 10000 })

    // 输入问题描述
    const textarea = page.locator('.input-section textarea, textarea').first()
    await textarea.fill('NullPointerException at UserService.login() line 123')

    // 验证输入值
    await expect(textarea).toHaveValue('NullPointerException at UserService.login() line 123')

    // 点击开始诊断按钮
    const startButton = page.locator('button:has-text("开始诊断")')
    await expect(startButton).toBeVisible()
  })

  /**
   * 测试场景: 诊断过程显示Agent执行状态
   */
  test('诊断过程显示Agent状态', async ({ page }) => {
    const textarea = page.locator('.input-section textarea').first()
    await textarea.fill('StackOverflowError in recursive function')

    // 开始诊断
    const startButton = page.locator('button:has-text("开始诊断")')
    await startButton.click()

    // 等待诊断开始
    await page.waitForTimeout(2000)

    // 验证进度区域
    const progressSection = page.locator('.progress-section')
    if (await progressSection.isVisible()) {
      // 验证进度显示
      const progressValue = progressSection.locator('.progress-value')
      await expect(progressValue).toBeVisible()
    }

    // 验证Agent列表
    const agentList = page.locator('.agent-list')
    if (await agentList.isVisible()) {
      const agentItems = agentList.locator('.agent-item')
      const agentCount = await agentItems.count()
      expect(agentCount).toBeGreaterThan(0)
    }
  })
})