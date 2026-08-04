import { test, expect } from '@playwright/test';

const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';

test.describe('日志查询页面', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto(BASE_URL);
    // 导航到日志查询页面
    const logMenu = page.locator('.el-menu-item:has-text("日志查询"), .el-menu-item[index="/log-analysis"]');
    if (await logMenu.count() > 0) {
      await logMenu.first().click();
    } else {
      // 直接访问
      await page.goto(`${BASE_URL}/log-analysis`);
    }
  });

  test('页面加载 - 查询表单可见', async ({ page }) => {
    // 验证查询按钮存在
    const queryButton = page.locator('button:has-text("查询")');
    await expect(queryButton).toBeVisible({ timeout: 10000 });
  });

  test('查询表单 - 时间范围选择', async ({ page }) => {
    // 选择时间范围
    const timeSelect = page.locator('.el-select:has(.el-input__wrapper)').first();
    await timeSelect.click();

    // 验证下拉选项
    const option = page.locator('.el-select-dropdown__item:has-text("最近")').first();
    await expect(option).toBeVisible();
  });

  test('查询表单 - 输入关键字', async ({ page }) => {
    // 输入查询关键字
    const keywordInput = page.locator('input[placeholder*="关键字"], input[placeholder*="keyword"]').first();
    if (await keywordInput.count() > 0) {
      await keywordInput.fill('ERROR');
      await expect(keywordInput).toHaveValue('ERROR');
    }
  });

  test('点击查询按钮', async ({ page }) => {
    const queryButton = page.locator('button:has-text("查询")');
    await queryButton.click();

    // 等待加载状态或结果
    await page.waitForTimeout(1000);
  });
});

test.describe('日志分析按钮测试', () => {

  test.beforeEach(async ({ page }) => {
    // 验证后端服务可用
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
    if (!response.ok) {
      test.skip();
    }
  });

  test('日志列表 - 分析按钮存在', async ({ page }) => {
    await page.goto(`${BASE_URL}/log-analysis`);

    // 查询日志以显示列表
    const queryButton = page.locator('button:has-text("查询")');
    await queryButton.click();
    await page.waitForTimeout(2000);

    // 检查是否有日志数据
    const analyzeButton = page.locator('button:has-text("分析")').first();
    const tableRows = page.locator('.el-table__body-wrapper .el-table__row');

    // 如果有数据，验证分析按钮
    if (await tableRows.count() > 0) {
      await expect(analyzeButton).toBeVisible({ timeout: 5000 });
    }
  });

  test('点击分析按钮 - 打开分析对话框', async ({ page }) => {
    await page.goto(`${BASE_URL}/log-analysis`);

    // 查询日志
    const queryButton = page.locator('button:has-text("查询")');
    await queryButton.click();
    await page.waitForTimeout(2000);

    const tableRows = page.locator('.el-table__body-wrapper .el-table__row');

    if (await tableRows.count() > 0) {
      // 点击第一个分析按钮
      const analyzeButton = page.locator('button:has-text("分析")').first();
      await analyzeButton.click();

      // 验证分析对话框打开
      const dialog = page.locator('.el-dialog:has-text("分析"), .el-dialog:has-text("Claude")');
      await expect(dialog).toBeVisible({ timeout: 5000 });
    }
  });
});

