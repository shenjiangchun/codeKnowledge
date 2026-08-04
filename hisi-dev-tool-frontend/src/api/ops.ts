import request from '@/utils/request'

export const opsApi = {
  // 健康检查
  getHealth() {
    return request.get('/ops/health')
  },

  // 影响范围分析
  runImpactAnalysis(data: { project: string; type: string; target: string }) {
    return request.post('/ops/analysis/impact', data)
  },

  // 获取项目列表
  getProjects() {
    return request.get('/callchain/projects')
  },

  // 生成接口文档
  generateDocs(params: { project: string }) {
    return request.get('/ops/docs/interface', { params })
  },

  // 下载错误日志
  downloadLogs(data: { startTime: string; endTime: string; appId?: string }) {
    return request.post('/ops/logs/download', data, { responseType: 'blob' })
  }
}