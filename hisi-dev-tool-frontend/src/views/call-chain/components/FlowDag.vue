<template>
  <div class="flow-dag-container">
    <svg
      ref="svgRef"
      :viewBox="`${vbX} ${vbY} ${vbW} ${vbH}`"
      :class="{ grabbing: isPanning }"
      @mousedown.self="onPanStart"
      @mousemove="onPanMove"
      @mouseup="onPanEnd"
      @mouseleave="onPanEnd"
      @wheel.prevent="onZoom"
    >
      <defs>
        <marker
          id="flow-arrow"
          viewBox="0 0 10 6"
          refX="10"
          refY="3"
          markerWidth="10"
          markerHeight="6"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,3 L0,6 Z" fill="#909399" />
        </marker>
        <marker
          v-for="(color, bt) in BRIDGE_COLORS"
          :id="`flow-arrow-${String(bt).toLowerCase()}`"
          :key="bt"
          viewBox="0 0 10 6"
          refX="10"
          refY="3"
          markerWidth="10"
          markerHeight="6"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,3 L0,6 Z" :fill="color" />
        </marker>
      </defs>

      <g class="edges-layer">
        <path
          v-for="(edge, idx) in layout.edges"
          :key="`e-${idx}`"
          :d="buildEdgePath(edge.points)"
          :stroke="edgeColor(edge)"
          :stroke-dasharray="edge.isCycleEdge ? '6 3' : undefined"
          stroke-width="1.5"
          fill="none"
          :marker-end="edgeMarker(edge)"
        />
      </g>

      <g class="nodes-layer">
        <foreignObject
          v-for="node in layout.nodes"
          :key="node.id"
          :x="node.x - node.width / 2"
          :y="node.y - node.height / 2"
          :width="node.width"
          :height="node.height"
        >
          <div
            xmlns="http://www.w3.org/1999/xhtml"
            :class="['flow-node-card', cardClasses(node)]"
            :style="cardStyle(node)"
            :title="couplingTooltip(node)"
            @click.stop="$emit('node-click', node)"
            @contextmenu.prevent.stop="$emit('contextmenu', node, $event)"
          >
            <div class="node-header">
              <span class="node-name">{{ node.name }}</span>
              <span
                v-if="node.bridgeType"
                class="bridge-tag"
                :style="{ backgroundColor: BRIDGE_COLORS[node.bridgeType] }"
              >
                {{ node.bridgeType }}
              </span>
            </div>
            <div class="node-class">{{ shortClass(node.className) }}</div>
            <div v-if="node.description" class="node-desc">{{ node.description }}</div>
            <span v-if="node.sources.size > 1" class="coupling-badge">
              {{ node.sources.size }}
            </span>
          </div>
        </foreignObject>
      </g>
    </svg>

    <div v-if="entryColors && entryColors.size > 0" class="legend-panel">
      <div v-for="[entryId, color] in entryColors" :key="entryId" class="legend-item">
        <span class="legend-dot" :style="{ backgroundColor: color }" />
        <span class="legend-label">{{ entryLabels?.get(entryId) ?? entryId }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { GraphNode, GraphEdge } from '@/api/knowledgeGraph'
import {
  computeFlowLayout,
  buildEdgePath,
  getNodeColor,
  BRIDGE_COLORS,
  type FlowNode,
  type FlowEdge,
} from './flowDagLayout'

interface Props {
  nodes: GraphNode[]
  edges: GraphEdge[]
  direction?: 'TB' | 'BT'
  entrySources?: Map<string, Set<string>>
  entryColors?: Map<string, string>
  entryLabels?: Map<string, string>
}

const props = withDefaults(defineProps<Props>(), {
  direction: 'TB',
  entrySources: undefined,
  entryColors: undefined,
  entryLabels: undefined,
})

defineEmits<{
  'node-click': [node: FlowNode]
  'contextmenu': [node: FlowNode, event: MouseEvent]
}>()

const svgRef = ref<SVGSVGElement | null>(null)

const PADDING = 30
const MIN_SCALE = 0.2
const MAX_SCALE = 5

const vbX = ref(0)
const vbY = ref(0)
const vbW = ref(800)
const vbH = ref(600)
const scale = ref(1)
const isPanning = ref(false)
const panOrigin = ref({ x: 0, y: 0 })

const layout = computed(() =>
  computeFlowLayout(props.nodes, props.edges, props.direction, props.entrySources),
)

watch(layout, (l) => {
  vbX.value = -PADDING
  vbY.value = -PADDING
  vbW.value = l.width + PADDING * 2
  vbH.value = l.height + PADDING * 2
  scale.value = 1
})

function shortClass(cls: string): string {
  const parts = cls.split('.')
  if (parts.length > 2) {
    return '...' + parts.slice(-2).join('.')
  }
  return cls
}

function cardClasses(node: FlowNode): Record<string, boolean> {
  return {
    'is-entry': node.isEntry,
    'is-coupling': node.sources.size > 1,
    'is-cycle': node.inCycle,
  }
}

function cardStyle(node: FlowNode): Record<string, string> {
  if (node.sources.size > 1 && props.entryColors) {
    const colors: string[] = []
    for (const src of node.sources) {
      const c = props.entryColors.get(src)
      if (c) colors.push(c)
    }
    if (colors.length >= 2) {
      const stops = colors.map((c, i) => `${c} ${(i / (colors.length - 1)) * 100}%`).join(', ')
      return {
        borderImage: `linear-gradient(180deg, ${stops}) 1`,
        borderWidth: '2px',
        borderStyle: 'solid',
      }
    }
  }
  return {
    borderLeft: `3px solid ${getNodeColor(node)}`,
  }
}

function edgeColor(edge: FlowEdge): string {
  return (BRIDGE_COLORS as Record<string, string>)[edge.callType] ?? '#909399'
}

function edgeMarker(edge: FlowEdge): string {
  const key = edge.callType.toLowerCase()
  if ((BRIDGE_COLORS as Record<string, string>)[edge.callType]) {
    return `url(#flow-arrow-${key})`
  }
  return 'url(#flow-arrow)'
}

function couplingTooltip(node: FlowNode): string {
  if (node.sources.size <= 1) return ''
  const labels: string[] = []
  for (const src of node.sources) {
    labels.push(props.entryLabels?.get(src) ?? src)
  }
  return `Shared by: ${labels.join(', ')}`
}

function onPanStart(e: MouseEvent) {
  isPanning.value = true
  panOrigin.value = { x: e.clientX, y: e.clientY }
}

function onPanMove(e: MouseEvent) {
  if (!isPanning.value) return
  const dx = e.clientX - panOrigin.value.x
  const dy = e.clientY - panOrigin.value.y
  const svg = svgRef.value
  if (!svg) return
  const rect = svg.getBoundingClientRect()
  const ratioX = vbW.value / rect.width
  const ratioY = vbH.value / rect.height
  vbX.value -= dx * ratioX
  vbY.value -= dy * ratioY
  panOrigin.value = { x: e.clientX, y: e.clientY }
}

function onPanEnd() {
  isPanning.value = false
}

function onZoom(e: WheelEvent) {
  const svg = svgRef.value
  if (!svg) return
  const rect = svg.getBoundingClientRect()
  const mouseX = e.clientX - rect.left
  const mouseY = e.clientY - rect.top
  const svgX = vbX.value + (mouseX / rect.width) * vbW.value
  const svgY = vbY.value + (mouseY / rect.height) * vbH.value

  const factor = e.deltaY > 0 ? 1.1 : 0.9
  const newScale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale.value * factor))
  const ratio = newScale / scale.value
  scale.value = newScale

  vbW.value *= ratio
  vbH.value *= ratio
  vbX.value = svgX - (mouseX / rect.width) * vbW.value
  vbY.value = svgY - (mouseY / rect.height) * vbH.value
}
</script>

