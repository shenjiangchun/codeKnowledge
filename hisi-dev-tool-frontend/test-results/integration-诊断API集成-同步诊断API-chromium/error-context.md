# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: integration.spec.ts >> 诊断API集成 >> 同步诊断API
- Location: e2e\integration.spec.ts:101:3

# Error details

```
Error: expect(received).toBeTruthy()

Received: false
```

# Test source

```ts
  12  |   });
  13  | 
  14  |   test('首页加载正常', async ({ page }) => {
  15  |     await page.goto(BASE_URL);
  16  |     await expect(page).toHaveTitle(/HiSi|DevTool/);
  17  |   });
  18  | 
  19  |   test('侧边栏导航 - 智能诊断入口', async ({ page }) => {
  20  |     await page.goto(BASE_URL);
  21  | 
  22  |     // Element Plus menu item with index attribute
  23  |     const diagnosticLink = page.locator('.el-menu-item[index="/diagnostic"], li:has-text("智能诊断")');
  24  |     await expect(diagnosticLink.first()).toBeVisible({ timeout: 10000 });
  25  |   });
  26  | 
  27  |   test('侧边栏导航 - 语义搜索入口', async ({ page }) => {
  28  |     await page.goto(BASE_URL);
  29  | 
  30  |     // Element Plus menu item with index attribute
  31  |     const searchLink = page.locator('.el-menu-item[index="/search"], li:has-text("语义搜索")');
  32  |     await expect(searchLink.first()).toBeVisible({ timeout: 10000 });
  33  |   });
  34  | });
  35  | 
  36  | test.describe('智能诊断页面', () => {
  37  | 
  38  |   test.beforeEach(async ({ page }) => {
  39  |     await page.goto(`${BASE_URL}/diagnostic`);
  40  |   });
  41  | 
  42  |   test('诊断页面加载', async ({ page }) => {
  43  |     // 验证页面主要元素存在
  44  |     await expect(page.locator('input, textarea').first()).toBeVisible({ timeout: 10000 });
  45  |   });
  46  | 
  47  |   test('诊断表单输入', async ({ page }) => {
  48  |     // 输入问题描述
  49  |     const errorInput = page.locator('textarea, input[type="text"]').first();
  50  |     await errorInput.fill('NullPointerException at UserService.login()');
  51  |     await expect(errorInput).toHaveValue('NullPointerException at UserService.login()');
  52  |   });
  53  | 
  54  |   test('诊断按钮存在', async ({ page }) => {
  55  |     // 查找诊断按钮
  56  |     const diagnoseButton = page.locator('button:has-text("诊断"), button:has-text("开始"), button:has-text("Diagnose")');
  57  |     await expect(diagnoseButton.first()).toBeVisible({ timeout: 10000 });
  58  |   });
  59  | });
  60  | 
  61  | test.describe('语义搜索页面', () => {
  62  | 
  63  |   test.beforeEach(async ({ page }) => {
  64  |     await page.goto(`${BASE_URL}/search`);
  65  |   });
  66  | 
  67  |   test('搜索页面加载', async ({ page }) => {
  68  |     // 验证搜索输入框存在
  69  |     const searchInput = page.locator('input[type="text"], input[type="search"]').first();
  70  |     await expect(searchInput).toBeVisible({ timeout: 10000 });
  71  |   });
  72  | 
  73  |   test('搜索输入功能', async ({ page }) => {
  74  |     const searchInput = page.locator('input[type="text"], input[type="search"]').first();
  75  |     await searchInput.fill('处理用户登录的方法');
  76  |     await expect(searchInput).toHaveValue('处理用户登录的方法');
  77  |   });
  78  | });
  79  | 
  80  | test.describe('诊断API集成', () => {
  81  | 
  82  |   test('健康检查API', async () => {
  83  |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
  84  |     expect(response.ok).toBeTruthy();
  85  | 
  86  |     const data = await response.json();
  87  |     expect(data.success).toBe(true);
  88  |     expect(data.data.status).toBe('UP');
  89  |     expect(data.data.agentCount).toBeGreaterThanOrEqual(1);
  90  |   });
  91  | 
  92  |   test('Agent列表API', async () => {
  93  |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/agents`);
  94  |     expect(response.ok).toBeTruthy();
  95  | 
  96  |     const data = await response.json();
  97  |     expect(data.success).toBe(true);
  98  |     expect(data.data).toContain('STACK_TRACE');
  99  |   });
  100 | 
  101 |   test('同步诊断API', async () => {
  102 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
  103 |       method: 'POST',
  104 |       headers: { 'Content-Type': 'application/json' },
  105 |       body: JSON.stringify({
  106 |         projectPath: '/test',
  107 |         errorMessage: 'Test NullPointerException',
  108 |         stackTrace: 'java.lang.NullPointerException\n\tat Test.main(Test.java:1)'
  109 |       })
  110 |     });
  111 | 
