import request from '@/utils/request'

export interface ApiConsumer {
  url: string
  sourceFile: string
  componentName: string | null
}

export interface BackendDep {
  entryId: string
  entryKey: string
  entryType: string
}

export interface ApiConsumersResponse {
  entryId: string
  consumers: ApiConsumer[]
}

export interface BackendDepsResponse {
  apiClientId: string
  deps: BackendDep[]
}

/**
 * 跨层关系查询 API（前端 → 后端）。
 */
export const frontendBackendApi = {
  /**
   * 查某后端接口的前端调用方列表。
   */
  getApiConsumers(entryId: string): Promise<ApiConsumersResponse> {
    return request.get('/v2/knowledge-graph/api-consumers', { params: { entryId } })
  },

  /**
   * 查某前端 ApiClient 调用的后端接口列表。
   */
  getBackendDeps(apiClientId: string): Promise<BackendDepsResponse> {
    return request.get('/v2/knowledge-graph/backend-deps', { params: { apiClientId } })
  },
}
