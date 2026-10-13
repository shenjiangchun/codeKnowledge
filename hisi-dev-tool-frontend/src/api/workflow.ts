/**
 * Workflow API layer — unified endpoints for all workflow types.
 */
import request from '@/utils/request'
import type {
  WorkflowStartRequest,
  WorkflowStartResponse,
  WorkflowStatusResponse,
  WorkflowReportResponse,
  WorkflowEvent,
  WorkflowDefinition,
  NodeInfo,
  WorkflowClarifyRequest,
  WorkflowConfirmRequest,
} from '@/types/workflow'

const BASE = '/workflow'

const TOKEN_KEY = 'hisi-token'

/** Start a workflow session */
export function startWorkflow(payload: WorkflowStartRequest): Promise<WorkflowStartResponse> {
  return request.post(`${BASE}/start`, payload)
}

/** Get session status */
export function getWorkflowStatus(sessionId: string): Promise<WorkflowStatusResponse> {
  return request.get(`${BASE}/sessions/${sessionId}/status`)
}

/** Get session report (latest checkpoint) */
export function getWorkflowReport(
  sessionId: string,
  nodeName?: string,
): Promise<WorkflowReportResponse> {
  const params = nodeName ? { nodeName } : {}
  return request.get(`${BASE}/sessions/${sessionId}/report`, { params })
}

/** Get all session events */
export function getWorkflowEvents(sessionId: string): Promise<WorkflowEvent[]> {
  return request.get(`${BASE}/sessions/${sessionId}/events`)
}

/** Build the SSE stream URL */
export function workflowStreamUrl(sessionId: string, afterSeq = 0): string {
  const base = (request.defaults?.baseURL ?? '').replace(/\/+$/, '')
  // EventSource 无法携带 Authorization 头，token 经查询参数传给后端（镜像 WebSocket 的 ?token= 握手）。
  const params = new URLSearchParams({ afterSeq: String(afterSeq) })
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) params.set('token', token)
  return `${base}${BASE}/sessions/${sessionId}/stream?${params.toString()}`
}

/** Submit clarification answers */
export function submitWorkflowClarify(
  sessionId: string,
  payload: WorkflowClarifyRequest,
): Promise<void> {
  return request.post(`${BASE}/sessions/${sessionId}/clarify`, payload)
}

/** Submit HITL confirmation */
export function submitWorkflowConfirm(
  sessionId: string,
  payload: WorkflowConfirmRequest,
): Promise<void> {
  return request.post(`${BASE}/sessions/${sessionId}/confirm`, payload)
}

/** Rerun from a specific node */
export function rerunWorkflowFromNode(
  sessionId: string,
  nodeName: string,
): Promise<void> {
  return request.post(`${BASE}/sessions/${sessionId}/rerun-from/${nodeName}`)
}

/** Abort a running session */
export function abortWorkflow(sessionId: string): Promise<void> {
  return request.post(`${BASE}/sessions/${sessionId}/abort`)
}

/** List all registered workflow definitions */
export function listWorkflowDefinitions(): Promise<WorkflowDefinition[]> {
  return request.get(`${BASE}/definitions`)
}

/** List all available node types */
export function listWorkflowNodes(): Promise<Record<string, NodeInfo>> {
  return request.get(`${BASE}/nodes`)
}
