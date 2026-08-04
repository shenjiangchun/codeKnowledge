<script setup lang="ts">
/**
 * ImpactSankeyGraph — ECharts Sankey visualization for the RAM impact analysis.
 *
 * Groups nodeIds by Java package and renders a three-column horizontal Sankey
 * (involved → modified → impacted). Edge width represents method count flowing
 * between packages across rings. Node color indicates risk heat.
 *
 * Drop-in replacement for ThreeRingGraph.vue — same props interface.
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { transformImpactToSankey, buildSankeyOption, RING_LABELS } from './impactSankeyTransform'
import { RING_COLORS } from './threeRingLayout'
import type { RingColumn } from './impactSankeyTransform'

interface Props {
  involved: readonly string[]
  modified: readonly string[]
  impacted: readonly string[]
  riskScores?: Readonly<Record<string, number>>
  width?: number
  height?: number
}

const props = withDefaults(defineProps<Props>(), {
  width: 740,
  height: 460,
  riskScores: () => ({})
})

const emit = defineEmits<{
  (e: 'nodeClick', packageName: string): void
}>()

const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

const isEmpty = computed(() =>
  props.involved.length === 0 && props.modified.length === 0 && props.impacted.length === 0
)

function updateChart(): void {
  if (!chart || isEmpty.value) return
  const sankeyData = transformImpactToSankey({
    involved: props.involved,
    modified: props.modified,
    impacted: props.impacted,
    riskScores: props.riskScores
  })
  const option = buildSankeyOption(sankeyData, props.width, props.height)
  chart.setOption(option, { notMerge: true })
}

function initChart(): void {
  if (!chartRef.value) return
  if (chart) {
    chart.dispose()
    chart = null
  }
  chart = echarts.init(chartRef.value)
  updateChart()

  chart.on('click', (params: unknown) => {
    const p = params as { dataType?: string; data?: Record<string, unknown> }
    if (p.dataType === 'node' && p.data?.name) {
      const fullName = String(p.data.name)
      const packageName = fullName.includes('::') ? fullName.split('::')[1] : fullName
      emit('nodeClick', packageName)
    }
  })
}

onMounted(() => {
  initChart()
  if (chartRef.value) {
    resizeObserver = new ResizeObserver(() => {
      chart?.resize()
    })
    resizeObserver.observe(chartRef.value)
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (chart) {
    chart.dispose()
    chart = null
  }
})

watch(
  () => [props.involved, props.modified, props.impacted, props.riskScores] as const,
  () => updateChart(),
  { deep: true }
)

const LEGEND: { key: RingColumn; label: string; color: string }[] = [
  { key: 'involved', label: RING_LABELS.involved, color: RING_COLORS.involved },
  { key: 'modified', label: RING_LABELS.modified, color: RING_COLORS.modified },
  { key: 'impacted', label: RING_LABELS.impacted, color: RING_COLORS.impacted }
]
</script>

<template>
  <div
    class="impact-sankey-wrap"
    :style="{ width: `${props.width}px`, height: `${props.height}px` }"
  >
    <div ref="chartRef" class="sankey-chart" />
    <div v-if="isEmpty" class="empty-hint">暂无影响数据</div>
    <div class="legend">
      <div v-for="item in LEGEND" :key="item.key" class="legend-row">
        <span class="legend-dot" :style="{ background: item.color }" />
        <span class="legend-label">{{ item.label }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.impact-sankey-wrap {
  position: relative;
  display: inline-block;
  background:
    radial-gradient(circle at 50% 50%, rgba(64, 158, 255, 0.03) 0%, transparent 60%),
    #fafbfc;
  border-radius: 12px;
  overflow: hidden;
}

.sankey-chart {
  width: 100%;
  height: 100%;
}

.empty-hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 14px;
  pointer-events: none;
}

.legend {
  position: absolute;
  bottom: 8px;
  right: 8px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 4px 10px;
  font-size: 11px;
  display: flex;
  gap: 12px;
  backdrop-filter: blur(4px);
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-label {
  font-weight: 600;
  color: #606266;
}
</style>
