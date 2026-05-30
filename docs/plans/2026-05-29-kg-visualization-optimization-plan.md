# Knowledge Graph Visualization Optimization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the ECharts force-directed DAG view with a dagre hierarchical flowchart, add upstream call chain visualization, and implement multi-entry merged graph with coupling point markers.

**Architecture:** Hand-drawn SVG with `@dagrejs/dagre` for layout computation. Node cards rendered via `<foreignObject>` for full HTML/CSS styling. Reuses the dagre pattern from RAM module (`dagLayout.ts`). No backend API changes needed.

**Tech Stack:** Vue 3 (Composition API), TypeScript, @dagrejs/dagre (already installed), SVG, Element Plus

---

### Task 1: Create `flowDagLayout.ts` — dagre layout computation for call chains

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/components/flowDagLayout.ts`

**Step 1: Write the layout module**

This is a pure function module — no Vue component, no DOM access. It takes call chain graph data (nodes + edges) and returns positioned layout data.

```typescript
import dagre from '@dagrejs/dagre'
import type { GraphNode, GraphEdge, BridgeType } from '@/api/knowledgeGraph'

export interface FlowNode {
  readonly id: string
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
  readonly name: string
  readonly className: string
  readonly description?: string
  readonly bridgeType?: BridgeType
  readonly callType?: string
  readonly depth: number
  readonly isEntry: boolean
  readonly inCycle: boolean
  readonly sources: ReadonlySet<string>
}

export interface FlowEdge {
  readonly from: string
  readonly to: string
  readonly callType: string
  readonly isCycleEdge: boolean
  readonly points: readonly { x: number; y: number }[]
}

export interface FlowLayout {
  readonly nodes: readonly FlowNode[]
  readonly edges: readonly FlowEdge[]
  readonly width: number
  readonly height: number
}

const NODE_W = 220
const NODE_H_BASE = 56
const NODE_H_WITH_DESC = 80

export const BRIDGE_COLORS: Record<string, string> = {
  MAPPER: '#67c23a',
  JPA: '#409eff',
  MQ: '#e6a23c',
  FEIGN: '#f56c6c',
  HTTP: '#f5d44d',
  ASPECT: '#b37feb',
  DIRECT: '#909399'
}

export function computeFlowLayout(
  nodes: readonly GraphNode[],
  edges: readonly GraphEdge[],
  direction: 'TB' | 'BT' = 'TB',
  entrySources?: ReadonlyMap<string, ReadonlySet<string>>
): FlowLayout {
  const g = new dagre.graphlib.Graph({ directed: true })
  g.setGraph({ rankdir: direction, nodesep: 40, ranksep: 60, marginx: 30, marginy: 30 })
  g.setDefaultEdgeLabel(() => ({}))

  const entryDepth = direction === 'BT' ? Math.max(...nodes.map(n => n.depth), 0) : 0

  for (const n of nodes) {
    const h = n.description ? NODE_H_WITH_DESC : NODE_H_BASE
    g.setNode(n.id, { width: NODE_W, height: h })
  }
  for (const e of edges) {
    g.setEdge(e.source, e.target)
  }

  dagre.layout(g)

  const flowNodes: FlowNode[] = nodes.map(n => {
    const pos = g.node(n.id)
    const h = n.description ? NODE_H_WITH_DESC : NODE_H_BASE
    return {
      id: n.id,
      x: pos?.x ?? 0,
      y: pos?.y ?? 0,
      width: NODE_W,
      height: h,
      name: n.name,
      className: n.className,
      description: n.description,
      bridgeType: (n as any).bridgeType,
      callType: n.callType,
      depth: n.depth,
      isEntry: n.depth === entryDepth,
      inCycle: n.inCycle,
      sources: entrySources?.get(n.id) ?? new Set()
    }
  })

  const flowEdges: FlowEdge[] = edges.map(e => {
    const ge = g.edge(e.source, e.target)
    return {
      from: e.source,
      to: e.target,
      callType: e.callType,
      isCycleEdge: e.isCycleEdge,
      points: ge?.points ?? []
    }
  })

  const graphInfo = g.graph()
  return {
    nodes: flowNodes,
    edges: flowEdges,
    width: graphInfo.width ?? 800,
    height: graphInfo.height ?? 600
  }
}

