import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { skillMarketApi } from '@/api/skillMarket'
import type {
  SkillDefinition,
  ProjectSkillStatus,
  SkillCategory,
  SkillMarketResponse
} from '@/types/skill'
import { ElMessage } from 'element-plus'

/**
 * 技能市场 Store
 */
export const useSkillStore = defineStore('skill', () => {
  // State
  /** 技能列表 */
  const skills = ref<SkillDefinition[]>([])
  /** 项目技能状态 */
  const projectStatus = ref<ProjectSkillStatus[]>([])
  /** 加载状态 */
  const loading = ref(false)
  /** 项目状态加载状态 */
  const statusLoading = ref(false)
  /** 安装/卸载操作中的技能 ID */
  const operatingSkillIds = ref<Set<string>>(new Set())
  /** 当前选中的分类 */
  const selectedCategory = ref<SkillCategory | ''>('')
  /** 搜索关键词 */
  const searchKeyword = ref('')
  /** 分类统计 */
  const categoryStats = ref<Record<SkillCategory, number>>({
    diagnosis: 0,
    analysis: 0,
    generation: 0,
    operation: 0,
    other: 0
  })
  /** 技能总数 */
  const totalSkills = ref(0)

  // Computed
  /** 根据筛选条件过滤的技能列表 */
  const filteredSkills = computed(() => {
    let result = skills.value

    // 按分类筛选
    if (selectedCategory.value) {
      result = result.filter(skill => skill.category === selectedCategory.value)
    }

    // 按关键词搜索
    if (searchKeyword.value.trim()) {
      const keyword = searchKeyword.value.trim().toLowerCase()
      result = result.filter(skill =>
        skill.name.toLowerCase().includes(keyword) ||
        skill.description.toLowerCase().includes(keyword) ||
        skill.tags?.some(tag => tag.toLowerCase().includes(keyword))
      )
    }

    return result
  })

  /** 已安装的技能 ID 集合 */
  const installedSkillIds = computed(() => {
    return new Set(
      projectStatus.value
        .filter(status => status.status === 'installed')
        .map(status => status.skillId)
    )
  })

  // Actions
  /**
   * 加载技能列表
   */
  async function loadSkills() {
    loading.value = true
    try {
      const response = await skillMarketApi.getSkillList()
      // 注意: axios 拦截器已经处理了响应
      // 后端返回 ResponseEntity<Map>，拦截器直接返回 response.data
      const data = response as unknown as SkillMarketResponse
      skills.value = data.skills || []
      totalSkills.value = data.total || skills.value.length
      categoryStats.value = data.categoryStats || categoryStats.value
    } catch (error: any) {
      ElMessage.error('加载技能列表失败')
      console.error('Failed to load skills:', error)
    } finally {
      loading.value = false
    }
  }

  /**
   * 加载项目技能状态
   * @param projectDir 项目目录
   */
  async function loadProjectStatus(projectDir: string) {
    if (!projectDir) {
      projectStatus.value = []
      return
    }

    statusLoading.value = true
    try {
      const response = await skillMarketApi.getProjectStatus(projectDir)
      // 后端返回 ResponseEntity<List<Map>>，拦截器直接返回 data
      projectStatus.value = response as unknown as ProjectSkillStatus[]
    } catch (error: any) {
      ElMessage.error('加载项目技能状态失败')
      console.error('Failed to load project status:', error)
      projectStatus.value = []
    } finally {
      statusLoading.value = false
    }
  }

  /**
   * 安装技能
   * @param skillId 技能 ID
   * @param projectDir 项目目录
   */
  async function installSkill(skillId: string, projectDir: string) {
    if (!projectDir) {
      ElMessage.warning('请先选择项目目录')
      return false
    }

    const skill = skills.value.find(s => s.id === skillId)
    if (!skill) {
      ElMessage.error('技能不存在')
      return false
    }

    operatingSkillIds.value.add(skillId)
    try {
      const response = await skillMarketApi.installSkill({
        skillId,
        projectDir
      })
      // 后端返回 ResponseEntity<Map>，拦截器直接返回 data
      const data = response as unknown as { success: boolean; message: string; installPath: string }

      if (data.success) {
        ElMessage.success(`技能 "${skill.name}" 安装成功`)
        // 更新项目状态
        await loadProjectStatus(projectDir)
        return true
      } else {
        ElMessage.error(data.message || '安装失败')
        return false
      }
    } catch (error: any) {
      ElMessage.error(`安装技能失败: ${error.message || '未知错误'}`)
      console.error('Failed to install skill:', error)
      return false
    } finally {
      operatingSkillIds.value.delete(skillId)
    }
  }

  /**
   * 卸载技能
   * @param skillId 技能 ID
   * @param projectDir 项目目录
   */
  async function uninstallSkill(skillId: string, projectDir: string) {
    if (!projectDir) {
      ElMessage.warning('请先选择项目目录')
      return false
    }

    const skill = skills.value.find(s => s.id === skillId)
    if (!skill) {
      ElMessage.error('技能不存在')
      return false
    }

    operatingSkillIds.value.add(skillId)
    try {
      const response = await skillMarketApi.uninstallSkill({
        skillId,
        projectDir
      })
      // 后端返回 ResponseEntity<Map>，拦截器直接返回 data
      const data = response as unknown as { success: boolean; message: string }

      if (data.success) {
        ElMessage.success(`技能 "${skill.name}" 已卸载`)
        // 更新项目状态
        await loadProjectStatus(projectDir)
        return true
      } else {
        ElMessage.error(data.message || '卸载失败')
        return false
      }
    } catch (error: any) {
      ElMessage.error(`卸载技能失败: ${error.message || '未知错误'}`)
      console.error('Failed to uninstall skill:', error)
      return false
    } finally {
      operatingSkillIds.value.delete(skillId)
    }
  }

  /**
   * 检查技能是否已安装
   * @param skillId 技能 ID
   */
  function isSkillInstalled(skillId: string) {
    return installedSkillIds.value.has(skillId)
  }

  /**
   * 检查技能是否正在操作中
   * @param skillId 技能 ID
   */
  function isSkillOperating(skillId: string) {
    return operatingSkillIds.value.has(skillId)
  }

  /**
   * 获取技能的安装状态信息
   * @param skillId 技能 ID
   */
  function getSkillInstallStatus(skillId: string): ProjectSkillStatus | undefined {
    return projectStatus.value.find(status => status.skillId === skillId)
  }

  /**
   * 设置分类筛选
   * @param category 分类
   */
  function setCategory(category: SkillCategory | '') {
    selectedCategory.value = category
  }

  /**
   * 设置搜索关键词
   * @param keyword 关键词
   */
  function setSearchKeyword(keyword: string) {
    searchKeyword.value = keyword
  }

  /**
   * 清空筛选条件
   */
  function clearFilters() {
    selectedCategory.value = ''
    searchKeyword.value = ''
  }

  return {
    // State
    skills,
    projectStatus,
    loading,
    statusLoading,
    selectedCategory,
    searchKeyword,
    categoryStats,
    totalSkills,
    // Computed
    filteredSkills,
    installedSkillIds,
    // Actions
    loadSkills,
    loadProjectStatus,
    installSkill,
    uninstallSkill,
    isSkillInstalled,
    isSkillOperating,
    getSkillInstallStatus,
    setCategory,
    setSearchKeyword,
    clearFilters
  }
})