import { expect, test, type APIRequestContext } from '@playwright/test'

import { api, loginToken, openAsAdmin, TARGET_PATH, waitFor } from './helpers/real'

/**
 * 日志分析 agent 流（真实后端、真实模型调用），分析对象是 java-spring-test。
 *
 * 前置：给项目提交一条异常日志——取项目代码里真实存在的空指针点
 * （UserServiceImpl.createUser：email 为 null 时 trim() 抛 NPE，项目里有意保留给自动修复当靶子）。
 * 页面流程：分析报告页签 → 对这条报告点「重新分析」（页面触发 v2 分析流）→ 等分析完成 →
 * 「查看快照」弹窗与「查看报告」详情页都给出根因、证据与修复建议。
 */
const STACK = [
  'java.lang.NullPointerException: Cannot invoke "String.trim()" because "email" is null',
  '\tat com.example.service.UserServiceImpl.createUser(UserServiceImpl.java:26)',
  '\tat com.example.controller.UserController.createUser(UserController.java:30)',
  '\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)',
  '\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1086)',
].join('\n')
const MESSAGE =
  '2026-11-27 10:00:00.000 ERROR [http-nio-8081-exec-5] c.example.controller.UserController - Failed to create user'
const SERVICE_NAME = 'java-spring-test'
const ANALYZE_TIMEOUT = 25 * 60_000

test('日志分析：在页面上对 java-spring-test 的异常报告重新分析并查看报告', async ({ page, request }) => {
  const token = await loginToken(request)
  let reportId = await findReport(token, request)
  if (!reportId) {
    const submitted = await api(request, token, 'POST', '/api/v2/log/analyze', {
      message: MESSAGE, stackTrace: STACK, projectPath: TARGET_PATH, errorType: 'NullPointerException', serviceName: SERVICE_NAME,
    })
    reportId = String(submitted.body?.data?.reportId ?? '')
    expect(reportId, `提交分析失败：${JSON.stringify(submitted.body)?.slice(0, 200)}`).toBeTruthy()
    await reportDone(request, token, reportId)
  }

  await openAsAdmin(page, request, '/log-analysis')
  await page.getByRole('tab', { name: '分析报告' }).click()
  const row = page.locator('.el-table__row', { hasText: reportId })
  await expect(row).toBeVisible()

  // 页面触发重新分析（会先弹确认框）
  await row.getByRole('button', { name: '重新分析' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认' }).click()
  await expect(page.getByText('已触发重新分析')).toBeVisible()

  // 等这次重新分析完成（v2：解析 → 图谱取证 → 假设 → 验证 → 综合）
  const final = await reportDone(request, token, reportId)
  test.info().annotations.push({ type: 'report', description: `reportId=${reportId} status=${final}` })
  expect(final, '重新分析应完成').toBe('completed')

  // 列表是触发后 3 秒刷新的，那时还在分析中：重新打开页面拿最新状态
  await page.reload()
  await page.getByRole('tab', { name: '分析报告' }).click()
  await expect(row).toContainText(/completed|已完成/)

  // 「查看快照」：留在当前页，弹窗展示报告摘要
  await row.getByRole('button', { name: '查看快照' }).click()
  const dialog = page.locator('.el-dialog', { hasText: '报告快照' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('NullPointerException')
  await page.screenshot({ path: test.info().outputPath('01-report-dialog.png') })
  await dialog.locator('.el-dialog__headerbtn').click()

  // 详情页：根因、证据、修复建议都基于项目的代码（根因正文里也会出现「修复建议」几个字，所以按小节标题定位）
  await row.getByRole('button', { name: '查看报告' }).click()
  await expect(page).toHaveURL(new RegExp(`/log-analysis/report/${reportId}`), { timeout: 30_000 })
  const section = (title: string) =>
    page.locator('.report-section').filter({ has: page.locator('h4.section-title', { hasText: title }) })
  await expect(section('错误摘要')).toBeVisible()
  await expect(section('修复建议')).toBeVisible()
  await expect(page.locator('body')).toContainText('createUser')
  await expect(page.locator('body')).toContainText('UserServiceImpl')
  await page.screenshot({ path: test.info().outputPath('02-report-detail.png'), fullPage: true })
})

/** 已有的本项目报告：错误类型与服务名都对得上的那一条。 */
async function findReport(token: string, request: APIRequestContext) {
  const listed = await api(request, token, 'GET', '/api/log/reports?page=1&pageSize=50')
  const hit = (listed.body?.data?.list ?? []).find(
    (r: { serviceName?: string; errorType?: string }) =>
      r.serviceName === SERVICE_NAME && r.errorType === 'NullPointerException',
  )
  return hit ? String(hit.reportId) : ''
}

/** 等报告到终态，返回状态。 */
function reportDone(request: APIRequestContext, token: string, reportId: string) {
  return waitFor(
    async () => {
      const status = await api(request, token, 'GET', `/api/v2/log/report/${reportId}/status`)
      return String(status.body?.data?.status ?? '')
    },
    (status) => status === 'completed' || status === 'failed',
    ANALYZE_TIMEOUT,
    '日志分析完成',
  )
}
