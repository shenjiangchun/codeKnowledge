<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, computed } from 'vue'
import * as echarts from 'echarts'
import type { ApmSpan } from '@/types/apm'

const props = defineProps<{
  spans: ApmSpan[]
}>()

const emit = defineEmits<{
  spanClick: [span: ApmSpan]
}>()

const chartRef = ref<HTMLDivElement | null>(null)
let chartInstance: echarts.ECharts | null = null

interface GraphNode {
  id: string
  name: string
  value: number
  symbolSize: number
  itemStyle: { color: string }
  category: number
}

interface GraphLink {
  source: string
  target: string
}

const statusColorMap: Record<string, string> = {
  OK: '#67c23a',
  ERROR: '#f56c6c',
  UNSET: '#909399',
}

function getStatusCategory(statusCode: string): number {
  if (statusCode === 'OK') return 0
  if (statusCode === 'ERROR') return 1
  return 2
}

function getNodeLabel(span: ApmSpan): string {
  if (span.className && span.methodName) {
    const shortClass = span.className.split('.').pop() || span.className
    return `${shortClass}.${span.methodName}`
  }
  return span.operationName
}

const graphData = computed(() => {
  const nodes: GraphNode[] = []
  const links: GraphLink[] = []
  const maxDuration = Math.max(...props.spans.map(s => s.durationMs), 1)

  for (const span of props.spans) {
    const sizeRatio = span.durationMs / maxDuration
    const symbolSize = Math.max(20, Math.min(60, 20 + sizeRatio * 40))

    nodes.push({
      id: span.spanId,
      name: getNodeLabel(span),
      value: span.durationMs,
      symbolSize,
      itemStyle: {
        color: statusColorMap[span.statusCode] || statusColorMap['UNSET'],
      },
      category: getStatusCategory(span.statusCode),
    })

    if (span.parentSpanId) {
      links.push({
        source: span.parentSpanId,
        target: span.spanId,
      })
    }
  }

  return { nodes, links }
})

function buildChartOption(): echarts.EChartsOption {
  const { nodes, links } = graphData.value

  return {
    tooltip: {
      trigger: 'item',
      formatter(params: unknown): string {
        const p = params as { data?: { name?: string; value?: number } }
        if (p.data && p.data.name) {
          return `${p.data.name}<br/>耗时: ${p.data.value ?? 0}ms`
        }
        return ''
      },
    },
    legend: {
      data: ['正常', '错误', '未知'],
      top: 10,
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        label: {
          show: true,
          position: 'bottom',
          fontSize: 10,
          formatter: '{b}',
        },
        categories: [
          { name: '正常' },
          { name: '错误' },
          { name: '未知' },
        ],
        data: nodes,
        links,
        force: {
          repulsion: 200,
          edgeLength: [80, 200],
          gravity: 0.1,
        },
        lineStyle: {
          color: '#aaa',
          curveness: 0.1,
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 3 },
        },
      },
    ],
  }
}

function initChart(): void {
  if (!chartRef.value) return

  chartInstance = echarts.init(chartRef.value)
  chartInstance.setOption(buildChartOption())

  chartInstance.on('click', (params: unknown) => {
    const p = params as { dataType?: string; data?: { id?: string } }
    if (p.dataType === 'node' && p.data?.id) {
      const span = props.spans.find(s => s.spanId === p.data?.id)
      if (span) {
        emit('spanClick', span)
      }
    }
  })
}

function updateChart(): void {
  if (!chartInstance) {
    initChart()
    return
  }
  chartInstance.setOption(buildChartOption(), { notMerge: true })
}

function handleResize(): void {
  chartInstance?.resize()
}

watch(
  () => props.spans.length,
  () => {
    updateChart()
  }
)

onMounted(() => {
  initChart()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="chart-header">
        <span>Span 流图</span>
        <el-tag size="small" type="info">{{ spans.length }} spans</el-tag>
      </div>
    </template>
    <div
      v-if="spans.length > 0"
      ref="chartRef"
      class="chart-container"
    />
    <el-empty
      v-else
      description="等待 Span 数据..."
    />
  </el-card>
</template>

<style scoped>
.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-container {
  width: 100%;
  height: 500px;
}
</style>
