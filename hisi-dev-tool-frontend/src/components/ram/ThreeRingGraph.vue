<script setup lang="ts">
/**
 * ThreeRingGraph — three-ring impact visualization with risk heat colors,
 * inter-ring propagation edges, ring legend, and zoom/pan support.
 *
 * Rendering stays in plain SVG so happy-dom can assert against it. All layout
 * math is delegated to {@code threeRingLayout.ts} so the template is dumb.
 *
 * Backward compatible with the previous shape: still emits {@code nodeClick}
 * with the file path; still renders {@code circle.ring-outline} (3) and
 * {@code circle.ring-node} (one per file). New behavior:
 *   - {@code riskScores} prop colors and sizes nodes on a green→red gradient
 *   - inter-ring edges connect files that appear in adjacent rings
 *   - pinch/wheel zoom + pan via viewBox transform
 */
import { computed, ref } from 'vue'
import {
  computeLayout,
  RING_COLORS,
  truncate,
  type RingKey
} from './threeRingLayout'

interface Props {
  involved: readonly string[]
  modified: readonly string[]
  impacted: readonly string[]
  riskScores?: Readonly<Record<string, number>>
  width?: number
  height?: number
}

const props = withDefaults(defineProps<Props>(), {
  width: 560,
  height: 560,
  riskScores: () => ({})
})

const emit = defineEmits<{
  (e: 'nodeClick', file: string): void
}>()

const layout = computed(() =>
  computeLayout({
    width: props.width,
    height: props.height,
    involved: props.involved,
    modified: props.modified,
    impacted: props.impacted,
    riskScores: props.riskScores
  })
)

// Simple zoom/pan: viewBox manipulation. Default 1× / center origin.
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })

const viewBox = computed(() => {
  const w = props.width / zoom.value
  const h = props.height / zoom.value
  const cx = props.width / 2 - w / 2 + offset.value.x
  const cy = props.height / 2 - h / 2 + offset.value.y
  return `${cx} ${cy} ${w} ${h}`
})

function onWheel(e: WheelEvent): void {
  e.preventDefault()
  const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12
  zoom.value = Math.max(0.4, Math.min(4, zoom.value * factor))
}

function onMouseDown(e: MouseEvent): void {
  dragging.value = true
  dragStart.value = { x: e.clientX, y: e.clientY }
}

function onMouseMove(e: MouseEvent): void {
  if (!dragging.value) return
  const dx = (e.clientX - dragStart.value.x) / zoom.value
  const dy = (e.clientY - dragStart.value.y) / zoom.value
  offset.value = { x: offset.value.x - dx, y: offset.value.y - dy }
  dragStart.value = { x: e.clientX, y: e.clientY }
}

function onMouseUp(): void {
  dragging.value = false
}

function resetView(): void {
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
}

function handleClick(file: string): void {
  emit('nodeClick', file)
}

// Exposed for tests — happy-dom SVG <circle> click events do not bubble
// reliably through @vue/test-utils, so the previous test suite asserts
// directly against this handler.
defineExpose({ handleClick, resetView, zoom })

const LEGEND: { key: RingKey; label: string; desc: string }[] = [
  { key: 'involved', label: '涉及', desc: '直接命中' },
  { key: 'modified', label: '修改', desc: '需要改动' },
  { key: 'impacted', label: '影响', desc: '波及范围' }
]
</script>

