<script setup lang="ts">
/**
 * RAM DraftPage — subscribes (rejoin) to an existing RAM session via the
 * {@code sid} route param, streams events through {@code useRamSession}, and
 * surfaces three side tabs (Clarify / Impact / Implement) plus a top-bar
 * {@code CostMeter} and an event timeline.
 *
 * When status === 'clarify', a {@code ClarifyModal} pops up to collect answers.
 * When the orchestrator reports an Impact result, an action surfaces to
 * navigate to {@code GraphPreviewPage} after stashing the payload in the
 * Pinia store.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import CostMeter from '@/components/ram/CostMeter.vue'
import ClarifyModal from '@/components/ram/ClarifyModal.vue'
import { useRamSession } from '@/composables/useRamSession'
import { getRamSession } from '@/api/ram'
import { useRamStore, type ImpactPayload } from '@/stores/ram'

const route = useRoute()
const router = useRouter()
const ramStore = useRamStore()
const session = useRamSession()

const sid = computed<string>(() => String(route.params.sid ?? ''))
const activeTab = ref<'clarify' | 'impact' | 'implement'>('clarify')
const showClarify = ref<boolean>(false)
const draftMd = ref<string>('')
const impactMd = ref<string>('')
const implementMd = ref<string>('')
const impactPayload = ref<ImpactPayload | null>(null)

/**
 * Best-effort extraction of structured payloads from a streamed event.
 * The backend may emit either {payload: {markdown: "..."}} or
 * {payload: {phase: "draft", text: "..."}} — handle both.
 */
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

// Watch incoming events and route them to the right tab.
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
      } else if (phase === 'draft' || evt.type === 'DRAFT_UPDATE') {
        if (md) draftMd.value = md
      }
    }
  },
  { deep: true }
)

// Sync the clarify modal with the composable status.
watch(
  () => session.status.value,
  (s) => {
    showClarify.value = s === 'clarify'
    if (s === 'completed' || s === 'error' || s === 'aborted') {
      // ensure we are off the clarify view
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

/**
 * Rejoin an existing session: ask the backend for {@code currentSeq} and
 * {@code clarifyPending}, then route everything through the composable's
 * public {@link useRamSession.rejoin} method so dedup, cumulative-cost guard,
 * terminal-state transitions, and EventSource teardown all stay encapsulated
 * inside the composable.
 */
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
      // We still rejoin the stream so future events keep flowing, but the
      // modal is shown immediately based on the synchronous REST status.
      session.rejoin(id, info.currentSeq ?? 0)
      showClarify.value = true
    } else if (info.status === 'completed' || info.status === 'aborted') {
      // Terminal: register the sid for downstream actions, no SSE needed.
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
    <div class="topbar">
      <CostMeter :tokens="session.cost.value.tokens" :usd="session.cost.value.usd" />
      <el-tag size="small">状态: {{ session.status.value }}</el-tag>
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
    </div>

    <div class="body">
      <el-card class="timeline" shadow="never">
        <template #header>事件流</template>
        <ul class="event-list">
          <li v-for="evt in session.events.value" :key="evt.seq" class="event-item">
            <span class="seq">#{{ evt.seq }}</span>
            <span class="type">{{ evt.type }}</span>
          </li>
        </ul>
      </el-card>

      <el-card class="panel" shadow="never">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="Clarify 草稿" name="clarify">
            <pre class="md">{{ draftMd || '— 暂无 —' }}</pre>
          </el-tab-pane>
          <el-tab-pane label="Impact 报告" name="impact">
            <pre class="md">{{ impactMd || '— 暂无 —' }}</pre>
          </el-tab-pane>
          <el-tab-pane label="Implement 三联" name="implement">
            <pre class="md">{{ implementMd || '— 暂无 —' }}</pre>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>

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
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.topbar-spacer {
  flex: 1;
}
.body {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.timeline {
  overflow: auto;
}
.event-list {
  list-style: none;
  margin: 0;
  padding: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.event-item {
  padding: 4px 0;
  border-bottom: 1px solid #f0f0f0;
}
.event-item .seq {
  color: #909399;
  margin-right: 8px;
}
.panel {
  overflow: auto;
}
.md {
  white-space: pre-wrap;
  margin: 0;
  font-size: 13px;
  color: #303133;
}
</style>
