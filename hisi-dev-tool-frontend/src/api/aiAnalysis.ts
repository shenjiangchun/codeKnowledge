import request from '@/utils/request'

export interface CallChainPromptRequest {
  entryKey: string
  projectPath: string
}

export interface ImpactPromptRequest {
  className: string
  methodName: string
  projectPath: string
}

export interface LogPromptRequest {
  errorMessage?: string
  errorType?: string
  stackTrace?: string
  projectPath?: string
}

export interface MethodPromptRequest {
  nodeId: string
  projectPath?: string
}

export interface AIPromptResponse {
  prompt: string
  scene: string
  promptLength: number
  [key: string]: unknown
}

export const aiAnalysisApi = {
  /**
   * 生成调用链分析提示词
   * 后端从 Neo4j 拉取完整调用链数据组装为结构化富提示词
   */
  buildCallChainPrompt(data: CallChainPromptRequest) {
    return request.post<AIPromptResponse>('/ai-analysis/call-chain/prompt', data)
  },

  /**
   * 生成影响分析提示词
   * 后端从 Neo4j 拉取上下游调用关系、受影响入口点
   */
  buildImpactPrompt(data: ImpactPromptRequest) {
    return request.post<AIPromptResponse>('/ai-analysis/impact/prompt', data)
  },

  /**
   * 生成日志/异常分析提示词
   * 后端从堆栈信息中提取类名，关联知识图谱中的代码上下文
   */
  buildLogPrompt(data: LogPromptRequest) {
    return request.post<AIPromptResponse>('/ai-analysis/log/prompt', data)
  },

  /**
   * 生成方法级分析提示词
   * 后端从 Neo4j 获取单个方法的代码和上下游信息
   */
  buildMethodPrompt(data: MethodPromptRequest) {
    return request.post<AIPromptResponse>('/ai-analysis/method/prompt', data)
  }
}
