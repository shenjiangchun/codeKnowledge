/**
 * 路径处理工具函数
 * 统一将所有路径格式标准化为正斜杠格式，确保与后端 Neo4j 和数据库存储一致
 */

/**
 * 标准化路径格式
 * - 将所有反斜杠转换为正斜杠
 * - 去除首尾空白
 * - 去除末尾的斜杠
 *
 * @param path 原始路径
 * @returns 标准化后的路径
 */
export function normalizePath(path: string | null | undefined): string {
  if (!path) {
    return ''
  }
  // 将反斜杠转换为正斜杠
  let normalized = path.trim().replace(/\\/g, '/')
  // 去除末尾斜杠（但保留根路径如 "C:/"）
  while (normalized.length > 1 && normalized.endsWith('/')) {
    normalized = normalized.slice(0, -1)
  }
  return normalized
}

/**
 * 检查路径是否为空
 */
export function isPathEmpty(path: string | null | undefined): boolean {
  return !path || path.trim() === '' || normalizePath(path) === ''
}

/**
 * 连接多个路径部分
 */
export function joinPaths(...parts: (string | null | undefined)[]): string {
  const validParts = parts
    .filter((p): p is string => !!p)
    .map(p => normalizePath(p))
    .filter(p => p !== '')

  return validParts.join('/')
}

/**
 * 从路径中提取文件/目录名
 */
export function getBaseName(path: string | null | undefined): string {
  const normalized = normalizePath(path)
  if (!normalized) return ''
  const lastSlash = normalized.lastIndexOf('/')
  return lastSlash >= 0 ? normalized.slice(lastSlash + 1) : normalized
}

/**
 * 从路径中提取父目录
 */
export function getDirName(path: string | null | undefined): string {
  const normalized = normalizePath(path)
  if (!normalized) return ''
  const lastSlash = normalized.lastIndexOf('/')
  return lastSlash > 0 ? normalized.slice(0, lastSlash) : ''
}
