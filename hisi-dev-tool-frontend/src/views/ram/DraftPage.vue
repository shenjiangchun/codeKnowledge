<script setup lang="ts">
/**
 * RAM DraftPage (Wave-A refactor) — top-bar cost/status + DAG主视图 + 节点详情侧栏。
 *
 * Layout:
 *   ┌─ top bar ───────────────────────────────────────────┐
 *   │ CostMeter | status tag |   spacer  | actions        │
 *   ├─ DAG (horizontal 4-card) ───────────────────────────┤
 *   │  Clarify → Impact → Implement → Verify              │
 *   ├─ detail body (2-col) ───────────────────────────────┤
 *   │  Node detail (markdown + ring graph + …)  │ Events  │
 *   └─────────────────────────────────────────────────────┘
 *
 * The DAG card a user clicks drives which "node detail" view is rendered on
 * the left of the body row. The right column always shows the live event feed.
 *
 * Behavior preserved from the prior version:
 *   - Rejoins SSE on mount via {@code useRamSession.rejoin}
 *   - Pops ClarifyModal when status === 'clarify'
 *   - Pushes impact payload to the Pinia store + offers a graph navigation
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import CostMeter from '@/components/ram/CostMeter.vue'
import ClarifyModal from '@/components/ram/ClarifyModal.vue'
import ConfirmModal from '@/components/ram/ConfirmModal.vue'
import DagFlow from '@/components/ram/DagFlow.vue'
import ImpactOutputView from '@/components/ram/ImpactOutputView.vue'
import TechPlanOutputView from '@/components/ram/TechPlanOutputView.vue'
import { deriveDagSnapshot, type DagNodeKey } from '@/components/ram/dagModel'
import { useRamSession } from '@/composables/useRamSession'
import { executeTechPlan, getRamSession } from '@/api/ram'
import { useRamStore, type ImpactPayload } from '@/stores/ram'

const route = useRoute()
const router = useRouter()
const ramStore = useRamStore()
const session = useRamSession()

const sid = computed<string>(() => String(route.params.sid ?? ''))
const activeNode = ref<DagNodeKey>('clarify')
const showClarify = ref<boolean>(false)
const showConfirm = ref<boolean>(false)
const draftMd = ref<string>('')
const impactMd = ref<string>('')
const implementMd = ref<string>('')
const verifyMd = ref<string>('')
const techPlanMd = ref<string>('')
const impactOutputData = ref<Record<string, unknown> | null>(null)
const techPlanOutputData = ref<Record<string, unknown> | null>(null)
const impactPayload = ref<ImpactPayload | null>(null)

// Per-node reasoning from CHECKPOINT output
const nodeReasoning = ref<Record<string, string>>({})

// Current progress message for running node
const progressMessage = ref<string>('')

const dagNodes = computed(() => deriveDagSnapshot(session.events.value, session.status.value))

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
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

function asRecord(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
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
        // Prefer nodeId, fallback to className
        if (typeof rec['nodeId'] === 'string' && rec['nodeId']) return rec['nodeId']
        if (typeof rec['className'] === 'string' && rec['className']) return rec['className']
        // Last resort: JSON to avoid [object Object]
        return JSON.stringify(v)
      }
      return String(v)
    })
    .filter((s) => s.length > 0 && s !== '{}')
}

/**
 * Resolve the DAG node key from any event, handling both legacy phase-based
 * events and the current CHECKPOINT format (which uses {@code nodeName}).
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
      // Distinguish: project dirs (no .java/.py etc, no src/main) vs file paths
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
  // Show any other top-level string fields
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
    const passed = validation['passed'] === true ? '✅ 通过' : '❌ 未通过'
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
    // Show any other biz_plan fields not yet handled
    for (const [k, v] of Object.entries(biz)) {
      if (['steps', 'data_flow', 'acceptance_mapping'].includes(k)) continue
      if (typeof v === 'string') lines.push(`### ${k}\n${v}`)
    }
  }
  // api_changes: array of API change entries
  const apiChanges = output['api_changes']
  if (Array.isArray(apiChanges) && apiChanges.length > 0) {
    lines.push('## API 变更 (api_changes)')
    lines.push(apiChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  // state_machine_changes: array of state machine change entries
  const stateChanges = output['state_machine_changes']
  if (Array.isArray(stateChanges) && stateChanges.length > 0) {
    lines.push('## 状态机变更 (state_machine_changes)')
    lines.push(stateChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  // data_model_changes: array of data model change entries
  const dataModelChanges = output['data_model_changes']
  if (Array.isArray(dataModelChanges) && dataModelChanges.length > 0) {
    lines.push('## 数据模型变更 (data_model_changes)')
    lines.push(dataModelChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  // config_changes: array of config change entries
  const configChanges = output['config_changes']
  if (Array.isArray(configChanges) && configChanges.length > 0) {
    lines.push('## 配置变更 (config_changes)')
    lines.push(configChanges.map((item) => `- ${typeof item === 'string' ? item : JSON.stringify(item)}`).join('\n'))
  }
  // Fallback: show any other top-level string fields
  for (const [k, v] of Object.entries(output)) {
    if (['biz_plan', 'api_changes', 'state_machine_changes', 'data_model_changes', 'config_changes'].includes(k)) continue
    if (typeof v === 'string' && v.length > 0) lines.push(`## ${k}\n${v}`)
  }
  return lines.length > 0 ? lines.join('\n\n') : JSON.stringify(output, null, 2)
}

/** Chinese labels for the 6 VerifyNode check keys. */
const VERIFY_CHECK_LABELS: Record<string, string> = {
  acceptance_criteria_addressed: '验收标准覆盖',
  api_changes_consistent: 'API变更一致性',
  state_changes_complete: '状态变更完整性',
  data_migration_covered: '数据迁移覆盖',
  impact_validation_passed: '影响分析验证',
  change_coverage_ratio: '变更覆盖率'
}

