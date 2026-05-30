import request from '@/utils/request'
import type { CallChainTask } from '@/types/callchain'

// Response types - axios interceptor returns response.data directly
type ApiTaskResponse = CallChainTask
type ApiTaskListResponse = CallChainTask[]

export const taskApi = {
  /**
   * 启动调用链生成任务
   * @param projectPath 项目完整路径
   */
  startGenerate(projectPath: string): Promise<ApiTaskResponse> {
    return request.post(`/tasks/generate?projectPath=${encodeURIComponent(projectPath)}`)
  },

  /**
   * 批量查询任务状态
   * @param projectPaths 项目完整路径列表，为空则查询所有运行中任务
   */
  getStatus(projectPaths?: string[]): Promise<ApiTaskListResponse> {
    const params = projectPaths && projectPaths.length > 0
      ? { projectPaths: projectPaths.join(',') }
      : {}
    return request.get('/tasks/status', { params })
  },

  /**
   * 获取单个项目最新任务
   * @param projectPath 项目完整路径
   */
  getLatest(projectPath: string): Promise<ApiTaskResponse> {
    return request.get(`/tasks/latest?projectPath=${encodeURIComponent(projectPath)}`)
  }
}