export function buildEdgePath(points: readonly { x: number; y: number }[]): string {
  if (points.length === 0) return ''
  const [first, ...rest] = points
  let d = `M ${first.x} ${first.y}`
  if (rest.length === 1) {
    d += ` L ${rest[0].x} ${rest[0].y}`
  } else if (rest.length >= 2) {
    for (let i = 0; i < rest.length - 1; i++) {
      const cx = (rest[i].x + rest[i + 1].x) / 2
      const cy = (rest[i].y + rest[i + 1].y) / 2
      d += ` Q ${rest[i].x} ${rest[i].y} ${cx} ${cy}`
    }
    const last = rest[rest.length - 1]
    d += ` L ${last.x} ${last.y}`
  }
  return d
}

export function getNodeColor(node: FlowNode): string {
  if (node.bridgeType && BRIDGE_COLORS[node.bridgeType]) {
    return BRIDGE_COLORS[node.bridgeType]
  }
  if (node.inCycle) return '#f0a020'
  return '#409eff'
}
```

**Step 2: Verify it compiles**

Run: `cd hisi-dev-tool-frontend && npx tsc --noEmit --skipLibCheck src/views/call-chain/components/flowDagLayout.ts`
Expected: no errors (or only unrelated pre-existing errors)

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/call-chain/components/flowDagLayout.ts
git commit -m "feat(kg-viz): add flowDagLayout.ts — dagre layout computation for call chains"
```

---

### Task 2: Create `FlowDag.vue` — SVG flowchart component

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/components/FlowDag.vue`

**Step 1: Write the FlowDag component**

This is the core SVG rendering component. It takes `GraphNode[]` + `GraphEdge[]` (or pre-computed `FlowLayout`), computes layout via `computeFlowLayout`, and renders an interactive SVG with `<foreignObject>` HTML cards.

Key features:
- Pan: mousedown+mousemove on SVG background
- Zoom: wheel event adjusts viewBox
- Click node: emits `node-click`
- Right-click node: emits `contextmenu`
- Entry nodes: double-border style
- Coupling nodes (multi-source): gradient border + badge

```vue
<template>
  <div ref="containerRef" class="flow-dag-container" @contextmenu.prevent>
    <svg
      ref="svgRef"
      :viewBox="viewBox"
      class="flow-dag-svg"
      @mousedown="onPanStart"
      @mousemove="onPanMove"
      @mouseup="onPanEnd"
      @mouseleave="onPanEnd"
      @wheel.prevent="onZoom"
    >
      <defs>
        <marker id="flow-arrow" viewBox="0 0 10 6" refX="10" refY="3"
                markerWidth="10" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 3 L 0 6 z" fill="#909399" />
        </marker>
        <marker v-for="bt in bridgeTypes" :key="bt"
                :id="`flow-arrow-${bt.toLowerCase()}`"
                viewBox="0 0 10 6" refX="10" refY="3"
                markerWidth="10" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 0 L 10 3 L 0 6 z" :fill="bridgeColors[bt]" />
        </marker>
      </defs>

      <!-- Edges -->
      <g class="edges-layer">
        <path
          v-for="(edge, i) in layout.edges"
          :key="`e-${i}`"
          :d="buildEdgePath(edge.points)"
          :stroke="edgeColor(edge)"
          :stroke-width="edge.isCycleEdge ? 2.5 : 1.5"
          :stroke-dasharray="edge.isCycleEdge ? '6 3' : 'none'"
          fill="none"
          :marker-end="edgeMarker(edge)"
        />
      </g>

      <!-- Nodes -->
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
            class="flow-node-card"
            :class="cardClasses(node)"
            :style="cardStyle(node)"
            @click.stop="$emit('node-click', node)"
            @contextmenu.stop.prevent="$emit('contextmenu', node, $event)"
          >
            <div class="node-header">
              <span class="node-name" :title="node.name">{{ node.name }}</span>
              <span v-if="node.bridgeType" class="node-bridge-tag"
                    :style="{ background: bridgeColors[node.bridgeType] || '#909399' }">
                {{ node.bridgeType }}
              </span>
            </div>
            <div class="node-class" :title="node.className">{{ shortClass(node.className) }}</div>
            <div v-if="node.description" class="node-desc" :title="node.description">
              {{ node.description }}
            </div>
            <!-- Multi-source coupling badge -->
            <div v-if="node.sources.size > 1" class="coupling-badge"
                 :title="couplingTooltip(node)">
              {{ node.sources.size }}
            </div>
          </div>
        </foreignObject>
      </g>
    </svg>

    <!-- Legend for multi-entry mode -->
    <div v-if="entryColors && entryColors.size > 0" class="flow-legend">
      <div v-for="[entryId, color] in entryColors" :key="entryId" class="legend-item">
        <span class="legend-dot" :style="{ background: color }"></span>
        <span class="legend-label">{{ entryLabels?.get(entryId) || entryId }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import type { GraphNode, GraphEdge } from '@/api/knowledgeGraph'
import {
  computeFlowLayout, buildEdgePath, getNodeColor, BRIDGE_COLORS,
  type FlowLayout, type FlowNode, type FlowEdge
} from './flowDagLayout'

const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
  direction?: 'TB' | 'BT'
  entrySources?: Map<string, Set<string>>
  entryColors?: Map<string, string>
  entryLabels?: Map<string, string>
}>()

