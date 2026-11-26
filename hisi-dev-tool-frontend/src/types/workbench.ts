/** 工作台：跨项目聚合的技术债待办与 SDD 自动修复运行。 */

export type Severity = 'P0' | 'P1' | 'P2'
export type ItemStatus = 'NEW' | 'RUNNING' | 'WAITING' | 'DONE' | 'FAILED' | 'IGNORED'
export type ItemSource = 'log' | 'arch_rule' | 'arch_ai'
export type RunStatus = 'RUNNING' | 'WAITING_CONFIRM' | 'DONE' | 'FAILED' | 'ABORTED' | 'INTERRUPTED'

export interface WorkbenchSummary {
  total: number
  open: number
  running: number
  waiting: number
  done: number
  failed: number
  ignored: number
  p0: number
  p1: number
  p2: number
  cleared7d: number
  byProject: Record<string, { total?: number; done?: number; open?: number }>
}

export interface WorkbenchItem {
  id: number
  itemKey: string
  source: ItemSource
  projectPath: string
  title: string
  location: string | null
  severity: Severity
  status: ItemStatus
  confidence: string | null
  chainStatus: string | null
  why: string | null
  suggestion: string | null
  evidenceJson: string | null
  sourceRefJson: string | null
  occurrenceCount: number
  firstSeenAt: number
  lastSeenAt: number
  currentRunId: number | null
  trend?: number[]
}

export interface WorkbenchFixRun {
  id: number
  itemKey: string
  sessionUuid: string
  projectPath: string
  status: RunStatus
  currentStage: string | null
  stageIndex: number
  iteration: number
  chosenOption: string | null
  pushRequested: boolean
  worktreePath: string | null
  branchName: string | null
  commitHash: string | null
  pushed: boolean
  errorMessage: string | null
  createdAt: number
  updatedAt: number
}

export interface WorkbenchStageDef {
  index: number
  key: string
  label: string
  hint: string
}

export interface FixRunDetail extends WorkbenchFixRun {
  stages: WorkbenchStageDef[]
  checkpoints: Record<string, unknown>
  logTail: string[]
}

export interface ProposalOption {
  id: string
  title: string
  summary: string
  risk: 'low' | 'medium' | 'high'
  effort: string
  touchedFiles: number
}
