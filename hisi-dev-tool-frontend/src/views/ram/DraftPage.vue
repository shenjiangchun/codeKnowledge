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
import { ElMessage, ElMessageBox } from 'element-plus'
import { renderMarkdown } from '@/utils/markdown'
import CostMeter from '@/components/ram/CostMeter.vue'
import ClarifyModal from '@/components/ram/ClarifyModal.vue'
import ConfirmModal from '@/components/ram/ConfirmModal.vue'
import DagFlow from '@/components/ram/DagFlow.vue'
import ImpactOutputView from '@/components/ram/ImpactOutputView.vue'
import TechPlanOutputView from '@/components/ram/TechPlanOutputView.vue'
import type { DagNodeKey } from '@/components/ram/dagModel'
import { useRamSession } from '@/composables/useRamSession'
import { useDagEventHandler } from '@/composables/useDagEventHandler'
import { executeTechPlan, getRamHealth, getRamSession, rerunFromNode, listClarifyRounds, rerunFromRound, type ClarifyRoundSummary } from '@/api/ram'
import { exportRamSessionMd } from '@/api/ram'
import { downloadBlob } from '@/utils/download'
import { useRamStore } from '@/stores/ram'

const route = useRoute()
const router = useRouter()
const ramStore = useRamStore()
const session = useRamSession()

const sid = computed<string>(() => String(route.params.sid ?? ''))

// Event handler composable - manages node outputs and event processing
const eventHandler = useDagEventHandler({ session, sid })

// Destructure state and methods from composable
const {
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
  resetProcessedSeq,
  dagNodes
} = eventHandler

// Local UI state
const activeNode = ref<DagNodeKey>('clarify')
const showClarify = ref<boolean>(false)
const showClarifyAlert = ref<boolean>(false)  // Controls clarify alert bar instead of auto-popup
const showConfirm = ref<boolean>(false)

// Clarify rounds history
const clarifyRounds = ref<ClarifyRoundSummary[]>([])
const clarifyRoundsLoading = ref(false)

// Watch for new events and process them via the composable
watch(
  () => session.events.value,
  (list) => {
    for (const evt of list) {
      processEvent(evt)
    }
    // Auto-advance the active card focus to the latest running stage
    const running = dagNodes.value.find((n) => n.status === 'running' || n.status === 'awaiting-hitl')
    if (running) activeNode.value = running.key
  },
  { deep: true }
)