> 112 |     expect(response.ok).toBeTruthy();
      |                         ^ Error: expect(received).toBeTruthy()
  113 |     const data = await response.json();
  114 |     expect(data.success).toBe(true);
  115 |     expect(data.data.requestId).toBeTruthy();
  116 |     expect(data.data.confidence).toBeGreaterThanOrEqual(0);
  117 |     expect(data.data.agents).toBeDefined();
  118 |     expect(data.data.agents.length).toBeGreaterThan(0);
  119 |   });
  120 | 
  121 |   test('诊断结果包含必要字段', async () => {
  122 |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/analyze`, {
  123 |       method: 'POST',
  124 |       headers: { 'Content-Type': 'application/json' },
  125 |       body: JSON.stringify({
  126 |         projectPath: '/test',
  127 |         errorMessage: 'NullPointerException in UserService',
  128 |         stackTrace: 'java.lang.NullPointerException: null\n\tat com.example.UserService.login(UserService.java:123)'
  129 |       })
  130 |     });
  131 | 
  132 |     const data = await response.json();
  133 | 
  134 |     // 验证响应结构
  135 |     expect(data.data.requestId).toBeTruthy();
  136 |     expect(data.data.conclusion).toBeTruthy();
  137 |     expect(data.data.confidence).toBeGreaterThanOrEqual(0);
  138 |     expect(data.data.confidence).toBeLessThanOrEqual(1);
  139 |     expect(data.data.fixSuggestions).toBeInstanceOf(Array);
  140 |     expect(data.data.executionTimeMs).toBeGreaterThanOrEqual(0);
  141 |   });
  142 | });
  143 | 
  144 | test.describe('WebSocket连接测试', () => {
  145 | 
  146 |   test('WebSocket端点可访问', async ({ page }) => {
  147 |     // 在浏览器环境中测试WebSocket
  148 |     await page.goto(BASE_URL);
  149 | 
  150 |     const wsResult = await page.evaluate(async () => {
  151 |       return new Promise((resolve) => {
  152 |         try {
  153 |           const ws = new WebSocket(`ws://localhost:8080/ws/diagnosis`);
  154 |           let connected = false;
  155 | 
  156 |           ws.onopen = () => {
  157 |             connected = true;
  158 |             ws.close();
  159 |           };
  160 | 
  161 |           ws.onclose = () => {
  162 |             resolve({ connected });
  163 |           };
  164 | 
  165 |           ws.onerror = () => {
  166 |             resolve({ connected: false });
  167 |           };
  168 | 
  169 |           // 5秒超时
  170 |           setTimeout(() => {
  171 |             if (!connected) {
  172 |               ws.close();
  173 |               resolve({ connected: false });
  174 |             }
  175 |           }, 5000);
  176 |         } catch (e) {
  177 |           resolve({ connected: false, error: e.message });
  178 |         }
  179 |       });
  180 |     });
  181 | 
  182 |     expect(wsResult.connected).toBe(true);
  183 |   });
  184 | });
```