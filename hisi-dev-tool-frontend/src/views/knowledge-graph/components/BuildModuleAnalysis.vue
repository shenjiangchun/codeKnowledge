<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import { ElCard, ElAlert, ElEmpty, ElSkeleton, ElTag } from 'element-plus'
import { knowledgeGraphApi, type BuildModuleGraphData, type ModuleLayerViolation } from '@/api/knowledgeGraph'
import * as echarts from 'echarts'

const props = defineProps<{ projectPaths: string[] }>()
const loading = ref(false)
const graphData = ref<BuildModuleGraphData | null>(null)
const cycles = ref<string[][]>([])
const violations = ref<ModuleLayerViolation[]>([])
const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

const PALETTE = ['#1976d2', '#388e3c', '#f57c00', '#7b1fa2', '#c62828', '#00838f', '#ad1457', '#6d4c41']

function projectName(p: string): string {
  return p.split('/').pop() || p.split('\\').pop() || p
}

const projectColors = new Map<string, string>()

function colorForProject(p: string): string {
  const name = projectName(p)
  let idx = 0
  for (const key of projectColors.keys()) {
    if (key === name) return projectColors.get(key)!
    idx++
  }
  const color = PALETTE[idx % PALETTE.length]
  projectColors.set(name, color)
  return color
}

async function load() {
  if (!props.projectPaths.length) return
  loading.value = true
  try {
    graphData.value = await knowledgeGraphApi.getBuildModules(props.projectPaths)
    const cyc = await knowledgeGraphApi.getBuildModuleCycles(props.projectPaths)
    cycles.value = cyc.cycles ?? []
    const vio = await knowledgeGraphApi.getBuildModuleLayerViolations(props.projectPaths)
    violations.value = vio.violations ?? []
  } catch (e) {
    console.error('[BuildModuleAnalysis] load 失败:', e)
  } finally { loading.value = false }
}

function cycleEdgeKeys(): Set<string> {
  const keys = new Set<string>()
  for (const cyc of cycles.value) {
    for (let i = 0; i < cyc.length - 1; i++) keys.add(cyc[i] + '->' + cyc[i + 1])
  }
  return keys
}

function cycleNodeKeys(): Set<string> {
  const keys = new Set<string>()
  for (const cyc of cycles.value) for (const n of cyc) keys.add(n)
  return keys
}

function ensureObserver() {
  if (resizeObserver || typeof ResizeObserver === 'undefined' || !chartRef.value) return
  resizeObserver = new ResizeObserver(() => renderGraph())
  resizeObserver.observe(chartRef.value)
}

function renderGraph() {
  const el = chartRef.value
  if (!el || !graphData.value) return
  ensureObserver()
  const rect = el.getBoundingClientRect()
  if (rect.width < 50 || rect.height < 50) return

  const d = graphData.value
  const inCycle = cycleNodeKeys()
  const cycleEdges = cycleEdgeKeys()

  if (chart) { chart.dispose(); chart = null }
  chart = echarts.init(el)
  chart.setOption({
    tooltip: {
      formatter: (p: any) => {
        if (p.dataType === 'edge') return `${p.data.source} → ${p.data.target}`
        return `${p.data.id}<br/>${p.data.groupId ?? ''}`
      }
    },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      data: d.nodes.map(n => ({
        id: n.moduleName,
        name: n.artifactId,
        groupId: n.groupId,
        projectPath: n.projectPath,
        symbolSize: 36,
        itemStyle: { color: inCycle.has(n.moduleName) ? '#ef5350' : colorForProject(n.projectPath ?? '') },
        label: { show: true, fontSize: 11, color: '#333' }
      })),
      links: d.edges.map(e => ({
        source: e.source,
        target: e.target,
        lineStyle: cycleEdges.has(e.source + '->' + e.target)
          ? { color: '#ef5350', width: 3 }
          : { color: '#90a4ae', width: 1.5 }
      })),
      force: { repulsion: 300, edgeLength: 120 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } }
    }]
  })
}

onMounted(load)
watch(() => props.projectPaths, load)
watch([graphData, cycles], () => { nextTick(() => renderGraph()) }, { flush: 'post' })
onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="build-module">
    <el-alert type="info" :closable="false" class="desc-bar">
      <template #title>
        <b>构建模块依赖</b>：解析各项目 pom.xml 的直接依赖（一跳），查询时内存拼边 + 穷举循环依赖。节点按归属项目着色，红色节点/边 = 循环依赖。
      </template>
    </el-alert>

    <el-skeleton :loading="loading" animated :count="5">
      <el-empty v-if="!graphData || !graphData.nodes.length" description="请先构建图谱（pom 解析在聚合阶段执行）" />
      <template v-else>
        <el-card>
          <template #header>
            <div class="card-header">
              <span>构建模块依赖图</span>
              <span class="hint">拖拽/滚轮缩放 · 节点按项目着色 · 红色=循环依赖</span>
            </div>
          </template>
          <div ref="chartRef" class="graph-container" />
        </el-card>

        <div class="row">
          <el-card v-if="cycles.length" class="half">
            <template #header>
              <span>⚠ 构建级循环依赖（{{ cycles.length }}）</span>
            </template>
            <el-alert
              v-for="(cyc, i) in cycles"
              :key="i"
              type="error"
              :closable="false"
              class="cyc-item"
              :title="cyc.map((n, j) => j < cyc.length - 1 ? n : n).join(' → ')"
            />
          </el-card>
          <el-card v-else class="half">
            <template #header><span>⚠ 构建级循环依赖</span></template>
            <el-empty description="未检测到循环依赖" :image-size="60" />
          </el-card>

          <el-card v-if="violations.length" class="half">
            <template #header>
              <span>📐 module 级分层违规（{{ violations.length }}）</span>
            </template>
            <div v-for="(v, i) in violations" :key="i" class="vio-item">
              <el-tag :type="v.type === 'CONTRADICTION' ? 'warning' : 'danger'" size="small">{{ v.type }}</el-tag>
              <span class="vio-msg">{{ v.message }}</span>
            </div>
          </el-card>
          <el-card v-else class="half">
            <template #header><span>📐 module 级分层违规</span></template>
            <el-empty description="未检测到分层违规" :image-size="60" />
          </el-card>
        </div>
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped>
.build-module { display: flex; flex-direction: column; gap: 16px; }
.desc-bar { margin-bottom: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.hint { color: #909399; font-size: 12px; }
.graph-container { width: 100%; height: 520px; }
.row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.half { min-height: 120px; }
.cyc-item { margin-bottom: 8px; }
.vio-item { display: flex; align-items: flex-start; gap: 8px; padding: 6px 0; border-bottom: 1px solid #f5f5f5; }
.vio-msg { color: #303133; font-size: 13px; line-height: 1.5; word-break: break-all; }
</style>
