/**
 * 语义搜索 API
 */

import request from '@/utils/request'
import type {
  SemanticSearchRequest,
  SemanticSearchResponse,
  CodeNode
} from '@/types/search'

/** @deprecated 使用 semanticSearchV2 替代，支持分词多路召回和 RRF 融合 */
export async function semanticSearch(params: SemanticSearchRequest): Promise<SemanticSearchResponse> {
  // axios 拦截器已解包，request.post 直接返回 data
  return request.post('/search/semantic', params)
}

/** 多路召回 + RRF 融合语义搜索（v2），返回 subQueries 和 rrfScores */
export async function semanticSearchV2(params: SemanticSearchRequest): Promise<SemanticSearchResponse & {
  subQueries?: string[]
  rrfScores?: Record<string, number>
}> {
  return request.post('/search/semantic/v2', params)
}

// 获取搜索结果详情（代码节点）
export async function getCodeNode(nodeId: string): Promise<CodeNode> {
  return request.get(`/search/node/${nodeId}`)
}

// 获取代码节点的关系
export async function getNodeRelations(nodeId: string): Promise<CodeNode[]> {
  return request.get(`/search/node/${nodeId}/relations`)
}

// 获取搜索建议
export async function getSearchSuggestions(query: string): Promise<string[]> {
  return request.get('/search/suggestions', {
    params: { query, limit: 5 }
  })
}

// 获取搜索历史
export async function getSearchHistory(limit: number = 20): Promise<string[]> {
  return request.get('/search/history', {
    params: { limit }
  })
}

// 获取代码上下文（扩展示代码片段）
export async function getCodeContext(
  filePath: string,
  lineNumber: number,
  contextLines: number = 10
): Promise<{ before: string; target: string; after: string }> {
  return request.get('/search/context', {
    params: { filePath, lineNumber, contextLines }
  })
}