<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ArrowDown, ArrowRight, Check, Warning, Loading } from '@element-plus/icons-vue'

interface Step {
  toolName: string
  input: string
  result: string
  status: 'done' | 'error'
}

const props = defineProps<{
  turnId: string
  steps: Step[]
  turnComplete: boolean
}>()

const STORAGE_KEY_PREFIX = 'ram-chat-step-collapse-'
const TTL_DAYS = 7

const hasError = computed(() => props.steps.some(s => s.status === 'error'))

// Collapse rule: >=3 steps, all done, no error -> default collapsed
const shouldDefaultCollapse = computed(() =>
  props.turnComplete && props.steps.length >= 3 && !hasError.value
)

// Check localStorage for saved preference
const storageKey = computed(() => STORAGE_KEY_PREFIX + props.turnId)
const collapsed = ref(true)

onMounted(() => {
  const saved = localStorage.getItem(storageKey.value)
  if (saved) {
    try {
      const { value, ts } = JSON.parse(saved) as { value: boolean; ts: number }
      if (Date.now() - ts < TTL_DAYS * 86400000) {
        collapsed.value = value
        return
      }
    } catch { /* ignore */ }
  }
  collapsed.value = shouldDefaultCollapse.value
})

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem(storageKey.value, JSON.stringify({
    value: collapsed.value,
    ts: Date.now()
  }))
}

function truncate(s: string, max: number): string {
  if (!s) return ''
  return s.length > max ? s.substring(0, max) + '...' : s
}
</script>

<template>
  <div class="step-card">
    <div class="step-header" @click="toggleCollapse">
      <el-icon>
        <component :is="collapsed ? ArrowRight : ArrowDown" />
      </el-icon>
      <span class="step-count">{{ steps.length }} 个工具调用</span>
      <el-icon v-if="hasError" class="error-icon"><Warning /></el-icon>
      <el-icon v-else-if="turnComplete" class="success-icon"><Check /></el-icon>
      <el-icon v-else class="loading-icon is-loading"><Loading /></el-icon>
    </div>
    <div v-if="!collapsed" class="step-list">
      <div v-for="(step, idx) in steps" :key="idx" class="step-item">
        <div class="step-name">
          <el-icon v-if="step.status === 'done'" class="success-icon"><Check /></el-icon>
          <el-icon v-else class="error-icon"><Warning /></el-icon>
          {{ step.toolName }}
        </div>
        <div v-if="step.input" class="step-detail">
          <span class="detail-label">输入:</span> {{ truncate(step.input, 200) }}
        </div>
        <div v-if="step.result" class="step-detail">
          <span class="detail-label">结果:</span> {{ truncate(step.result, 300) }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.step-card {
  margin: 8px 0;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}
.step-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: #f5f7fa;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
}
.step-header:hover {
  background: #ecf5ff;
}
.step-count {
  font-weight: 500;
}
.success-icon { color: #67c23a; }
.error-icon { color: #f56c6c; }
.loading-icon { color: #909399; }
.step-list {
  border-top: 1px solid #e4e7ed;
}
.step-item {
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 12px;
}
.step-item:last-child {
  border-bottom: none;
}
.step-name {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
  color: #303133;
}
.step-detail {
  margin-top: 4px;
  color: #909399;
  word-break: break-all;
}
.detail-label {
  font-weight: 500;
  color: #606266;
}
</style>
