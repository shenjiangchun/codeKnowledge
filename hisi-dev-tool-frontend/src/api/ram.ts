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

export interface StartSessionPayload {
  rawInput: string
  projectPath?: string
  projectPaths?: string[]
  userId?: string
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
}

export function listRamSessions(limit = 50): Promise<SessionSummary[]> {
  return request.get('/ram/sessions', { params: { limit } })
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
