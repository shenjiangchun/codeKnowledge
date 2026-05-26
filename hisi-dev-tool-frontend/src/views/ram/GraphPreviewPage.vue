<script setup lang="ts">
/**
 * RAM GraphPreviewPage — two-column impact view.
 *
 *  ┌──────────────────────────────┬─────────────────┐
 *  │  DagGraph (main)             │ FileBrowserPanel│
 *  │                              │ (search/group/  │
 *  │                  ┌─────────┐ │  select/export) │
 *  │                  │ Minimap │ │                 │
 *  │                  └─────────┘ │                 │
 *  └──────────────────────────────┴─────────────────┘
 *
 * Bi-directional linkage runs through useRamStore (selectedFile,
 * hoveredFile, highlightPath).
 */
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DagGraph from '@/components/ram/DagGraph.vue'
import FileBrowserPanel from '@/components/ram/FileBrowserPanel.vue'
import Minimap from '@/components/ram/Minimap.vue'
import { useRamStore, type ImpactPayload } from '@/stores/ram'

const route = useRoute()
const router = useRouter()
const store = useRamStore()
const sid = computed<string>(() => String(route.params.sid ?? ''))

const empty: ImpactPayload = { involved: [], modified: [], impacted: [] }
const impact = computed<ImpactPayload>(() => store.impact ?? empty)

const riskScores = computed<Record<string, number>>(() => impact.value.riskScores ?? {})

// Build DAG edges from impact: seeds=involved → modified → impacted.
const dagSeeds = computed<string[]>(() => [...impact.value.involved])
// NOTE: O(involved×modified + modified×impacted). Acceptable for typical (<100) impact sets; revisit if payloads grow.
const dagEdges = computed(() => {
  const edges: { from: string; to: string; kind: 'call' }[] = []
  for (const seed of impact.value.involved)
    for (const m of impact.value.modified) edges.push({ from: seed, to: m, kind: 'call' })
  for (const m of impact.value.modified)
    for (const i of impact.value.impacted) edges.push({ from: m, to: i, kind: 'call' })
  return edges
})
const inDegree = computed<Record<string, number>>(() => {
  const d: Record<string, number> = {}
  for (const e of dagEdges.value) d[e.to] = (d[e.to] ?? 0) + 1
  return d
})

function backToDraft(): void {
  router.push({ name: 'RamDraft', params: { sid: sid.value } })
}

onMounted(() => {
  if (!store.impact) ElMessage.warning('未发现影响数据，请先返回 Draft 页等待 Impact 完成')
})

onUnmounted(() => {
  // Prevent cross-page state leak: clear the linkage state when leaving this view.
  store.selectFile(null)
  store.hoverFile(null)
  store.clearHighlight()
})
</script>

<template>
  <div class="ram-graph-view">
    <div class="topbar">
      <el-button size="small" @click="backToDraft">返回 Draft</el-button>
      <span class="title">影响图谱（分层 DAG）</span>
      <span class="sid">session: {{ sid }}</span>
    </div>
    <div class="body">
      <div class="canvas">
        <DagGraph
          :seeds="dagSeeds"
          :edges="dagEdges"
          :risk-scores="riskScores"
          :in-degree="inDegree"
        />
        <div class="minimap-overlay">
          <Minimap
            :involved="impact.involved"
            :modified="impact.modified"
            :impacted="impact.impacted"
            :risk-scores="riskScores"
          />
        </div>
      </div>
      <aside class="side">
        <FileBrowserPanel
          :involved="impact.involved"
          :modified="impact.modified"
          :impacted="impact.impacted"
          :risk-scores="riskScores"
          group-by="ring"
        />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.ram-graph-view { padding: 12px; display: flex; flex-direction: column; gap: 12px; height: 100%; }
.topbar { display: flex; align-items: center; gap: 12px; }
.title { font-weight: 600; }
.sid { color: #909399; font-size: 12px; }
.body { display: grid; grid-template-columns: 1fr 380px; gap: 12px; flex: 1; min-height: 0; }
.canvas { position: relative; background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; overflow: hidden; }
.minimap-overlay { position: absolute; right: 12px; bottom: 12px; background: rgba(255,255,255,0.9); border-radius: 8px; padding: 4px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
.side { border: 1px solid #ebeef5; border-radius: 4px; overflow: hidden; }
</style>