const emit = defineEmits<{
  (e: 'node-click', node: FlowNode): void
  (e: 'contextmenu', node: FlowNode, event: MouseEvent): void
}>()

const containerRef = ref<HTMLElement | null>(null)
const svgRef = ref<SVGSVGElement | null>(null)
const bridgeTypes = ['MAPPER', 'JPA', 'MQ', 'FEIGN', 'HTTP', 'ASPECT'] as const
const bridgeColors = BRIDGE_COLORS

// Layout computation
const layout = computed<FlowLayout>(() => {
  if (!props.nodes.length) return { nodes: [], edges: [], width: 800, height: 600 }
  return computeFlowLayout(
    props.nodes,
    props.edges,
    props.direction ?? 'TB',
    props.entrySources
  )
})

// Viewbox state for pan/zoom
const vbX = ref(0)
const vbY = ref(0)
const vbW = ref(800)
const vbH = ref(600)
const scale = ref(1)

const viewBox = computed(() => `${vbX.value} ${vbY.value} ${vbW.value} ${vbH.value}`)

watch(() => layout.value, (l) => {
  vbX.value = -30
  vbY.value = -30
  vbW.value = l.width + 60
  vbH.value = l.height + 60
  scale.value = 1
}, { immediate: true })

// Pan
let panning = false
let panStartX = 0
let panStartY = 0
let panStartVbX = 0
let panStartVbY = 0

const onPanStart = (e: MouseEvent) => {
  if (e.button !== 0) return
  panning = true
  panStartX = e.clientX
  panStartY = e.clientY
  panStartVbX = vbX.value
  panStartVbY = vbY.value
}

const onPanMove = (e: MouseEvent) => {
  if (!panning || !svgRef.value) return
  const rect = svgRef.value.getBoundingClientRect()
  const dx = (e.clientX - panStartX) * (vbW.value / rect.width)
  const dy = (e.clientY - panStartY) * (vbH.value / rect.height)
  vbX.value = panStartVbX - dx
  vbY.value = panStartVbY - dy
}

const onPanEnd = () => { panning = false }

// Zoom
const onZoom = (e: WheelEvent) => {
  const factor = e.deltaY > 0 ? 1.1 : 0.9
  const newScale = Math.max(0.2, Math.min(5, scale.value * factor))
  if (newScale === scale.value) return

  const rect = svgRef.value?.getBoundingClientRect()
  if (!rect) return
  const mx = vbX.value + (e.clientX - rect.left) / rect.width * vbW.value
  const my = vbY.value + (e.clientY - rect.top) / rect.height * vbH.value

  const ratio = newScale / scale.value
  vbW.value *= ratio
  vbH.value *= ratio
  vbX.value = mx - (mx - vbX.value) * ratio
  vbY.value = my - (my - vbY.value) * ratio
  scale.value = newScale
}

// Helpers
const shortClass = (cls: string): string => {
  if (!cls) return ''
  const parts = cls.split('.')
  return parts.length > 2 ? '...' + parts.slice(-2).join('.') : cls
}

const ENTRY_PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#9b59b6', '#1abc9c', '#e74c3c', '#3498db']

