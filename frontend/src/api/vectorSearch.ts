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
}

export const vectorSearchApi = {
  search(params: VectorSearchRequest): Promise<VectorSearchResponse> {
    return request.post('/vector-search', params)
  }
}
