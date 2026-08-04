/**
 * 技能市场相关类型定义
 */

/**
 * 技能分类类型
 */
export type SkillCategory = 'diagnosis' | 'analysis' | 'generation' | 'operation' | 'other'

/**
 * 技能分类显示信息
 */
export const SkillCategoryInfo: Record<SkillCategory, { label: string; color: string }> = {
  diagnosis: { label: '诊断', color: '#409EFF' },
  analysis: { label: '分析', color: '#67C23A' },
  generation: { label: '生成', color: '#E6A23C' },
  operation: { label: '操作', color: '#F56C6C' },
  other: { label: '其他', color: '#909399' }
}

/**
 * 技能定义接口 - 简化版，无统计数据
 */
export interface SkillDefinition {
  /** 技能唯一标识 */
  id: string
  /** 技能名称 */
  name: string
  /** 技能描述 */
  description: string
  /** 技能分类 */
  category: SkillCategory
  /** 技能版本 */
  version: string
  /** 技能作者 */
  author?: string
  /** 技能标签 */
  tags?: string[]
  /** 技能图标 URL */
  icon?: string
  /** 技能文件列表 */
  files: SkillFile[]
  /** 是否为官方技能 */
  isOfficial?: boolean
  /** 创建时间 */
  createdAt?: string
  /** 更新时间 */
  updatedAt?: string
  /** 下载次数 */
  downloadCount?: number
  /** 评分 */
  rating?: number
}

/**
 * 技能文件接口
 */
export interface SkillFile {
  /** 文件路径 */
  path: string
  /** 文件名称 */
  name: string
  /** 文件类型 */
  type: 'prompt' | 'config' | 'script' | 'template' | 'other'
  /** 文件大小 (字节) */
  size?: number
  /** 文件描述 */
  description?: string
}

/**
 * 项目技能状态接口
 */
export interface ProjectSkillStatus {
  /** 技能 ID */
  skillId: string
  /** 技能名称 */
  skillName: string
  /** 安装状态 */
  status: 'installed' | 'not_installed' | 'updating' | 'error'
  /** 安装版本 */
  installedVersion?: string
  /** 安装时间 */
  installedAt?: string
  /** 安装路径 */
  installPath?: string
  /** 错误信息 */
  errorMessage?: string
}

/**
 * 技能安装请求
 */
export interface SkillInstallRequest {
  /** 技能 ID */
  skillId: string
  /** 项目目录 */
  projectDir: string
}

/**
 * 技能卸载请求
 */
export interface SkillUninstallRequest {
  /** 技能 ID */
  skillId: string
  /** 项目目录 */
  projectDir: string
}

/**
 * 技能市场响应
 */
export interface SkillMarketResponse {
  /** 技能列表 */
  skills: SkillDefinition[]
  /** 总数量 */
  total: number
  /** 分类统计 */
  categoryStats: Record<SkillCategory, number>
}