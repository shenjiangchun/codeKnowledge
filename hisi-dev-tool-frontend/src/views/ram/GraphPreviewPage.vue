<script setup lang="ts">
/**
 * RAM GraphPreviewPage — renders the three concentric impact rings via
 * {@code ThreeRingGraph} alongside a side panel listing files per ring with
 * optional risk-score badges.
 *
 * The impact payload is provided by the Pinia {@code useRamStore}, populated
 * earlier by {@code DraftPage} when an Impact-phase event lands.
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ThreeRingGraph from '@/components/ram/ThreeRingGraph.vue'
import { useRamStore, type ImpactPayload } from '@/stores/ram'

const route = useRoute()
const router = useRouter()
const store = useRamStore()

const sid = computed<string>(() => String(route.params.sid ?? ''))

const empty: ImpactPayload = { involved: [], modified: [], impacted: [] }
const impact = computed<ImpactPayload>(() => store.impact ?? empty)

function riskBadge(file: string): { score: number; type: 'success' | 'warning' | 'danger' } | null {
  const score = impact.value.riskScores?.[file]
  if (typeof score !== 'number') return null
  let type: 'success' | 'warning' | 'danger' = 'success'
  if (score >= 0.7) type = 'danger'
  else if (score >= 0.4) type = 'warning'
  return { score, type }
}

function backToDraft(): void {
  router.push({ name: 'RamDraft', params: { sid: sid.value } })
}

onMounted(() => {
  if (!store.impact) {
    ElMessage.warning('未发现影响数据，请先返回 Draft 页等待 Impact 完成')
  }
})
</script>

<template>
  <div class="ram-graph-view">
    <div class="topbar">
      <el-button size="small" @click="backToDraft">返回 Draft</el-button>
      <span class="title">影响图谱（三层环）</span>
      <span class="sid">session: {{ sid }}</span>
    </div>
    <div class="body">
      <div class="canvas">
        <ThreeRingGraph
          :involved="impact.involved"
          :modified="impact.modified"
          :impacted="impact.impacted"
        />
      </div>
      <el-card class="side" shadow="never">
        <template #header>文件清单</template>
        <div v-for="ring in ['involved', 'modified', 'impacted'] as const" :key="ring" class="ring-block">
          <h4 :class="`label-${ring}`">{{ ring }}（{{ impact[ring].length }}）</h4>
          <ul>
            <li v-for="file in impact[ring]" :key="`${ring}-${file}`" class="file-row">
              <span class="file">{{ file }}</span>
              <el-tag v-if="riskBadge(file)" :type="riskBadge(file)!.type" size="small">
                {{ riskBadge(file)!.score.toFixed(2) }}
              </el-tag>
            </li>
          </ul>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.ram-graph-view {
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
.title {
  font-weight: 600;
}
.sid {
  color: #909399;
  font-size: 12px;
}
.body {
  display: grid;
  grid-template-columns: 1fr 360px;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.canvas {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.side {
  overflow: auto;
}
.ring-block {
  margin-bottom: 16px;
}
.ring-block ul {
  list-style: none;
  padding: 0;
  margin: 0;
}
.file-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
}
.file {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #303133;
}
.label-involved {
  color: #d4a017;
  margin: 0 0 6px;
}
.label-modified {
  color: #e6a23c;
  margin: 0 0 6px;
}
.label-impacted {
  color: #909399;
  margin: 0 0 6px;
}
</style>
