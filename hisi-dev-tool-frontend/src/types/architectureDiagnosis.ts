/**
 * 架构诊断报告类型定义
 * 与后端 ArchDiagnosisReportService 返回结构一致
 */

export type Severity = 'HIGH' | 'MEDIUM' | 'LOW'
export type Grade = 'A' | 'B' | 'C' | 'D'

/** 扣分明细 */
export interface Deduction {
  source: string
  sourceLevel: string
  severity: Severity
  points: number
  reason: string
}

/** 问题清单项 */
export interface Issue {
  source: string
  sourceLevel: string
  severity: Severity
  message: string
}

/** 方法级热点 */
export interface MethodHotspot {
  nodeId: string
  className: string
  methodName: string
  filePath: string
  complexity: number
  commitCount90d: number
  inDegree: number
  riskScore: number
}

/** 类级度量（上帝类/枢纽类） */
export interface ClassMetric {
  className: string
  methodCount: number
  wmc: number
  avgComplexity: number
  inDegree: number
  outDegree: number
}

/** 类级度量排行结果 */
export interface ClassMetricsResult {
  godClasses: ClassMetric[]
  hubClasses: ClassMetric[]
  thresholds: {
    wmc95: number
    methodCount95: number
    in95: number
    out95: number
  }
}

/** 报告章节 */
export interface ReportChapters {
  project: Record<string, unknown>
  module: Record<string, unknown>
  class: Record<string, unknown>
  method: Record<string, unknown>
}

/** 诊断报告 */
export interface DiagnosisReport {
  healthScore: number
  grade: Grade
  deductions: Deduction[]
  issues: Issue[]
  chapters: ReportChapters
  layerOrder: string[]
  projectPaths: string[]
  generatedAt: string
}

/** 快照历史项 */
export interface SnapshotItem {
  id: number
  scope: string
  healthScore: number | null
  grade: string | null
  highCount: number | null
  mediumCount: number | null
  lowCount: number | null
  createdAt: string
}

/** 快照历史响应 */
export interface SnapshotHistoryResponse {
  snapshots: SnapshotItem[]
  total: number
}
