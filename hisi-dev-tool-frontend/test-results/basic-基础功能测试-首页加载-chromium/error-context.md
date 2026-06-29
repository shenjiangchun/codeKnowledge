# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: basic.spec.ts >> 基础功能测试 >> 首页加载
- Location: e2e\basic.spec.ts:8:3

# Error details

```
Error: expect(page).toHaveURL(expected) failed

Expected pattern: /log-analysis/
Received string:  "http://localhost:5173/project"
Timeout: 5000ms

Call log:
  - Expect "toHaveURL" with timeout 5000ms
    5 × unexpected value "http://localhost:5173/"
    7 × unexpected value "http://localhost:5173/project"

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
  1  | import { test, expect } from '@playwright/test'
  2  | 
  3  | /**
  4  |  * 基础功能测试
  5  |  * 测试应用基本加载和导航
  6  |  */
  7  | test.describe('基础功能测试', () => {
  8  |   test('首页加载', async ({ page }) => {
  9  |     await page.goto('/')
  10 | 
  11 |     // 检查标题
  12 |     await expect(page).toHaveTitle(/HiSi Dev Tool/)
  13 | 
  14 |     // 检查默认跳转到日志分析页
> 15 |     await expect(page).toHaveURL(/log-analysis/)
     |                        ^ Error: expect(page).toHaveURL(expected) failed
  16 |   })
  17 | 
  18 |   test('侧边栏导航', async ({ page }) => {
  19 |     await page.goto('/')
  20 | 
  21 |     // 测试所有导航项
  22 |     const navItems = [
  23 |       { text: '日志分析', href: '/log-analysis' },
  24 |       { text: '调用链分析', href: '/call-chain' },
  25 |       { text: '项目管理', href: '/project' },
  26 |       { text: '运维监控', href: '/ops' },
  27 |     ]
  28 | 
  29 |     for (const item of navItems) {
  30 |       // 点击导航项
  31 |       await page.click(`text=${item.text}`)
  32 | 
  33 |       // 验证 URL 变化
  34 |       await expect(page).toHaveURL(new RegExp(item.href))
  35 | 
  36 |       // 等待页面加载
  37 |       await page.waitForLoadState('networkidle')
  38 |     }
  39 |   })
  40 | })
```