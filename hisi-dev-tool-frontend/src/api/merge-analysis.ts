import request from '@/utils/request'
import type { DiffResult } from '@/types/merge-analysis'

/** 图片数据格式（OpenAI Vision API） */
export interface ImageContent {
  type: 'image_url'
  image_url: {
    url: string // data:image/jpeg;base64,... 或 URL
  }
}

export function listBranches(projectPath: string): Promise<string[]> {
  return request.get('/merge-analysis/branches', { params: { projectPath } })
}

export function getDiff(data: {
  projectPath: string
  sourceBranch: string
  targetBranch: string
}): Promise<DiffResult> {
  return request.post('/merge-analysis/diff', data)
}

export function startMergeAnalysis(data: {
  projectPath: string
  sourceBranch: string
  targetBranch: string
  /** 多模态图片输入（Base64 格式） */
  images?: ImageContent[]
}): Promise<{ sessionHandle: string }> {
  return request.post('/merge-analysis/sessions', data)
}

export function getMergeAnalysisSession(sessionId: string): Promise<{
  status: string
  currentNode: string
  lastSeq: number
}> {
  return request.get(`/merge-analysis/sessions/${sessionId}`)
}

export function mergeAnalysisStreamUrl(sessionId: string, afterSeq = 0): string {
  const base = `/api/merge-analysis/sessions/${sessionId}/stream`
  return afterSeq > 0 ? `${base}?afterSeq=${afterSeq}` : base
}

// ──────────────── Session History ────────────────

export interface MergeSessionSummary {
  sessionId: string | null
  status: string | null
  currentNode: string | null
  intent: string | null
  projectPaths: string | null
  sourceBranch: string | null
  targetBranch: string | null
  createdAt: number
  updatedAt: number
}

export function listMergeAnalysisSessions(limit = 50): Promise<MergeSessionSummary[]> {
  return request.get('/merge-analysis/sessions', { params: { limit } })
}

export interface MergeEvent {
  seq: number
  type: string | null
  payload: Record<string, unknown>
  createdAt: number
}

export function getMergeAnalysisSessionEvents(sessionId: string): Promise<MergeEvent[]> {
  return request.get(`/merge-analysis/sessions/${sessionId}/events`)
}

export function rerunMergeAnalysisNode(
  sessionId: string,
  nodeName: string
): Promise<Record<string, unknown>> {
  return request.post(`/merge-analysis/sessions/${sessionId}/rerun-from/${nodeName}`)
}

// ──────────────── Session Export ────────────────

/** Export merge analysis session as Markdown file. */
export function exportMergeAnalysisMd(sessionId: string): Promise<Blob> {
  return request.get(`/merge-analysis/sessions/${sessionId}/export/md`, { responseType: 'blob' })
}
