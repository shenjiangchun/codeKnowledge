/**
 * Type definitions for the Requirement Analysis Master (RAM) Phase-1 frontend.
 */

export type RamStatus = 'idle' | 'running' | 'clarify' | 'confirm' | 'completed' | 'error' | 'aborted'

/**
 * A single event streamed from {@code GET /api/ram/sessions/{sid}/stream}.
 *
 * The {@code type} string covers both the persisted {@code agent_event.type}
 * enum (USER_MSG / ASSISTANT_DELTA / TOOL_USE / TOOL_RESULT / CHECKPOINT /
 * CLARIFY_REQ / CLARIFY_RES / HITL_REQ / HITL_RES / ERROR) and synthetic
 * lifecycle markers emitted by the controller (RUN_COMPLETED / RUN_FAILED /
 * RUN_ABORTED / CLARIFY_REQUIRED).
 */
export interface RamEvent {
  readonly seq: number
  readonly type: string
  readonly payload: Readonly<Record<string, unknown>>
}

/**
 * Schema for a clarify-required interrupt. The orchestrator emits an array of
 * open-ended questions; future versions may add JSON-Schema structure here.
 */
export interface ClarifySchema {
  readonly nodeName?: string
  readonly questions: readonly string[]
}

export interface RamCostSnapshot {
  readonly tokens: number
  readonly usd: number
}

export interface StartSessionResponse {
  readonly sessionId: string
}

export interface ClarifyResponse {
  readonly accepted: boolean
  readonly nextSeq: number
}

export interface ResumeResponse {
  readonly resumed: boolean
}

export interface AbortResponse {
  readonly aborted: boolean
}

/**
 * Schema for a node-confirmation interrupt (HITL_REQ). The frontend receives
 * this when the orchestrator pauses after a node completes, waiting for user
 * approval before proceeding to the next node.
 */
export interface HitlSchema {
  readonly nodeName: string
  readonly output: Readonly<Record<string, unknown>>
}

export interface ConfirmResponse {
  readonly accepted: boolean
  readonly nextSeq: number
}
