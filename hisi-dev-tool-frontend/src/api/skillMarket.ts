import request from '@/utils/request'
import type {
  SkillDefinition,
  ProjectSkillStatus,
  SkillMarketResponse,
  SkillInstallRequest,
  SkillUninstallRequest
} from '@/types/skill'

/**
 * 技能市场 API 模块
 */
export const skillMarketApi = {
  /**
   * 获取技能列表
   * @param category 可选的分类筛选
   * @param search 可选的搜索关键词
   */
  getSkillList(category?: string, search?: string) {
    return request.get<SkillMarketResponse>('/skill-market/list', {
      params: { category, search }
    })
  },

  /**
   * 获取项目技能状态
   * @param projectDir 项目目录路径
   */
  getProjectStatus(projectDir: string) {
    return request.get<ProjectSkillStatus[]>('/skill-market/project-status', {
      params: { projectDir }
    })
  },

  /**
   * 获取技能详情
   * @param id 技能 ID
   */
  getSkillDetail(id: string) {
    return request.get<SkillDefinition>(`/skill-market/detail/${id}`)
  },

  /**
   * 安装技能到项目
   * @param data 安装请求参数
   */
  installSkill(data: SkillInstallRequest) {
    return request.post<{ success: boolean; message: string; installPath: string }>(
      '/skill-market/install',
      data
    )
  },

  /**
   * 从项目卸载技能
   * @param data 卸载请求参数
   */
  uninstallSkill(data: SkillUninstallRequest) {
    return request.post<{ success: boolean; message: string }>(
      '/skill-market/uninstall',
      data
    )
  },

  /**
   * 检查技能更新
   * @param projectDir 项目目录路径
   */
  checkUpdates(projectDir: string) {
    return request.get<{ skillId: string; currentVersion: string; latestVersion: string }[]>(
      '/skill-market/check-updates',
      { params: { projectDir } }
    )
  },

  /**
   * 更新技能
   * @param data 安装请求参数 (更新使用相同的接口)
   */
  updateSkill(data: SkillInstallRequest) {
    return request.post<{ success: boolean; message: string }>(
      '/skill-market/update',
      data
    )
  }
}

export default skillMarketApi