<style scoped>
.flow-dag-container {
  position: relative;
  width: 100%;
  height: 100%;
  background: #fafbfc;
  border-radius: 6px;
  overflow: hidden;
}

svg {
  width: 100%;
  height: 100%;
  cursor: grab;
}

svg.grabbing {
  cursor: grabbing;
}

.flow-node-card {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  padding: 6px 10px;
  background: #fff;
  border-radius: 4px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  font-size: 12px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.flow-node-card.is-entry {
  border: 2px solid #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.15), 0 1px 3px rgba(0, 0, 0, 0.08);
}

.flow-node-card.is-cycle {
  background: #fffbe6;
}

.node-header {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 18px;
}

.node-name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
  color: #303133;
}

.bridge-tag {
  flex-shrink: 0;
  padding: 0 5px;
  border-radius: 8px;
  font-size: 10px;
  line-height: 16px;
  color: #fff;
  white-space: nowrap;
}

.node-class {
  color: #909399;
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 2px;
}

.node-desc {
  color: #909399;
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 2px;
}

.coupling-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #f56c6c;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
}

.legend-panel {
  position: absolute;
  top: 10px;
  left: 10px;
  background: rgba(255, 255, 255, 0.92);
  border-radius: 4px;
  padding: 6px 10px;
  font-size: 11px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-label {
  color: #606266;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}
</style>
