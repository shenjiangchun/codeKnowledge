<script setup lang="ts">
import { onMounted, onUnmounted, ref, nextTick } from 'vue'
import { ElCard, ElDrawer, ElRadioGroup, ElRadioButton, ElEmpty, ElTag } from 'element-plus'
import { knowledgeGraphApi, type LayerDomainClass } from '@/api/knowledgeGraph'
import * as echarts from 'echarts'

const props = defineProps<{ projectPaths: string[] }>()

const loading = ref(false)
const classes = ref<LayerDomainClass[]>([])
const viewMode = ref<'sankey' | 'heatmap'>('sankey')

const sankeyRef = ref<HTMLDivElement>()
const heatmapRef = ref<HTMLDivElement>()
let sankeyChart: echarts.ECharts | null = null
let heatmapChart: echarts.ECharts | null = null

// 分层固定偏序 + 颜色（与包级图一致）
const LAYER_BIAS = ['CONTROLLER', 'SERVICE', 'REPOSITORY', 'MODEL', 'UTILITY']
const UNKNOWN_COLOR = '#bdbdbd'
const LAYER_PALETTE = ['#1976d2', '#388e3c', '#f57c00', '#7b1fa2', '#607d8b', '#00838f']

// 聚合结果
interface Aggregate {
  layers: string[]
  domains: string[]
  links: { source: string; target: string; value: number }[]
  matrix: number[][]
  classLists: Map<string, string[]>
}
const agg = ref<Aggregate | null>(null)

function keyOf(layer: string, domain: string) { return layer + '→' + domain }

function aggregate(): Aggregate {
  const layerSet = new Set<string>()
  const domainSet = new Set<string>()
  const linkMap = new Map<string, number>()
  const classLists = new Map<string, string[]>()
  for (const c of classes.value) {
    layerSet.add(c.classRole)
    domainSet.add(c.domainName)
    const k = keyOf(c.classRole, c.domainName)
    linkMap.set(k, (linkMap.get(k) ?? 0) + 1)
    const arr = classLists.get(k) ?? []
    arr.push(c.className)
    classLists.set(k, arr)
  }
  // 分层按偏序排序，未知层追加
  const layers = [...LAYER_BIAS.filter(r => layerSet.has(r)), ...[...layerSet].filter(r => !LAYER_BIAS.includes(r)).sort()]
  // 领域按类总数降序
  const domainTotal = new Map<string, number>()
  for (const c of classes.value) domainTotal.set(c.domainName, (domainTotal.get(c.domainName) ?? 0) + 1)
  const domains = [...domainSet].sort((a, b) => (domainTotal.get(b) ?? 0) - (domainTotal.get(a) ?? 0))
  const links = [...linkMap].map(([k, v]) => { const [s, t] = k.split('→'); return { source: s, target: t, value: v } })
  const matrix = layers.map(layer => domains.map(dom => linkMap.get(keyOf(layer, dom)) ?? 0))
  return { layers, domains, links, matrix, classLists }
}

function layerColor(layer: string): string {
  const i = LAYER_BIAS.indexOf(layer)
  return i >= 0 ? LAYER_PALETTE[i % LAYER_PALETTE.length] : (layer === 'UNKNOWN' ? UNKNOWN_COLOR : '#9e9e9e')
}

// 某 (分层,领域) 组合下的类所属包全限定名（去重），用于 tooltip 展示「具体是哪些包」
function packagesOf(layer: string, domain: string): string[] {
  const list = agg.value?.classLists.get(keyOf(layer, domain)) ?? []
  return [...new Set(list.map(c => {
    const i = c.lastIndexOf('.')
    return i > 0 ? c.substring(0, i) : '(默认包)'
  }))]
}

// 拼 tooltip 的包名片段：最多列 6 个包，超出折叠
function packageHtml(packages: string[]): string {
  if (!packages.length) return ''
  const preview = packages.slice(0, 6).join('<br/>')
  const more = packages.length > 6 ? `<br/>... 等 ${packages.length} 个包` : ''
  return `<br/>${preview}${more}`
}

async function load() {
  if (!props.projectPaths.length) return
  loading.value = true
  try {
    classes.value = (await knowledgeGraphApi.getLayerDomainMatrix(props.projectPaths)).classes ?? []
    agg.value = aggregate()
    nextTick(() => { renderSankey(); renderHeatmap() })
  } catch (e) {
    console.error('[LayerDomainDiff] load 失败:', e)
  } finally { loading.value = false }
}

function renderSankey() {
  const el = sankeyRef.value
  if (!el || !agg.value) return
  const a = agg.value
  const nodes = [
    ...a.layers.map(name => ({ name, depth: 0, itemStyle: { color: layerColor(name) } })),
    ...a.domains.map(name => ({ name, depth: 1 })),
  ]
  if (sankeyChart) { sankeyChart.dispose(); sankeyChart = null }
  sankeyChart = echarts.init(el)
  sankeyChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => {
        if (p.dataType === 'edge') {
          return `${p.data.source} → ${p.data.target}<br/>类数量：<b>${p.data.value}</b>${packageHtml(packagesOf(p.data.source, p.data.target))}`
        }
        return `${p.name}<br/>类总数：<b>${p.value}</b>`
      },
    },
    series: [{
      type: 'sankey',
      data: nodes,
      links: a.links,
      nodeAlign: 'justify',
      layoutIterations: 0,
      orient: 'horizontal',
      nodeWidth: 16,
      nodeGap: 8,
      left: 8, right: 140, top: 16, bottom: 16,
      label: { fontSize: 11, color: '#333' },
      lineStyle: { color: 'gradient', curveness: 0.5, opacity: 0.3 },
      emphasis: { focus: 'adjacency' },
    }],
  })
  sankeyChart.on('click', (p: any) => {
    if (p.dataType === 'edge') openDrill(p.data.source, p.data.target)
  })
}

