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
import CostMeter from '@/components/ram/CostMeter.vue'
import ClarifyModal from '@/components/ram/ClarifyModal.vue'
import ConfirmModal from '@/components/ram/ConfirmModal.vue'
import DagFlow from '@/components/ram/DagFlow.vue'
import ImpactSankeyGraph from '@/components/ram/ImpactSankeyGraph.vue'
import { deriveDagSnapshot, type DagNodeKey } from '@/components/ram/dagModel'
import { useRamSession } from '@/composables/useRamSession'
import { getRamSession } from '@/api/ram'
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
const impactPayload = ref<ImpactPayload | null>(null)

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
    verify: 'verify'
  }
  if (phase && map[phase]) return map[phase]
  const t = evt.type
  if (t === 'CLARIFY_REQ' || t === 'CLARIFY_REQUIRED' || t === 'CLARIFY_RES') return 'clarify'
  if (t === 'IMPACT_DONE' || t === 'IMPACT_UPDATE') return 'impact'
  if (t === 'IMPLEMENT_DONE' || t === 'IMPLEMENT_UPDATE' || t === 'DRAFT_UPDATE') return 'implement'
  if (t === 'VERIFY_DONE' || t === 'VERIFY_UPDATE') return 'verify'
  return null
}

/** Format the structured clarify output into readable text. */
function formatClarifyOutput(output: Record<string, unknown>): string {
  const lines: string[] = []
  const intent = asString(output['intent'])
  if (intent) lines.push(`## 需求意图\n${intent}`)
  const paths = output['project_paths']
  if (Array.isArray(paths) && paths.length > 0) {
    lines.push(`## 项目路径\n${paths.map((p) => `- ${p}`).join('\n')}`)
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
    lines.push(`## 涉及范围 (InvolvedRing)\n- Seeds: ${seeds}\n- Entry points: ${entries}\n- Implementations: ${impls}`)
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
    for (const [k, v] of Object.entries(biz)) {
      if (k === 'steps') continue
      if (typeof v === 'string') lines.push(`### ${k}\n${v}`)
    }
  }
  const ui = asRecord(output['ui_plan'])
  if (ui) {
    lines.push('## UI 方案 (ui_plan)')
    for (const [k, v] of Object.entries(ui)) {
      if (typeof v === 'string') lines.push(`### ${k}\n${v}`)
      else if (Array.isArray(v)) lines.push(`### ${k}\n${v.map((i) => `- ${i}`).join('\n')}`)
    }
  }
  const tech = asRecord(output['tech_plan'])
  if (tech) {
    lines.push('## 技术方案 (tech_plan)')
    const files = tech['files']
    if (Array.isArray(files) && files.length > 0) {
      lines.push(`### 涉及文件\n${files.map((f) => `- \`${f}\``).join('\n')}`)
    }
    for (const [k, v] of Object.entries(tech)) {
      if (k === 'files') continue
      if (typeof v === 'string') lines.push(`### ${k}\n${v}`)
    }
  }
  // Fallback: show any other top-level string fields
  for (const [k, v] of Object.entries(output)) {
    if (['biz_plan', 'ui_plan', 'tech_plan'].includes(k)) continue
    if (typeof v === 'string' && v.length > 0) lines.push(`## ${k}\n${v}`)
  }
  return lines.length > 0 ? lines.join('\n\n') : JSON.stringify(output, null, 2)
}

/** Format the structured verify output into readable text. */
function formatVerifyOutput(output: Record<string, unknown>): string {
  const lines: string[] = []
  const pass = output['pass'] === true
  lines.push(`## 验证结果: ${pass ? '✅ 通过' : '❌ 未通过'}`)
  const checks = output['checks']
  if (Array.isArray(checks) && checks.length > 0) {
    lines.push('## 检查项')
    for (const c of checks) {
      const rec = asRecord(c)
      if (!rec) continue
      const icon = rec['passed'] === true ? '✅' : '❌'
      lines.push(`${icon} **${rec['name'] ?? '—'}**: ${rec['detail'] ?? '—'}`)
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
  }
}

function extractImpactFromCheckpoint(output: Record<string, unknown>): ImpactPayload | null {
  const involved = asRecord(output['involved'])
  const modified = asRecord(output['modified'])
  const impacted = asRecord(output['impacted'])
  if (!involved && !modified && !impacted) return null

  // Collect nodeIds from each ring for ThreeRingGraph
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
        switch (nodeKey) {
          case 'clarify':
            draftMd.value = formatted
            break
          case 'impact': {
            impactMd.value = formatted
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
        }
        continue
      }

      // --- Legacy events: content may be at payload top level ---
      const md = extractMd(evt.payload)
      switch (nodeKey) {
        case 'clarify':
          if (md) draftMd.value = md
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
  }
})

const detailTitle = computed(() => {
  const labels: Record<DagNodeKey, string> = {
    clarify: '澄清草稿',
    impact: '影响分析报告',
    implement: '实现三联草案',
    verify: '验证清单'
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
  try {
    const info = await getRamSession(id)
    if (info.clarifyPending) {
      showClarify.value = true
    }
    if (info.hitlPending) {
      showConfirm.value = true
    }
    // Always rejoin from seq 0 so we receive the complete event history.
    // Each DraftPage mount creates a fresh useRamSession() with no prior
    // events, so we must replay from the beginning regardless of the
    // session's current progress.
    session.rejoin(id, 0)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '加载会话失败'
    ElMessage.error(msg)
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
          <div v-if="activeNode === 'impact' && impactPayload" class="detail-impact">
            <ImpactSankeyGraph
              :involved="impactPayload.involved"
              :modified="impactPayload.modified"
              :impacted="impactPayload.impacted"
              :risk-scores="impactPayload.riskScores ?? {}"
              :width="740"
              :height="460"
            />
            <pre class="md">{{ impactMd || '— 暂无详细 Markdown —' }}</pre>
          </div>
          <pre v-else class="md">{{ detailMarkdown || '— 暂无内容 —' }}</pre>
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
.detail-impact {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 16px;
  align-items: start;
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
</style>
