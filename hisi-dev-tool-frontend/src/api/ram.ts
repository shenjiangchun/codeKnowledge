/**
 * REST client for the Requirement Analysis Master (RAM) controller.
 *
 * The interceptor in {@link '@/utils/request'} unwraps the {@code ApiResponse}
 * envelope so each call here resolves directly to the {@code data} payload.
 */
import request from '@/utils/request'
import type {
  AbortResponse,
  ClarifyResponse,
  ConfirmResponse,
  ResumeResponse,
  StartSessionResponse
} from '@/types/ram'

/** 图片数据格式（OpenAI Vision API） */
export interface ImageContent {
  type: 'image_url'
  image_url: {
    url: string // data:image/jpeg;base64,... 或 URL
  }
}

export interface StartSessionPayload {
  rawInput: string
  projectPath?: string
  projectPaths?: string[]
  userId?: string
  /** 多模态图片输入（Base64 格式） */
  images?: ImageContent[]
}

export function startRamSession(payload: StartSessionPayload): Promise<StartSessionResponse> {
  return request.post('/ram/sessions', payload)
}

export function submitRamClarify(
  sessionId: string,
  answers: Record<string, unknown>
): Promise<ClarifyResponse> {
  return request.post(`/ram/sessions/${sessionId}/clarify`, { answers })
}

export function resumeRamSession(sessionId: string): Promise<ResumeResponse> {
  return request.post(`/ram/sessions/${sessionId}/resume`)
}

export function abortRamSession(sessionId: string): Promise<AbortResponse> {
  return request.post(`/ram/sessions/${sessionId}/abort`)
}

export interface ConfirmPayload {
  nodeName: string
  action: 'approve' | 'reject' | 'edit'
  feedback?: string
  editedOutput?: Record<string, unknown>
}

export function confirmRamNode(
  sessionId: string,
  payload: ConfirmPayload
): Promise<ConfirmResponse> {
  return request.post(`/ram/sessions/${sessionId}/confirm`, payload)
}

/** Absolute (proxied) URL for the SSE endpoint. */
export function ramStreamUrl(sessionId: string, afterSeq?: number): string {
  const base = `/api/ram/sessions/${sessionId}/stream`
  return typeof afterSeq === 'number' && afterSeq > 0 ? `${base}?afterSeq=${afterSeq}` : base
}

export interface SessionInfoResponse {
  status: string
  currentSeq: number
  clarifyPending: boolean
  hitlPending: boolean
  hitlNodeName?: string
}

export function getRamSession(sessionId: string): Promise<SessionInfoResponse> {
  return request.get(`/ram/sessions/${sessionId}`)
}

/** Trigger the tech_plan node manually for a session. */
export function executeTechPlan(sessionId: string): Promise<Record<string, unknown>> {
  return request.post(`/ram/sessions/${sessionId}/nodes/tech-plan`)
}

// ──────────────── Session History ────────────────

export interface SessionSummary {
  sessionId: string | null
  status: string | null
  currentNode: string | null
  intent: string | null
  projectPaths: string | null
  createdAt: number
  updatedAt: number
  sessionType?: string
}

export function listRamSessions(limit = 50, sessionType?: string): Promise<SessionSummary[]> {
  return request.get('/ram/sessions', { params: { limit, sessionType } })
}

export interface RamEvent {
  seq: number
  type: string | null
  payload: Record<string, unknown>
  createdAt: number
}

export function getRamSessionEvents(sessionId: string): Promise<RamEvent[]> {
  return request.get(`/ram/sessions/${sessionId}/events`)
}

export function rerunFromNode(sessionId: string, nodeName: string): Promise<Record<string, unknown>> {
  return request.post(`/ram/sessions/${sessionId}/rerun-from/${nodeName}`)
}

export interface ClarifyRoundSummary {
  roundNo: number
  questions: string[]
  answers: Record<string, unknown>
}

export function listClarifyRounds(sessionId: string): Promise<ClarifyRoundSummary[]> {
  return request.get(`/ram/sessions/${sessionId}/clarify-rounds`)
}

export function rerunFromRound(sessionId: string, roundNo: number): Promise<Record<string, unknown>> {
  return request.post(`/ram/sessions/${sessionId}/rerun-from-round/${roundNo}`)
}

export interface RamHealthResponse {
  status: string
  startedAt: number
}

export function getRamHealth(): Promise<RamHealthResponse> {
  return request.get('/ram/health')
}

// ──────────────── Project Status Analysis ────────────────

export interface StartStatusAnalysisPayload {
  projectPath: string
  mode?: 'quick' | 'guided'
  question?: string  // User's question for customized analysis
}

export interface StatusReportResponse {
  status: string
  report: Record<string, unknown>
}

export function startStatusAnalysis(payload: StartStatusAnalysisPayload): Promise<StartSessionResponse> {
  return request.post('/ram/status/start', payload)
}

export function getStatusReport(sessionId: string): Promise<StatusReportResponse> {
  return request.get(`/ram/status/${sessionId}/report`)
}

// ──────────────── Phase2 Precise Location Analysis ────────────────

export interface StartPhase2Payload {
  sessionId: string  // Parent session ID (Phase1)
  question: string   // Question for precise analysis
  focusAreas?: string[]  // Optional focus areas
}

export interface Phase2StartResponse {
  phase2SessionId: string
  status: string
}

export interface Phase2ReportResponse {
  status: string
  report: Record<string, unknown>
}

/** Start Phase2 precise location analysis based on Phase1 session. */
export function startPhase2Analysis(payload: StartPhase2Payload): Promise<Phase2StartResponse> {
  return request.post('/ram/status/phase2/start', payload)
}

/** Get Phase2 analysis report. */
export function getPhase2Report(sessionId: string): Promise<Phase2ReportResponse> {
  return request.get(`/ram/status/phase2/${sessionId}/report`)
}

// ──────────────── Phase2 V2 Multi-Agent Orchestration ────────────────

export interface Phase2V2StartResponse {
  sessionId: string
  status: string
  estimatedChains: number
}

export interface Phase2V2StatusResponse {
  status: string
  progress: {
    chainsTotal: number
    chainsCompleted: number
    currentChain: string
    estimatedTimeRemaining: number
  }
}

export interface Phase2V2ReportResponse {
  status: string
  summaryLayer: Record<string, unknown>
  detailLayer: Record<string, unknown>
}

/** Start Phase2 V2 multi-agent orchestration analysis. */
export function startPhase2V2Analysis(payload: StartPhase2Payload): Promise<Phase2V2StartResponse> {
  return request.post('/ram/status/phase2/v2/start', payload)
}

/** Get Phase2 V2 execution status. */
export function getPhase2V2Status(sessionId: string): Promise<Phase2V2StatusResponse> {
  return request.get(`/ram/status/phase2/v2/${sessionId}/status`)
}

/** Get Phase2 V2 layered report. */
export function getPhase2V2Report(sessionId: string): Promise<Phase2V2ReportResponse> {
  return request.get(`/ram/status/phase2/v2/${sessionId}/report`)
}

// ──────────────── Session Export ────────────────

/** Export RAM session as Markdown file. */
export function exportRamSessionMd(sessionId: string): Promise<Blob> {
  return request.get(`/ram/sessions/${sessionId}/export/md`, { responseType: 'blob' })
}