const cardClasses = (node: FlowNode) => ({
  'is-entry': node.isEntry,
  'is-coupling': node.sources.size > 1,
  'is-cycle': node.inCycle
})

const cardStyle = (node: FlowNode) => {
  if (node.sources.size > 1 && props.entryColors) {
    const colors = [...node.sources]
      .map(s => props.entryColors!.get(s))
      .filter(Boolean) as string[]
    if (colors.length >= 2) {
      return { borderImage: `linear-gradient(135deg, ${colors.join(', ')}) 1`, borderWidth: '3px', borderStyle: 'solid' }
    }
  }
  const color = getNodeColor(node)
  return { borderLeft: `4px solid ${color}` }
}

const edgeColor = (edge: FlowEdge): string => {
  if (edge.callType && BRIDGE_COLORS[edge.callType]) return BRIDGE_COLORS[edge.callType]
  return '#909399'
}

const edgeMarker = (edge: FlowEdge): string => {
  if (edge.callType && BRIDGE_COLORS[edge.callType]) return `url(#flow-arrow-${edge.callType.toLowerCase()})`
  return 'url(#flow-arrow)'
}

const couplingTooltip = (node: FlowNode): string => {
  const labels = [...node.sources].map(s => props.entryLabels?.get(s) || s)
  return `共享节点: ${labels.join(', ')}`
}
</script>

<style scoped>
.flow-dag-container {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 400px;
  background: #fafbfc;
  border-radius: 8px;
  overflow: hidden;
}

.flow-dag-svg {
  width: 100%;
  height: 100%;
  cursor: grab;
}
.flow-dag-svg:active { cursor: grabbing; }

.flow-node-card {
  box-sizing: border-box;
  padding: 8px 12px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #dcdfe6;
  cursor: pointer;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  font-size: 12px;
  position: relative;
  transition: box-shadow 0.2s;
  overflow: hidden;
}
.flow-node-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.12); }

.flow-node-card.is-entry {
  border: 2px solid #409eff;
  box-shadow: 0 0 0 2px rgba(64,158,255,0.2);
}
.flow-node-card.is-cycle {
  border-color: #f0a020;
  background: #fffbe6;
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 4px;
}

.node-name {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.node-bridge-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 3px;
  color: #fff;
  font-size: 10px;
  font-weight: 500;
}