watch(
  () => session.status.value,
  (s) => {
    // R-12: Show alert bar instead of auto-popup on first clarify
    if (s === 'clarify') {
      showClarifyAlert.value = true
    } else {
      showClarifyAlert.value = false
    }
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

async function handleExportMd(): Promise<void> {
  exportingMd.value = true
  try {
    const blob = await exportRamSessionMd(sid.value)
    const timestamp = new Date().toISOString().slice(0, 19).replace(/[:-]/g, "")
    const filename = `ram-session-${sid.value.slice(0, 8)}-${timestamp}.md`
    downloadBlob(blob, filename)
    ElMessage.success("会话已导出")
  } catch (e) {
    const msg = e instanceof Error ? e.message : "导出失败"
    ElMessage.error(msg)
  } finally {
    exportingMd.value = false
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
    const resp = await executeTechPlan(sid.value)
    // Mark tech_plan as running in clearedNodeKeys so DAG shows "执行中"
    clearedNodeKeys.value = new Set(['tech_plan'])
    techPlanMd.value = ''
    techPlanOutputData.value = null
    // Set session status to running so SSE stays open
    session.status.value = 'running'
    // Use nextSeq to skip already-processed events and rejoin SSE
    const nextSeq = typeof resp['nextSeq'] === 'number' ? resp['nextSeq'] as number : 0
    resetProcessedSeq(nextSeq)
    session.rejoin(sid.value, nextSeq)
    ElMessage.success('技术方案已开始生成')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '触发技术方案失败'
    ElMessage.error(msg)
  } finally {
    techPlanTriggering.value = false
  }
}

/** Map DagNodeKey to backend node name for rerun API. */
const dagKeyToNodeName: Record<DagNodeKey, string> = {
  clarify: 'clarify',
  impact: 'impact',
  implement: 'implement',
  verify: 'verify',
  tech_plan: 'tech_plan'
}

/** Chinese labels for DAG nodes. */
const dagNodeLabels: Record<DagNodeKey, string> = {
  clarify: '澄清',
  impact: '影响分析',
  implement: '实现方案',
  verify: '验证',
  tech_plan: '技术方案'
}

const rerunning = ref(false)
const exportingMd = ref(false)

async function onRerunFromNode(key: DagNodeKey): Promise<void> {
  const nodeName = dagKeyToNodeName[key]
  const nodeLabel = dagNodeLabels[key]

  // Calculate downstream nodes to clear
  const downstream: DagNodeKey[] = ['impact', 'implement', 'verify', 'tech_plan']
  const startIdx = downstream.indexOf(key)
  const toClear = key === 'clarify' ? ['clarify', ...downstream] as DagNodeKey[] : [key, ...downstream.slice(startIdx)]
  const toClearLabels = toClear.map(k => dagNodeLabels[k]).join('、')

  try {
    await ElMessageBox.confirm(
      `将重跑「${nodeLabel}」及其下游共 ${toClear.length} 个节点（${toClearLabels}），已完成的结果将被清除。确定继续？`,
      '确认重跑',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    // User cancelled
    return
  }

  try {
    const resp = await rerunFromNode(sid.value, nodeName)
    rerunning.value = true
    ElMessage.success(`将从 ${nodeName} 节点重新分析`)
    // Mark this node + downstream as cleared so DAG shows correct status
    clearedNodeKeys.value = new Set(toClear)
    // Clear output for this node + downstream
    for (const k of toClear) {
      switch (k) {
        case 'clarify': draftMd.value = ''; break
        case 'impact': impactMd.value = ''; impactOutputData.value = null; impactPayload.value = null; break
        case 'implement': implementMd.value = ''; break
        case 'verify': verifyMd.value = ''; break
        case 'tech_plan': techPlanMd.value = ''; techPlanOutputData.value = null; break
      }
    }
    // Use nextSeq from backend to skip all historical events
    const nextSeq = typeof resp['nextSeq'] === 'number' ? resp['nextSeq'] as number : 0
    resetProcessedSeq(nextSeq)
    // Set session status to running so DAG shows correct state
    session.status.value = 'running'
    // Rejoin with nextSeq so SSE starts after historical events
    session.rejoin(sid.value, nextSeq)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '重跑失败'
    ElMessage.error(msg)
  } finally {
    rerunning.value = false
  }
}

/** Whether the rerun button should show for a DAG node (completed + not currently running). */
function canRerun(key: DagNodeKey): boolean {
  const node = dagNodes.value.find(n => n.key === key)
  return (node?.status === 'done') && session.status.value !== 'running' && !rerunning.value
}

/** Load clarify rounds from backend. */
async function loadClarifyRounds(): Promise<void> {
  clarifyRoundsLoading.value = true
  try {
    clarifyRounds.value = await listClarifyRounds(sid.value)
  } catch {
    clarifyRounds.value = []
  } finally {
    clarifyRoundsLoading.value = false
  }
}

async function onRerunFromRound(roundNo: number): Promise<void> {
  const toClear: DagNodeKey[] = ['impact', 'implement', 'verify', 'tech_plan']
  const toClearLabels = toClear.map(k => dagNodeLabels[k]).join('、')

  try {
    await ElMessageBox.confirm(
      `将从第 ${roundNo} 轮澄清结果重新执行后续节点（${toClearLabels}），已完成的结果将被清除。确定继续？`,
      '确认重跑',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    // User cancelled
    return
  }

  try {
    const resp = await rerunFromRound(sid.value, roundNo)
    // Clear impact + downstream
    clearedNodeKeys.value = new Set(toClear)
    for (const k of toClear) {
      switch (k) {
        case 'impact': impactMd.value = ''; impactOutputData.value = null; impactPayload.value = null; break
        case 'implement': implementMd.value = ''; break
        case 'verify': verifyMd.value = ''; break
        case 'tech_plan': techPlanMd.value = ''; techPlanOutputData.value = null; break
      }
    }
    const nextSeq = typeof resp['nextSeq'] === 'number' ? resp['nextSeq'] as number : 0
    resetProcessedSeq(nextSeq)
    session.status.value = 'running'
    session.rejoin(sid.value, nextSeq)
    ElMessage.success(`从第 ${roundNo} 轮澄清结果重新执行后续节点`)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '重跑失败'
    ElMessage.error(msg)
  }
}

/** Track backend startedAt for restart detection. */

// Load clarify rounds when clarify node is done and has output
watch(
  () => [dagNodes.value.find(n => n.key === 'clarify')?.status, draftMd.value] as const,
  ([status, md]) => {
    if (status === 'done' && md && clarifyRounds.value.length === 0) {
      loadClarifyRounds()
    }
  },
  { immediate: true }
)
let backendStartedAt = 0

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
  return renderMarkdown(md)
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

async function initSession(id: string): Promise<void> {
  if (!id) {
    ElMessage.error('缺少 session id')
    router.replace({ name: 'RamSessions' })
    return
  }
  // Reset DraftPage local state for the new session
  draftMd.value = ''
  impactMd.value = ''
  implementMd.value = ''
  verifyMd.value = ''
  techPlanMd.value = ''
  impactOutputData.value = null
  techPlanOutputData.value = null
  impactPayload.value = null
  ramStore.clear()
  nodeReasoning.value = {}
  progressMessage.value = ''
  clearedNodeKeys.value = new Set()
  resetProcessedSeq(0)
  clarifyRounds.value = []

  // Capture backend startedAt for restart detection
  try {
    const health = await getRamHealth()
    backendStartedAt = health.startedAt
  } catch { /* non-critical */ }
  // rejoin resets composable state and loads historical events
  await session.rejoin(id, 0)
  // Sync UI state from composable
  // R-12: Show alert bar instead of auto-popup on clarify
  if (session.status.value === 'clarify') {
    showClarifyAlert.value = true
  }
  if (session.status.value === 'confirm') {
    showConfirm.value = true
  }
  try {
    const info = await getRamSession(id)
    if (info.clarifyPending) {
      showClarifyAlert.value = true
    }
    if (info.hitlPending) {
      showConfirm.value = true
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '加载会话信息失败，但SSE流已建立'
    ElMessage.warning(msg)
  }
}

// Watch sid for route param changes (Vue Router reuses the component)
watch(sid, async (newSid, oldSid) => {
  if (newSid && newSid !== oldSid) {
    await initSession(newSid)
  }
})

onMounted(async () => {
  await initSession(sid.value)
})

// Restart detection: when SSE errors out, check if backend restarted
watch(
  () => session.status.value,
  async (newStatus) => {
    if (newStatus !== 'error') return
    if (!backendStartedAt) return
    try {
      const health = await getRamHealth()
      if (health.startedAt > backendStartedAt) {
        backendStartedAt = health.startedAt
        ElMessage.warning('后端已重启，正在恢复会话...')
        await session.rejoin(sid.value, 0)
      }
    } catch { /* give up silently */ }
  }
)

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
      <el-button
        type="success"
        size="small"
        :loading="exportingMd"
        @click="handleExportMd"
      >
        导出 MD
      </el-button>
    </header>

    <!-- R-12: Clarify alert bar (replaces auto-popup) -->
    <el-alert
      v-if="showClarifyAlert"
      type="warning"
      :closable="false"
      class="clarify-alert"
    >
      <template #title>
        <div class="clarify-alert-content">
          <span>需要澄清：分析器需要更多信息才能继续。</span>
          <el-button type="primary" size="small" @click="showClarify = true; showClarifyAlert = false">
            打开
          </el-button>
        </div>
      </template>
    </el-alert>

    <section class="dag-section">
      <DagFlow :nodes="dagNodes" :active-key="activeNode" @node-click="onDagClick" />
    </section>

    <section class="body">
      <article class="detail">
        <header class="detail-header">
          <h2>{{ detailTitle }}</h2>
          <el-button
            v-if="canRerun(activeNode)"
            size="small"
            type="warning"
            :loading="rerunning"
            @click="onRerunFromNode(activeNode)"
          >
            从此节点重跑
          </el-button>
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

          <!-- Clarify rounds history (shown when clarify node is active and done) -->
          <div v-if="activeNode === 'clarify' && clarifyRounds.length > 0" class="clarify-rounds-panel">
            <el-divider>澄清轮次历史</el-divider>
            <div class="round-list">
              <div v-for="round in clarifyRounds" :key="round.roundNo" class="round-item">
                <div class="round-header">
                  <el-tag size="small" effect="plain">第 {{ round.roundNo }} 轮</el-tag>
                  <el-button
                    v-if="session.status.value !== 'running'"
                    size="small"
                    type="warning"
                    plain
                    @click="onRerunFromRound(round.roundNo)"
                  >从本轮重跑后续节点</el-button>
                </div>
                <div class="round-body">
                  <div class="round-questions">
                    <span class="round-label">提问:</span>
                    <ul>
                      <li v-for="(q, qi) in round.questions" :key="qi">{{ q }}</li>
                    </ul>
                  </div>
                  <div class="round-answers">
                    <span class="round-label">回答:</span>
                    <ul>
                      <li v-for="(val, key) in round.answers" :key="String(key)">
                        <span class="answer-key">{{ key }}:</span> {{ val }}
                      </li>
                    </ul>
                  </div>
                </div>
              </div>
            </div>
          </div>

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

.clarify-alert {
  margin-bottom: 0;
}
.clarify-alert-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
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

/* Clarify rounds history */
.clarify-rounds-panel {
  margin-top: 16px;
}
.round-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.round-item {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 10px 14px;
}
.round-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.round-body {
  display: flex;
  gap: 24px;
}
.round-questions,
.round-answers {
  flex: 1;
  font-size: 13px;
}
.round-label {
  font-weight: 600;
  color: #606266;
  margin-right: 4px;
}
.round-questions ul,
.round-answers ul {
  margin: 4px 0 0;
  padding-left: 18px;
}
.round-questions li,
.round-answers li {
  margin-bottom: 2px;
  line-height: 1.5;
}
.answer-key {
  font-family: monospace;
  font-size: 12px;
  color: #909399;
}
</style>
