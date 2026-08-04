<script setup lang="ts">
/**
 * Minimap — small (160×160) orientation widget reusing the existing
 * three concentric rings. Displays involved/modified/impacted at-a-glance
 * counts without competing with the main DAG for space.
 */
import ThreeRingGraph from './ThreeRingGraph.vue'

interface Props {
  involved: readonly string[]
  modified: readonly string[]
  impacted: readonly string[]
  riskScores?: Readonly<Record<string, number>>
}

const props = withDefaults(defineProps<Props>(), { riskScores: () => ({}) })
</script>

<template>
  <div class="minimap" aria-label="影响范围小地图">
    <ThreeRingGraph
      :involved="props.involved"
      :modified="props.modified"
      :impacted="props.impacted"
      :risk-scores="props.riskScores"
      :width="160"
      :height="160"
    />
  </div>
</template>

<style scoped>
.minimap { width: 160px; height: 160px; pointer-events: none; opacity: 0.85; }
</style>
