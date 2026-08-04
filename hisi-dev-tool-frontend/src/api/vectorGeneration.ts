import request from '@/utils/request'

export interface VectorGenerationTask {
  id: number
  projectPath: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalMethods: number
  processedMethods: number
  successCount?: number
  failCount?: number
  startTime: string | null
  endTime: string | null
  costTimeMs: number | null
  errorMessage: string | null
}

export interface MissingEmbeddingPreview {
  nodeId: string
  className: string
  methodName: string
  signature: string
}

export interface MissingEmbeddingInfo {
  totalMethods: number
  missingCount: number
  generatedCount: number
  preview: MissingEmbeddingPreview[]
}

/**
 * 获取向量生成状态
 */
export function getVectorGenerationStatus(projectPath: string) {
  return request.get<VectorGenerationTask | null>(
    `/vector-generation/status`,
    { params: { projectPath } }
  )
}

/**
 * 批量获取向量生成状态
 */
export function getVectorGenerationStatusBatch(projectPaths: string[]) {
  return request.get<VectorGenerationTask[]>(
    `/vector-generation/status/batch`,
    { params: { projectPaths: projectPaths.join(',') } }
  )
}

/**
 * 启动向量生成
 */
export function startVectorGeneration(projectPath: string) {
  return request.post<string>(
    `/vector-generation/start`,
    null,
    { params: { projectPath } }
  )
}

/**
 * 查询缺失描述向量的方法信息
 */
export function getMissingEmbeddings(projectPath: string, limit = 50) {
  return request.get<MissingEmbeddingInfo>(
    `/vector-generation/missing`,
    { params: { projectPath, limit } }
  )
}

/**
 * 补齐缺失向量（只处理 embedding 为 null 的方法）
 */
export function refreshMissing(projectPath: string) {
  return request.post<string>(
    `/vector-generation/refresh-missing`,
    null,
    { params: { projectPath } }
  )
}

/**
 * 全量重新生成（先清除所有描述和向量，再重新生成）
 */
export function regenerateAll(projectPath: string) {
  return request.post<string>(
    `/vector-generation/regenerate`,
    null,
    { params: { projectPath } }
  )
}

export const vectorGenerationApi = {
  getStatus: getVectorGenerationStatus,
  getStatusBatch: getVectorGenerationStatusBatch,
  start: startVectorGeneration,
  getMissing: getMissingEmbeddings,
  refreshMissing,
  regenerateAll,
}