.node-class {
  color: #909399;
  font-size: 11px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-desc {
  color: #606266;
  font-size: 11px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.coupling-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #e6a23c;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid #fff;
}

.flow-legend {
  position: absolute;
  top: 12px;
  left: 12px;
  background: rgba(255,255,255,0.95);
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  z-index: 10;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-label {
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}
</style>
```

**Step 2: Verify it compiles**

Run: `cd hisi-dev-tool-frontend && npx vue-tsc --noEmit 2>&1 | head -20`
Expected: no new errors related to FlowDag.vue

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/call-chain/components/FlowDag.vue
git commit -m "feat(kg-viz): add FlowDag.vue — SVG flowchart component with dagre layout"
```

---

### Task 3: Create `mergeGraphs.ts` — multi-entry graph merge + coupling detection

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/components/mergeGraphs.ts`

**Step 1: Write the merge utility**

```typescript
import type { GraphNode, GraphEdge, CallChainGraphData } from '@/api/knowledgeGraph'

export interface MergedGraph {
  readonly nodes: GraphNode[]
  readonly edges: GraphEdge[]
  readonly entrySources: Map<string, Set<string>>
  readonly entryLabels: Map<string, string>
  readonly entryColors: Map<string, string>
}

const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#9b59b6', '#1abc9c', '#e74c3c', '#3498db']

export function mergeCallChainGraphs(
  graphs: readonly { entryFqn: string; data: CallChainGraphData }[]
): MergedGraph {
  const nodeMap = new Map<string, GraphNode>()
  const edgeSet = new Set<string>()
  const mergedEdges: GraphEdge[] = []
  const entrySources = new Map<string, Set<string>>()
  const entryLabels = new Map<string, string>()
  const entryColors = new Map<string, string>()

  for (let i = 0; i < graphs.length; i++) {
    const { entryFqn, data } = graphs[i]
    const entryId = data.entryId || entryFqn
    entryLabels.set(entryId, entryFqn)
    entryColors.set(entryId, PALETTE[i % PALETTE.length])

    for (const n of data.nodes) {
      if (!nodeMap.has(n.id)) nodeMap.set(n.id, n)
      if (!entrySources.has(n.id)) entrySources.set(n.id, new Set())
      entrySources.get(n.id)!.add(entryId)
    }

    for (const e of data.edges) {
      const key = `${e.source}|${e.target}`
      if (!edgeSet.has(key)) {
        edgeSet.add(key)
        mergedEdges.push(e)
      }
    }
  }

  return {
    nodes: [...nodeMap.values()],
    edges: mergedEdges,
    entrySources,
    entryLabels,
    entryColors
  }
}
```

**Step 2: Verify it compiles**

Run: `cd hisi-dev-tool-frontend && npx tsc --noEmit --skipLibCheck src/views/call-chain/components/mergeGraphs.ts`
Expected: no errors

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/call-chain/components/mergeGraphs.ts
git commit -m "feat(kg-viz): add mergeGraphs.ts — multi-entry graph merge with coupling detection"
```

---

### Task 4: Replace DAG view in `ChainChart.vue` with `FlowDag`

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/call-chain/components/ChainChart.vue`

**Step 1: Update template — replace DAG `<div>` with `<FlowDag>`**

Find the DAG view section in the template (inside `<div class="content-area">`). The current code has a `<div ref="dagChartRef" class="dag-chart">` for ECharts. Replace it with the FlowDag component.

In template, after the list view section (before `</div> <!-- content-area -->`), find the `v-else-if="viewMode === 'dag'"` block. It currently renders an ECharts div. Replace it:

**Old** (approximately lines 175-199 area — look for `viewMode === 'dag'`):
```html
      <!-- DAG 图视图 -->
      <div v-else-if="viewMode === 'dag'" class="dag-view">
        <div ref="dagChartRef" class="dag-chart" />
      </div>
```

**New**:
```html
      <!-- DAG 图视图 — 层级流程图 -->
      <div v-else-if="viewMode === 'dag'" class="dag-view">
        <FlowDag
          :nodes="dagGraphNodes"
          :edges="dagGraphEdges"
          direction="TB"
          @node-click="handleFlowNodeClick"
          @contextmenu="handleFlowContextMenu"
        />
      </div>
```

**Step 2: Update script — add imports, computed, and event handlers**

Add import at top of `<script setup>`:
```typescript
import FlowDag from './FlowDag.vue'
import type { GraphNode as ApiGraphNode, GraphEdge as ApiGraphEdge } from '@/api/knowledgeGraph'
import type { FlowNode } from './flowDagLayout'
```

Add computed properties that extract graph nodes/edges from the tree data for the FlowDag component. Place these after the existing `filteredNodes` computed:

```typescript
// DAG 图 — 从扁平节点重建 GraphNode[] + GraphEdge[]
const dagGraphNodes = computed<ApiGraphNode[]>(() => {
  return filteredNodes.value.map(n => ({
    id: n.id,
    name: n.name,
    className: n.className || '',
    depth: n.depth,
    inCycle: n.isNoMatch || false,
    callType: n.bridgeType || n.callType || '',
    description: n.description,
    bridgeType: n.bridgeType
  } as ApiGraphNode & { bridgeType?: string }))
})

const dagGraphEdges = computed<ApiGraphEdge[]>(() => {
  const edges: ApiGraphEdge[] = []
  const flatList = filteredNodes.value
  const nodeSet = new Set(flatList.map(n => n.id))

  // Rebuild edges from tree children
  const walk = (node: ChainNode) => {
    if (!node.children) return
    for (const child of node.children) {
      const parentId = node.id || node.name
      const childId = child.id || child.name
      if (nodeSet.has(parentId) && nodeSet.has(childId)) {
        edges.push({
          source: parentId,
          target: childId,
          callType: child.bridgeType || child.callType || '',
          callLine: 0,
          isCycleEdge: child.isNoMatch || false
        })
      }
      walk(child)
    }
  }
  if (props.data) walk(props.data)
  return edges
})
```

Add event handlers:
```typescript
const handleFlowNodeClick = (node: FlowNode) => {
  const chainNode = flatNodes.value.find(n => n.id === node.id)
  if (chainNode) handleSelect(chainNode)
}

const handleFlowContextMenu = (node: FlowNode, event: MouseEvent) => {
  const chainNode = flatNodes.value.find(n => n.id === node.id)
  if (chainNode) handleContextMenu(chainNode, event)
}
```

**Step 3: Remove ECharts DAG code**

Remove:
- The `dagChartRef` ref
- The `dagChart` variable (echarts instance)
- The `dagData` ref
- The `initDagChart` function (lines ~894-1041)
- The watcher for `viewMode === 'dag'` that calls `initDagChart` (lines ~1044-1049)
- The watcher on `[flatNodes, filteredNodes]` that calls `initDagChart` (lines ~1052-1056)
- The `echarts.dispose()` in `onUnmounted` (lines ~887-890)
- The `import * as echarts from 'echarts'` (line 377)

Keep the `highlightCycles` ref and `cycleCount` ref since they're used in the toolbar template.

**Step 4: Verify it compiles and the dev server loads**

Run: `cd hisi-dev-tool-frontend && npx vue-tsc --noEmit 2>&1 | head -30`
Then start dev server: `npm run dev`
Expected: no build errors, DAG view tab renders the new FlowDag component

**Step 5: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/call-chain/components/ChainChart.vue
git commit -m "feat(kg-viz): replace ECharts DAG view with FlowDag hierarchical flowchart"
```

---

### Task 5: Add upstream visualization to `MethodReferenceGraph.vue`

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/call-chain/MethodReferenceGraph.vue`

**Step 1: Update template — add FlowDag for upstream results**

Replace the upstream section. Currently it shows a flat `el-table` (lines 67-86). Change it to show both a FlowDag and the table as a fallback:

**Old**:
```html
      <!-- 向上查询：展示调用者方法 -->
      <div v-if="analysisDirection === 'upstream' && upstreamCallers.length > 0" class="uri-list-section">
        ...el-table...
      </div>
```

**New**:
```html
      <!-- 向上查询：展示调用链流程图 -->
      <div v-if="analysisDirection === 'upstream' && upstreamGraphNodes.length > 0" class="upstream-section">
        <div class="section-header">
          <el-icon><Link /></el-icon>
          <span>上游调用链 ({{ upstreamGraphNodes.length }} 节点)</span>
          <el-radio-group v-model="upstreamViewMode" size="small" style="margin-left: auto;">
            <el-radio-button label="flow">流程图</el-radio-button>
            <el-radio-button label="table">表格</el-radio-button>
          </el-radio-group>
        </div>

        <div v-if="upstreamViewMode === 'flow'" class="upstream-flow-container">
          <FlowDag
            :nodes="upstreamGraphNodes"
            :edges="upstreamGraphEdges"
            direction="BT"
            @node-click="handleUpstreamNodeClick"
          />
        </div>

        <el-table v-else :data="upstreamCallers" stripe style="width: 100%">
          <el-table-column prop="display" label="调用者方法" min-width="400" />
          <el-table-column prop="callType" label="调用类型" width="120" />
          <el-table-column prop="callLine" label="行号" width="80" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button size="small" @click="drillUpFromCaller(row)">继续向上</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 向上查询：无结果提示 -->
      <el-empty v-if="analysisDirection === 'upstream' && upstreamGraphNodes.length === 0 && !loading && hasQueried"
        description="未找到调用者" />
```

**Step 2: Update script — add imports and upstream graph logic**

Add imports:
```typescript
import FlowDag from './components/FlowDag.vue'
import type { GraphNode as ApiGraphNode, GraphEdge as ApiGraphEdge, CallChainGraphData } from '@/api/knowledgeGraph'
import type { FlowNode } from './components/flowDagLayout'
```

Add state:
```typescript
const upstreamViewMode = ref<'flow' | 'table'>('flow')
const upstreamGraphNodes = ref<ApiGraphNode[]>([])
const upstreamGraphEdges = ref<ApiGraphEdge[]>([])
```

Modify the upstream query in `loadDependencyGraph` to also call `getCallChainsAffecting` for graph data. **Important**: We must check which API is best. The `getRootEntries` already returns both rootEntries and directCallers. For a full graph, we use the `affecting` endpoint which returns `CallChainView[]` — but that's depth-bucketed, not nodes+edges.

Actually, looking at the backend, `buildUpstreamGraph` exists but no controller exposes it as a separate endpoint returning `CallChainGraphData`. The simplest approach is: keep using `getRootEntries` for the table data AND build graph nodes/edges from the response by treating `rootEntries` as top-level nodes and `directCallers` as intermediate nodes:

```typescript
    if (analysisDirection.value === 'upstream') {
      const allNodes: ApiGraphNode[] = []
      const allEdges: ApiGraphEdge[] = []
      const callers: UpstreamCaller[] = []
      let nodeIdCounter = 0

      for (const method of entryMethods.value) {
        const { className, methodName } = splitFqn(method)
        const resp = await knowledgeGraphApi.getRootEntries(className, methodName, projectPath, projectPaths) as unknown as { rootEntries: any[]; directCallers: any[] }
        const targetId = `target_${nodeIdCounter++}`

        // Target node (the method we're querying)
        allNodes.push({
          id: targetId, name: methodName, className, depth: 0,
          inCycle: false, callType: ''
        })

        for (const item of (resp?.directCallers || [])) {
          const callerId = item.callerId || `caller_${nodeIdCounter++}`
          callers.push({
            callerId, callerClassName: item.callerClassName || '',
            callerMethodName: item.callerMethodName || '',
            callType: item.callType || '', callLine: item.callLine || 0,
            display: `${item.callerClassName || ''}.${item.callerMethodName || ''}`
          })
          allNodes.push({
            id: callerId, name: item.callerMethodName || '', className: item.callerClassName || '',
            depth: 1, inCycle: false, callType: item.callType || ''
          })
          allEdges.push({
            source: callerId, target: targetId, callType: item.callType || '',
            callLine: item.callLine || 0, isCycleEdge: false
          })
        }

        for (const r of (resp?.rootEntries || [])) {
          const entryId = r.entryId || `entry_${nodeIdCounter++}`
          if (!allNodes.find(n => n.id === entryId)) {
            allNodes.push({
              id: entryId, name: r.entryKey || '', className: r.entryType || '',
              depth: 2, inCycle: false, callType: r.entryType || '',
              description: `入口: ${r.entryType}`
            })
          }
        }
      }

      upstreamCallers.value = callers
      upstreamGraphNodes.value = allNodes
      upstreamGraphEdges.value = allEdges

      if (callers.length > 0) {
        ElMessage.success(`找到 ${callers.length} 个调用者`)
      } else {
        ElMessage.info('未找到调用者')
      }
    }
```

Add event handler:
```typescript
const handleUpstreamNodeClick = (node: FlowNode) => {
  if (node.className && node.name) {
    const fqn = `${node.className}.${node.name}`
    if (!entryMethods.value.includes(fqn)) {
      entryMethods.value.push(fqn)
    }
    loadDependencyGraph()
  }
}
```

**Step 3: Add CSS for upstream section**

```css
.upstream-section { margin-top: 16px; }
.upstream-flow-container { height: 500px; border: 1px solid #ebeef5; border-radius: 8px; }
```

**Step 4: Verify it compiles**

Run: `cd hisi-dev-tool-frontend && npx vue-tsc --noEmit 2>&1 | head -30`
Expected: no new errors

**Step 5: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/call-chain/MethodReferenceGraph.vue
git commit -m "feat(kg-viz): add upstream call chain FlowDag visualization"
```

---

### Task 6: Add multi-entry merge + coupling markers to `MethodReferenceGraph.vue`

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/call-chain/MethodReferenceGraph.vue`

**Step 1: Update downstream query to use merge logic**

Add import:
```typescript
import { mergeCallChainGraphs, type MergedGraph } from './components/mergeGraphs'
```

Add state:
```typescript
const mergedGraph = ref<MergedGraph | null>(null)
```

Modify the downstream section of `loadDependencyGraph`. When there are **multiple** entry methods, use `mergeCallChainGraphs` and render the merged FlowDag instead of the tree. For a single entry, keep the existing tree-based ChainChart behavior.

In the `else` (downstream) branch, change the approach:

```typescript
    } else {
      // 向下：调用 callees-tree 获取完整子树
      const graphResults: { entryFqn: string; data: CallChainGraphData }[] = []
      const rootChildren: ChainNode[] = []

      for (const method of entryMethods.value) {
        const { className, methodName } = splitFqn(method)
        try {
          const graph = await knowledgeGraphApi.getCalleesTree(className, methodName, projectPath, maxDepth.value, projectPaths) as unknown as CallChainGraphData
          if (graph && Array.isArray(graph.nodes) && graph.nodes.length > 0) {
            graphResults.push({ entryFqn: method, data: graph })
            rootChildren.push(buildSubtreeFromCalleesTree(graph, method))
          } else {
            rootChildren.push({ name: methodName, className, methodSignature: method, children: [] })
          }
        } catch {
          rootChildren.push({ name: methodName, className, methodSignature: method, children: [] })
        }
      }

      chainData.value = { name: '入口方法', className: '', children: rootChildren }

      // Multi-entry: build merged graph for FlowDag
      if (graphResults.length > 1) {
        mergedGraph.value = mergeCallChainGraphs(graphResults)
      } else {
        mergedGraph.value = null
      }

      ElMessage.success('依赖图生成成功')
    }
```

**Step 2: Update template — add merged FlowDag for multi-entry downstream**

After the existing `<ChainChart>` block for downstream, add a toggle for merged graph view:

```html
      <!-- 向下查询：多入口合并流程图 -->
      <div v-if="analysisDirection === 'downstream' && mergedGraph" class="merged-graph-section">
        <div class="section-header">
          <span>多入口合并流程图 ({{ mergedGraph.nodes.length }} 节点，耦合点已标记)</span>
        </div>
        <div class="merged-flow-container">
          <FlowDag
            :nodes="mergedGraph.nodes"
            :edges="mergedGraph.edges"
            direction="TB"
            :entry-sources="mergedGraph.entrySources"
            :entry-colors="mergedGraph.entryColors"
            :entry-labels="mergedGraph.entryLabels"
            @node-click="handleFlowNodeClick"
          />
        </div>
      </div>

      <!-- 向下查询：单入口或后备树视图 -->
      <ChainChart
        v-if="analysisDirection === 'downstream' && chainData"
        :data="chainData"
        :loading="loading"
        :project-paths="effectiveProjectPaths"
        @node-contextmenu="handleContextMenu"
      />
```

Add event handler:
```typescript
const handleFlowNodeClick = (node: FlowNode) => {
  if (node.className && node.name) {
    ElMessage.info(`${node.className}.${node.name}`)
  }
}
```

Add CSS:
```css
.merged-graph-section { margin-top: 16px; }
.merged-flow-container { height: 600px; border: 1px solid #ebeef5; border-radius: 8px; }
```

**Step 3: Verify it compiles**

Run: `cd hisi-dev-tool-frontend && npx vue-tsc --noEmit 2>&1 | head -30`
Expected: no new errors

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/call-chain/MethodReferenceGraph.vue
git commit -m "feat(kg-viz): add multi-entry merged graph with coupling markers"
```

---

### Task 7: Visual testing and polish

**Files:**
- Modify: any files from Tasks 1-6 as needed

**Step 1: Start dev server and test**

Run: `cd hisi-dev-tool-frontend && npm run dev`

Test checklist:
1. Navigate to Knowledge Graph → 引用分析 tab
2. Add a method FQN and click query (downstream)
3. Switch to "DAG 图" view → should show hierarchical flowchart, NOT blue circles
4. Each node card should show: method name, class name, description (if available), bridge type tag
5. Pan (drag) and zoom (scroll) should work
6. Right-click a node → context menu should appear
7. Switch direction to upstream → should show bottom-to-top flowchart
8. Add multiple entry methods → query downstream → should show merged graph with coupling badges on shared nodes
9. Legend should show entry colors

**Step 2: Fix any rendering issues found during testing**

Common issues to watch for:
- `<foreignObject>` not rendering in Firefox → ensure `xmlns="http://www.w3.org/1999/xhtml"` is set
- SVG viewBox sizing → adjust margins in layout
- Edge paths not connecting to node borders → adjust dagre node size parameters
- Coupling badge position → tweak absolute positioning

**Step 3: Final commit**

```bash
git add -A
git commit -m "fix(kg-viz): polish FlowDag rendering and interaction"
```
