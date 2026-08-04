/**
 * Composable for processing RAM DAG events and managing node output state.
 *
 * Extracts the event handling logic from DraftPage.vue into a reusable unit.
 * Manages:
 *   - Per-node markdown outputs (clarify, impact, implement, verify, tech_plan)
 *   - Impact output data for visualization
 *   - Tech plan output data for visualization
 *   - Node reasoning content
 *   - Progress messages for running nodes
 *   - Cleared node keys (for rerun handling)
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { deriveDagSnapshot, type DagNodeKey } from '@/components/ram/dagModel'
import type { RamEvent } from '@/types/ram'
import type { UseRamSessionReturn } from './useRamSession'
import { useRamStore, type ImpactPayload } from '@/stores/ram'

/** Chinese labels for the 6 VerifyNode check keys. */
const VERIFY_CHECK_LABELS: Record<string, string> = {
  acceptance_criteria_addressed: '验收标准覆盖',
  api_changes_consistent: 'API变更一致性',
  state_changes_complete: '状态变更完整性',
  data_migration_covered: '数据迁移覆盖',
  impact_validation_passed: '影响分析验证',
  change_coverage_ratio: '变更覆盖率'
}

export interface UseDagEventHandlerOptions {
  /** The RAM session composable */
  session: UseRamSessionReturn
  /** The session ID (computed from route) */
  sid: ComputedRef<string>
}

export interface UseDagEventHandlerReturn {
  /** Markdown output for clarify node */
  draftMd: Ref<string>
  /** Markdown output for impact node */
  impactMd: Ref<string>
  /** Markdown output for implement node */
  implementMd: Ref<string>
  /** Markdown output for verify node */
  verifyMd: Ref<string>
  /** Markdown output for tech_plan node */
  techPlanMd: Ref<string>
  /** Structured impact output data for specialized view */
  impactOutputData: Ref<Record<string, unknown> | null>
  /** Structured tech plan output data for specialized view */
  techPlanOutputData: Ref<Record<string, unknown> | null>
  /** Impact payload for graph navigation */
  impactPayload: Ref<ImpactPayload | null>
  /** Per-node reasoning from CHECKPOINT output */
  nodeReasoning: Ref<Record<string, string>>
  /** Current progress message for running node */
  progressMessage: Ref<string>
  /** Nodes whose CHECKPOINT results were cleared by rerun-from-node */
  clearedNodeKeys: Ref<Set<DagNodeKey>>
  /** Process a single event and update state */
  processEvent: (evt: RamEvent) => void
  /** Clear output for a specific node (used in rerun) */
  clearNode: (key: DagNodeKey) => void
  /** Reset the processed sequence counter */
  resetProcessedSeq: (seq?: number) => void
  /** Get the current processed sequence number */
  getProcessedSeq: () => number
  /** Derived DAG nodes snapshot */
  dagNodes: ComputedRef<readonly DagNodeSnapshot[]>
}

interface DagNodeSnapshot {
  readonly key: DagNodeKey
  readonly label: string
  readonly status: 'pending' | 'running' | 'awaiting-hitl' | 'done' | 'failed' | 'circuit-open'
  readonly tokens: number
  readonly events: number
  readonly riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH'
  readonly reasoning?: string
}

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

/** Try to extract a markdown/text string from the payload or its nested output. */
function extractMd(payload: Readonly<Record<string, unknown>>): string | null {
  return (
    asString(payload['markdown']) ??
    asString(payload['text']) ??
    asString(payload['content']) ??
    null
  )
}

/**
 * Extract an array of nodeId strings from a payload field that may contain:
 *   - string[] (nodeId directly)
 *   - object[] with {nodeId: "..."} or {className: "..."}
 */
function extractNodeIds(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value
    .map((v) => {
      if (typeof v === 'string') return v
      if (v != null && typeof v === 'object') {
        const rec = v as Record<string, unknown>
        if (typeof rec['nodeId'] === 'string' && rec['nodeId']) return rec['nodeId']
        if (typeof rec['className'] === 'string' && rec['className']) return rec['className']
        return JSON.stringify(v)
      }
      return String(v)
    })
    .filter((s) => s.length > 0 && s !== '{}')
}

/**
 * Resolve the DAG node key from any event, handling both legacy phase-based
 * events and the current CHECKPOINT format (which uses nodeName).
 */
