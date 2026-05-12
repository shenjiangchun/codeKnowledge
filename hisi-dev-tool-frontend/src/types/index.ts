// Re-export all types
// Note: Some types are defined in multiple files, we use explicit exports to resolve conflicts
export * from './log'
export * from './callchain'
export * from './search'
export * from './api'
export * from './skill'

// From agent.ts - Agent-specific types
export {
  AgentStatus,
  AgentType,
  AgentEventType
} from './agent'
export type {
  AgentEvent,
  AgentResult,
  FinalDiagnosticResult,
  CodeLocation,
  DiagnosisRequest,
  DiagnosisClientMessage,
  DiagnosisServerMessage,
  ConnectionStatus as AgentConnectionStatus
} from './agent'

// From dialog.ts - Dialog WebSocket types
export {
  DialogEventType
} from './dialog'
export type {
  IntentType as DialogIntentType,
  DialogPhase,
  AgentDiagnosticStatus,
  InterventionRequest,
  InterventionResponse,
  StreamChunk,
  DialogClientMessage,
  DialogServerMessage,
  DialogFinalResult,
  DialogSessionState,
  ConnectionStatus as DialogConnectionStatus,
  IntentResult as DialogIntentResult
} from './dialog'

// From intent.ts - Natural language intent types (primary IntentResult)
export {
  INTENT_NAMES,
  INTENT_COLORS
} from './intent'
export type {
  IntentType,
  IntentResult,
  IntentEntities,
  DialogMessage,
  DialogSession,
  DialogContext,
  NaturalLanguageRequest,
  NaturalLanguageStreamEvent,
  NaturalLanguageCallbacks,
  DiagnosisExecutionRequest,
  ProgressInfo
} from './intent'