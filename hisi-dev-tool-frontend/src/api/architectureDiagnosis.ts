import request from '@/utils/request'
import type { DiagnosisReport, SnapshotHistoryResponse } from '@/types/architectureDiagnosis'

/**
 * 生成诊断报告
 * @param persist 落快照（默认 false：实时查询，不落快照）
 */
export function getReport(projectPaths: string[], language?: string, persist = false): Promise<DiagnosisReport> {
  const params = new URLSearchParams()
  projectPaths.forEach(p => params.append('projectPaths', p))
  if (language) params.set('language', language)
  params.set('persist', String(persist))
  return request.get(`/architecture-diagnosis/report?${params}`)
}

/**
 * 查询快照历史
 */
export function getSnapshots(scope: string, limit = 20): Promise<SnapshotHistoryResponse> {
  return request.get(`/architecture-diagnosis/snapshots?scope=${encodeURIComponent(scope)}&limit=${limit}`)
}

/**
 * 获取快照完整报告体（供「查看历史」与「进入页面展示最新快照」）
 */
export function getSnapshotReport(snapshotId: number): Promise<DiagnosisReport> {
  return request.get(`/architecture-diagnosis/report/snapshot?snapshotId=${snapshotId}`)
}

/**
 * 导出报告为 Markdown（Blob 下载）
 * @param params - { projectPaths } 即时生成 或 { snapshotId } 历史还原
 */
export async function exportReport(params: { projectPaths: string[]; language?: string } | { snapshotId: number }): Promise<Blob> {
  const query = new URLSearchParams()
  if ('snapshotId' in params) {
    query.set('snapshotId', String(params.snapshotId))
  } else {
    params.projectPaths.forEach(p => query.append('projectPaths', p))
    if (params.language) query.set('language', params.language)
  }
  const response = await fetch(`/api/architecture-diagnosis/report/export?${query}`, {
    headers: { Authorization: `Bearer ${localStorage.getItem('hisi-token') || ''}` }
  })
  if (!response.ok) {
    throw new Error(`Export failed: ${response.status}`)
  }
  return response.blob()
}
