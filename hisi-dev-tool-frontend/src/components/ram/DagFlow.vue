<script setup lang="ts">
/**
 * RAM 5-stage DAG visualization.
 *
 * Layout: 5 horizontal cards (Clarify → Impact → Implement → Verify → TechPlan) linked by
 * directional edges. Each card surfaces:
 *   - phase label (中文)
 *   - status pill (待执行 / 执行中 / 待澄清 / 完成 / 失败 / 熔断)
 *   - event count + cumulative tokens
 *   - risk badge (when present)
 *   - a flowing "energy" stroke on edges leading INTO the running node
 *
 * The card is clickable — emits {@code nodeClick} with the {@link DagNodeKey}
 * so the parent can swap the right-hand detail drawer.
 *
 * We deliberately roll the layout by hand (no vue-flow): vue-flow ships a CSS
 * reset that fights Element Plus, and for a 5-node fixed-topology DAG plain
 * SVG keeps the component testable under happy-dom and crisp at any zoom.
 */
import { computed } from 'vue'
import {
  formatTokens,
  statusColor,
  statusLabel,
  type DagNodeKey,
  type DagNodeSnapshot
} from './dagModel'

interface Props {
  nodes: readonly DagNodeSnapshot[]
  activeKey?: DagNodeKey | null
}

const props = withDefaults(defineProps<Props>(), {
  activeKey: null
})

const emit = defineEmits<{
  (e: 'nodeClick', key: DagNodeKey): void
}>()

const CARD_WIDTH = 200
const CARD_HEIGHT = 116
const GAP = 56
const PAD_X = 24
const PAD_Y = 24

const dimensions = computed(() => {
  const n = props.nodes.length || 4
  const w = PAD_X * 2 + n * CARD_WIDTH + (n - 1) * GAP
  const h = PAD_Y * 2 + CARD_HEIGHT
  return { width: w, height: h }
})

interface CardLayout {
  readonly node: DagNodeSnapshot
  readonly x: number
  readonly y: number
}

const cards = computed<CardLayout[]>(() => {
  return props.nodes.map((node, i) => ({
    node,
    x: PAD_X + i * (CARD_WIDTH + GAP),
    y: PAD_Y
  }))
})

interface EdgeLayout {
  readonly from: DagNodeKey
  readonly to: DagNodeKey
  readonly x1: number
  readonly y1: number
  readonly x2: number
  readonly y2: number
  readonly flowing: boolean
}

const edges = computed<EdgeLayout[]>(() => {
  const out: EdgeLayout[] = []
  for (let i = 0; i < cards.value.length - 1; i++) {
    const a = cards.value[i]
    const b = cards.value[i + 1]
    out.push({
      from: a.node.key,
      to: b.node.key,
      x1: a.x + CARD_WIDTH,
      y1: a.y + CARD_HEIGHT / 2,
      x2: b.x,
      y2: b.y + CARD_HEIGHT / 2,
      flowing: b.node.status === 'running' || b.node.status === 'awaiting-hitl'
    })
  }
  return out
})

function onCardClick(key: DagNodeKey): void {
  emit('nodeClick', key)
}

function riskColor(level?: 'LOW' | 'MEDIUM' | 'HIGH'): string {
  if (level === 'HIGH') return '#F56C6C'
  if (level === 'MEDIUM') return '#E6A23C'
  return '#67C23A'
}

defineExpose({ onCardClick })
</script>

