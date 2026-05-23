<script setup lang="ts">
/**
 * ThreeRingGraph — concentric three-ring impact visualization.
 *
 * Renders three rings (Involved / Modified / Impacted) with nodes placed evenly
 * around each ring's circumference. Implemented in plain SVG for testability
 * under happy-dom (ECharts canvas is hard to assert against in unit tests).
 */
import { computed } from 'vue'

interface Props {
  involved: readonly string[]
  modified: readonly string[]
  impacted: readonly string[]
  width?: number
  height?: number
}

const props = withDefaults(defineProps<Props>(), {
  width: 520,
  height: 520
})

const emit = defineEmits<{
  (e: 'nodeClick', file: string): void
}>()

interface RingDef {
  readonly key: 'involved' | 'modified' | 'impacted'
  readonly label: string
  readonly color: string
  readonly files: readonly string[]
}

const RING_COLORS = {
  involved: '#FFD700',
  modified: '#FF8C00',
  impacted: '#B0B0B0'
} as const

interface NodePoint {
  readonly file: string
  readonly cx: number
  readonly cy: number
  readonly color: string
  readonly ring: RingDef['key']
}

const center = computed(() => ({
  x: props.width / 2,
  y: props.height / 2
}))

const radii = computed(() => {
  const outer = Math.min(props.width, props.height) / 2 - 24
  // Three concentric rings: innermost = involved, middle = modified, outer = impacted.
  return {
    involved: outer * 0.35,
    modified: outer * 0.65,
    impacted: outer * 0.95
  }
})

const rings = computed<RingDef[]>(() => [
  { key: 'involved', label: '涉及', color: RING_COLORS.involved, files: props.involved },
  { key: 'modified', label: '修改', color: RING_COLORS.modified, files: props.modified },
  { key: 'impacted', label: '影响', color: RING_COLORS.impacted, files: props.impacted }
])

const nodes = computed<NodePoint[]>(() => {
  const c = center.value
  const r = radii.value
  const out: NodePoint[] = []
  for (const ring of rings.value) {
    const radius = r[ring.key]
    const n = ring.files.length
    ring.files.forEach((file, i) => {
      const angle = n === 0 ? 0 : (2 * Math.PI * i) / n - Math.PI / 2
      out.push({
        file,
        cx: c.x + radius * Math.cos(angle),
        cy: c.y + radius * Math.sin(angle),
        color: ring.color,
        ring: ring.key
      })
    })
  }
  return out
})

function truncate(label: string, max = 18): string {
  return label.length > max ? `${label.slice(0, max - 1)}…` : label
}

function handleClick(file: string): void {
  emit('nodeClick', file)
}

// Exposed for unit tests — happy-dom does not bubble SVG <circle> click events
// reliably through @vue/test-utils, so tests reach in via the exposed handler.
defineExpose({ handleClick })
</script>

<template>
  <svg
    class="three-ring-graph"
    :width="props.width"
    :height="props.height"
    :viewBox="`0 0 ${props.width} ${props.height}`"
    role="img"
    aria-label="三层影响环"
  >
    <!-- Ring outlines -->
    <circle
      v-for="ring in rings"
      :key="`ring-${ring.key}`"
      class="ring-outline"
      :cx="center.x"
      :cy="center.y"
      :r="radii[ring.key]"
      :stroke="ring.color"
      fill="none"
      stroke-width="2"
      stroke-dasharray="4 4"
    />

    <!-- Ring labels -->
    <text
      v-for="ring in rings"
      :key="`label-${ring.key}`"
      class="ring-label"
      :x="center.x"
      :y="center.y - radii[ring.key] - 6"
      text-anchor="middle"
      :fill="ring.color"
      font-size="12"
    >
      {{ ring.label }}
    </text>

    <!-- Nodes -->
    <g
      v-for="node in nodes"
      :key="`${node.ring}-${node.file}`"
      class="ring-node-group"
      @click="handleClick(node.file)"
    >
      <circle
        class="ring-node"
        :cx="node.cx"
        :cy="node.cy"
        r="7"
        :fill="node.color"
        :data-file="node.file"
        :data-ring="node.ring"
        @click="handleClick(node.file)"
      >
        <title>{{ node.file }}</title>
      </circle>
      <text
        class="ring-node-label"
        :x="node.cx"
        :y="node.cy + 18"
        text-anchor="middle"
        font-size="10"
        fill="#444"
      >
        {{ truncate(node.file) }}
      </text>
    </g>
  </svg>
</template>

<style scoped>
.three-ring-graph {
  display: block;
  user-select: none;
}

.ring-node-group {
  cursor: pointer;
}

.ring-node-group:hover .ring-node {
  stroke: #303133;
  stroke-width: 2;
}

.ring-node-label {
  pointer-events: none;
}
</style>
