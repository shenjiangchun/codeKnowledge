/**
 * 知识图谱入口类型工具函数
 */

// 入口类型图标映射
export const entryTypeIcons: Record<string, { icon: string; color: string; label: string }> = {
  HTTP: { icon: '🌐', color: '#409EFF', label: 'HTTP 接口' },
  SCHEDULED: { icon: '⏰', color: '#E6A23C', label: '定时任务' },
  MQ: { icon: '📨', color: '#909399', label: 'MQ 消费者' },
  EVENT: { icon: '📢', color: '#67C23A', label: '事件监听' },
  WEBSOCKET: { icon: '🔌', color: '#9B59B6', label: 'WebSocket' },
  RPC: { icon: '🔗', color: '#F56C6C', label: 'RPC 服务' },
  LIFECYCLE: { icon: '🔄', color: '#BDC3C7', label: '生命周期' },
  FEIGN_CLIENT: { icon: '🔗', color: '#8E44AD', label: 'Feign 客户端' }
}

/**
 * 获取入口类型的 el-tag 类型
 */
export function getEntryTagType(entryType: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  const typeMap: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    HTTP: '',
    SCHEDULED: 'warning',
    MQ: 'info',
    EVENT: 'success',
    WEBSOCKET: '',
    RPC: 'danger',
    LIFECYCLE: 'info',
    FEIGN_CLIENT: 'danger'
  }
  return typeMap[entryType] || 'info'
}

/**
 * 获取入口类型图标
 */
export function getEntryIcon(entryType: string): string {
  return entryTypeIcons[entryType]?.icon || '📌'
}

/**
 * 获取入口类型标签
 */
export function getEntryLabel(entryType: string): string {
  return entryTypeIcons[entryType]?.label || entryType
}

/**
 * 解析方法签名
 * 输入格式: className.methodName 或 full.package.ClassName.methodName
 * 返回: { className, methodName }
 */
export function parseMethodSignature(input: string): { className: string; methodName: string } | null {
  if (!input || !input.trim()) return null

  const trimmed = input.trim()
  const lastDotIndex = trimmed.lastIndexOf('.')

  if (lastDotIndex <= 0) return null

  const methodName = trimmed.substring(lastDotIndex + 1)
  const className = trimmed.substring(0, lastDotIndex)

  if (!className || !methodName) return null

  return { className, methodName }
}

/**
 * 格式化入口标识显示
 */
export function formatEntryKey(entryKey: string, entryType: string): string {
  if (entryType === 'HTTP') {
    return entryKey || '/'
  }
  if (entryType === 'SCHEDULED') {
    // cron 表达式截取显示
    if (entryKey && entryKey.length > 20) {
      return entryKey.substring(0, 20) + '...'
    }
    return entryKey || '定时任务'
  }
  return entryKey || '-'
}

/**
 * 计算风险等级
 */
export function calculateRiskLevel(entryCount: number, maxDepth: number): {
  level: string
  type: '' | 'success' | 'warning' | 'danger'
  description: string
} {
  if (entryCount >= 10 || maxDepth >= 5) {
    return {
      level: '高风险',
      type: 'danger',
      description: '影响范围广，建议谨慎修改'
    }
  }
  if (entryCount >= 5 || maxDepth >= 3) {
    return {
      level: '中等风险',
      type: 'warning',
      description: '有一定影响范围，建议评估后修改'
    }
  }
  return {
    level: '低风险',
    type: 'success',
    description: '影响范围较小，可以安全修改'
  }
}
