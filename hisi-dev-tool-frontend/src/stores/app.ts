import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { configApi } from '@/api/config'

export interface SelectedProjectInfo {
  name: string
  path: string
}

export const useAppStore = defineStore('app', () => {
  // State
  const projectDir = ref<string>('')
  const projectDirConfigured = computed(() => projectDir.value.trim() !== '')

  // 多项目选择（存储项目名称+路径）
  const selectedProjects = ref<SelectedProjectInfo[]>([])
  const projectSelected = computed(() => selectedProjects.value.length > 0)

  // 向后兼容：返回第一个选中的项目名称
  const selectedProject = computed(() => selectedProjects.value[0]?.name || '')

  // 选中项目名称列表（用于显示）
  const selectedProjectNames = computed(() => selectedProjects.value.map(p => p.name))

  // Config loading state
  const configLoading = ref(false)
  const configError = ref<string>('')

  // Menu availability - requires project selection for analysis features
  const availableMenus = computed(() => ({
    'project-management': true, // Always available
    'skill-market': true, // Always available
    'claude-terminal': true, // Always available
    'search': projectDirConfigured.value && projectSelected.value,
    'knowledge-graph': projectDirConfigured.value && projectSelected.value,
    'log-analysis': projectDirConfigured.value && projectSelected.value,
    'prompt-config': true, // Always available
    'settings': true, // Always available
    'apm-debug': true, // Always available
  }))

  // Actions
  async function loadProjectDir() {
    configLoading.value = true
    configError.value = ''

    try {
      const response = await configApi.getProjectDir()
      if (response && response.value) {
        projectDir.value = response.value
      }
    } catch (e: any) {
      configError.value = e.message || 'Failed to load configuration'
    } finally {
      configLoading.value = false
    }
  }

  async function updateProjectDir(newPath: string) {
    try {
      await configApi.updateProjectDir(newPath)
      projectDir.value = newPath
      return true
    } catch (e: any) {
      configError.value = e.message || 'Failed to update configuration'
      return false
    }
  }

  /** 设置多个选中项目（替换当前选择） */
  function selectProjects(projects: SelectedProjectInfo[]) {
    selectedProjects.value = [...projects]
  }

  /** 向后兼容：选择单个项目 */
  function selectProject(name: string, path?: string) {
    const resolvedPath = path || `${projectDir.value.replace(/\\/g, '/')}/${name}`
    selectedProjects.value = [{ name, path: resolvedPath }]
  }

  function clearSelectedProject() {
    selectedProjects.value = []
  }

  /**
   * 获取选中项目的完整路径列表（直接使用后端返回的真实路径）
   */
  function getSelectedProjectPaths(): string[] {
    return selectedProjects.value.map(p => p.path.replace(/\\/g, '/'))
  }

  return {
    // State
    projectDir,
    projectDirConfigured,
    selectedProjects,
    selectedProjectNames,
    selectedProject,
    projectSelected,
    configLoading,
    configError,
    availableMenus,
    // Actions
    loadProjectDir,
    updateProjectDir,
    selectProjects,
    selectProject,
    clearSelectedProject,
    getSelectedProjectPaths
  }
})