function resolveNodeKey(evt: { type: string; payload: Record<string, unknown> }): DagNodeKey | null {
  const phase = String(evt.payload['phase'] ?? evt.payload['nodeName'] ?? '').toLowerCase()
  const map: Record<string, DagNodeKey> = {
    clarify: 'clarify',
    draft: 'clarify',
    impact: 'impact',
    implement: 'implement',
    verify: 'verify',
    tech_plan: 'tech_plan'
  }
  if (phase && map[phase]) return map[phase]
  const t = evt.type
  if (t === 'CLARIFY_REQ' || t === 'CLARIFY_REQUIRED' || t === 'CLARIFY_RES') return 'clarify'
  if (t === 'IMPACT_DONE' || t === 'IMPACT_UPDATE') return 'impact'
  if (t === 'IMPLEMENT_DONE' || t === 'IMPLEMENT_UPDATE' || t === 'DRAFT_UPDATE') return 'implement'
  if (t === 'VERIFY_DONE' || t === 'VERIFY_UPDATE') return 'verify'
  if (t === 'TECH_PLAN_DONE' || t === 'TECH_PLAN_UPDATE') return 'tech_plan'
  return null
}

/** Format the structured clarify output into readable text. */
function formatClarifyOutput(output: Record<string, unknown>): string {
  const lines: string[] = []
  const intent = asString(output['intent'])
  if (intent) lines.push(`## 需求意图\n${intent}`)
  const paths = output['project_paths']
  if (Array.isArray(paths) && paths.length > 0) {
    const projectPaths: string[] = []
    const filePaths: string[] = []
    for (const p of paths) {
      if (typeof p !== 'string') continue
      if (/\.java\b|\.py\b|\.xml\b|src\/main|src\/test/.test(p)) {
        filePaths.push(p)
      } else {
        projectPaths.push(p)
      }
    }
    if (projectPaths.length > 0) {
      lines.push(`## 项目路径\n${projectPaths.map((p) => `- ${p}`).join('\n')}`)
    }
    if (filePaths.length > 0) {
      lines.push(`## 影响范围\n${filePaths.map((p) => `- ${p}`).join('\n')}`)
    }
  }
  const criteria = output['acceptance_criteria']
  if (Array.isArray(criteria) && criteria.length > 0) {
    lines.push(`## 验收标准\n${criteria.map((c, i) => `${i + 1}. ${c}`).join('\n')}`)
  }
  for (const [k, v] of Object.entries(output)) {
    if (['intent', 'project_paths', 'acceptance_criteria'].includes(k)) continue
    if (typeof v === 'string' && v.length > 0) {
      lines.push(`## ${k}\n${v}`)
    }
  }
  return lines.length > 0 ? lines.join('\n\n') : JSON.stringify(output, null, 2)
}

/** Format the structured impact output into readable text. */
function formatImpactOutput(output: Record<string, unknown>): string {
  const lines: string[] = []
  const involved = asRecord(output['involved'])
  if (involved) {
    const seeds = Array.isArray(involved['seeds']) ? involved['seeds'].length : 0
    const entries = Array.isArray(involved['entries']) ? involved['entries'].length : 0
    const impls = Array.isArray(involved['impls']) ? involved['impls'].length : 0
    lines.push(`## 受影响的入口 (InvolvedRing)\n- Seeds: ${seeds}\n- Entry points: ${entries}\n- Implementations: ${impls}`)
  }
  const impacted = asRecord(output['impacted'])
  if (impacted) {
    const up = Array.isArray(impacted['upstream']) ? impacted['upstream'].length : 0
    const down = Array.isArray(impacted['downstream']) ? impacted['downstream'].length : 0
    const cross = Array.isArray(impacted['crossService']) ? impacted['crossService'].length : 0
    const bridges = Array.isArray(impacted['bridges']) ? impacted['bridges'].length : 0
    lines.push(`## 影响范围 (ImpactRing)\n- Upstream: ${up}\n- Downstream: ${down}\n- Cross-service: ${cross}\n- Bridges: ${bridges}`)
  }
  const risk = asRecord(output['risk'])
  if (risk) {
    lines.push(`## 风险评分\n- Score: ${risk['score'] ?? '—'}\n- Level: ${risk['level'] ?? '—'}`)
  }
  const validation = asRecord(output['validation'])
  if (validation) {
    const passed = validation['passed'] === true ? '通过' : '未通过'
    lines.push(`## 验证\n${passed}`)
    const violations = validation['violations']
    if (Array.isArray(violations) && violations.length > 0) {
      lines.push(violations.map((v) => `- ${v}`).join('\n'))
    }
  }
  return lines.length > 0 ? lines.join('\n\n') : JSON.stringify(output, null, 2)
}

