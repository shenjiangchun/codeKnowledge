import request from '@/utils/request'

export interface VectorGenerationTask {
  id: number
  projectPath: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalMethods: number
  processedMethods: number
  startTime: string | null
  endTime: string | null
  costTimeMs: number | null
  errorMessage: string | null
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

export const vectorGenerationApi = {
  getStatus: getVectorGenerationStatus,
  getStatusBatch: getVectorGenerationStatusBatch,
  start: startVectorGeneration
}
