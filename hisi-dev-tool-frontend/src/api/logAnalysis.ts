import request from '@/utils/request'
import type {
  LogQueryDto, LogAnalyzeRequest, AnalyzeTaskResponse,
  DetailedAnalysisReport, ReportListResponse,
  FollowupStartResponse, FollowupContinueResponse,
  LogQueryResponse, ReportStatus
} from '@/types/log'

export interface AppLogConfig {
  id?: number
  appId: string
  projectPath: string
  dslQuery: string
  pullIntervalMinutes: number
  enabled: boolean
  lastPullAt?: number
}

// 分析流程的接口版本。v2 走「取证 → 假设 → 验证 → 综合」的新 agent 流，请求与响应结构与 v1 完全一致。
// 回滚到旧流程时把这里改回 '/log' 即可；列表、导出、追问不在 agent 流内，仍用原接口。
const LOG_FLOW_BASE = '/v2/log'

export const logAnalysisApi = {
  // 查询日志
  queryLogs(data: LogQueryDto): Promise<LogQueryResponse> {
    return request.post('/log/query', data)
  },

  // 提交分析任务
  analyze(data: LogAnalyzeRequest): Promise<AnalyzeTaskResponse> {
    return request.post(`${LOG_FLOW_BASE}/analyze`, data) as Promise<AnalyzeTaskResponse>
  },

  // 获取报告列表
  getReports(params?: { userId?: string; status?: string; startTime?: string; endTime?: string; page?: number; pageSize?: number }): Promise<ReportListResponse> {
    return request.get('/log/reports', { params }) as Promise<ReportListResponse>
  },

  // Task 71: 按应用ID获取报告列表
  getReportsByAppId(appId: string): Promise<ReportListResponse> {
    return request.get(`/log/reports/by-app/${appId}`) as Promise<ReportListResponse>
  },

  // Task 71: 按配置ID获取报告列表
  getReportsByConfigId(configId: number): Promise<ReportListResponse> {
    return request.get(`/log/reports/by-config/${configId}`) as Promise<ReportListResponse>
  },

  // 获取报告详情
  getReport(id: string): Promise<DetailedAnalysisReport> {
    return request.get(`${LOG_FLOW_BASE}/report/${id}`) as Promise<DetailedAnalysisReport>
  },

  // 获取任务状态
  getStatus(id: string): Promise<ReportStatus> {
    return request.get(`${LOG_FLOW_BASE}/report/${id}/status`)
  },

  // 重新分析报告
  reanalyze(id: string): Promise<string> {
    return request.post(`${LOG_FLOW_BASE}/report/${id}/reanalyze`)
  },

  // 删除报告
  deleteReport(id: string): Promise<void> {
    return request.delete(`/log/report/${id}`)
  },

  // ========== 日志拉取配置 ==========

  // 获取已图谱化项目列表（用于下拉选择）
  getGraphedProjects(): Promise<string[]> {
    return request.get('/log/config/graphed-projects') as Promise<string[]>
  },

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
  },

  // ========== 报告导出 ==========

  // 导出单个报告为 Markdown
  exportReportMd(id: string): Promise<Blob> {
    return request.get(`/log/report/${id}/export/md`, { responseType: 'blob' })
  },

  // 批量导出报告为 ZIP（带时间范围筛选）
  exportReportsZip(startTime?: string, endTime?: string): Promise<Blob> {
    return request.get('/log/reports/export/zip', {
      params: { startTime, endTime },
      responseType: 'blob'
    })
  },

  // ========== 追问 Follow-up ==========

  // 启动追问会话
  startFollowup(reportId: string, message: string): Promise<FollowupStartResponse> {
    return request.post(`/log/report/${reportId}/followup`, { message }) as Promise<FollowupStartResponse>
  },

  // 继续追问
  continueFollowup(sessionId: string, message: string): Promise<FollowupContinueResponse> {
    return request.post(`/log/followup/${sessionId}/message`, { message }) as Promise<FollowupContinueResponse>
  }
}
