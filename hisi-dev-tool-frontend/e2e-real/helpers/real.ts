import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { E2E_USERNAME, e2ePassword } from '../../e2e/helpers/credentials'

/**
 * 真实环境 E2E 的公共部分：用真实接口登录（凭据来自环境变量或未入库的本地凭据文件，不写进仓库、不打印），
 * 把分析对象设为「选中的项目」，然后才打开页面。
 */
export const BACKEND = 'http://localhost:8080'

/**
 * 被分析的项目：test-projects/java-spring-test。
 *
 * 选它的唯一硬条件是「有向量」：需求分析的影响面节点依赖混合检索，没向量的项目上对任何查询都返回 0，
 * 那条流程必然失败，不能作为回归对象（2026-11-27 普查：20 个方法全部带 desc+code 向量）。
 * 它是独立的 git 仓库（4 个 Java 文件：UserController / UserService / UserServiceImpl / User），
 * 项目里有意留了一个缺陷给自动修复当靶子（见 UserServiceImpl.createUser 的注释）。
 * 之前用的 v4.0 后端有 4455 个节点但 0 向量，已全部迁走（V4_* 常量仅留给还没迁移的调用方）。
 */
export const TARGET_ROOT = 'C:/Users/47583/projects/hisi_dev_tool v5.0/test-projects'
export const TARGET_PROJECTS = [
  { name: 'java-spring-test', path: `${TARGET_ROOT}/java-spring-test` },
]
export const TARGET_NAME = TARGET_PROJECTS[0].name
export const TARGET_PATH = TARGET_PROJECTS[0].path

/** @deprecated v4.0 后端没有向量（4455 节点 / 0 向量），不能作为依赖检索的用例的被分析项目。 */
export const V4_ROOT = 'C:/Users/47583/projects/hisi_dev_tool v4.0'
/** @deprecated 同上。 */
export const V4_PROJECTS = [
  { name: 'hisi-dev-tool', path: `${V4_ROOT}/hisi-dev-tool` },
  { name: 'hisi-dev-tool-frontend', path: `${V4_ROOT}/hisi-dev-tool-frontend` },
  { name: 'hisi-mcp-server', path: `${V4_ROOT}/hisi-mcp-server` },
]
/** @deprecated 同上。 */
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
    [token, TARGET_PROJECTS] as const,
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