/** Format the structured implement output into readable text. */
function formatImplementOutput(output: Record<string, unknown>): string {
  const lines: string[] = []
  const biz = asRecord(output['biz_plan'])
  if (biz) {
    lines.push('## 业务方案 (biz_plan)')
    const steps = biz['steps']
    if (Array.isArray(steps) && steps.length > 0) {
      lines.push(steps.map((s, i) => `${i + 1}. ${s}`).join('\n'))
    }
    const dataFlow = biz['data_flow']
    if (typeof dataFlow === 'string' && dataFlow.length > 0) {
      lines.push(`### 数据流\n${dataFlow}`)
    }
    const acceptanceMapping = biz['acceptance_mapping']
    if (acceptanceMapping != null) {
      if (typeof acceptanceMapping === 'string' && acceptanceMapping.length > 0) {
        lines.push(`### 验收标准映射\n${acceptanceMapping}`)
      } else if (typeof acceptanceMapping === 'object') {
        lines.push('### 验收标准映射')
        if (Array.isArray(acceptanceMapping)) {
          lines.push(acceptanceMapping.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
        } else {
          for (const [k, v] of Object.entries(acceptanceMapping as Record<string, unknown>)) {
            lines.push(`- **${k}**: ${typeof v === 'string' ? v : JSON.stringify(v)}`)
          }
        }
      }
    }
    for (const [k, v] of Object.entries(biz)) {
      if (['steps', 'data_flow', 'acceptance_mapping'].includes(k)) continue
      if (typeof v === 'string') lines.push(`### ${k}\n${v}`)
    }
  }
  const apiChanges = output['api_changes']
  if (Array.isArray(apiChanges) && apiChanges.length > 0) {
    lines.push('## API 变更 (api_changes)')
    lines.push(apiChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  const stateChanges = output['state_machine_changes']
  if (Array.isArray(stateChanges) && stateChanges.length > 0) {
    lines.push('## 状态机变更 (state_machine_changes)')
    lines.push(stateChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  const dataModelChanges = output['data_model_changes']
  if (Array.isArray(dataModelChanges) && dataModelChanges.length > 0) {
    lines.push('## 数据模型变更 (data_model_changes)')
    lines.push(dataModelChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  const configChanges = output['config_changes']
  if (Array.isArray(configChanges) && configChanges.length > 0) {
    lines.push('## 配置变更 (config_changes)')
    lines.push(configChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  for (const [k, v] of Object.entries(output)) {
    if (['biz_plan', 'api_changes', 'state_machine_changes', 'data_model_changes', 'config_changes'].includes(k)) continue
    if (typeof v === 'string' && v.length > 0) lines.push(`## ${k}\n${v}`)
  }
  return lines.length > 0 ? lines.join('\n\n') : JSON.stringify(output, null, 2)
}

/** Format the structured verify output into readable text. */
function formatVerifyOutput(output: Record<string, unknown>): string {
  const lines: string[] = []
  const pass = output['pass'] === true
  lines.push(`## 验证结果: ${pass ? '通过' : '未通过'}`)

  for (const [key, label] of Object.entries(VERIFY_CHECK_LABELS)) {
    const val = output[key]
    if (val === undefined || val === null) continue
    if (typeof val === 'boolean') {
      lines.push(`${val ? '通过' : '未通过'} **${label}**: ${val ? '通过' : '未通过'}`)
    } else if (typeof val === 'number') {
      const icon = val >= 1 ? '通过' : val > 0 ? '警告' : '未通过'
      lines.push(`${icon} **${label}**: ${val}`)
    } else if (typeof val === 'string') {
      lines.push(`- **${label}**: ${val}`)
    } else if (typeof val === 'object') {
      const rec = asRecord(val)
      if (rec) {
        const passed = rec['passed']
        const detail = rec['detail'] ?? rec['reason'] ?? ''
        const icon = passed === true ? '通过' : '未通过'
        lines.push(`${icon} **${label}**: ${detail || (passed ? '通过' : '未通过')}`)
      } else {
        lines.push(`- **${label}**: ${JSON.stringify(val)}`)
      }
    }
  }

  const checks = output['checks']
  if (Array.isArray(checks) && checks.length > 0) {
    lines.push('## 检查项')
    for (const c of checks) {
      const rec = asRecord(c)
      if (!rec) continue
      const icon = rec['passed'] === true ? '通过' : '未通过'
      const name = rec['name'] ?? '—'
      const label = VERIFY_CHECK_LABELS[String(name)] ?? String(name)
      lines.push(`${icon} **${label}**: ${rec['detail'] ?? '—'}`)
    }
  }

  const blockers = output['blockers']
  if (Array.isArray(blockers) && blockers.length > 0) {
    lines.push(`## 阻塞项\n${blockers.map((b) => `- ${b}`).join('\n')}`)
  }
  return lines.join('\n\n')
}

/** Format a CHECKPOINT output map based on the node it belongs to. */
function formatNodeOutput(nodeKey: DagNodeKey, output: Record<string, unknown>): string {
  switch (nodeKey) {
    case 'clarify':
      return formatClarifyOutput(output)
    case 'impact':
      return formatImpactOutput(output)
    case 'implement':
      return formatImplementOutput(output)
    case 'verify':
      return formatVerifyOutput(output)
    case 'tech_plan':
      return asString(output['markdown_report']) ?? JSON.stringify(output, null, 2)
    default:
      return JSON.stringify(output, null, 2)
  }
}

function extractImpactFromCheckpoint(output: Record<string, unknown>): ImpactPayload | null {
  const methodsToModify = output['methods_to_modify']
  const affectedEntries = asRecord(output['affected_entries'])
  if (methodsToModify || affectedEntries) {
    const modifiedIds = extractNodeIds(methodsToModify)
    const impactedIds = [
      ...extractNodeIds(affectedEntries?.['direct']),
      ...extractNodeIds(affectedEntries?.['indirect'])
    ]
    const risk = asRecord(output['risk'])
    const riskScores = risk
      ? (Object.fromEntries(
          Object.entries(risk).map(([k, v]) => [k, Number(v) || 0])
        ) as Record<string, number>)
      : undefined
    return { involved: [], modified: modifiedIds, impacted: impactedIds, riskScores }
  }

  const involved = asRecord(output['involved'])
  const modified = asRecord(output['modified'])
  const impacted = asRecord(output['impacted'])
  if (!involved && !modified && !impacted) return null

  const involvedIds = [
    ...extractNodeIds(involved?.['seeds']),
    ...extractNodeIds(involved?.['entries']),
    ...extractNodeIds(involved?.['impls'])
  ]
  const modifiedIds = extractNodeIds(modified?.['tree'])
  const impactedIds = [
    ...extractNodeIds(impacted?.['upstream']),
    ...extractNodeIds(impacted?.['downstream']),
    ...extractNodeIds(impacted?.['bridges'])
  ]

  const risk = asRecord(output['risk'])
  const riskScores = risk
    ? (Object.fromEntries(
        Object.entries(risk).map(([k, v]) => [k, Number(v) || 0])
      ) as Record<string, number>)
    : undefined

  return {
    involved: involvedIds,
    modified: modifiedIds,
    impacted: impactedIds,
    riskScores
  }
}

export function useDagEventHandler(options: UseDagEventHandlerOptions): UseDagEventHandlerReturn {
  const { session, sid } = options
  const ramStore = useRamStore()

  // State
  const draftMd = ref<string>('')
  const impactMd = ref<string>('')
  const implementMd = ref<string>('')
  const verifyMd = ref<string>('')
  const techPlanMd = ref<string>('')
  const impactOutputData = ref<Record<string, unknown> | null>(null)
  const techPlanOutputData = ref<Record<string, unknown> | null>(null)
  const impactPayload = ref<ImpactPayload | null>(null)
  const nodeReasoning = ref<Record<string, string>>({})
  const progressMessage = ref<string>('')
  const clearedNodeKeys = ref<Set<DagNodeKey>>(new Set())

  // Track which events we already processed
  let processedSeq = 0

  // Derived DAG nodes snapshot
  const dagNodes = computed(() => {
    const snapshot = deriveDagSnapshot(session.events.value, session.status.value)
    const cleared = clearedNodeKeys.value
    if (cleared.size === 0) return snapshot
    const rerunNode = [...cleared][0]
    return snapshot.map(n => {
      if (!cleared.has(n.key)) return n
      const isRerunNode = n.key === rerunNode
      return { ...n, status: isRerunNode ? 'running' as const : 'pending' as const }
    })
  })

  /**
   * Process a single event and update state.
   * This is the core event handling logic extracted from DraftPage.vue.
   */
  function processEvent(evt: RamEvent): void {
    // Skip already processed events
    if (evt.seq <= processedSeq) return
    processedSeq = evt.seq

    const nodeKey = resolveNodeKey(evt)
    if (!nodeKey) return

    // When a rerun produces a CHECKPOINT for a cleared node, remove only that
    // node from the cleared set. Downstream nodes stay cleared until they
    // receive their own new CHECKPOINTs.
    const cleared = clearedNodeKeys.value
    if (cleared.has(nodeKey) && evt.type === 'CHECKPOINT') {
      const newCleared = new Set(cleared)
      newCleared.delete(nodeKey)
      clearedNodeKeys.value = newCleared
    }

    // --- CHECKPOINT events: content is in payload.output ---
    if (evt.type === 'CHECKPOINT') {
      const output = asRecord(evt.payload['output'])
      if (!output) return

      const formatted = formatNodeOutput(nodeKey, output)
      const reasoning = typeof output['reasoning'] === 'string' ? output['reasoning'] as string : ''
      if (reasoning) {
        nodeReasoning.value = { ...nodeReasoning.value, [nodeKey]: reasoning }
      }
      progressMessage.value = ''
      switch (nodeKey) {
        case 'clarify':
          draftMd.value = formatted
          break
        case 'impact': {
          impactMd.value = formatted
          impactOutputData.value = output
          const impact = extractImpactFromCheckpoint(output)
          if (impact) {
            impactPayload.value = impact
            ramStore.setImpact(sid.value, impact)
          }
          break
        }
        case 'implement':
          implementMd.value = formatted
          break
        case 'verify':
          verifyMd.value = formatted
          break
        case 'tech_plan':
          techPlanMd.value = formatted
          techPlanOutputData.value = output
          break
      }
      return
    }

    // --- Legacy events: content may be at payload top level ---
    const md = extractMd(evt.payload)
    // Update progress message for running nodes
    if (nodeKey && evt.type !== 'CHECKPOINT') {
      const progressLabels: Record<string, string> = {
        clarify: '正在分析需求...',
        impact: '正在分析影响范围...',
        implement: '正在生成实现方案...',
        verify: '正在验证...',
        tech_plan: '正在生成技术方案...'
      }
      progressMessage.value = progressLabels[nodeKey] ?? '处理中...'
    }
    switch (nodeKey) {
      case 'clarify':
        if (md) draftMd.value = md
        // CLARIFY_REQ/CLARIFY_REQUIRED may carry partialOutput from the LLM
        if (evt.type === 'CLARIFY_REQ' || evt.type === 'CLARIFY_REQUIRED') {
          const partial = asRecord(evt.payload['partialOutput'])
          if (partial && Object.keys(partial).length > 0) {
            draftMd.value = formatClarifyOutput(partial)
          }
        }
        break
      case 'impact': {
        if (md) impactMd.value = md
        const involved = extractNodeIds(evt.payload['involved'])
        const modified = extractNodeIds(evt.payload['modified'])
        const impacted = extractNodeIds(evt.payload['impacted'])
        if (involved.length > 0 || modified.length > 0 || impacted.length > 0) {
          const riskRaw = evt.payload['riskScores']
          const riskScores =
            riskRaw && typeof riskRaw === 'object'
              ? (Object.fromEntries(
                  Object.entries(riskRaw as Record<string, unknown>).map(([k, v]) => [k, Number(v) || 0])
                ) as Record<string, number>)
              : undefined
          const payload: ImpactPayload = { involved, modified, impacted, riskScores }
          impactPayload.value = payload
          ramStore.setImpact(sid.value, payload)
        }
        break
      }
      case 'implement':
        if (md) implementMd.value = md
        break
      case 'verify':
        if (md) verifyMd.value = md
        break
      case 'tech_plan':
        if (md) techPlanMd.value = md
        break
    }
  }

  /**
   * Clear output for a specific node (used in rerun scenarios).
   */
  function clearNode(key: DagNodeKey): void {
    switch (key) {
      case 'clarify':
        draftMd.value = ''
        break
      case 'impact':
        impactMd.value = ''
        impactOutputData.value = null
        impactPayload.value = null
        break
      case 'implement':
        implementMd.value = ''
        break
      case 'verify':
        verifyMd.value = ''
        break
      case 'tech_plan':
        techPlanMd.value = ''
        techPlanOutputData.value = null
        break
    }
    // Also clear reasoning for this node
    if (nodeReasoning.value[key]) {
      const newReasoning = { ...nodeReasoning.value }
      delete newReasoning[key]
      nodeReasoning.value = newReasoning
    }
  }

  /**
   * Reset the processed sequence counter.
   * Call this when starting a new session or rejoining with a specific seq.
   */
  function resetProcessedSeq(seq = 0): void {
    processedSeq = seq
  }

  /**
   * Get the current processed sequence number.
   */
  function getProcessedSeq(): number {
    return processedSeq
  }

  return {
    draftMd,
    impactMd,
    implementMd,
    verifyMd,
    techPlanMd,
    impactOutputData,
    techPlanOutputData,
    impactPayload,
    nodeReasoning,
    progressMessage,
    clearedNodeKeys,
    processEvent,
    clearNode,
    resetProcessedSeq,
    getProcessedSeq,
    dagNodes
  }
}