<template>
  <div class="dag-flow-wrap">
    <svg
      class="dag-flow"
      :width="dimensions.width"
      :height="dimensions.height"
      :viewBox="`0 0 ${dimensions.width} ${dimensions.height}`"
      role="img"
      aria-label="需求分析 5 阶段流程"
    >
      <defs>
        <marker
          id="dag-arrow"
          viewBox="0 0 10 10"
          refX="9"
          refY="5"
          markerUnits="strokeWidth"
          markerWidth="6"
          markerHeight="6"
          orient="auto"
        >
          <path d="M 0 0 L 10 5 L 0 10 z" fill="#909399" />
        </marker>
        <linearGradient id="dag-edge-flow" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stop-color="#409EFF" stop-opacity="0" />
          <stop offset="50%" stop-color="#409EFF" stop-opacity="1" />
          <stop offset="100%" stop-color="#409EFF" stop-opacity="0" />
        </linearGradient>
      </defs>

      <!-- Edges -->
      <g class="dag-edges">
        <line
          v-for="edge in edges"
          :key="`edge-${edge.from}-${edge.to}`"
          class="dag-edge"
          :class="{ 'dag-edge--flowing': edge.flowing }"
          :x1="edge.x1"
          :y1="edge.y1"
          :x2="edge.x2"
          :y2="edge.y2"
          stroke="#DCDFE6"
          stroke-width="2"
          marker-end="url(#dag-arrow)"
          :data-from="edge.from"
          :data-to="edge.to"
        />
      </g>

      <!-- Cards -->
      <g
        v-for="card in cards"
        :key="`card-${card.node.key}`"
        class="dag-card-group"
        :class="{
          'dag-card-group--active': activeKey === card.node.key,
          [`dag-card-group--${card.node.status}`]: true
        }"
        :data-key="card.node.key"
        :data-status="card.node.status"
        :transform="`translate(${card.x}, ${card.y})`"
        @click="onCardClick(card.node.key)"
      >
        <rect
          class="dag-card-bg"
          :class="{ 'dag-card-bg--manual': card.node.key === 'tech_plan' && card.node.status === 'pending' }"
          :width="CARD_WIDTH"
          :height="CARD_HEIGHT"
          rx="12"
          ry="12"
          :stroke="activeKey === card.node.key ? '#409EFF' : statusColor(card.node.status)"
          :stroke-width="activeKey === card.node.key ? 3 : 2"
          :stroke-dasharray="card.node.key === 'tech_plan' && card.node.status === 'pending' ? '8 4' : 'none'"
          fill="#FFFFFF"
          @click="onCardClick(card.node.key)"
        />
        <!-- Status pill -->
        <g class="dag-card-pill" transform="translate(16, 16)">
          <rect
            :width="64"
            :height="22"
            rx="11"
            ry="11"
            :fill="statusColor(card.node.status)"
            opacity="0.16"
          />
          <circle :cx="11" :cy="11" :r="4" :fill="statusColor(card.node.status)">
            <animate
              v-if="card.node.status === 'running'"
              attributeName="opacity"
              values="0.3;1;0.3"
              dur="1.2s"
              repeatCount="indefinite"
            />
          </circle>
          <text
            :x="22"
            :y="15"
            font-size="11"
            :fill="statusColor(card.node.status)"
            dominant-baseline="middle"
          >
            {{ statusLabel(card.node.status) }}
          </text>
        </g>
        <!-- Phase label -->
        <text
          class="dag-card-label"
          :x="16"
          :y="64"
          font-size="20"
          font-weight="600"
          fill="#303133"
        >
          {{ card.node.label }}
        </text>
        <!-- Metrics row -->
        <g transform="translate(16, 80)">
          <text font-size="11" fill="#909399">事件</text>
          <text :x="36" font-size="12" font-weight="600" fill="#606266">
            {{ card.node.events }}
          </text>
          <text :x="84" font-size="11" fill="#909399">Tokens</text>
          <text :x="132" font-size="12" font-weight="600" fill="#606266">
            {{ formatTokens(card.node.tokens) }}
          </text>
        </g>
        <!-- Risk badge -->
        <g
          v-if="card.node.riskLevel"
          class="dag-card-risk"
          :transform="`translate(${CARD_WIDTH - 56}, 14)`"
        >
          <rect
            :width="44"
            :height="22"
            rx="11"
            ry="11"
            :fill="riskColor(card.node.riskLevel)"
          />
          <text
            :x="22"
            :y="15"
            font-size="11"
            font-weight="600"
            fill="#FFFFFF"
            text-anchor="middle"
            dominant-baseline="middle"
          >
            {{ card.node.riskLevel }}
          </text>
        </g>
        <!-- Reasoning indicator -->
        <g
          v-if="card.node.reasoning"
          class="dag-card-reasoning"
          :transform="`translate(${CARD_WIDTH - 20}, ${CARD_HEIGHT - 24})`"
        >
          <title>{{ card.node.reasoning }}</title>
          <circle
            :r="9"
            fill="#409EFF"
            opacity="0.18"
          />
          <text
            :y="4"
            font-size="11"
            fill="#409EFF"
            text-anchor="middle"
            dominant-baseline="middle"
          >
            💬
          </text>
        </g>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.dag-flow-wrap {
  overflow-x: auto;
  background: linear-gradient(135deg, #f6f8fb 0%, #eef2f7 100%);
  border-radius: 12px;
  padding: 4px;
}
.dag-flow {
  display: block;
  user-select: none;
}
.dag-card-group {
  cursor: pointer;
  transition: filter 160ms ease;
}
.dag-card-group:hover .dag-card-bg {
  filter: drop-shadow(0 6px 18px rgba(64, 158, 255, 0.18));
}
.dag-card-group--active .dag-card-bg {
  filter: drop-shadow(0 6px 22px rgba(64, 158, 255, 0.32));
}
.dag-edge {
  stroke-linecap: round;
}
.dag-edge--flowing {
  stroke: url(#dag-edge-flow);
  stroke-dasharray: 6 6;
  animation: dag-dash 1.2s linear infinite;
}
@keyframes dag-dash {
  to {
    stroke-dashoffset: -24;
  }
}
</style>
