<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch, nextTick, computed } from 'vue'
import { knowledgeGraphApi, type DsmData } from '@/api/knowledgeGraph'
import { ElSkeleton, ElEmpty, ElAlert, ElTag, ElSelect, ElOption, ElButton, ElMessage } from 'element-plus'
import * as echarts from 'echarts'

const props = defineProps<{ projectPaths: string[]; language?: string }>()
const loading = ref(false)
const data = ref<DsmData | null>(null)
const chartRef = ref<HTMLDivElement>()
const maxCell = ref(1)
const truncatedCount = ref(0)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

const topN = ref(20)  // 0 = 全部
// DSM 下钻：勾选模块 → 展示模块内类依赖（类粒度）
const selectedModules = ref<string[]>([])
const drillDownData = ref<DsmData | null>(null)
const drillDownLoading = ref(false)
// 当前展示的数据：下钻后为类粒度，否则为包粒度
const currentData = computed<DsmData | null>(() => drillDownData.value ?? data.value)

async function load() {
  if (!props.projectPaths.length) return
  loading.value = true
  try {
    const res = await knowledgeGraphApi.getDsm(props.projectPaths, props.language)
    data.value = res
    drillDownData.value = null
    selectedModules.value = []
  } catch { ElMessage.error('DSM 依赖矩阵加载失败') } finally { loading.value = false }
}

/** 勾选模块后下钻：展示这些模块内部类之间的依赖 */
async function drillDown() {
  if (!selectedModules.value.length) return
  drillDownLoading.value = true
  try {
    const res = await knowledgeGraphApi.getDsmDrillDown(props.projectPaths, selectedModules.value)
    drillDownData.value = res
    nextTick(() => renderDsm())
  } finally { drillDownLoading.value = false }
}

/** 返回包级视图 */
function backToPackage() {
  drillDownData.value = null
  selectedModules.value = []
  nextTick(() => renderDsm())
}

function ensureObserver() {
  if (resizeObserver || typeof ResizeObserver === 'undefined' || !chartRef.value) return
  resizeObserver = new ResizeObserver(() => renderDsm())
  resizeObserver.observe(chartRef.value)
}

function renderDsm() {
  const el = chartRef.value
  if (!el || !currentData.value) return
  ensureObserver()  // 容器首次出现即挂上（onMounted 时容器还没渲染）
  const rect = el.getBoundingClientRect()
  const W = rect.width
  const H = rect.height
  if (W < 50 || H < 50) return  // 容器还没展开（tab 未激活），跳过，等 ResizeObserver 触发

  const modules = currentData.value.modules
  const n = modules.length

  // 1. 计算每个模块的依赖总权重（作为依赖方 + 被依赖方）
  const weight = new Array(n).fill(0)
  for (const c of currentData.value.cells) {
    weight[c.sourceIdx] = (weight[c.sourceIdx] ?? 0) + c.weight
    weight[c.targetIdx] = (weight[c.targetIdx] ?? 0) + c.weight
  }

  // 2. 选依赖最多的 Top N 模块（保留原顺序）；类粒度下钻时不截断
  const limit = topN.value === 0 || drillDownData.value ? modules.length : topN.value
  const topIdx = modules
    .map((_, i) => i)
    .sort((a, b) => weight[b] - weight[a])
    .slice(0, limit)
    .sort((a, b) => a - b)
  const oldToNew = new Map<number, number>(topIdx.map((old, i) => [old, i]))
  const labels = topIdx.map(i => modules[i].split('.').pop()!)
  const m = topIdx.length
  truncatedCount.value = Math.max(0, n - m)

  // 3. 重建矩阵：matrix[sourceIdx][targetIdx] = 依赖次数
  const matrix: number[][] = Array.from({ length: m }, () => Array(m).fill(0))
  for (const c of currentData.value.cells) {
    if (oldToNew.has(c.sourceIdx) && oldToNew.has(c.targetIdx)) {
      matrix[oldToNew.get(c.sourceIdx)!][oldToNew.get(c.targetIdx)!] = c.weight
    }
  }
  maxCell.value = Math.max(...matrix.flat(), 1)

  // 每次从当前容器尺寸干净重建，避免残留 100px 的坏实例
  if (chart) { chart.dispose(); chart = null }
  chart = echarts.init(el)
  chart.setOption({
    tooltip: {
      formatter: (p: any) => {
        const dep = labels[p.value[1]]   // y = 依赖方（行）
        const tgt = labels[p.value[0]]   // x = 被依赖方（列）
        const v = p.value[2]
        if (v === 0) return `${dep} 不依赖 ${tgt}`
        return `<b>${dep}</b> 依赖 <b>${tgt}</b>：${v} 次调用`
      }
    },
    grid: { top: 90, right: 30, bottom: 90, left: 130 },
    xAxis: { type: 'category', data: labels, position: 'top', name: '被依赖方（列）', nameLocation: 'middle', nameGap: 55, axisLabel: { rotate: 45, fontSize: 10 } },
    yAxis: { type: 'category', data: labels, inverse: true, name: '依赖方（行）', nameLocation: 'middle', nameGap: 110, axisLabel: { fontSize: 10 } },
    visualMap: {
      min: 0, max: maxCell.value, calculable: true, orient: 'horizontal', left: 'center', bottom: 10,
      inRange: { color: ['#f5f5f5', '#1565c0'] },
      text: ['高依赖', '无依赖']
    },
    series: [{
      type: 'heatmap',
      data: matrix.flatMap((row, i) => row.map((v, j) => [j, i, v])),
      label: { show: true, fontSize: 9, formatter: (p: any) => (p.value[2] > 0 ? p.value[2] : '') },
      emphasis: { itemStyle: { borderColor: '#333', borderWidth: 1 } }
    }]
  })
}

