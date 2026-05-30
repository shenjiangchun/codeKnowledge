import request from '@/utils/request'
import type { LogQueryDto, LogAnalyzeRequest, AnalyzeTaskResponse, DetailedAnalysisReport, ReportListResponse } from '@/types/log'

export const logAnalysisApi = {
  // 查询日志
  queryLogs(data: LogQueryDto) {
    return request.post('/log/query', data)
  },

  // 提交分析任务
  analyze(data: LogAnalyzeRequest) {
    return request.post<AnalyzeTaskResponse>('/log/analyze', data)
  },

  // 获取报告列表
  getReports(params?: { userId?: string; status?: string; page?: number; pageSize?: number }) {
    return request.get<ReportListResponse>('/log/reports', { params })
  },

  // 获取报告详情
  getReport(id: number) {
    return request.get<DetailedAnalysisReport>(`/log/report/${id}`)
  },

  // 获取任务状态
  getStatus(id: number) {
    return request.get(`/log/report/${id}/status`)
  }
}