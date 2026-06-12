import request from '@/utils/request'
import type { LogQueryDto, LogAnalyzeRequest, AnalyzeTaskResponse, DetailedAnalysisReport, ReportListResponse } from '@/types/log'

export interface AppLogConfig {
  id?: number
  appId: string
  projectPath: string
  dslQuery: string
  pullIntervalMinutes: number
  enabled: boolean
  lastPullAt?: number
}

export const logAnalysisApi = {
  // 查询日志
  queryLogs(data: LogQueryDto): Promise<any> {
    return request.post('/log/query', data)
  },

  // 提交分析任务
  analyze(data: LogAnalyzeRequest): Promise<AnalyzeTaskResponse> {
    return request.post('/log/analyze', data) as Promise<AnalyzeTaskResponse>
  },

  // 获取报告列表
  getReports(params?: { userId?: string; status?: string; page?: number; pageSize?: number }): Promise<ReportListResponse> {
    return request.get('/log/reports', { params }) as Promise<ReportListResponse>
  },

  // 获取报告详情
  getReport(id: number): Promise<DetailedAnalysisReport> {
    return request.get(`/log/report/${id}`) as Promise<DetailedAnalysisReport>
  },

  // 获取任务状态
  getStatus(id: number): Promise<any> {
    return request.get(`/log/report/${id}/status`)
  },

  // ========== 日志拉取配置 ==========

  // 获取所有配置
  getConfigs(): Promise<AppLogConfig[]> {
    return request.get('/log/config') as Promise<AppLogConfig[]>
  },

  // 获取单个配置
  getConfig(appId: string): Promise<AppLogConfig> {
    return request.get(`/log/config/${appId}`) as Promise<AppLogConfig>
  },

  // 创建或更新配置
  saveConfig(config: AppLogConfig): Promise<AppLogConfig> {
    return request.post('/log/config', config) as Promise<AppLogConfig>
  },

  // 删除配置
  deleteConfig(appId: string): Promise<void> {
    return request.delete(`/log/config/${appId}`)
  },

  // 切换启用状态
  toggleConfig(appId: string): Promise<AppLogConfig> {
    return request.post(`/log/config/${appId}/toggle`) as Promise<AppLogConfig>
  }
}