onMounted(load)
watch(() => props.projectPaths, load)
watch(data, (d) => {
  if (d) nextTick(() => renderDsm())
}, { flush: 'post' })
watch(topN, () => {
  nextTick(() => renderDsm())
})
onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" class="desc-bar">
      <template #title>
        <b>DSM 依赖矩阵</b>：<b>行 = 依赖方</b>（发起依赖的模块），<b>列 = 被依赖方</b>。看某一行：深色格子表示「这一行对应的模块依赖了哪些列」。颜色越深 = 调用次数越多，悬停查看具体次数。
      </template>
    </el-alert>

    <el-skeleton :loading="loading" animated :count="3">
      <el-empty v-if="!data || !data.modules.length" description="尚无模块依赖数据，请先构建知识图谱" />
      <div v-else>
        <div class="matrix-meta">
          <el-tag size="small" type="info" effect="plain">{{ (drillDownData?.modules.length ?? data.modules.length) }} 个{{ drillDownData ? '类' : '模块' }}</el-tag>
          <template v-if="!drillDownData">
            <el-select v-model="topN" size="small" style="width:110px">
              <el-option label="Top 20" :value="20" />
              <el-option label="Top 50" :value="50" />
              <el-option label="全部" :value="0" />
            </el-select>
            <el-select v-model="selectedModules" multiple collapse-tags size="small" placeholder="勾选模块下钻" style="width:260px">
              <el-option v-for="m in data.modules" :key="m" :label="m.split('.').pop()" :value="m" />
            </el-select>
            <el-button size="small" type="primary" :disabled="!selectedModules.length" :loading="drillDownLoading" @click="drillDown">下钻</el-button>
          </template>
          <el-button v-else size="small" @click="backToPackage">返回包级</el-button>
          <span v-if="truncatedCount > 0" class="meta-hint">仅显示依赖最多的 {{ topN }} 个，其余 {{ truncatedCount }} 个已隐藏</span>
          <span class="meta-hint">最大依赖强度 {{ maxCell }} 次调用</span>
        </div>
        <div ref="chartRef" class="dsm-container" />
      </div>
    </el-skeleton>
  </div>
</template>

<style scoped>
.desc-bar { margin-bottom: 12px; }
.matrix-meta { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.meta-hint { font-size: 12px; color: #909399; }
.dsm-container { width: 100%; height: 560px; }
</style>
