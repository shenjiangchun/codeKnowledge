import { readFileSync } from 'node:fs'

/**
 * E2E 用的登录凭据。仓库是公开的，口令不写在用例里：
 * 优先取环境变量 HISI_ADMIN_USER / HISI_ADMIN_PASSWORD，
 * 其次读后端目录下未入库的 hisi-dev-tool/.env.local（与 restart-backend.sh 用同一份）。
 */
const LOCAL_ENV_FILE = new URL('../../../hisi-dev-tool/.env.local', import.meta.url)

function fromLocalEnvFile(key: string): string | undefined {
  let text: string
  try {
    text = readFileSync(LOCAL_ENV_FILE, 'utf8')
  } catch {
    return undefined
  }
  const line = text.split(/\r?\n/).find((entry) => entry.startsWith(`${key}=`))
  return line?.slice(key.length + 1).trim() || undefined
}

function resolve(key: string): string | undefined {
  return process.env[key] || fromLocalEnvFile(key)
}

/** 管理员用户名；没有配置时用应用默认的管理员账号名。 */
export const E2E_USERNAME = resolve('HISI_ADMIN_USER') ?? 'root'

/** 管理员口令；没有配置时直接给出明确的错误，而不是拿空口令去登录。 */
export function e2ePassword(): string {
  const password = resolve('HISI_ADMIN_PASSWORD')
  if (!password) {
    throw new Error('未配置 E2E 登录口令：请设置环境变量 HISI_ADMIN_PASSWORD，或写入 hisi-dev-tool/.env.local（已被 .gitignore 排除）')
  }
  return password
}
