import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { configApi } from '@/api/config'

const LS_KEY = 'hisi-selected-projects'

export interface SelectedProjectInfo {
  name: string
  path: string
}

export const useAppStore = defineStore('app', () => {
  // State
  const projectDir = ref<string>('')
  const projectDirConfigured = computed(() => projectDir.value.trim() !== '')

  // 多项目选择（存储项目名称+路径） — 从 localStorage 恢复
  const selectedProjects = ref<SelectedProjectInfo[]>(loadPersistedProjects())
  const projectSelected = computed(() => selectedProjects.value.length > 0)

  // Persist project selection to localStorage
  watch(selectedProjects, (val) => {
    try {
      localStorage.setItem(LS_KEY, JSON.stringify(val))
    } catch { /* quota exceeded — ignore */ }
  }, { deep: true })

  function loadPersistedProjects(): SelectedProjectInfo[] {
    try {
      const raw = localStorage.getItem(LS_KEY)
      if (raw) {
        const parsed = JSON.parse(raw)
        if (Array.isArray(parsed)) return parsed
      }
    } catch { /* ignore */ }
    return []
  }

  // 向后兼容：返回第一个选中的项目名称
  const selectedProject = computed(() => selectedProjects.value[0]?.name || '')

  // 选中项目名称列表（用于显示）
  const selectedProjectNames = computed(() => selectedProjects.value.map(p => p.name))

  // Config loading state
  const configLoading = ref(false)
  const configError = ref<string>('')

  // Menu availability - requires project selection for analysis features
  // Note: projectDirConfigured is NOT required — remote projects may exist
  // without a local scan root configured. Only projectSelected matters.
  const availableMenus = computed(() => ({
    'project-management': true,
    'skill-market': true,
    'claude-terminal': true,
    'search': projectSelected.value,
    'knowledge-graph': projectSelected.value,
    'log-analysis': true, // 日志分析始终可用，不依赖项目选择
    'ram': true, // 需求分析大师 - 有内部项目选择器
    'ram-demand': true, // 需求分析大师 - 有内部项目选择器
    'ram-status': true, // 项目现状分析 - 有内部项目选择器
    'ram-chat': true, // RAM 对话 - 有内部项目选择器
    'merge-analysis': true, // Has its own project selector inside
    'prompt-config': true,
    'settings': true,
    'apm-debug': true,
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
