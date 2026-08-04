/**
 * Pure derivation helpers for the RAM 4-stage DAG visualization.
 *
 * The orchestrator emits a stream of {@link RamEvent}s; this module folds them
 * into a per-node status snapshot suitable for rendering with vue-flow.
 *
 * Why pure: keeps the rendering layer dumb and the state transitions covered
 * by unit tests under happy-dom (no canvas / SVG quirks).
 */
import type { RamEvent, RamStatus } from '@/types/ram'

export type DagNodeKey = 'clarify' | 'impact' | 'implement' | 'verify' | 'tech_plan'

export type DagNodeStatus =
  | 'pending'
  | 'running'
  | 'awaiting-hitl'
  | 'done'
  | 'failed'
  | 'circuit-open'

export interface DagNodeSnapshot {
  readonly key: DagNodeKey
  readonly label: string
  readonly status: DagNodeStatus
  readonly tokens: number
  readonly events: number
  readonly riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH'
  readonly reasoning?: string
}

export const DAG_ORDER: readonly DagNodeKey[] = ['clarify', 'impact', 'implement', 'verify', 'tech_plan'] as const

const LABELS: Readonly<Record<DagNodeKey, string>> = {
  clarify: '澄清',
  impact: '影响',
  implement: '实现',
  verify: '验证',
  tech_plan: '技术方案'
}

/**
 * Phase-to-node lookup. The orchestrator sometimes uses {@code phase} in the
 * event payload, and sometimes encodes the phase in the event {@code type}
 * (e.g. {@code IMPACT_DONE} / {@code IMPLEMENT_DONE}). Both are handled.
 */
const PHASE_TO_NODE: Readonly<Record<string, DagNodeKey>> = {
  clarify: 'clarify',
  draft: 'clarify',
  impact: 'impact',
  implement: 'implement',
  verify: 'verify',
  tech_plan: 'tech_plan'
}

function classifyEvent(evt: RamEvent): DagNodeKey | null {
  // Backend CHECKPOINT events use 'nodeName'; legacy events may use 'phase'.
  const phase = String(evt.payload['phase'] ?? evt.payload['nodeName'] ?? '').toLowerCase()
  if (phase && PHASE_TO_NODE[phase]) return PHASE_TO_NODE[phase]
  const t = evt.type
  if (t === 'CLARIFY_REQ' || t === 'CLARIFY_REQUIRED' || t === 'CLARIFY_RES') return 'clarify'
  if (t === 'HITL_REQ' || t === 'HITL_REQUIRED' || t === 'HITL_RES') {
    const nn = String(evt.payload['nodeName'] ?? '').toLowerCase()
    if (nn && PHASE_TO_NODE[nn]) return PHASE_TO_NODE[nn]
  }
  if (t === 'IMPACT_DONE' || t === 'IMPACT_UPDATE') return 'impact'
  if (t === 'IMPLEMENT_DONE' || t === 'IMPLEMENT_UPDATE' || t === 'DRAFT_UPDATE') return 'implement'
  if (t === 'VERIFY_DONE' || t === 'VERIFY_UPDATE') return 'verify'
  if (t === 'TECH_PLAN_DONE' || t === 'TECH_PLAN_UPDATE') return 'tech_plan'
  return null
}

function asTokens(evt: RamEvent): number {
  const usage = evt.payload['usage']
  if (usage && typeof usage === 'object') {
    const u = usage as Record<string, unknown>
    const v = u['tokens']
    if (typeof v === 'number' && Number.isFinite(v)) return v
  }
  return 0
}

/**
 * Fold an event stream into per-node snapshots.
 *
 * Rules:
 *  - The first event of any node flips it from {@code pending} → {@code running}.
 *  - A {@code *_DONE} event flips that node to {@code done}.
 *  - A {@code CLARIFY_REQ}/{@code CLARIFY_REQUIRED} event flips clarify to
 *    {@code awaiting-hitl}.
 *  - A {@code CIRCUIT_OPEN} event flips the carrying node to {@code circuit-open}.
 *  - A {@code RUN_FAILED}/{@code ERROR} flips the currently-running node to
 *    {@code failed} (heuristic: the last node we observed running).
 *  - {@code RUN_COMPLETED} forces all unfinished nodes to {@code done}.
 *
 * The terminal session status takes precedence when supplied (e.g.,
 * {@code aborted} bubbles to all running nodes).
 */