function renderHeatmap() {
  const el = heatmapRef.value
  if (!el || !agg.value) return
  const a = agg.value
  if (heatmapChart) { heatmapChart.dispose(); heatmapChart = null }
  heatmapChart = echarts.init(el)
  const data: [number, number, number][] = []
  a.layers.forEach((_layer, i) => {
    a.domains.forEach((_dom, j) => {
      if (a.matrix[i][j] > 0) data.push([j, i, a.matrix[i][j]])
    })
  })
  const maxV = Math.max(1, ...data.map(d => d[2]))
  heatmapChart.setOption({
    tooltip: {
      formatter: (p: any) => {
        const layer = a.layers[p.value[1]]
        const domain = a.domains[p.value[0]]
        return `${layer} × ${domain}<br/>类数量：<b>${p.value[2]}</b>${packageHtml(packagesOf(layer, domain))}`
      },
    },
    grid: { left: 140, right: 24, top: 8, bottom: 90 },
    xAxis: { type: 'category', data: a.domains, axisLabel: { rotate: 60, fontSize: 10, interval: 0 } },
    yAxis: { type: 'category', data: a.layers },
    visualMap: {
      min: 0, max: maxV, calculable: true, orient: 'vertical', right: 4, top: 'center',
      inRange: { color: ['#f0f0f0', '#1976d2'] },
    },
    series: [{
      type: 'heatmap',
      data,
      label: { show: true, fontSize: 10 },
      emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(0,0,0,0.4)' } },
    }],
  })
  heatmapChart.on('click', (p: any) => openDrill(a.layers[p.value[1]], a.domains[p.value[0]]))
}

// 下钻：点击连线/格子 → 展示该 (分层,领域) 下的类清单
const drillVisible = ref(false)
const drillTitle = ref('')
const drillClasses = ref<string[]>([])
function openDrill(layer: string, domain: string) {
  drillVisible.value = true
  drillTitle.value = `${layer} × ${domain}`
  drillClasses.value = agg.value?.classLists.get(keyOf(layer, domain)) ?? []
}

const viewKey = ref(0)
function onViewChange() {
  // key 变化强制 div 重建（旧 ECharts 实例已绑在销毁的 DOM 上），
  // 必须在新 DOM 就绪后重新 init 并渲染对应图。
  nextTick(() => {
    viewKey.value++
    nextTick(() => {
      if (viewMode.value === 'sankey') renderSankey()
      else renderHeatmap()
    })
  })
}

let resizeObserver: ResizeObserver | null = null
function ensureObserver() {
  if (resizeObserver || typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver(() => {
    if (viewMode.value === 'sankey') renderSankey()
    else renderHeatmap()
  })
}

onMounted(() => { load(); ensureObserver() })
onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  sankeyChart?.dispose(); sankeyChart = null
  heatmapChart?.dispose(); heatmapChart = null
})
</script>

<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>分层 × 领域 差异图（Sankey / 热力图，点击连线或格子查看类清单）</span>
        <el-radio-group v-model="viewMode" size="small" @change="onViewChange">
          <el-radio-button value="sankey">冲积图</el-radio-button>
          <el-radio-button value="heatmap">热力图</el-radio-button>
        </el-radio-group>
      </div>
    </template>
    <div v-loading="loading">
      <el-empty v-if="!agg || classes.length === 0" description="暂无领域归属数据（需先执行架构现状分析）" :image-size="60" />
      <div v-else>
        <div v-show="viewMode === 'sankey'" :key="'sankey-' + viewKey" ref="sankeyRef" class="diff-chart diff-chart-tall" />
        <div v-show="viewMode === 'heatmap'" :key="'heatmap-' + viewKey" ref="heatmapRef" class="diff-chart diff-chart-tall" />
      </div>
    </div>
  </el-card>

  <el-drawer v-model="drillVisible" :title="'类清单：' + drillTitle" size="40%">
    <div style="max-height:60vh;overflow:auto">
      <el-tag v-for="c in drillClasses" :key="c" class="class-tag" size="small" effect="plain">
        {{ c.split('.').pop() }}
      </el-tag>
      <div v-if="!drillClasses.length" style="color:#909399;font-size:13px">无类</div>
      <div style="margin-top:8px;color:#909399;font-size:12px">共 {{ drillClasses.length }} 个类</div>
    </div>
  </el-drawer>
</template>

<style scoped>
.diff-chart { width: 100%; }
.diff-chart-tall { height: 720px; }
.class-tag { margin: 3px; }
</style>
