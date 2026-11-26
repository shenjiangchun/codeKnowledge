import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { E2E_USERNAME, e2ePassword } from '../../e2e/helpers/credentials'

/**
 * 真实环境 E2E 的公共部分：用真实接口登录（凭据来自环境变量或未入库的本地凭据文件，不写进仓库、不打印），
 * 把分析对象设为「选中的项目」，然后才打开页面。
 */
export const BACKEND = 'http://localhost:8080'

/** 被分析的项目：v4.0（应用里登记的「hisi开发工具」项目组）。 */
export const V4_ROOT = 'C:/Users/47583/projects/hisi_dev_tool v4.0'
export const V4_PROJECTS = [
  { name: 'hisi-dev-tool', path: `${V4_ROOT}/hisi-dev-tool` },
  { name: 'hisi-dev-tool-frontend', path: `${V4_ROOT}/hisi-dev-tool-frontend` },
  { name: 'hisi-mcp-server', path: `${V4_ROOT}/hisi-mcp-server` },
]
export const V4_BACKEND = V4_PROJECTS[0].path

/** 用真实接口登录，返回 token。 */
export async function loginToken(request: APIRequestContext): Promise<string> {
  const response = await request.post(`${BACKEND}/api/auth/login`, {
    data: { username: E2E_USERNAME, password: e2ePassword() },
  })
  expect(response.ok(), `登录接口返回 ${response.status()}`).toBeTruthy()
  const body = await response.json()
  expect(body?.data?.token, '登录响应里没有 token').toBeTruthy()
  return body.data.token as string
}

/** 带着登录态与分析项目打开页面（token 与选中项目在页面脚本执行前写入 localStorage）。 */
export async function openAsAdmin(page: Page, request: APIRequestContext, path: string) {
  const token = await loginToken(request)
  await page.addInitScript(
    ([token, projects]) => {
      localStorage.setItem('hisi-token', token as string)
      localStorage.setItem('hisi-selected-projects', JSON.stringify(projects))
    },
    [token, V4_PROJECTS] as const,
  )
  await page.goto(path)
}

/** 带登录头的后端请求。 */
export async function api(request: APIRequestContext, token: string, method: 'GET' | 'POST', path: string, data?: unknown) {
  const response = await request.fetch(`${BACKEND}${path}`, { method, data, headers: { Authorization: `Bearer ${token}` } })
  const body = await response.json().catch(() => undefined)
  return { status: response.status(), body }
}

/** 每隔几秒检查一次，直到条件成立或超时；返回满足条件的值。模型调用的耗时以分钟计。 */
export async function waitFor<T>(
  probe: () => Promise<T>,
  done: (value: T) => boolean,
  timeoutMs: number,
  label: string,
): Promise<T> {
  const deadline = Date.now() + timeoutMs
  let last: T | undefined
  let lastLog = 0
  for (;;) {
    last = await probe()
    if (done(last)) return last
    if (Date.now() > deadline) {
      throw new Error(`等待「${label}」超时（${Math.round(timeoutMs / 60000)} 分钟），最后状态：${JSON.stringify(last)?.slice(0, 300)}`)
    }
    if (Date.now() - lastLog > 60_000) {
      lastLog = Date.now()
      console.log(`[wait] ${label}: ${JSON.stringify(last)?.slice(0, 160)}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 10_000))
  }
}
