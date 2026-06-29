# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: e2e.spec.ts >> 端到端流程测试 >> 响应式布局测试
- Location: e2e\e2e.spec.ts:30:3

# Error details

```
Test timeout of 30000ms exceeded.
```

```
Error: page.waitForLoadState: Test timeout of 30000ms exceeded.
```

# Page snapshot

```yaml
- generic [active] [ref=e1]:
  - generic [ref=e3]:
    - complementary [ref=e4]:
      - menubar [ref=e5]:
        - menuitem "技能市场" [ref=e6] [cursor=pointer]:
          - img [ref=e8]
          - generic [ref=e10]: 技能市场
        - menuitem "KG Skills 套件" [ref=e11]:
          - img [ref=e13]
          - generic [ref=e15]: KG Skills 套件
        - menuitem "Claude 终端" [ref=e16] [cursor=pointer]:
          - img [ref=e18]
          - generic [ref=e20]: Claude 终端
        - menuitem "APM 调试" [ref=e21] [cursor=pointer]:
          - img [ref=e23]
          - generic [ref=e26]: APM 调试
        - menuitem "增强检索" [ref=e27]:
          - img [ref=e29]
          - generic [ref=e31]: 增强检索
        - menuitem "日志分析" [ref=e32] [cursor=pointer]:
          - img [ref=e34]
          - generic [ref=e36]: 日志分析
        - menuitem "知识图谱" [ref=e37]:
          - img [ref=e39]
          - generic [ref=e41]: 知识图谱
        - menuitem "需求分析大师" [ref=e42] [cursor=pointer]:
          - img [ref=e44]
          - generic [ref=e46]: 需求分析大师
        - menuitem "项目现状分析" [ref=e47] [cursor=pointer]:
          - img [ref=e49]
          - generic [ref=e51]: 项目现状分析
        - menuitem "合入分析" [ref=e52] [cursor=pointer]:
          - img [ref=e54]
          - generic [ref=e57]: 合入分析
        - menuitem "项目管理" [ref=e58] [cursor=pointer]:
          - img [ref=e60]
          - generic [ref=e62]: 项目管理
        - menuitem "系统设置" [ref=e63] [cursor=pointer]:
          - img [ref=e65]
          - generic [ref=e67]: 系统设置
    - generic [ref=e68]:
      - generic [ref=e70]:
        - heading "HiSi DevTool" [level=1] [ref=e71]
        - button "登录" [ref=e74] [cursor=pointer]:
          - generic [ref=e75]: 登录
      - main [ref=e76]:
        - generic [ref=e77]:
          - generic [ref=e78]:
            - generic [ref=e80]:
              - generic [ref=e81]: 项目目录配置
              - generic [ref=e83]: 已配置
            - generic [ref=e85]:
              - generic [ref=e86]:
                - generic [ref=e87]: 项目目录
                - generic [ref=e89]:
                  - textbox "项目目录" [ref=e91]:
                    - /placeholder: 请输入项目代码存放目录
                    - text: C:\Users\47583\projects\hisi_dev_tool v4.0
                  - button "选择目录" [ref=e94] [cursor=pointer]:
                    - generic [ref=e95]: 选择目录
              - generic [ref=e97]:
                - button "保存配置" [ref=e98] [cursor=pointer]:
                  - generic [ref=e99]: 保存配置
                - button "重置" [ref=e100] [cursor=pointer]:
                  - generic [ref=e101]: 重置
          - alert [ref=e102]:
            - img [ref=e104]
            - generic [ref=e107]: 请在表格中勾选一个或多个项目以开始分析
          - generic [ref=e110]:
            - tablist [ref=e114]:
              - tab "本地项目" [selected] [ref=e116]
              - tab "远端项目" [ref=e117]
            - tabpanel "本地项目" [ref=e119]:
              - generic [ref=e120]:
                - generic [ref=e121]: 项目管理
                - generic [ref=e122]:
                  - button "项目分组" [ref=e123] [cursor=pointer]:
                    - generic [ref=e124]:
                      - img [ref=e126]
                      - text: 项目分组
                  - button "一键更新所有仓库" [ref=e128] [cursor=pointer]:
                    - generic [ref=e129]:
                      - img [ref=e131]
                      - text: 一键更新所有仓库
                  - button "扫描仓库" [disabled]:
                    - generic:
                      - img
                    - generic:
                      - generic:
                        - img
                      - text: 扫描仓库
                  - button "图谱屏蔽目录" [ref=e133] [cursor=pointer]:
                    - generic [ref=e134]:
                      - img [ref=e136]
                      - text: 图谱屏蔽目录
                  - button "术语配置" [ref=e138] [cursor=pointer]:
                    - generic [ref=e139]:
                      - img [ref=e141]
                      - text: 术语配置
                  - button "克隆项目" [ref=e143] [cursor=pointer]:
                    - generic [ref=e144]:
                      - img [ref=e146]
                      - text: 克隆项目
                  - button "跨服务依赖构建 (0)" [disabled] [ref=e148]:
                    - generic [ref=e149]: 跨服务依赖构建 (0)
                  - button "确认选择 (0)" [disabled] [ref=e150]:
                    - generic [ref=e151]:
                      - img [ref=e153]
                      - text: 确认选择 (0)
                  - button "批量生成图谱 (0)" [disabled] [ref=e155]:
                    - generic [ref=e156]:
                      - img [ref=e158]
                      - text: 批量生成图谱 (0)
              - button "未分组 5 个项目" [ref=e162] [cursor=pointer]:
                - generic [ref=e164]:
                  - generic [ref=e166]:
                    - checkbox
                  - generic [ref=e168]: 未分组
                  - generic:
                    - generic: 5 个项目
                - img [ref=e170]
  - alert [ref=e172]:
    - img [ref=e174]
    - paragraph [ref=e176]: 扫描完成，发现 5 个仓库
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
  11 |     await expect(page).toHaveURL(/log-analysis/)
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
> 42 |       await page.waitForLoadState('networkidle')
     |                  ^ Error: page.waitForLoadState: Test timeout of 30000ms exceeded.
  43 | 
  44 |       // 检查页面是否正常渲染
  45 |       await expect(page.locator('body')).toBeVisible()
  46 |     }
  47 |   })
  48 | })
```