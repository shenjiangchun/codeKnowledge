import request from '@/utils/request'
import type { PromptTemplate, RenderTemplateRequest, UpdatePromptRequest } from '@/types/session'

export const promptApi = {
  /**
   * 获取所有模板
   */
  list() {
    return request.get<PromptTemplate[]>('/prompts')
  },

  /**
   * 获取模板详情
   */
  get(key: string) {
    return request.get<PromptTemplate>(`/prompts/${key}`)
  },

  /**
   * 更新模板
   */
  update(key: string, data: UpdatePromptRequest) {
    return request.put<void>(`/prompts/${key}`, data)
  },

  /**
   * 渲染模板
   */
  render(key: string, variables: RenderTemplateRequest) {
    return request.post<string>(`/prompts/${key}/render`, variables)
  },

  /**
   * 提取变量
   */
  extractVariables(content: string) {
    return request.post<string[]>('/prompts/extract-variables', { content })
  }
}
