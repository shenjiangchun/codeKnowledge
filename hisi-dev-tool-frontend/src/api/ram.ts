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
export function ramStreamUrl(sessionId: string): string {
  return `/api/ram/sessions/${sessionId}/stream`
}
