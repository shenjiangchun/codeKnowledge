import request from '@/utils/request'

export interface ProjectBinding {
  id: number
  backendPath: string
  frontendPath: string
  createdAt?: number
  updatedAt?: number
}

export interface BuildFullGraphResult {
  backendPath: string
  frontendPath: string
  apiClients: number
  routes: number
  linked: number
  componentEmbeddings?: number
  routeEmbeddings?: number
  apiClientEmbeddings?: number
  totalEmbeddings?: number
  status?: string
}

export const bindingApi = {
  /** 查询全部绑定记录 */
  list(): Promise<ProjectBinding[]> {
    return request.get('/knowledge-graph/binding')
  },

  /** 创建绑定 */
  create(backendPath: string, frontendPath: string): Promise<ProjectBinding> {
    return request.post('/knowledge-graph/binding', { backendPath, frontendPath })
  },

  /** 删除绑定 */
  delete(id: number): Promise<{ id: number; deleted: boolean }> {
    return request.delete(`/knowledge-graph/binding/${id}`)
  },

  /** 构建前后端完整图谱（建立/复用绑定 + 前端实体化 + 向量化 + 跨层链接） */
  build(backendPath: string, frontendPath: string): Promise<BuildFullGraphResult> {
    return request.post('/knowledge-graph/binding/build', { backendPath, frontendPath })
  }
}
