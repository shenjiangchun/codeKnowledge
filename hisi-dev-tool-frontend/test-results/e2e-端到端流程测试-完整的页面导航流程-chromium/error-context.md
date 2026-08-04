# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: e2e.spec.ts >> 端到端流程测试 >> 完整的页面导航流程
- Location: e2e\e2e.spec.ts:8:3

# Error details

```
Error: expect(page).toHaveURL(expected) failed

Expected pattern: /log-analysis/
Received string:  "http://localhost:5173/project"
Timeout: 5000ms

Call log:
  - Expect "toHaveURL" with timeout 5000ms
    12 × unexpected value "http://localhost:5173/project"

```

```yaml
- complementary:
  - menubar:
    - menuitem "技能市场":
      - img
      - text: 技能市场
    - menuitem "KG Skills 套件":
      - img
      - text: KG Skills 套件
    - menuitem "Claude 终端":
      - img
      - text: Claude 终端
    - menuitem "APM 调试":
      - img
      - text: APM 调试
    - menuitem "增强检索":
      - img
      - text: 增强检索
    - menuitem "日志分析":
      - img
      - text: 日志分析
    - menuitem "知识图谱":
      - img
      - text: 知识图谱
    - menuitem "需求分析大师":
      - img
      - text: 需求分析大师
    - menuitem "项目现状分析":
      - img
      - text: 项目现状分析
    - menuitem "合入分析":
      - img
      - text: 合入分析
    - menuitem "项目管理":
      - img
      - text: 项目管理
    - menuitem "系统设置":
      - img
      - text: 系统设置
- heading "HiSi DevTool" [level=1]
- button "登录"
- main:
  - text: 项目目录配置 已配置 项目目录
  - textbox "项目目录":
    - /placeholder: 请输入项目代码存放目录
    - text: C:\Users\47583\projects\hisi_dev_tool v4.0
  - button "选择目录"
  - button "保存配置"
  - button "重置"
  - alert:
    - img
    - text: 请在表格中勾选一个或多个项目以开始分析
  - tablist:
    - tab "本地项目" [selected]
    - tab "远端项目"
  - tabpanel "本地项目":
    - text: 项目管理
    - button "项目分组":
      - img
      - text: 项目分组
    - button "一键更新所有仓库":
      - img
      - text: 一键更新所有仓库
    - button "扫描仓库" [disabled]:
      - img
      - img
      - text: 扫描仓库
    - button "图谱屏蔽目录":
      - img
      - text: 图谱屏蔽目录
    - button "术语配置":
      - img
      - text: 术语配置
    - button "克隆项目":
      - img
      - text: 克隆项目
    - button "跨服务依赖构建 (0)" [disabled]
    - button "确认选择 (0)" [disabled]:
      - img
      - text: 确认选择 (0)
    - button "批量生成图谱 (0)" [disabled]:
      - img
      - text: 批量生成图谱 (0)
```

# Test source

```ts
  1  | import { test, expect, Page } from '@playwright/test'
  2  | 
  3  | /**
  4  |  * 端到端流程测试
  5  |  * 测试完整的用户操作流程
  6  |  */
  7  | test.describe('端到端流程测试', () => {
  8  |   test('完整的页面导航流程', async ({ page }) => {
  9  |     // 1. 访问首页
  10 |     await page.goto('/')
> 11 |     await expect(page).toHaveURL(/log-analysis/)
     |                        ^ Error: expect(page).toHaveURL(expected) failed
  12 | 
  13 |     // 2. 导航到调用链分析
  14 |     await page.click('text=调用链分析')
  15 |     await expect(page).toHaveURL(/call-chain/)
  16 | 
  17 |     // 3. 导航到项目管理
  18 |     await page.click('text=项目管理')
  19 |     await expect(page).toHaveURL(/project/)
  20 | 
  21 |     // 4. 导航到运维监控
  22 |     await page.click('text=运维监控')
  23 |     await expect(page).toHaveURL(/ops/)
  24 | 
  25 |     // 5. 返回日志分析
  26 |     await page.click('text=日志分析')
  27 |     await expect(page).toHaveURL(/log-analysis/)
  28 |   })
  29 | 
  30 |   test('响应式布局测试', async ({ page }) => {
  31 |     // 测试不同屏幕尺寸
  32 |     const viewports = [
  33 |       { width: 1920, height: 1080, name: 'Desktop' },
  34 |       { width: 1366, height: 768, name: 'Laptop' },
  35 |       { width: 768, height: 1024, name: 'Tablet' },
  36 |       { width: 375, height: 667, name: 'Mobile' },
  37 |     ]
  38 | 
  39 |     for (const viewport of viewports) {
  40 |       await page.setViewportSize({ width: viewport.width, height: viewport.height })
  41 |       await page.goto('/')
  42 |       await page.waitForLoadState('networkidle')
  43 | 
  44 |       // 检查页面是否正常渲染
  45 |       await expect(page.locator('body')).toBeVisible()
  46 |     }
  47 |   })
  48 | })
```