export function deriveDagSnapshot(
  events: readonly RamEvent[],
  sessionStatus: RamStatus = 'idle'
): readonly DagNodeSnapshot[] {
  type Mut = {
    -readonly [K in keyof DagNodeSnapshot]: DagNodeSnapshot[K]
  }
  const acc: Record<DagNodeKey, Mut> = {
    clarify: { key: 'clarify', label: LABELS.clarify, status: 'pending', tokens: 0, events: 0 },
    impact: { key: 'impact', label: LABELS.impact, status: 'pending', tokens: 0, events: 0 },
    implement: { key: 'implement', label: LABELS.implement, status: 'pending', tokens: 0, events: 0 },
    verify: { key: 'verify', label: LABELS.verify, status: 'pending', tokens: 0, events: 0 },
    tech_plan: { key: 'tech_plan', label: LABELS.tech_plan, status: 'pending', tokens: 0, events: 0 }
  }
  let lastRunning: DagNodeKey | null = null

  for (const evt of events) {
    const node = classifyEvent(evt)
    if (node) {
      acc[node].events += 1
      acc[node].tokens += asTokens(evt)
      if (acc[node].status === 'pending') {
        acc[node].status = 'running'
      }
      lastRunning = node

      const t = evt.type
      if (t === 'CLARIFY_REQ' || t === 'CLARIFY_REQUIRED') {
        acc[node].status = 'awaiting-hitl'
      } else if (t === 'HITL_REQ' || t === 'HITL_REQUIRED') {
        acc[node].status = 'awaiting-hitl'
      } else if (t === 'HITL_RES') {
        // Confirmed — mark done (next node hasn't started yet)
        acc[node].status = 'done'
      } else if (t.endsWith('_DONE') || t === 'CHECKPOINT') {
        // CHECKPOINT is emitted after successful node execution → mark done
        acc[node].status = 'done'
      }
      const risk = evt.payload['riskLevel']
      if (risk === 'LOW' || risk === 'MEDIUM' || risk === 'HIGH') {
        acc[node].riskLevel = risk
      }
      // Extract reasoning from CHECKPOINT output
      if (evt.type === 'CHECKPOINT') {
        const output = evt.payload['output']
        if (output && typeof output === 'object') {
          const r = (output as Record<string, unknown>)['reasoning']
          if (typeof r === 'string' && r.trim()) {
            acc[node].reasoning = r.trim()
          }
        }
      }
    }
    if (evt.type === 'CIRCUIT_OPEN' && lastRunning) {
      acc[lastRunning].status = 'circuit-open'
    }
    if (evt.type === 'NODES_CLEARED') {
      const clearedNodes = evt.payload['clearedNodes']
      if (Array.isArray(clearedNodes)) {
        for (const nodeName of clearedNodes) {
          const key = PHASE_TO_NODE[nodeName.toLowerCase()]
          if (key) {
            acc[key].status = 'pending'
            acc[key].tokens = 0
            acc[key].events = 0
          }
        }
      }
    }
    if ((evt.type === 'RUN_FAILED' || evt.type === 'ERROR') && lastRunning) {
      acc[lastRunning].status = 'failed'
    }
    if (evt.type === 'RUN_COMPLETED') {
      for (const k of DAG_ORDER) {
        if (acc[k].status === 'pending' || acc[k].status === 'running' || acc[k].status === 'awaiting-hitl') {
          acc[k].status = 'done'
        }
      }
    }
  }

  // When the session is actively running but no node shows "running" (e.g. right
  // after a HITL confirm before the next node's first event arrives, or when a
  // node's first classified event is also its CHECKPOINT), promote the first
  // pending node so the user always sees an "执行中" indicator while work is
  // happening on the backend.
  if (sessionStatus === 'running') {
    const hasRunning = DAG_ORDER.some((k) => acc[k].status === 'running')
    if (!hasRunning) {
      for (const k of DAG_ORDER) {
        if (acc[k].status === 'pending') {
          acc[k].status = 'running'
          break
        }
      }
    }
  }

  // Honor terminal session status as a final overlay.
  // Defense-in-depth: also flip 'pending' → 'done' when session confirmed completed,
  // covering race conditions where CHECKPOINT events weren't properly classified.
  if (sessionStatus === 'completed') {
    for (const k of DAG_ORDER) {
      if (acc[k].status === 'pending' || acc[k].status === 'running' || acc[k].status === 'awaiting-hitl') {
        acc[k].status = 'done'
      }
    }
  } else if (sessionStatus === 'error') {
    for (const k of DAG_ORDER) {
      if (acc[k].status === 'running') {
        acc[k].status = 'failed'
        break
      }
    }
  } else if (sessionStatus === 'aborted') {
    for (const k of DAG_ORDER) {
      if (acc[k].status === 'running' || acc[k].status === 'awaiting-hitl') {
        acc[k].status = 'failed'
      }
    }
  }

  return DAG_ORDER.map((k) => acc[k])
}

/**
 * Format a token count compactly: 1234 → "1.2k", 0 → "0".
 */
export function formatTokens(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return '0'
  if (n < 1000) return String(Math.round(n))
  if (n < 1_000_000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`
  return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}m`
}

/**
 * Status → CSS color token. Centralized so component templates stay declarative.
 */
export function statusColor(s: DagNodeStatus): string {
  switch (s) {
    case 'pending':
      return '#C0C4CC'
    case 'running':
      return '#409EFF'
    case 'awaiting-hitl':
      return '#E6A23C'
    case 'done':
      return '#67C23A'
    case 'failed':
      return '#F56C6C'
    case 'circuit-open':
      return '#909399'
  }
}

export function statusLabel(s: DagNodeStatus): string {
  switch (s) {
    case 'pending':
      return '待执行'
    case 'running':
      return '执行中'
    case 'awaiting-hitl':
      return '待确认'
    case 'done':
      return '完成'
    case 'failed':
      return '失败'
    case 'circuit-open':
      return '熔断'
  }
}
