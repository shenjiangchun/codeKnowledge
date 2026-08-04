# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: modules.spec.ts >> 日志分析模块 >> 页面加载
- Location: e2e\modules.spec.ts:12:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('text=日志分析')
Expected: visible
Error: strict mode violation: locator('text=日志分析') resolved to 2 elements:
    1) <span data-v-352df3f7="">日志分析</span> aka getByText('日志分析', { exact: true })
    2) <p class="el-alert__description"> 查看已提交的日志分析任务及其 AI 生成的根因分析报告。 </p> aka getByText('查看已提交的日志分析任务及其 AI 生成的根因分析报告。')

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for locator('text=日志分析')

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
  4  |  * 日志分析模块测试
  5  |  */
  6  | test.describe('日志分析模块', () => {
  7  |   test.beforeEach(async ({ page }) => {
  8  |     await page.goto('/log-analysis')
  9  |     await page.waitForLoadState('networkidle')
  10 |   })
  11 | 
  12 |   test('页面加载', async ({ page }) => {
  13 |     // 检查标题
  14 |     await expect(page).toHaveTitle(/日志分析/)
  15 | 
  16 |     // 检查主要元素存在
> 17 |     await expect(page.locator('text=日志分析')).toBeVisible()
     |                                             ^ Error: expect(locator).toBeVisible() failed
  18 |   })
  19 | 
  20 |   test('查询表单', async ({ page }) => {
  21 |     // 检查查询按钮存在
  22 |     const queryButton = page.locator('button:has-text("查询")')
  23 |     await expect(queryButton).toBeVisible()
  24 |   })
  25 | })
  26 | 
  27 | /**
  28 |  * 调用链分析模块测试
  29 |  */
  30 | test.describe('调用链分析模块', () => {
  31 |   test.beforeEach(async ({ page }) => {
  32 |     await page.goto('/call-chain')
  33 |     await page.waitForLoadState('networkidle')
  34 |   })
  35 | 
  36 |   test('项目列表页面加载', async ({ page }) => {
  37 |     await expect(page).toHaveTitle(/调用链分析/)
  38 |     await expect(page.locator('text=调用链分析')).toBeVisible()
  39 |   })
  40 | 
  41 |   test('项目列表为空时显示提示', async ({ page }) => {
  42 |     // 等待数据加载
  43 |     await page.waitForTimeout(1000)
  44 | 
  45 |     // 检查是否显示空状态或项目列表
  46 |     const content = page.locator('body')
  47 |     await expect(content).toBeVisible()
  48 |   })
  49 | })
  50 | 
  51 | /**
  52 |  * 项目管理模块测试
  53 |  */
  54 | test.describe('项目管理模块', () => {
  55 |   test.beforeEach(async ({ page }) => {
  56 |     await page.goto('/project')
  57 |     await page.waitForLoadState('networkidle')
  58 |   })
  59 | 
  60 |   test('项目管理页面加载', async ({ page }) => {
  61 |     await expect(page).toHaveTitle(/项目管理/)
  62 |   })
  63 | })
  64 | 
  65 | /**
  66 |  * 运维监控模块测试
  67 |  */
  68 | test.describe('运维监控模块', () => {
  69 |   test.beforeEach(async ({ page }) => {
  70 |     await page.goto('/ops')
  71 |     await page.waitForLoadState('networkidle')
  72 |   })
  73 | 
  74 |   test('健康检查页面加载', async ({ page }) => {
  75 |     await expect(page).toHaveTitle(/运维监控/)
  76 |   })
  77 | })
```