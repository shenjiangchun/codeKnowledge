import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { PromptTemplate, SceneType } from '@/types/session'
import { promptApi } from '@/api/prompt'

export const usePromptStore = defineStore('prompt', () => {
  // 状态
  const templates = ref<PromptTemplate[]>([])
  const loading = ref(false)

  // Actions

  /**
   * 加载模板列表
   */
  async function loadTemplates() {
    loading.value = true
    try {
      const response = await promptApi.list()
      // axios 拦截器已解包，response 直接是数据数组
      templates.value = response
    } catch (error) {
      console.error('Failed to load templates:', error)
    } finally {
      loading.value = false
    }
  }

  /**
   * 获取模板
   */
  function getTemplate(key: SceneType): PromptTemplate | undefined {
    return templates.value.find(t => t.templateKey === key)
  }

  /**
   * 更新模板
   */
  async function updateTemplate(key: string, content: string, variables?: string) {
    try {
      await promptApi.update(key, { content, variables })
      const template = templates.value.find(t => t.templateKey === key)
      if (template) {
        template.content = content
        if (variables) {
          template.variables = variables
        }
      }
    } catch (error) {
      console.error('Failed to update template:', error)
    }
  }

  /**
   * 渲染模板（替换变量）
   */
  function render(key: SceneType, variables: Record<string, string>): string {
    const template = getTemplate(key)
    if (!template) {
      console.warn('Template not found:', key)
      return ''
    }

    let content = template.content
    for (const [varName, value] of Object.entries(variables)) {
      content = content.replace(new RegExp(`#\\{${varName}\\}`, 'g'), value || '')
    }
    return content
  }

  /**
   * 从模板内容提取变量列表
   */
  function extractVariables(content: string): string[] {
    const regex = /#\{([a-zA-Z_][a-zA-Z0-9_]*)\}/g
    const variables: string[] = []
    let match
    while ((match = regex.exec(content)) !== null) {
      if (!variables.includes(match[1])) {
        variables.push(match[1])
      }
    }
    return variables
  }

  return {
    // 状态
    templates,
    loading,
    // Actions
    loadTemplates,
    getTemplate,
    updateTemplate,
    render,
    extractVariables
  }
})
