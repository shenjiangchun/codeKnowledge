import { expect, test, type APIRequestContext } from '@playwright/test'
import { api, loginToken, openAsAdmin, V4_BACKEND, waitFor } from './helpers/real'

/**
 * 日志分析 agent 流（真实后端、真实模型调用），分析对象是 v4.0 后端。
 *
 * 前置：给 v4.0 提交一条异常日志——取 v4.0 代码里真实存在的空指针风险点
 * （SkillMarketController.getSkillList 第 190 行：技能项缺少 category 时 .equals 会空指针）。
 * 页面流程：分析报告页签 → 对这条报告点「重新分析」（页面触发 v2 分析流）→ 等分析完成 →「查看报告」
 * → 报告详情弹窗与详情页都给出根因、证据与修复建议。
 */
const STACK = [
  'java.lang.NullPointerException: Cannot invoke "Object.equals(Object)" because the return value of "java.util.Map.get(Object)" is null',
  '\tat com.huawei.hisi.controller.SkillMarketController.getSkillList(SkillMarketController.java:190)',
  '\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)',
  '\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1086)',
].join('\n')
const MESSAGE = '2026-11-26 00:10:00.123 ERROR [http-nio-8081-exec-5] c.h.hisi.controller.SkillMarketController - Failed to list skill market items'
const ANALYZE_TIMEOUT = 25 * 60_000

test('日志分析：在页面上对 v4.0 后端的异常报告重新分析并查看报告', async ({ page, request }) => {
  const token = await loginToken(request)
  let reportId = await findReport(token, request)
  if (!reportId) {
    const submitted = await api(request, token, 'POST', '/api/v2/log/analyze', {
      message: MESSAGE, stackTrace: STACK, projectPath: V4_BACKEND, errorType: 'NullPointerException', serviceName: 'devTool',
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
  await row.getByRole('button', { name: '查看报告' }).click()
  const dialog = page.locator('.el-dialog', { hasText: '分析报告详情' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('NullPointerException')
  await page.screenshot({ path: test.info().outputPath('01-report-dialog.png') })

  // 详情页：根因、证据、修复建议都基于 v4.0 的代码（根因正文里也会出现「修复建议」几个字，所以按小节标题定位）
  await page.goto(`/log-analysis/report/${reportId}`)
  const section = (title: string) =>
    page.locator('.report-section').filter({ has: page.locator('h4.section-title', { hasText: title }) })
  await expect(section('错误摘要')).toBeVisible()
  await expect(section('修复建议')).toBeVisible()
  await expect(page.locator('body')).toContainText('getSkillList')
  await expect(page.locator('body')).toContainText('SkillMarketController')
  await page.screenshot({ path: test.info().outputPath('02-report-detail.png'), fullPage: true })
})

/** 已有的 v4.0 报告：错误类型、服务名与消息都对得上的那一条。 */
async function findReport(token: string, request: APIRequestContext) {
  const listed = await api(request, token, 'GET', '/api/log/reports?page=1&pageSize=50')
  const hit = (listed.body?.data?.list ?? []).find(
    (r: { serviceName?: string; errorType?: string; errorMessage?: string; message?: string }) =>
      r.serviceName === 'devTool' && r.errorType === 'NullPointerException',
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
