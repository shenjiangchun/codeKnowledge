import request from '@/utils/request'

export interface VectorSearchRequest {
  query: string
  projectPath?: string
  projectPaths?: string[]
  limit?: number
  graphDepth?: number
  language?: string
}

/** 调用者摘要（对应后端 SearchResultItem.CallerSummary） */
export interface CallerSummary {
  className: string
  methodName: string
  signature: string
}

/** 被调用者摘要（对应后端 SearchResultItem.CalleeSummary） */
export interface CalleeSummary {
  className: string
  methodName: string
  signature: string
}

/** 入口点摘要（对应后端 SearchResultItem.EntryPointSummary） */
export interface EntryPointSummary {
  entryType: string
  entryKey: string
}

/** SQL 摘要（对应后端 SearchResultItem.SqlSummary） */
export interface SqlSummary {
  sqlId: string
  statementType: string
  sqlStatement: string
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
  callers?: CallerSummary[]
  callees?: CalleeSummary[]
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
  /** 命中该结果的子查询列表（多路召回时记录，前端按分词筛选用） */
  matchedSubQueries?: string[]
  callers?: CallerSummary[]
  callees?: CalleeSummary[]
  entryPoints?: EntryPointSummary[]
  sqlNodes?: SqlSummary[]
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
  /** 多路召回 + RRF 融合搜索（v2），返回 subQueries 和 rrfScores */
  searchV2(params: VectorSearchRequest): Promise<VectorSearchResponse> {
    return request.post('/vector-search/v2', params)
  }
}
