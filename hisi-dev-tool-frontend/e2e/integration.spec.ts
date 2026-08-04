import { test, expect } from '@playwright/test';

const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';

test.describe('HiSi DevTool v4.1 集成测试', () => {

  test.beforeAll(async () => {
    // 验证后端服务可用
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
    expect(response.ok).toBeTruthy();
  });

  test('首页加载正常', async ({ page }) => {
    await page.goto(BASE_URL);
    await expect(page).toHaveTitle(/HiSi|DevTool/);
  });

  test('侧边栏导航 - 智能诊断入口', async ({ page }) => {
    await page.goto(BASE_URL);

    // Element Plus menu item with index attribute
    const diagnosticLink = page.locator('.el-menu-item[index="/diagnostic"], li:has-text("智能诊断")');
    await expect(diagnosticLink.first()).toBeVisible({ timeout: 10000 });
  });

  test('侧边栏导航 - 语义搜索入口', async ({ page }) => {
    await page.goto(BASE_URL);

    // Element Plus menu item with index attribute
    const searchLink = page.locator('.el-menu-item[index="/search"], li:has-text("语义搜索")');
    await expect(searchLink.first()).toBeVisible({ timeout: 10000 });
  });
});

test.describe('智能诊断页面', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto(`${BASE_URL}/diagnostic`);
  });

  test('诊断页面加载', async ({ page }) => {
    // 验证页面主要元素存在
    await expect(page.locator('input, textarea').first()).toBeVisible({ timeout: 10000 });
  });

  test('诊断表单输入', async ({ page }) => {
    // 输入问题描述
    const errorInput = page.locator('textarea, input[type="text"]').first();
    await errorInput.fill('NullPointerException at UserService.login()');
    await expect(errorInput).toHaveValue('NullPointerException at UserService.login()');
  });

  test('诊断按钮存在', async ({ page }) => {
    // 查找诊断按钮
    const diagnoseButton = page.locator('button:has-text("诊断"), button:has-text("开始"), button:has-text("Diagnose")');
    await expect(diagnoseButton.first()).toBeVisible({ timeout: 10000 });
  });
});

test.describe('语义搜索页面', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto(`${BASE_URL}/search`);
  });

  test('搜索页面加载', async ({ page }) => {
    // 验证搜索输入框存在
    const searchInput = page.locator('input[type="text"], input[type="search"]').first();
    await expect(searchInput).toBeVisible({ timeout: 10000 });
  });

  test('搜索输入功能', async ({ page }) => {
    const searchInput = page.locator('input[type="text"], input[type="search"]').first();
    await searchInput.fill('处理用户登录的方法');
    await expect(searchInput).toHaveValue('处理用户登录的方法');
  });
});

test.describe('诊断API集成', () => {

  test('健康检查API', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
    expect(response.ok).toBeTruthy();

    const data = await response.json();
    expect(data.success).toBe(true);
    expect(data.data.status).toBe('UP');
    expect(data.data.agentCount).toBeGreaterThanOrEqual(1);
  });

  test('Agent列表API', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/agents`);
    expect(response.ok).toBeTruthy();

    const data = await response.json();
    expect(data.success).toBe(true);
    expect(data.data).toContain('STACK_TRACE');
  });

  test('同步诊断API', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectPath: '/test',
        errorMessage: 'Test NullPointerException',
        stackTrace: 'java.lang.NullPointerException\n\tat Test.main(Test.java:1)'
      })
    });

    expect(response.ok).toBeTruthy();
    const data = await response.json();
    expect(data.success).toBe(true);
    expect(data.data.requestId).toBeTruthy();
    expect(data.data.confidence).toBeGreaterThanOrEqual(0);
    expect(data.data.agents).toBeDefined();
    expect(data.data.agents.length).toBeGreaterThan(0);
  });

  test('诊断结果包含必要字段', async () => {
    const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectPath: '/test',
        errorMessage: 'NullPointerException in UserService',
        stackTrace: 'java.lang.NullPointerException: null\n\tat com.example.UserService.login(UserService.java:123)'
      })
    });

    const data = await response.json();

    // 验证响应结构
    expect(data.data.requestId).toBeTruthy();
    expect(data.data.conclusion).toBeTruthy();
    expect(data.data.confidence).toBeGreaterThanOrEqual(0);
    expect(data.data.confidence).toBeLessThanOrEqual(1);
    expect(data.data.fixSuggestions).toBeInstanceOf(Array);
    expect(data.data.executionTimeMs).toBeGreaterThanOrEqual(0);
  });
});

test.describe('WebSocket连接测试', () => {

  test('WebSocket端点可访问', async ({ page }) => {
    // 在浏览器环境中测试WebSocket
    await page.goto(BASE_URL);

    const wsResult = await page.evaluate(async () => {
      return new Promise((resolve) => {
        try {
          const ws = new WebSocket(`ws://localhost:8080/ws/diagnosis`);
          let connected = false;

          ws.onopen = () => {
            connected = true;
            ws.close();
          };

          ws.onclose = () => {
            resolve({ connected });
          };

          ws.onerror = () => {
            resolve({ connected: false });
          };

          // 5秒超时
          setTimeout(() => {
            if (!connected) {
              ws.close();
              resolve({ connected: false });
            }
          }, 5000);
        } catch (e) {
          resolve({ connected: false, error: e.message });
        }
      });
    });

    expect(wsResult.connected).toBe(true);
  });
});