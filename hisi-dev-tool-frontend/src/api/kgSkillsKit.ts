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
    const response = await api.get('/api/kg-skills-kit/list')
    return response.data
  },

  async installSkill(skillId: string): Promise<InstallResult> {
    const response = await api.post(`/api/kg-skills-kit/install/${skillId}`)
    return response.data
  },

  async uninstallSkill(skillId: string): Promise<InstallResult> {
    const response = await api.post(`/api/kg-skills-kit/uninstall/${skillId}`)
    return response.data
  },

  async installAll(): Promise<BatchInstallResult> {
    const response = await api.post('/api/kg-skills-kit/install-all')
    return response.data
  },

  async uninstallAll(): Promise<BatchInstallResult> {
    const response = await api.post('/api/kg-skills-kit/uninstall-all')
    return response.data
  },

  async getGuide(): Promise<SkillGuide> {
    const response = await api.get('/api/kg-skills-kit/guide')
    return response.data
  }
}