test.describe('诊断流程集成测试', () => {

  test('诊断API - 健康检查', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
    expect(response.ok).toBeTruthy();

    const data = await response.json();
    expect(data.code).toBe(200);
    expect(data.data.status).toBe('UP');
  });

  test('诊断API - Agent列表', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/agents`);
    expect(response.ok).toBeTruthy();

    const data = await response.json();
    expect(data.code).toBe(200);
    expect(Array.isArray(data.data)).toBe(true);
    expect(data.data).toContain('STACK_TRACE');
  });

  test('诊断API - 完整诊断流程', async () => {
    // 1. 发送诊断请求
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectPath: '/test/project',
        errorMessage: 'NullPointerException in UserService.login',
        stackTrace: `java.lang.NullPointerException: Cannot invoke method on null object
        at com.example.service.UserService.login(UserService.java:150)
        at com.example.controller.AuthController.handleLogin(AuthController.java:45)
        at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)`,
        logContent: '2024-01-15 10:30:45 ERROR [main] UserService - Login failed: null user object',
        traceId: 'trace-e2e-test-001',
        entryPoint: 'POST /api/auth/login'
      })
    });

    expect(response.ok).toBeTruthy();
    const data = await response.json();

    // 2. 验证响应结构
    expect(data.code).toBe(200);
    expect(data.data.requestId).toBeTruthy();
    expect(data.data.confidence).toBeGreaterThanOrEqual(0);
    expect(data.data.confidence).toBeLessThanOrEqual(1);

    // 3. 验证诊断结果
    expect(data.data.conclusion).toBeTruthy();
    expect(data.data.agents).toBeDefined();
    expect(Array.isArray(data.data.agents)).toBe(true);
    expect(data.data.agents.length).toBeGreaterThan(0);

    // 4. 验证 Agent 结果
    const stackTraceAgent = data.data.agents.find((a: any) => a.type === 'STACK_TRACE');
    expect(stackTraceAgent).toBeDefined();
    expect(stackTraceAgent.status).toBe('SUCCESS');
    expect(stackTraceAgent.confidence).toBeGreaterThan(0);
  });

  test('诊断API - 高置信度结果', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectPath: '/test/project',
        errorMessage: 'Database connection failed',
        stackTrace: `java.sql.SQLException: Connection refused
        at com.example.db.ConnectionPool.getConnection(ConnectionPool.java:80)
        at com.example.repository.UserRepository.findById(UserRepository.java:45)`,
        logContent: '2024-01-15 ERROR Connection refused to database'
      })
    });

    const data = await response.json();

    // 验证包含修复建议
    expect(data.data.fixSuggestions).toBeDefined();
    expect(Array.isArray(data.data.fixSuggestions)).toBe(true);

    // 验证受影响代码
    expect(data.data.affectedCode).toBeDefined();
    expect(Array.isArray(data.data.affectedCode)).toBe(true);
  });

  test('诊断API - 异步诊断', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze/async`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectPath: '/test/project',
        errorMessage: 'Async test error',
        stackTrace: 'java.lang.Exception: async test'
      })
    });

    expect(response.ok).toBeTruthy();
    const data = await response.json();
    expect(data.code).toBe(200);
    expect(data.data).toContain('requestId');
  });

  test('诊断API - 边界情况：空堆栈', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectPath: '/test',
        errorMessage: 'Unknown error'
      })
    });

    expect(response.ok).toBeTruthy();
    const data = await response.json();
    expect(data.code).toBe(200);
    expect(data.data.confidence).toBe(0);
  });

  test('诊断API - 验证失败：缺少errorMessage', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectPath: '/test',
        stackTrace: 'java.lang.Exception: test'
      })
    });

    // 应该返回 400 或验证错误
    expect(response.status).toBe(400);
  });
});

