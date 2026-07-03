import api from './index'

export interface KgSkill {
  id: string
  name: string
  category: string
  description: string
  tags: string[]
  version: string
  isOfficial: boolean
  installed: boolean
  installPath?: string
}

export interface KgSkillsKitListResponse {
  skills: KgSkill[]
  total: number
  installedCount: number
  skillsDir: string
  kitVersion: string
  kitName: string
}

export interface InstallResult {
  success: boolean
  message: string
  installPath?: string
  skillId?: string
  installed?: boolean
}

export interface BatchInstallResult {
  success: boolean
  message: string
  results: InstallResult[]
  successCount: number
  skipCount?: number
}

export interface SkillGuide {
  kitName: string
  version: string
  description: string
  features: string[]
  usage: string[]
  prerequisites: string[]
  mcpTools: string[]
}

export const kgSkillsKitApi = {
  async getKitList(): Promise<KgSkillsKitListResponse> {
    return await api.get<KgSkillsKitListResponse>('/api/kg-skills-kit/list')
  },

  async installSkill(skillId: string): Promise<InstallResult> {
    return await api.post<InstallResult>(`/api/kg-skills-kit/install/${skillId}`)
  },

  async uninstallSkill(skillId: string): Promise<InstallResult> {
    return await api.post<InstallResult>(`/api/kg-skills-kit/uninstall/${skillId}`)
  },

  async installAll(): Promise<BatchInstallResult> {
    return await api.post<BatchInstallResult>('/api/kg-skills-kit/install-all')
  },

  async uninstallAll(): Promise<BatchInstallResult> {
    return await api.post<BatchInstallResult>('/api/kg-skills-kit/uninstall-all')
  },

  async getGuide(): Promise<SkillGuide> {
    return await api.get<SkillGuide>('/api/kg-skills-kit/guide')
  }
}
