<script setup lang="ts">
import { onMounted, ref, watch, nextTick, computed } from 'vue'
import { knowledgeGraphApi, type HotspotItem } from '@/api/knowledgeGraph'
import { ElSkeleton, ElEmpty, ElTag, ElTable, ElTableColumn, ElSelect, ElOption, ElAlert, ElDialog, ElDescriptions, ElDescriptionsItem } from 'element-plus'
import * as echarts from 'echarts'

const props = defineProps<{ projectPaths: string[]; language?: string }>()
const loading = ref(false)
const hotspots = ref<HotspotItem[]>([])
const viewMode = ref<'list' | 'treemap'>('list')
const filterRole = ref<string>('')
const chartRef = ref<HTMLDivElement>()
const detailVisible = ref(false)
const detail = ref<HotspotItem | null>(null)

const roleOptions = ['', 'CONTROLLER', 'SERVICE', 'REPOSITORY', 'MAPPER', 'DATA', 'UTILITY', 'UNKNOWN']

const filteredHotspots = computed(() =>
  filterRole.value ? hotspots.value.filter(h => h.layerRole === filterRole.value) : hotspots.value
)

// 归档仓库近 90 天可能无核心源码提交，此时变更次数全 0 是数据真实，需显式提示避免误以为功能坏了
const allChurnZero = computed(() =>
  hotspots.value.length > 0 && hotspots.value.every(h => h.commitCount90d === 0)
)

async function load() {
  if (!props.projectPaths.length) return
  loading.value = true
  try {
    const res = await knowledgeGraphApi.getHotspots(props.projectPaths, props.language, 100)
    hotspots.value = res.hotspots
  } finally { loading.value = false }
}

function renderTreemap() {
  if (!chartRef.value || !filteredHotspots.value.length) return
  const chart = echarts.init(chartRef.value)
  chart.off('click')
  chart.on('click', (p: any) => {
    const item = filteredHotspots.value.find(h => (h.filePath.split('/').pop() || h.filePath) === p.name)
    if (item) openDetail(item)
  })
  chart.setOption({
    tooltip: {
      formatter: (p: any) =>
        `${p.data.filePath}<br/>复杂度:${p.data.complexity} | 风险分:${p.data.riskScore?.toFixed(2)}<br/>变更:${p.data.commitCount90d} 次 | 层级:${p.data.layerRole}`
    },
    series: [{
      type: 'treemap',
      roam: false,
      leafDepth: 1,
      data: filteredHotspots.value.map(h => {
        // 面积用「复杂度+变更次数」综合，避免复杂度全 0 时塌缩成一条竖线
        const area = Math.max(1, h.complexity + h.commitCount90d)
        return {
          name: h.filePath.split('/').pop() || h.filePath,
          value: area,
          filePath: h.filePath,
          complexity: h.complexity,
          riskScore: h.riskScore,
          commitCount90d: h.commitCount90d,
          layerRole: h.layerRole,
          itemStyle: { color: `rgba(${Math.round(h.riskScore * 255)},${Math.round((1 - h.riskScore) * 200)},0,0.85)` }
        }
      }),
      label: { show: true, formatter: '{b}' },
      upperLabel: { show: false },
      levels: [{ itemStyle: { borderWidth: 1, borderColor: '#fff', gapWidth: 2 } }]
    }]
  })
  chart.resize()
}

function openDetail(h: HotspotItem) {
  detail.value = h
  detailVisible.value = true
}

function riskColor(score: number) {
  if (score > 0.7) return 'danger'; if (score > 0.4) return 'warning'; return 'success'
}

onMounted(load)
watch(() => props.projectPaths, load)
watch([hotspots, filterRole], () => {
  if (viewMode.value === 'treemap') nextTick(() => renderTreemap())
}, { flush: 'post' })
</script>

<template>
  <div>
    <!-- 说明栏 -->
    <el-alert type="info" :closable="false" class="desc-bar">
      <template #title>
        <b>热点分析</b>：找出最值得关注的高风险文件。风险分 = 圈复杂度 + Git 变更频率 + 入度 + 循环依赖。红色 = 高风险，点击文件查看详情。
      </template>
    </el-alert>

    <div class="toolbar">
      <el-select v-model="filterRole" placeholder="全部层级" clearable size="small" style="width:150px">
        <el-option v-for="r in roleOptions" :key="r" :label="r || '全部层级'" :value="r" />
      </el-select>
      <el-button-group>
        <el-button :type="viewMode === 'list' ? 'primary' : ''" size="small" @click="viewMode='list'">列表</el-button>
        <el-button :type="viewMode === 'treemap' ? 'primary' : ''" size="small" @click="viewMode='treemap';nextTick(renderTreemap)">热力图</el-button>
      </el-button-group>
    </div>

    <el-alert v-if="allChurnZero" type="warning" :closable="false" class="churn-hint">
      <template #title>
        <b>变更次数均为 0</b>：近 90 天内该仓库的核心源码没有提交变更（归档仓库常见），当前风险分主要来自圈复杂度与依赖结构，而非变更频率。
      </template>
    </el-alert>

    <el-skeleton :loading="loading" animated :count="3">
      <el-empty v-if="!filteredHotspots.length && !loading" description="尚无热点数据，请先完成知识图谱聚合" />
      <template v-else>
        <!-- 列表视图（默认，更可读） -->
        <el-table v-if="viewMode === 'list'" :data="filteredHotspots" size="small" max-height="520" @row-click="openDetail" class="clickable-table">
          <el-table-column label="文件" min-width="260" show-overflow-tooltip>
            <template #default="{ row }"><code>{{ row.filePath.split('/').pop() }}</code><div class="sub-path">{{ row.filePath }}</div></template>
          </el-table-column>
          <el-table-column prop="complexity" label="复杂度" width="80" sortable />
          <el-table-column prop="commitCount90d" label="变更次数" width="95" sortable />
          <el-table-column prop="layerRole" label="层级" width="110">
            <template #default="{ row }"><el-tag size="small" type="info" effect="plain">{{ row.layerRole }}</el-tag></template>
          </el-table-column>
          <el-table-column label="风险分" width="120" sortable sort-by="riskScore">
            <template #default="{ row }">
              <el-tag :type="riskColor(row.riskScore)" size="small">{{ row.riskScore?.toFixed(2) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <!-- 热力图视图 -->
        <div v-else ref="chartRef" class="treemap-container" />
      </template>
    </el-skeleton>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="热点文件详情" width="560px">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="文件路径">{{ detail.filePath }}</el-descriptions-item>
        <el-descriptions-item label="圈复杂度">{{ detail.complexity }}</el-descriptions-item>
        <el-descriptions-item label="90天变更次数">{{ detail.commitCount90d }}</el-descriptions-item>
        <el-descriptions-item label="架构层级">{{ detail.layerRole }}</el-descriptions-item>
        <el-descriptions-item label="风险分">
          <el-tag :type="riskColor(detail.riskScore)">{{ detail.riskScore?.toFixed(2) }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<style scoped>
.desc-bar { margin-bottom: 12px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.churn-hint { margin-bottom: 8px; }
.treemap-container { width: 100%; height: 520px; }
.clickable-table :deep(tbody tr) { cursor: pointer; }
.sub-path { font-size: 11px; color: #909399; margin-top: 2px; }
</style>
