<script setup lang="ts">
/**
 * DagGraph — layered SVG DAG for the RAM impact view.
 *
 * Replaces the three concentric rings with a left→right propagation graph.
 * Visual encoding: position = layer, size = in-degree, color = risk
 * gradient, edge width = cumulative risk flow, dashed = cross-service.
 * Hovering a node highlights its upstream ancestor path via store state.
 */
import { computed } from 'vue'
import { computeDagLayout, type EdgeKind } from './dagLayout'
import { useRamStore } from '@/stores/ram'

// Hoisted: avoid re-compiling the regex on every render for every node label.
const LABEL_SPLIT = /[/.]/

interface Props {
  seeds: readonly string[]
  edges: readonly { from: string; to: string; kind?: EdgeKind }[]
  riskScores?: Readonly<Record<string, number>>
  inDegree?: Readonly<Record<string, number>>
  width?: number
  height?: number
}

const props = withDefaults(defineProps<Props>(), {
  width: 900,
  height: 520,
  riskScores: () => ({}),
  inDegree: () => ({})
})

const emit = defineEmits<{
  (e: 'nodeClick', file: string): void
}>()

const store = useRamStore()

const layout = computed(() =>
  computeDagLayout({
    width: props.width,
    height: props.height,
    seeds: props.seeds,
    edges: props.edges,
    riskScores: props.riskScores,
    inDegree: props.inDegree
  })
)

// Build adjacency map for upstream DFS (reversed edges).
const upstream = computed<Map<string, string[]>>(() => {
  const m = new Map<string, string[]>()
  for (const e of props.edges) {
    if (!m.has(e.to)) m.set(e.to, [])
    m.get(e.to)!.push(e.from)
  }
  return m
})

// TODO(perf): memoize collectUpstream by node id once graph size > ~200 nodes.
// The `visited` Set already guards against cycles and revisits within a single
// call, so this remains O(V+E) per hover even on cyclic inputs.
function collectUpstream(file: string): string[] {
  const visited = new Set<string>()
  const stack = [file]
  while (stack.length) {
    const cur = stack.pop()!
    if (visited.has(cur)) continue
    visited.add(cur)
    const parents = upstream.value.get(cur) ?? []
    for (const p of parents) stack.push(p)
  }
  return [...visited]
}

function onHover(file: string | null): void {
  store.hoverFile(file)
  if (file) store.setHighlightPath(collectUpstream(file))
  else store.clearHighlight()
}

function onClick(file: string): void {
  store.selectFile(file)
  emit('nodeClick', file)
}

function onKeyActivate(file: string, ev: KeyboardEvent): void {
  // Enter / Space activate the node (same effect as click). Prevent default so
  // Space doesn't scroll the page when an SVG <g> is focused.
  ev.preventDefault()
  onClick(file)
}

function edgePath(points: readonly { x: number; y: number }[]): string {
  if (points.length === 0) return ''
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x} ${p.y}`).join(' ')
}

function isHighlighted(id: string): boolean {
  return store.highlightPath.has(id)
}

function isEdgeHighlighted(from: string, to: string): boolean {
  return store.highlightPath.has(from) && store.highlightPath.has(to)
}

function nodeAriaLabel(id: string, risk: number): string {
  return `${id}, risk ${risk.toFixed(2)}`
}

function labelOf(id: string): string {
  return id.split(LABEL_SPLIT).pop() ?? id
}
</script>

<template>
  <svg
    class="dag-graph"
    :width="props.width"
    :height="props.height"
    :viewBox="`0 0 ${layout.width} ${layout.height}`"
    role="application"
    aria-label="影响传播图"
  >
    <g class="dag-edges">
      <path
        v-for="(edge, i) in layout.edges"
        :key="`e-${i}`"
        class="dag-edge"
        :d="edgePath(edge.points)"
        :stroke="isEdgeHighlighted(edge.from, edge.to) ? '#409EFF' : '#C0C4CC'"
        :stroke-width="edge.strokeWidth"
        :stroke-dasharray="edge.dashed ? '4 4' : 'none'"
        fill="none"
        :opacity="store.highlightPath.size === 0 || isEdgeHighlighted(edge.from, edge.to) ? 1 : 0.25"
      />
    </g>
    <g class="dag-nodes">
      <g
        v-for="node in layout.nodes"
        :key="node.id"
        class="dag-node-group"
        role="button"
        tabindex="0"
        :aria-label="nodeAriaLabel(node.id, node.risk)"
        @mouseenter="onHover(node.id)"
        @mouseleave="onHover(null)"
        @focus="onHover(node.id)"
        @blur="onHover(null)"
        @click="onClick(node.id)"
        @keydown.enter="onKeyActivate(node.id, $event)"
        @keydown.space="onKeyActivate(node.id, $event)"
      >
        <circle
          class="dag-node"
          :class="{ 'is-seed': node.isSeed, 'is-highlighted': isHighlighted(node.id) }"
          :cx="node.x"
          :cy="node.y"
          :r="node.radius"
          :fill="node.color"
          :data-file="node.id"
          :data-layer="node.layer"
          stroke="#FFFFFF"
          stroke-width="1.5"
        >
          <title>{{ node.id }} · risk {{ node.risk.toFixed(2) }}</title>
        </circle>
        <text
          class="dag-node-label"
          :x="node.x"
          :y="node.y + node.radius + 11"
          text-anchor="middle"
          font-size="10"
          fill="#606266"
        >
          {{ labelOf(node.id) }}
        </text>
      </g>
    </g>
  </svg>
</template>

<style scoped>
.dag-graph { display: block; background: #fafbfc; border-radius: 8px; }
.dag-node-group { cursor: pointer; outline: none; }
.dag-node-group:focus-visible .dag-node {
  stroke: #409EFF;
  stroke-width: 3;
  /* Visible focus ring on the circle itself so keyboard users see selection. */
  filter: drop-shadow(0 0 2px #409EFF);
}
.dag-node.is-seed { stroke: #303133; stroke-width: 2; }
.dag-node.is-highlighted { stroke: #409EFF; stroke-width: 2.5; }
.dag-node-label { pointer-events: none; user-select: none; }
</style>