/** Format the structured verify output into readable text. */
function formatVerifyOutput(output: Record<string, unknown>): string {
  const lines: string[] = []
  const pass = output['pass'] === true
  lines.push(`## 验证结果: ${pass ? '✅ 通过' : '❌ 未通过'}`)

  // Render the 6 check keys as a structured checklist
  for (const [key, label] of Object.entries(VERIFY_CHECK_LABELS)) {
    const val = output[key]
    if (val === undefined || val === null) continue
    if (typeof val === 'boolean') {
      lines.push(`${val ? '✅' : '❌'} **${label}**: ${val ? '通过' : '未通过'}`)
    } else if (typeof val === 'number') {
      // change_coverage_ratio is a ratio (0~1 or percentage)
      const icon = val >= 1 ? '✅' : val > 0 ? '⚠️' : '❌'
      lines.push(`${icon} **${label}**: ${val}`)
    } else if (typeof val === 'string') {
      lines.push(`- **${label}**: ${val}`)
    } else if (typeof val === 'object') {
      // Object-valued check: render detail
      const rec = asRecord(val)
      if (rec) {
        const passed = rec['passed']
        const detail = rec['detail'] ?? rec['reason'] ?? ''
        const icon = passed === true ? '✅' : '❌'
        lines.push(`${icon} **${label}**: ${detail || (passed ? '通过' : '未通过')}`)
      } else {
        lines.push(`- **${label}**: ${JSON.stringify(val)}`)
      }
    }
  }

  // Fallback: if checks array is present (legacy or alternative format), render it
  const checks = output['checks']
  if (Array.isArray(checks) && checks.length > 0) {
    lines.push('## 检查项')
    for (const c of checks) {
      const rec = asRecord(c)
      if (!rec) continue
      const icon = rec['passed'] === true ? '✅' : '❌'
      const name = rec['name'] ?? '—'
      const label = VERIFY_CHECK_LABELS[name] ?? name
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
      // TechPlan uses a specialized view; fallback to markdown_report or JSON
      return asString(output['markdown_report']) ?? JSON.stringify(output, null, 2)
    default:
      // Exhaustive check for TypeScript
      return JSON.stringify(output, null, 2)
  }
}

function extractImpactFromCheckpoint(output: Record<string, unknown>): ImpactPayload | null {
  // New structure: methods_to_modify + affected_entries
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

  // Legacy structure: involved + modified + impacted
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

// Track which events we already processed to avoid re-processing the full list
// every time a new event arrives.
let processedSeq = 0

watch(
  () => session.events.value,
  (list) => {
    for (const evt of list) {
      // Skip already processed events
      if (evt.seq <= processedSeq) continue
      processedSeq = evt.seq

      const nodeKey = resolveNodeKey(evt)
      if (!nodeKey) continue

      // --- CHECKPOINT events: content is in payload.output ---
      if (evt.type === 'CHECKPOINT') {
        const output = asRecord(evt.payload['output'])
        if (!output) continue

        const formatted = formatNodeOutput(nodeKey, output)
        // Extract reasoning from output
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
        continue
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
          // so the user sees the draft even when clarification is needed
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
    // Auto-advance the active card focus to the latest running stage so the
    // user's eyes naturally follow the orchestrator without manual clicks.
    const running = dagNodes.value.find((n) => n.status === 'running' || n.status === 'awaiting-hitl')
    if (running) activeNode.value = running.key
  },
  { deep: true }
)

watch(
  () => session.status.value,
  (s) => {
    showClarify.value = s === 'clarify'
    showConfirm.value = s === 'confirm'
    if (s === 'completed' || s === 'error' || s === 'aborted') {
      showClarify.value = false
      showConfirm.value = false
    }
  }
)

async function onClarifySubmit(answers: Record<string, unknown>): Promise<void> {
  try {
    await session.submitClarify(answers)
    showClarify.value = false
  } catch (e) {
    const msg = e instanceof Error ? e.message : '提交澄清失败'
    ElMessage.error(msg)
  }
}

/** Whether the clarify modal can be re-opened (user closed it but status is still 'clarify'). */
const canReopenClarify = computed(
  () => session.status.value === 'clarify' && !showClarify.value
)

/** Whether the confirm modal can be re-opened (user closed it but status is still 'confirm'). */
const canReopenConfirm = computed(
  () => session.status.value === 'confirm' && !showConfirm.value
)

function onClarifyCancel(): void {
  showClarify.value = false
}

async function onConfirmAction(
  action: 'approve' | 'reject' | 'edit',
  feedback?: string,
  editedOutput?: Record<string, unknown>
): Promise<void> {
  try {
    await session.submitConfirm(action, feedback, editedOutput)
    showConfirm.value = false
  } catch (e) {
    const msg = e instanceof Error ? e.message : '提交确认失败'
    ElMessage.error(msg)
  }
}

function onConfirmCancel(): void {
  showConfirm.value = false
}

function gotoGraph(): void {
  if (!impactPayload.value) {
    ElMessage.warning('尚未生成影响图')
    return
  }
  router.push({ name: 'RamGraph', params: { sid: sid.value } })
}

async function onAbort(): Promise<void> {
  try {
    await session.abort()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '中止失败'
    ElMessage.error(msg)
  }
}

function onDagClick(key: DagNodeKey): void {
  activeNode.value = key
}

/** Whether the tech_plan node can be triggered (verify has output, tech_plan has no output). */
const canTriggerTechPlan = computed(() => {
  const verifyNode = dagNodes.value.find(n => n.key === 'verify')
  // tech_plan is manually triggered, not part of the auto DAG pipeline.
  // After RUN_COMPLETED, its DAG status becomes 'done' even without execution,
  // so we check for actual output data instead of DAG status.
  return verifyNode?.status === 'done' && !techPlanOutputData.value
})

const techPlanTriggering = ref(false)

async function onTriggerTechPlan(): Promise<void> {
  techPlanTriggering.value = true
  try {
    await executeTechPlan(sid.value)
    ElMessage.success('技术方案已开始生成')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '触发技术方案失败'
    ElMessage.error(msg)
  } finally {
    techPlanTriggering.value = false
  }
}

const detailMarkdown = computed(() => {
  switch (activeNode.value) {
    case 'clarify':
      return draftMd.value
    case 'impact':
      return impactMd.value
    case 'implement':
      return implementMd.value
    case 'verify':
      return verifyMd.value
    case 'tech_plan':
      return techPlanMd.value
  }
})

const detailHtml = computed(() => {
  const md = detailMarkdown.value
  if (!md) return ''
  return marked(md, { breaks: true }) as string
})

const activeReasoning = computed(() => nodeReasoning.value[activeNode.value] ?? '')

const activeNodeStatus = computed(() => {
  const node = dagNodes.value.find(n => n.key === activeNode.value)
  return node?.status ?? 'pending'
})

const detailTitle = computed(() => {
  const labels: Record<DagNodeKey, string> = {
    clarify: '澄清草稿',
    impact: '影响分析报告',
    implement: '实现三联草案',
    verify: '验证清单',
    tech_plan: '技术方案'
  }
  return labels[activeNode.value]
})

onMounted(async () => {
  const id = sid.value
  if (!id) {
    ElMessage.error('缺少 session id')
    router.replace({ name: 'RamInput' })
    return
  }
  // Always rejoin SSE first so we don't miss events even if getRamSession fails
  session.rejoin(id, 0)
  try {
    const info = await getRamSession(id)
    if (info.clarifyPending) {
      showClarify.value = true
    }
    if (info.hitlPending) {
      showConfirm.value = true
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '加载会话信息失败，但SSE流已建立'
    ElMessage.warning(msg)
  }
})

onBeforeUnmount(() => {
  session.disconnect()
})
</script>

<template>
  <div class="ram-draft-view">
    <header class="topbar">
      <div class="topbar-title">
        <span class="dot" />
        <span class="title-text">需求分析大师</span>
        <el-tag size="small" effect="plain" class="sid-tag">#{{ sid.slice(0, 8) }}</el-tag>
      </div>
      <CostMeter :tokens="session.cost.value.tokens" :usd="session.cost.value.usd" />
      <el-tag size="small" :type="session.status.value === 'error' ? 'danger' : 'info'">
        {{ session.status.value }}
      </el-tag>
      <div class="topbar-spacer" />
      <el-button
        v-if="canReopenClarify"
        type="warning"
        size="small"
        @click="showClarify = true"
      >
        继续澄清
      </el-button>
      <el-button
        v-if="canReopenConfirm"
        type="warning"
        size="small"
        @click="showConfirm = true"
      >
        继续确认
      </el-button>
      <el-button
        v-if="impactPayload"
        type="primary"
        size="small"
        data-test="goto-graph"
        @click="gotoGraph"
      >
        查看图谱
      </el-button>
      <el-button
        v-if="canTriggerTechPlan"
        type="success"
        size="small"
        :loading="techPlanTriggering"
        @click="onTriggerTechPlan"
      >
        生成技术方案
      </el-button>
      <el-button
        size="small"
        :disabled="session.status.value !== 'running'"
        @click="onAbort"
      >
        中止
      </el-button>
    </header>

    <section class="dag-section">
      <DagFlow :nodes="dagNodes" :active-key="activeNode" @node-click="onDagClick" />
    </section>

    <section class="body">
      <article class="detail">
        <header class="detail-header">
          <h2>{{ detailTitle }}</h2>
        </header>
        <div class="detail-body">
          <!-- Running node progress indicator -->
          <div v-if="activeNodeStatus === 'running' && progressMessage" class="detail-progress">
            <div class="progress-pulse" />
            <span class="progress-text">{{ progressMessage }}</span>
          </div>

          <!-- Impact node: specialized renderer -->
          <div v-if="activeNode === 'impact' && impactOutputData" class="detail-impact-view">
            <ImpactOutputView :output="impactOutputData as any" />
          </div>

          <!-- TechPlan node: specialized renderer with Mermaid diagrams -->
          <div v-else-if="activeNode === 'tech_plan' && techPlanOutputData" class="detail-techplan-view">
            <TechPlanOutputView :output="techPlanOutputData as any" />
          </div>

          <!-- TechPlan node: trigger prompt when no data yet -->
          <div v-else-if="activeNode === 'tech_plan' && !techPlanOutputData" class="detail-techplan-prompt">
            <template v-if="canTriggerTechPlan">
              <p>验证阶段已完成，可以生成技术方案。</p>
              <el-button type="success" :loading="techPlanTriggering" @click="onTriggerTechPlan">
                生成技术方案
              </el-button>
            </template>
            <template v-else>
              <p class="empty-hint">请先完成验证阶段后再生成技术方案。</p>
            </template>
          </div>

          <!-- All nodes: markdown-rendered content -->
          <template v-else>
            <div v-if="detailHtml" class="detail-md" v-html="detailHtml" />
            <div v-else-if="activeNodeStatus === 'running'" class="detail-waiting">
              {{ progressMessage || '正在执行中...' }}
            </div>
            <div v-else class="detail-empty">— 暂无内容 —</div>
          </template>

          <!-- Reasoning section (collapsible) -->
          <div v-if="activeReasoning" class="detail-reasoning">
            <el-collapse>
              <el-collapse-item>
                <template #title>
                  <div class="collapse-title">
                    <span>💭 分析过程</span>
                  </div>
                </template>
                <pre class="reasoning-text">{{ activeReasoning }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
      </article>

      <aside class="events">
        <header class="events-header">
          <span>事件流</span>
          <el-tag size="small" type="info" effect="plain">{{ session.events.value.length }}</el-tag>
        </header>
        <ul class="event-list">
          <li v-for="evt in session.events.value" :key="evt.seq" class="event-item">
            <span class="seq">#{{ evt.seq }}</span>
            <span class="type">{{ evt.type }}</span>
          </li>
        </ul>
      </aside>
    </section>

    <ClarifyModal
      :schema="session.clarifyQuestions.value"
      :visible="showClarify"
      @submit="onClarifySubmit"
      @cancel="onClarifyCancel"
      @update:visible="(v: boolean) => (showClarify = v)"
    />

    <ConfirmModal
      :schema="session.hitlSchema.value"
      :visible="showConfirm"
      @confirm="onConfirmAction"
      @cancel="onConfirmCancel"
      @update:visible="(v: boolean) => (showConfirm = v)"
    />
  </div>
</template>

<style scoped>
.ram-draft-view {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  background: #f6f8fb;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #ffffff;
  border-radius: 10px;
  padding: 10px 16px;
  border: 1px solid #ebeef5;
}
.topbar-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #303133;
}
.topbar-title .dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409EFF, #67C23A);
}
.title-text {
  font-size: 14px;
}
.sid-tag {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.topbar-spacer {
  flex: 1;
}

.dag-section {
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid #ebeef5;
  padding: 16px;
  overflow-x: auto;
}

.body {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.detail,
.events {
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.detail-header,
.events-header {
  padding: 12px 16px;
  border-bottom: 1px solid #f0f2f5;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.detail-header h2 {
  font-size: 15px;
  margin: 0;
}
.detail-body {
  padding: 16px;
  overflow: auto;
  flex: 1;
  min-height: 0;
}
.detail-impact-view {
  /* full-width impact output view */
}

.detail-techplan-view {
  /* full-width tech plan output view */
}

.detail-techplan-prompt {
  text-align: center;
  padding: 40px 0;
  color: #606266;
  font-size: 14px;
}

.detail-techplan-prompt .empty-hint {
  color: #c0c4cc;
  font-style: italic;
}

.event-list {
  list-style: none;
  margin: 0;
  padding: 8px 0;
  overflow: auto;
  flex: 1;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.event-item {
  padding: 6px 16px;
  border-bottom: 1px solid #f5f7fa;
  display: flex;
  gap: 10px;
}
.event-item:hover {
  background: #fafbfc;
}
.event-item .seq {
  color: #909399;
  min-width: 36px;
}
.event-item .type {
  color: #606266;
  word-break: break-all;
}

.md {
  white-space: pre-wrap;
  margin: 0;
  font-size: 13px;
  color: #303133;
  line-height: 1.55;
}

.detail-md {
  font-size: 13px;
  color: #303133;
  line-height: 1.55;
}

.detail-md :deep(h2) {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}

.detail-md :deep(h3) {
  font-size: 14px;
  font-weight: 600;
  margin: 12px 0 8px;
}

.detail-md :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 12px;
}

.detail-md :deep(th) {
  text-align: left;
  background: #f5f7fa;
  padding: 6px 8px;
  border: 1px solid #ebeef5;
  font-weight: 500;
}

.detail-md :deep(td) {
  padding: 6px 8px;
  border: 1px solid #ebeef5;
}

.detail-md :deep(code) {
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  background: #f0f2f5;
  padding: 1px 4px;
  border-radius: 3px;
  color: #409eff;
}

.detail-md :deep(ul) {
  padding-left: 20px;
}

.detail-md :deep(li) {
  margin: 4px 0;
  line-height: 1.5;
}

.detail-md :deep(ol) {
  padding-left: 20px;
}

.detail-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #ecf5ff;
  border-radius: 6px;
  margin-bottom: 12px;
}

.progress-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #409eff;
  animation: pulse-dot 1.5s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.7); }
}

.progress-text {
  font-size: 13px;
  color: #409eff;
  font-weight: 500;
}

.detail-waiting {
  text-align: center;
  padding: 40px 0;
  color: #909399;
  font-size: 14px;
}

.detail-empty {
  text-align: center;
  padding: 40px 0;
  color: #c0c4cc;
  font-size: 13px;
  font-style: italic;
}

.detail-reasoning {
  margin-top: 16px;
  border-top: 1px solid #ebeef5;
  padding-top: 8px;
}

.reasoning-text {
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  background: #f5f7fa;
  padding: 10px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-family: inherit;
}

:deep(.el-collapse) {
  border: none;
}

:deep(.el-collapse-item__header) {
  border-bottom: none;
  height: 36px;
  line-height: 36px;
  background: transparent;
}

:deep(.el-collapse-item__wrap) {
  border-bottom: none;
  background: transparent;
}

:deep(.el-collapse-item__content) {
  padding-bottom: 0;
}
</style>
