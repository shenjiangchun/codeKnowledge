# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: basic.spec.ts >> 基础功能测试 >> 侧边栏导航
- Location: e2e\basic.spec.ts:18:3

# Error details

```
TimeoutError: page.click: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('text=调用链分析')

```

# Page snapshot

```yaml
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
      - menuitem "日志分析" [active] [ref=e32] [cursor=pointer]:
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
      - generic [ref=e78]:
        - tablist [ref=e82]:
          - tab "日志查询" [selected] [ref=e83]
          - tab "定时任务配置" [ref=e84]
          - tab "分析报告" [ref=e85]
        - tabpanel "日志查询" [ref=e87]:
          - generic [ref=e88]:
            - generic [ref=e89]: 日志查询
            - generic [ref=e94]:
              - generic [ref=e96]:
                - generic [ref=e97]: DSL 查询配置
                - generic [ref=e98]:
                  - button "查询" [ref=e99] [cursor=pointer]:
                    - generic [ref=e100]:
                      - img [ref=e102]
                      - text: 查询
                  - button "重置" [ref=e104] [cursor=pointer]:
                    - generic [ref=e105]: 重置
                  - button "高级查询" [ref=e106] [cursor=pointer]:
                    - generic [ref=e107]: 高级查询
              - generic [ref=e108]:
                - separator [ref=e109]:
                  - generic [ref=e110]: 推荐查询
                - generic [ref=e111]:
                  - generic [ref=e113] [cursor=pointer]:
                    - generic [ref=e114]:
                      - img [ref=e116]
                      - generic [ref=e118]: 错误日志查询
                    - paragraph [ref=e119]: 查询最近 15 分钟的所有错误日志
                  - generic [ref=e121] [cursor=pointer]:
                    - generic [ref=e122]:
                      - img [ref=e124]
                      - generic [ref=e126]: NullPointerException
                    - paragraph [ref=e127]: 查询空指针异常日志
                  - generic [ref=e129] [cursor=pointer]:
                    - generic [ref=e130]:
                      - img [ref=e132]
                      - generic [ref=e134]: 数据库异常
                    - paragraph [ref=e135]: 查询数据库相关错误
                  - generic [ref=e137] [cursor=pointer]:
                    - generic [ref=e138]:
                      - img [ref=e140]
                      - generic [ref=e142]: Spring 异常
                    - paragraph [ref=e143]: 查询 Spring 框架相关错误
          - generic [ref=e144]:
            - generic [ref=e147]: 查询结果
            - generic [ref=e148]:
              - generic [ref=e150]:
                - table [ref=e152]:
                  - rowgroup [ref=e161]:
                    - row "级别 时间 服务 TraceID 消息 主机 操作" [ref=e162]:
                      - columnheader "级别" [ref=e163]:
                        - generic [ref=e164]: 级别
                      - columnheader "时间" [ref=e165]:
                        - generic [ref=e166]: 时间
                      - columnheader "服务" [ref=e167]:
                        - generic [ref=e168]: 服务
                      - columnheader "TraceID" [ref=e169]:
                        - generic [ref=e170]: TraceID
                      - columnheader "消息" [ref=e171]:
                        - generic [ref=e172]: 消息
                      - columnheader "主机" [ref=e173]:
                        - generic [ref=e174]: 主机
                      - columnheader "操作" [ref=e175]:
                        - generic [ref=e176]: 操作
                - generic [ref=e180]:
                  - table:
                    - rowgroup
                  - generic [ref=e182]: No Data
              - generic [ref=e183]:
                - generic [ref=e184]: Total 0
                - generic [ref=e187] [cursor=pointer]:
                  - generic:
                    - combobox [ref=e189]
                    - generic [ref=e190]: 20/page
                  - img [ref=e193]
                - button "Go to previous page" [disabled] [ref=e195]:
                  - generic:
                    - img
                - list [ref=e196]:
                  - listitem "page 1" [ref=e197]: "1"
                - button "Go to next page" [disabled] [ref=e198]:
                  - generic:
                    - img
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
  15 |     await expect(page).toHaveURL(/log-analysis/)
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
> 31 |       await page.click(`text=${item.text}`)
     |                  ^ TimeoutError: page.click: Timeout 10000ms exceeded.
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