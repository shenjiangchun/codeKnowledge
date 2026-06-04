export interface FileDiff {
  filePath: string
  changeType: 'ADD' | 'MODIFY' | 'DELETE' | 'RENAME'
  additions: number
  deletions: number
  patch: string
}

export interface DiffResult {
  sourceBranch: string
  targetBranch: string
  totalFiles: number
  totalAdditions: number
  totalDeletions: number
  files: FileDiff[]
}

export interface AffectedEntryPoint {
  nodeId: string
  entryType: string
  httpMethod?: string
  urlPattern?: string
  className: string
  methodName: string
}

export interface CallChainEdge {
  callerId: string
  callerName: string
  calleeId: string
  calleeName: string
  callType: string
}

export interface ImpactResult {
  affectedEntryPoints: AffectedEntryPoint[]
  callChainEdges: CallChainEdge[]
  businessImpactSummary: string
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW'
}

export interface TestCase {
  description: string
  riskLevel: string
  reason: string
}

export interface TestCaseGroup {
  entryPointName?: string
  urlPattern?: string
  urlRoot?: string
  coveredEntryCount?: number
  coveredMethods?: string
  riskLevel: string
  testCases: TestCase[]
}

export interface TestScopeResult {
  groups: TestCaseGroup[]
  regressionSuggestions: string[]
}

export interface MergeAnalysisEvent {
  seq: number
  type: string
  payload: Record<string, unknown>
}

export type MergeAnalysisStatus = 'idle' | 'running' | 'completed' | 'error'
