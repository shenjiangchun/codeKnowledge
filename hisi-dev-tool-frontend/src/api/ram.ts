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
  ResumeResponse,
  StartSessionResponse
} from '@/types/ram'

export interface StartSessionPayload {
  rawInput: string
  projectPath: string
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

/** Absolute (proxied) URL for the SSE endpoint. */
export function ramStreamUrl(sessionId: string, afterSeq?: number): string {
  const base = `/api/ram/sessions/${sessionId}/stream`
  return typeof afterSeq === 'number' && afterSeq > 0 ? `${base}?afterSeq=${afterSeq}` : base
}

export interface SessionInfoResponse {
  status: string
  currentSeq: number
  clarifyPending: boolean
}

export function getRamSession(sessionId: string): Promise<SessionInfoResponse> {
  return request.get(`/ram/sessions/${sessionId}`)
}
