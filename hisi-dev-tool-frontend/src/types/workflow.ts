/**
 * Workflow engine types — shared between API layer and composables.
 */

/** Workflow session status */
export type WorkflowStatus =
  | 'idle'
  | 'running'
  | 'clarify'
  | 'confirm'
  | 'completed'
  | 'error'
  | 'aborted'

/** A single workflow event from the SSE stream or REST API */
export interface WorkflowEvent {
  seq: number
  type: string
  payload: Record<string, unknown>
  clarifyRoundNo?: number
}

/** Workflow definition from the backend registry */
export interface WorkflowDefinition {
  workflowType: string
  displayName: string
  description: string
  nodeNames: string[]
  metadata: Record<string, unknown>
}

/** Node info from the backend registry */
export interface NodeInfo {
  name: string
  agentId: string
}

/** Cost snapshot (tokens + optional USD) */
export interface WorkflowCostSnapshot {
  tokens: number
  usd: number
}

/** Clarify questions from CLARIFY_REQUIRED event */
export interface ClarifySchema {
  nodeName?: string
  questions: string[]
}

/** HITL confirmation request from HITL_REQUIRED event */
export interface HitlSchema {
  nodeName: string
  output: Record<string, unknown>
}

/** Start request payload */
export interface WorkflowStartRequest {
  workflowType: string
  [key: string]: unknown
}

/** Start response */
export interface WorkflowStartResponse {
  sessionId: string
}

/** Status response */
export interface WorkflowStatusResponse {
  status: string
  currentNode?: string
}

/** Report response */
export interface WorkflowReportResponse {
  status: string
  report: Record<string, unknown> | null
}

/** Clarify request payload */
export interface WorkflowClarifyRequest {
  answers: Record<string, unknown>
}

/** Confirm request payload */
export interface WorkflowConfirmRequest {
  action: 'approve' | 'reject'
  feedback?: string
}
