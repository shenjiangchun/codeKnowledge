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
import DagFlow from '@/components/ram/DagFlow.vue'
import ThreeRingGraph from '@/components/ram/ThreeRingGraph.vue'
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
const draftMd = ref<string>('')
const impactMd = ref<string>('')
const implementMd = ref<string>('')
const verifyMd = ref<string>('')
const impactPayload = ref<ImpactPayload | null>(null)

const dagNodes = computed(() => deriveDagSnapshot(session.events.value, session.status.value))

function asString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function extractMd(payload: Readonly<Record<string, unknown>>): string | null {
  return asString(payload['markdown']) ?? asString(payload['text']) ?? asString(payload['content'])
}

function extractStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map((v) => String(v)).filter((s) => s.length > 0)
}

function extractImpact(payload: Readonly<Record<string, unknown>>): ImpactPayload | null {
  const involved = extractStringArray(payload['involved'])
  const modified = extractStringArray(payload['modified'])
  const impacted = extractStringArray(payload['impacted'])
  if (involved.length === 0 && modified.length === 0 && impacted.length === 0) {
    return null
  }
  const riskRaw = payload['riskScores']
  const riskScores =
    riskRaw && typeof riskRaw === 'object'
      ? (Object.fromEntries(
          Object.entries(riskRaw as Record<string, unknown>).map(([k, v]) => [k, Number(v) || 0])
        ) as Record<string, number>)
      : undefined
  return { involved, modified, impacted, riskScores }
}

watch(
  () => session.events.value,
  (list) => {
    for (const evt of list) {
      const md = extractMd(evt.payload)
      const phase = String(evt.payload['phase'] ?? '').toLowerCase()
      if (evt.type === 'IMPACT_DONE' || phase === 'impact') {
        const impact = extractImpact(evt.payload)
        if (impact) {
          impactPayload.value = impact
          ramStore.setImpact(sid.value, impact)
        }
        if (md) impactMd.value = md
      } else if (phase === 'implement' || evt.type === 'IMPLEMENT_DONE') {
        if (md) implementMd.value = md
      } else if (phase === 'verify' || evt.type === 'VERIFY_DONE') {
        if (md) verifyMd.value = md
      } else if (phase === 'draft' || evt.type === 'DRAFT_UPDATE') {
        if (md) draftMd.value = md
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
    if (s === 'completed' || s === 'error' || s === 'aborted') {
      showClarify.value = false
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

function onClarifyCancel(): void {
  showClarify.value = false
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
      session.rejoin(id, info.currentSeq ?? 0)
      showClarify.value = true
    } else if (info.status === 'completed' || info.status === 'aborted') {
      session.sessionId.value = id
      session.status.value = info.status
    } else {
      session.rejoin(id, info.currentSeq ?? 0)
    }
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
            <ThreeRingGraph
              :involved="impactPayload.involved"
              :modified="impactPayload.modified"
              :impacted="impactPayload.impacted"
              :risk-scores="impactPayload.riskScores ?? {}"
              :width="520"
              :height="520"
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
