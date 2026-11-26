<script setup lang="ts">
/** 近 7 日出现次数的小折线。 */
import { computed } from 'vue'

const props = withDefaults(defineProps<{ values: number[]; width?: number; height?: number; color?: string }>(), {
  width: 56, height: 18, color: '#2563eb',
})
const points = computed(() => {
  const max = Math.max(1, ...props.values)
  const step = props.width / Math.max(1, props.values.length - 1)
  return props.values
    .map((v, i) => `${(i * step).toFixed(1)},${(props.height - 2 - (v / max) * (props.height - 4)).toFixed(1)}`)
    .join(' ')
})
</script>

<template>
  <svg :width="width" :height="height" class="wb-spark" aria-hidden="true">
    <polyline :points="points" fill="none" :stroke="color" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round" />
  </svg>
</template>

<style scoped>
.wb-spark { display: block; }
</style>