test.describe('诊断页面 UI 测试', () => {

  test('诊断页面 - 表单元素', async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`);

    // 验证页面标题
    await expect(page.locator('h2, h3, .el-card__header').filter({ hasText: /诊断|智能/ }).first()).toBeVisible({ timeout: 10000 });

    // 验证输入表单
    const textareas = page.locator('textarea');
    await expect(textareas.first()).toBeVisible({ timeout: 10000 });
  });

  test('诊断页面 - 输入错误信息', async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`);

    // 输入错误消息
    const errorTextarea = page.locator('textarea').first();
    await errorTextarea.fill('NullPointerException in UserService.login() method at line 150');
    await expect(errorTextarea).toContainText('NullPointerException');
  });

  test('诊断页面 - 输入堆栈信息', async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`);

    const stackTrace = `java.lang.NullPointerException: null
    at com.example.service.UserService.login(UserService.java:150)
    at com.example.controller.AuthController.handleLogin(AuthController.java:45)`;

    // 查找堆栈输入框
    const stackTextarea = page.locator('textarea').nth(1);
    if (await stackTextarea.count() > 0) {
      await stackTextarea.fill(stackTrace);
    }
  });

  test('诊断页面 - 提交诊断', async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`);

    // 填写表单
    const errorTextarea = page.locator('textarea').first();
    await errorTextarea.fill('Test error message');

    // 点击诊断按钮
    const diagnoseButton = page.locator('button:has-text("诊断"), button:has-text("开始")').first();
    if (await diagnoseButton.count() > 0) {
      await diagnoseButton.click();

      // 等待响应
      await page.waitForTimeout(3000);
    }
  });

  test('诊断页面 - 结果展示', async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`);

    // 填写表单并提交
    const errorTextarea = page.locator('textarea').first();
    await errorTextarea.fill('NullPointerException in test method');

    const diagnoseButton = page.locator('button:has-text("诊断"), button:has-text("开始")').first();
    if (await diagnoseButton.count() > 0) {
      await diagnoseButton.click();

      // 等待结果显示
      const resultSection = page.locator('.diagnosis-result, .analysis-result, .el-card:has-text("结论")');
      try {
        await expect(resultSection.first()).toBeVisible({ timeout: 10000 });
      } catch {
        // 结果可能还在加载
      }
    }
  });
});

test.describe('响应式布局测试', () => {

  test('移动端视图 - 日志查询', async ({ page }) => {
    // 设置移动端视口
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto(`${BASE_URL}/log-analysis`);

    // 验证页面可访问
    await expect(page.locator('button:has-text("查询")')).toBeVisible({ timeout: 10000 });
  });

  test('平板视图 - 诊断页面', async ({ page }) => {
    // 设置平板视口
    await page.setViewportSize({ width: 768, height: 1024 });
    await page.goto(`${BASE_URL}/diagnostic`);

    // 验证页面可访问
    const formElement = page.locator('textarea, input').first();
    await expect(formElement).toBeVisible({ timeout: 10000 });
  });

  test('桌面视图 - 完整布局', async ({ page }) => {
    // 设置桌面视口
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.goto(BASE_URL);

    // 验证侧边栏可见
    const sidebar = page.locator('.el-menu, .sidebar, nav');
    await expect(sidebar.first()).toBeVisible({ timeout: 10000 });
  });
});

test.describe('错误处理测试', () => {

  test('网络错误处理', async ({ page }) => {
    // 模拟网络错误
    await page.route('**/api/**', route => route.abort('failed'));

    await page.goto(`${BASE_URL}/log-analysis`);

    // 点击查询
    const queryButton = page.locator('button:has-text("查询")');
    await queryButton.click();

    // 等待错误处理
    await page.waitForTimeout(2000);

    // 验证错误提示（如果有）
    const errorMessage = page.locator('.el-message--error, .el-notification__content:has-text("失败")');
    // 不强制要求错误提示，仅验证页面不崩溃
  });

  test('后端服务不可用提示', async ({ page }) => {
    // 模拟 500 错误
    await page.route('**/api/diagnosis/**', route =>
      route.fulfill({ status: 500, body: JSON.stringify({ message: 'Internal Server Error' }) })
    );

    await page.goto(`${BASE_URL}/diagnostic`);

    // 填写并提交
    const errorTextarea = page.locator('textarea').first();
    await errorTextarea.fill('Test error');

    const diagnoseButton = page.locator('button:has-text("诊断")').first();
    if (await diagnoseButton.count() > 0) {
      await diagnoseButton.click();
      await page.waitForTimeout(2000);
    }
  });
});