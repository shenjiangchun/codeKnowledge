import request from '@/utils/request'

export interface VectorSearchRequest {
  query: string
  projectPath?: string
  projectPaths?: string[]
  limit?: number
  graphDepth?: number
  language?: string
}

export interface VectorSearchResult {
  nodeId: string
  className: string
  methodName: string
  signature: string
  description: string
  filePath: string
  startLine: number
  endLine: number
  complexity: number
  methodBody?: string
  callers?: any[]
  callees?: any[]
  /** 相似度分数（0-1，仅在 items 中由后端返回） */
  similarityScore?: number
}

/**
 * 增强搜索结果条目（包含相似度分数与关联上下文）
 * 对应后端 SearchResultItem
 */
export interface VectorSearchResultItem {
  nodeId: string
  nodeType?: string
  className: string
  methodName: string
  signature: string
  filePath: string
  startLine: number
  endLine: number
  description: string
  similarityScore?: number
  callers?: any[]
  callees?: any[]
  entryPoints?: any[]
  sqlNodes?: any[]
}

export interface VectorSearchResponse {
  query: string
  intent: {
    entity: string | null
    methodType: string | null
    serviceName: string | null
    keywords: string[]
  }
  results: VectorSearchResult[]
  /** 增强结果（携带 similarityScore），与 results 按 nodeId 一一对应 */
  items?: VectorSearchResultItem[]
  totalCount: number
  costTimeMs: number
  /** 分词后的子查询列表（v2 多路召回时由 QueryDecomposer 生成） */
  subQueries?: string[]
  /** RRF 融合得分（nodeId → score），仅多路召回时存在 */
  rrfScores?: Record<string, number>
}

export const vectorSearchApi = {
  /** @deprecated 使用 searchV2 替代，支持分词多路召回和 RRF 融合 */
  search(params: VectorSearchRequest): Promise<VectorSearchResponse> {
    return request.post('/vector-search', params)
  },

  /** 多路召回 + RRF 融合搜索（v2），返回 subQueries 和 rrfScores */
  searchV2(params: VectorSearchRequest): Promise<VectorSearchResponse> {
    return request.post('/vector-search/v2', params)
  }
}
