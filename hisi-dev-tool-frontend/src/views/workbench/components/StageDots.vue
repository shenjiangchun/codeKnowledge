<script setup lang="ts">
/** 8 个 SDD 阶段的迷你进度条。 */
import { computed } from 'vue'
import type { FixRunDetail } from '@/types/workbench'

const props = defineProps<{ run: FixRunDetail; checkpoints?: Record<string, unknown> }>()
const states = computed(() => {
  if (!props.run?.stages) return []
  return props.run.stages.map((stage, i) => {
    const key = stage.key
    if (i < props.run.stageIndex) return 'done'
    if (i === props.run.stageIndex) {
      if (props.run.status === 'WAITING_CONFIRM') return 'waiting'
      if (props.run.status === 'FAILED' || props.run.status === 'ABORTED') return 'failed'
      return 'active'
    }
    // 已出结果的阶段（迭代回退时重新激活的后面阶段）
    if (props.checkpoints && key in props.checkpoints) {
      return i > props.run.stageIndex ? 'done' : 'done'
    }
    return 'pending'
  })
})
</script>

<template>
  <div class="wb-dots">
    <div
      v-for="(stage, i) in run.stages"
      :key="stage.key"
      class="wb-dot"
      :class="states[i]"
      :title="`${stage.label}${states[i] === 'active' ? '（进行中）' : ''}`"
    />
  </div>
</template>

<style scoped>
.wb-dots { display: flex; gap: 3px; align-items: center; }
.wb-dot { flex: 1; height: 4px; border-radius: 2px; background: #e5e7eb; }
.wb-dot.done { background: #2563eb; }
.wb-dot.active { background: linear-gradient(to right, #2563eb 40%, #e5e7eb 40%); }
.wb-dot.waiting { background: #f59e0b; }
.wb-dot.failed { background: #dc2626; }
</style>
