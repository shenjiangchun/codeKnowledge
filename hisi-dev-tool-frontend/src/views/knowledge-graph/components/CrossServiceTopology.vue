<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch, nextTick, computed } from 'vue'
import { knowledgeGraphApi, type ServiceTopology } from '@/api/knowledgeGraph'
import { ElSkeleton, ElEmpty, ElAlert } from 'element-plus'
import * as echarts from 'echarts'

const props = defineProps<{ projectPaths: string[]; language?: string }>()
const loading = ref(false)
const data = ref<ServiceTopology | null>(null)
const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

const MAX_NODES = 30
const BRIDGE_COLORS: Record<string, string> = { FEIGN: '#c62828', HTTP: '#ef6c00', MQ: '#f9a825', DIRECT: '#757575' }
const BRIDGE_LABELS: Record<string, string> = { FEIGN: 'Feign 调用', HTTP: 'HTTP 调用', MQ: '消息队列', DIRECT: '直接调用' }

const visibleServices = computed(() => {
  if (!data.value) return []
  return [...data.value.services].sort((a, b) => b.methodCount - a.methodCount).slice(0, MAX_NODES)
})
const truncatedCount = computed(() => {
  if (!data.value) return 0
  return Math.max(0, data.value.services.length - MAX_NODES)
})

async function load() {
  if (!props.projectPaths.length) return
  loading.value = true
  try {
    const res = await knowledgeGraphApi.getServiceTopology(props.projectPaths, props.language)
    data.value = res
  } catch { } finally { loading.value = false }
}

// 手动网格布局：像素坐标（x/y 单位一致 → 节点正圆），横向铺满画布
function gridPositions(count: number, W: number, H: number): { x: number; y: number }[] {
  const cols = Math.max(1, Math.round(Math.sqrt(count * (W / H))))
  const rows = Math.ceil(count / cols)
  const xGap = W / (cols + 1)
  const yGap = H / (rows + 1)
  return Array.from({ length: count }, (_, i) => ({
    x: ((i % cols) + 1) * xGap,
    y: (Math.floor(i / cols) + 1) * yGap
  }))
}

function ensureObserver() {
  if (resizeObserver || typeof ResizeObserver === 'undefined' || !chartRef.value) return
  resizeObserver = new ResizeObserver(() => renderGraph())
  resizeObserver.observe(chartRef.value)
}

function renderGraph() {
  const el = chartRef.value
  if (!el || !data.value) return
  ensureObserver()  // 容器首次出现即挂上（onMounted 时容器还没渲染）
  const rect = el.getBoundingClientRect()
  const W = rect.width
  const H = rect.height
  if (W < 50 || H < 50) return  // 容器还没展开（tab 未激活），等 ResizeObserver 触发

  const buildOption = (w: number, h: number) => {
    const visible = visibleServices.value
    const visibleNames = new Set(visible.map(s => s.name))
    const links = data.value.edges
      .filter(e => visibleNames.has(e.source) && visibleNames.has(e.target))
      .map(e => ({ source: e.source, target: e.target, value: e.weight, type: e.type }))
    const pos = gridPositions(visible.length, w, h)
    return {
      tooltip: {
        formatter: (p: any) => {
          if (p.dataType === 'edge') return `${p.data.sourceName} → ${p.data.targetName}<br/>${BRIDGE_LABELS[p.data.type] ?? p.data.type} · ${p.data.value ?? 0} 次`
          return `${p.data.name}<br/>方法数：${p.data.value}`
        }
      },
      legend: {
        data: Object.values(BRIDGE_LABELS),
        top: 0
      },
      series: [{
        type: 'graph',
        layout: 'none',
        roam: true,
        draggable: true,
        data: visible.map((s, i) => ({
          id: s.name,
          name: s.name.replace(/^.*:/, ''),
          value: s.methodCount,
          x: pos[i].x,
          y: pos[i].y,
          symbol: 'circle',
          symbolSize: Math.max(28, Math.min(80, s.methodCount / 5)),
          itemStyle: { color: '#1976d2' },
          label: { show: true, position: 'bottom', fontSize: 11, fontWeight: 'bold', color: '#333' }
        })),
        links,
        lineStyle: { color: '#bbb', width: 1.5, curveness: 0.15 },
        emphasis: { focus: 'adjacency', lineStyle: { width: 3 } }
      }]
    }
  }

  // 每次从当前容器尺寸干净重建，避免残留 100px 的坏实例
  if (chart) { chart.dispose(); chart = null }
  chart = echarts.init(el)
  chart.setOption(buildOption(W, H))
}

onMounted(load)
watch(() => props.projectPaths, load)
watch(data, (d) => {
  if (d) nextTick(() => renderGraph())
}, { flush: 'post' })
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
        <b>跨服务拓扑</b>：展示服务间调用关系。节点 = 服务，大小 = 方法数；连线 = 调用，颜色类型见下方图例。拖拽移动，滚轮缩放。
      </template>
    </el-alert>

    <el-skeleton :loading="loading" animated :count="3">
      <el-empty v-if="!data || !data.services.length">
        <template #description>
          <div style="line-height: 1.8">
            <p>当前项目没有检测到跨服务调用（Feign / MQ / HTTP 桥接）。</p>
            <p style="color:#909399;font-size:12px">单体架构项目不产生服务间调用，此视图仅对微服务/多模块项目有意义。</p>
          </div>
        </template>
      </el-empty>
      <div v-else>
        <div class="legend-row">
          <span v-for="(label, type) in BRIDGE_LABELS" :key="type" class="legend-item">
            <span class="legend-dot" :style="{ background: BRIDGE_COLORS[type] }" />{{ label }}
          </span>
        </div>
        <el-alert v-if="truncatedCount > 0" type="warning" :closable="false" class="truncate-tip">
          共 {{ data.services.length }} 个服务，仅显示方法数最多的 {{ MAX_NODES }} 个，其余 {{ truncatedCount }} 个已隐藏
        </el-alert>
        <div ref="chartRef" class="graph-container" />
      </div>
    </el-skeleton>
  </div>
</template>

<style scoped>
.desc-bar { margin-bottom: 12px; }
.legend-row { display: flex; flex-wrap: wrap; gap: 16px; margin-bottom: 8px; }
.legend-item { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: #606266; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.truncate-tip { margin-bottom: 8px; }
.graph-container { width: 100%; height: 480px; border: 1px solid #ebeef5; border-radius: 4px; }
</style>
