# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: ram-status.spec.ts >> 项目现状分析创建流程 >> 项目现状分析完整流程
- Location: e2e\ram-status.spec.ts:18:3

# Error details

```
Error: expect(locator).not.toBeVisible() failed

Locator:  locator('.el-dialog').filter({ hasText: '登录 / 注册' })
Expected: not visible
Received: visible
Timeout:  10000ms

Call log:
  - Expect "not toBeVisible" with timeout 10000ms
  - waiting for locator('.el-dialog').filter({ hasText: '登录 / 注册' })
    19 × locator resolved to <div tabindex="-1" class="el-dialog">…</div>
       - unexpected value "visible"

```

```yaml
- heading "登录 / 注册" [level=2]
- button "Close this dialog":
  - img
- tablist:
  - tab "登录" [selected]
  - tab "注册"
- tabpanel "登录":
  - text: 用户名
  - textbox "用户名":
    - /placeholder: 请输入用户名
  - text: 密码
  - textbox "密码":
    - /placeholder: 请输入密码
  - img
  - button "登录" [disabled]
```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test';
  2   | 
  3   | const BASE_URL = 'http://localhost:5173';
  4   | const BACKEND_URL = 'http://localhost:8080';
  5   | 
  6   | /**
  7   |  * 项目现状分析 E2E 测试
  8   |  * 测试流程：登录 -> 导航 -> 创建分析 -> 等待完成 -> 验证结果
  9   |  */
  10  | test.describe('项目现状分析创建流程', () => {
  11  | 
  12  |   test.beforeAll(async () => {
  13  |     // 验证后端服务可用
  14  |     const response = await fetch(`${BACKEND_URL}/api/diagnosis/health`);
  15  |     expect(response.ok).toBeTruthy();
  16  |   });
  17  | 
  18  |   test('项目现状分析完整流程', async ({ page }) => {
  19  |     // Step 1: 导航到首页
  20  |     await page.goto(BASE_URL);
  21  |     // 等待页面基本元素加载，不等待 networkidle（因为可能有 SSE 连接）
  22  |     await expect(page.locator('.app-header, .el-header')).toBeVisible({ timeout: 10000 });
  23  | 
  24  |     // Step 2: 登录
  25  |     // 点击右上角的"登录"按钮
  26  |     const loginButton = page.locator('button:has-text("登录")');
  27  |     await expect(loginButton).toBeVisible({ timeout: 10000 });
  28  |     await loginButton.click();
  29  | 
  30  |     // 等待登录对话框出现
  31  |     const loginDialog = page.locator('.el-dialog').filter({ hasText: '登录 / 注册' });
  32  |     await expect(loginDialog).toBeVisible({ timeout: 5000 });
  33  | 
  34  |     // 在登录表单中输入用户名和密码
  35  |     const usernameInput = loginDialog.getByPlaceholder('请输入用户名');
  36  |     const passwordInput = loginDialog.getByPlaceholder('请输入密码');
  37  | 
  38  |     await usernameInput.fill('root');
  39  |     await passwordInput.fill('123456');
  40  | 
  41  |     // 点击登录按钮
  42  |     const submitButton = loginDialog.locator('button').filter({ hasText: '登录' }).first();
  43  |     await submitButton.click();
  44  | 
  45  |     // 等待登录成功（验证右上角显示用户名或登录按钮消失）
  46  |     // 使用更宽松的等待条件，等待用户信息出现
  47  |     const userDropdown = page.locator('.user-dropdown, .user-info, .username');
  48  |     await expect(userDropdown).toBeVisible({ timeout: 10000 });
  49  | 
  50  |     // 等待对话框关闭（增加超时）
> 51  |     await expect(loginDialog).not.toBeVisible({ timeout: 10000 });
      |                                   ^ Error: expect(locator).not.toBeVisible() failed
  52  | 
  53  |     // 截图：登录成功
  54  |     await page.screenshot({ path: 'test-results/01-login-success.png' });
  55  | 
  56  |     // Step 3: 点击侧边栏"项目现状分析"
  57  |     const statusMenuItem = page.locator('.el-menu-item').filter({ hasText: '项目现状分析' });
  58  |     await expect(statusMenuItem).toBeVisible({ timeout: 10000 });
  59  |     await statusMenuItem.click();
  60  | 
  61  |     // 验证导航到正确页面
  62  |     await expect(page).toHaveURL(/ram\/status/, { timeout: 10000 });
  63  | 
  64  |     // 截图：项目现状分析列表页
  65  |     await page.screenshot({ path: 'test-results/02-status-list-page.png' });
  66  | 
  67  |     // Step 4: 点击"创建新分析"按钮
  68  |     const createButton = page.locator('button').filter({ hasText: '创建新分析' });
  69  |     await expect(createButton).toBeVisible({ timeout: 5000 });
  70  |     await createButton.click();
  71  | 
  72  |     // 验证导航到输入页
  73  |     await expect(page).toHaveURL(/ram\/status\/new/, { timeout: 10000 });
  74  | 
  75  |     // 截图：创建分析输入页
  76  |     await page.screenshot({ path: 'test-results/03-create-input-page.png' });
  77  | 
  78  |     // Step 5: 选择项目下拉框，选择 "hisi-dev-tool"
  79  |     // 等待项目加载完成
  80  |     const projectSelect = page.locator('.el-select');
  81  |     await expect(projectSelect).toBeVisible({ timeout: 15000 });
  82  | 
  83  |     // Element Plus 多选下拉框需要点击选项来选择
  84  |     // 点击打开下拉框
  85  |     await projectSelect.click();
  86  | 
  87  |     // 等待下拉菜单面板出现（Element Plus 下拉菜单挂载在 body 下）
  88  |     await page.waitForTimeout(2000);
  89  | 
  90  |     // 等待下拉面板可见
  91  |     const dropdownPanel = page.locator('.el-select-dropdown').filter({ has: page.locator('.el-select-dropdown__item') });
  92  | 
  93  |     // 查找 hisi-dev-tool 项目选项
  94  |     // 使用更宽松的匹配，因为选项可能包含额外信息
  95  |     const projectOptions = dropdownPanel.locator('.el-select-dropdown__item');
  96  |     const allOptions = await projectOptions.allTextContents();
  97  | 
  98  |     // 找到包含 "hisi-dev-tool" 的选项索引
  99  |     let selectedIndex = -1;
  100 |     for (let i = 0; i < allOptions.length; i++) {
  101 |       if (allOptions[i].includes('hisi-dev-tool') || allOptions[i].includes('hisidevtool')) {
  102 |         selectedIndex = i;
  103 |         break;
  104 |       }
  105 |     }
  106 | 
  107 |     // 如果找到了，点击选择
  108 |     if (selectedIndex >= 0) {
  109 |       await projectOptions.nth(selectedIndex).dispatchEvent('click');
  110 |     } else if (allOptions.length > 0) {
  111 |       // 如果没有找到 hisi-dev-tool，选择第一个包含"项目"标签的选项
  112 |       for (let i = 0; i < allOptions.length; i++) {
  113 |         if (allOptions[i].includes('项目')) {
  114 |           await projectOptions.nth(i).dispatchEvent('click');
  115 |           break;
  116 |         }
  117 |       }
  118 |     } else {
  119 |       // 使用手动输入路径 - 展开高级选项
  120 |       const advancedToggle = page.locator('.el-collapse-item__header').first();
  121 |       await advancedToggle.click();
  122 |       await page.waitForTimeout(500);
  123 | 
  124 |       const manualInput = page.getByPlaceholder(/项目绝对路径/);
  125 |       await manualInput.fill('C:\\Users\\47583\\projects\\hisi_dev_tool v5.0\\hisi-dev-tool');
  126 |       const addButton = page.locator('button').filter({ hasText: '添加' });
  127 |       await addButton.dispatchEvent('click');
  128 |       await page.waitForTimeout(500);
  129 |     }
  130 | 
  131 |     // 关闭下拉菜单（点击其他区域）
  132 |     await page.keyboard.press('Escape');
  133 |     await page.waitForTimeout(500);
  134 | 
  135 |     // 截图：项目选择完成
  136 |     await page.screenshot({ path: 'test-results/04-project-selected.png' });
  137 | 
  138 |     // Step 6: 点击"生成分析报告"
  139 |     const generateButton = page.locator('button').filter({ hasText: '生成分析报告' });
  140 |     await expect(generateButton).toBeVisible({ timeout: 5000 });
  141 |     await generateButton.click();
  142 | 
  143 |     // 截图：点击生成按钮后
  144 |     await page.screenshot({ path: 'test-results/05-after-click-generate.png' });
  145 | 
  146 |     // Step 7: 等待分析完成（最多90秒）
  147 |     // 验证导航到分析详情页（URL 应不包含 /new）
  148 |     await expect(page).not.toHaveURL(/ram\/status\/new/, { timeout: 15000 });
  149 |     await expect(page).toHaveURL(/ram\/status\//, { timeout: 15000 });
  150 | 
  151 |     // 等待页面主体内容出现
```