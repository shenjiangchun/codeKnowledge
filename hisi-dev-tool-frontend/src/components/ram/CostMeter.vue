<script setup lang="ts">
/**
 * CostMeter — top-bar readout of cumulative token usage and USD spend.
 *
 * Color thresholds (against optional {@code budget}):
 *   ratio < 0.6  → green
 *   ratio < 0.9  → orange
 *   ratio ≥ 0.9  → red
 * When no {@code budget} is provided the meter stays neutral.
 */
import { computed } from 'vue'

interface Props {
  tokens: number
  usd: number
  budget?: number
}

const props = withDefaults(defineProps<Props>(), {
  budget: 0
})

const ratio = computed<number>(() => {
  if (!props.budget || props.budget <= 0) {
    return 0
  }
  return props.usd / props.budget
})

const levelClass = computed<string>(() => {
  if (!props.budget || props.budget <= 0) {
    return 'cost-neutral'
  }
  const r = ratio.value
  if (r < 0.6) return 'cost-green'
  if (r < 0.9) return 'cost-orange'
  return 'cost-red'
})

function formatUsd(value: number): string {
  return `$${value.toFixed(4)}`
}

function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`
  return String(value)
}
</script>

<template>
  <div class="cost-meter" :class="levelClass" data-test="cost-meter">
    <span class="cost-meter__label">Tokens</span>
    <span class="cost-meter__value">{{ formatTokens(props.tokens) }}</span>
    <span class="cost-meter__sep">|</span>
    <span class="cost-meter__label">花费</span>
    <span class="cost-meter__value">{{ formatUsd(props.usd) }}</span>
    <template v-if="props.budget && props.budget > 0">
      <span class="cost-meter__sep">/</span>
      <span class="cost-meter__budget">{{ formatUsd(props.budget) }}</span>
      <span class="cost-meter__ratio">{{ Math.round(ratio * 100) }}%</span>
    </template>
  </div>
</template>

<style scoped>
.cost-meter {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 16px;
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  border: 1px solid transparent;
  background: #f5f7fa;
  color: #303133;
}
.cost-meter__label {
  color: #909399;
}
.cost-meter__value {
  font-weight: 600;
}
.cost-meter__sep {
  color: #c0c4cc;
}
.cost-meter__budget {
  color: #606266;
}
.cost-meter__ratio {
  font-weight: 600;
}
.cost-neutral {
  background: #f5f7fa;
  border-color: #e4e7ed;
}
.cost-green {
  background: #f0f9eb;
  border-color: #67c23a;
  color: #67c23a;
}
.cost-orange {
  background: #fdf6ec;
  border-color: #e6a23c;
  color: #e6a23c;
}
.cost-red {
  background: #fef0f0;
  border-color: #f56c6c;
  color: #f56c6c;
}
</style>