<template>
  <div class="three-ring-graph-wrap">
    <svg
      class="three-ring-graph"
      :width="props.width"
      :height="props.height"
      :viewBox="viewBox"
      role="img"
      aria-label="三层影响环"
      @wheel="onWheel"
      @mousedown="onMouseDown"
      @mousemove="onMouseMove"
      @mouseup="onMouseUp"
      @mouseleave="onMouseUp"
    >
      <!-- Ring fill halos for visual depth -->
      <circle
        v-for="ring in layout.rings"
        :key="`halo-${ring.key}`"
        :cx="layout.center.x"
        :cy="layout.center.y"
        :r="layout.radii[ring.key]"
        :fill="ring.color"
        opacity="0.04"
      />

      <!-- Ring outlines (3) -->
      <circle
        v-for="ring in layout.rings"
        :key="`ring-${ring.key}`"
        class="ring-outline"
        :cx="layout.center.x"
        :cy="layout.center.y"
        :r="layout.radii[ring.key]"
        :stroke="ring.color"
        fill="none"
        stroke-width="2"
        stroke-dasharray="6 6"
      />

      <!-- Ring labels -->
      <text
        v-for="ring in layout.rings"
        :key="`label-${ring.key}`"
        class="ring-label"
        :x="layout.center.x"
        :y="layout.center.y - layout.radii[ring.key] - 8"
        text-anchor="middle"
        :fill="ring.color"
        font-size="12"
        font-weight="600"
      >
        {{ ring.label }}
      </text>

      <!-- Inter-ring propagation edges -->
      <line
        v-for="(edge, i) in layout.edges"
        :key="`edge-${i}`"
        class="ring-edge"
        :x1="edge.x1"
        :y1="edge.y1"
        :x2="edge.x2"
        :y2="edge.y2"
        stroke="#909399"
        stroke-width="1"
        opacity="0.35"
        stroke-dasharray="2 4"
      />

      <!-- Nodes -->
      <g
        v-for="node in layout.nodes"
        :key="`${node.ring}-${node.file}`"
        class="ring-node-group"
        @click="handleClick(node.file)"
      >
        <circle
          class="ring-node"
          :cx="node.cx"
          :cy="node.cy"
          :r="node.radius"
          :fill="node.color"
          :data-file="node.file"
          :data-ring="node.ring"
          :data-risk="node.risk"
          stroke="#FFFFFF"
          stroke-width="1.5"
          @click="handleClick(node.file)"
        >
          <title>{{ node.file }}{{ node.risk ? ` · risk ${node.risk.toFixed(2)}` : '' }}</title>
        </circle>
        <text
          class="ring-node-label"
          :x="node.cx"
          :y="node.cy + node.radius + 12"
          text-anchor="middle"
          font-size="10"
          fill="#555"
        >
          {{ truncate(node.file) }}
        </text>
      </g>
    </svg>

    <!-- Legend + controls overlay -->
    <div class="legend">
      <div v-for="item in LEGEND" :key="item.key" class="legend-row">
        <span class="legend-dot" :style="{ background: RING_COLORS[item.key] }" />
        <span class="legend-label">{{ item.label }}</span>
        <span class="legend-desc">{{ item.desc }}</span>
      </div>
      <button class="reset-btn" type="button" @click="resetView">复位</button>
    </div>
  </div>
</template>

<style scoped>
.three-ring-graph-wrap {
  position: relative;
  display: inline-block;
  background:
    radial-gradient(circle at 50% 50%, rgba(64, 158, 255, 0.05) 0%, transparent 60%),
    #fafbfc;
  border-radius: 12px;
}
.three-ring-graph {
  display: block;
  user-select: none;
  cursor: grab;
}
.three-ring-graph:active {
  cursor: grabbing;
}
.ring-node-group {
  cursor: pointer;
  transition: filter 120ms ease;
}
.ring-node-group:hover .ring-node {
  filter: drop-shadow(0 0 6px rgba(64, 158, 255, 0.6));
  stroke: #409EFF;
  stroke-width: 2;
}
.ring-node-label {
  pointer-events: none;
}

.legend {
  position: absolute;
  top: 12px;
  right: 12px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 11px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  backdrop-filter: blur(4px);
}
.legend-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.legend-label {
  font-weight: 600;
  color: #303133;
}
.legend-desc {
  color: #909399;
}
.reset-btn {
  margin-top: 4px;
  font-size: 11px;
  border: 1px solid #DCDFE6;
  background: #FFFFFF;
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
  color: #606266;
}
.reset-btn:hover {
  border-color: #409EFF;
  color: #409EFF;
}
</style>
