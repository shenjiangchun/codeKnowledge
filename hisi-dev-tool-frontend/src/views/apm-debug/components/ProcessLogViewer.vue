<template>
  <div class="process-log-viewer" :class="{ 'is-collapsed': collapsed }">
    <div class="log-header">
      <div class="header-left">
        <el-icon><Monitor /></el-icon>
        <span class="title">目标进程日志</span>
        <el-tag :type="statusTagType" size="small" effect="dark">{{ statusText }}</el-tag>
        <span class="line-count">{{ filteredLines.length }} / {{ logs.length }} 行</span>
      </div>
      <div class="header-right">
        <el-input
          v-model="filter"
          size="small"
          placeholder="过滤 (regex 支持)"
          clearable
          class="filter-input"
        />
        <el-select v-model="levelFilter" size="small" class="level-select">
          <el-option label="全部" value="ALL" />
          <el-option label="ERROR" value="ERROR" />
          <el-option label="WARN+" value="WARN" />
          <el-option label="INFO+" value="INFO" />
        </el-select>
        <el-tooltip :content="autoScroll ? '关闭自动滚动' : '开启自动滚动'">
          <el-button
            size="small"
            :type="autoScroll ? 'primary' : 'default'"
            :icon="autoScroll ? Bottom : Top"
            @click="autoScroll = !autoScroll"
          />
        </el-tooltip>
        <el-tooltip content="复制全部">
          <el-button size="small" :icon="DocumentCopy" @click="copyAll" />
        </el-tooltip>
        <el-tooltip content="清空">
          <el-button size="small" :icon="Delete" @click="$emit('clear')" />
        </el-tooltip>
        <el-tooltip :content="collapsed ? '展开' : '折叠'">
          <el-button
            size="small"
            :icon="collapsed ? ArrowUp : ArrowDown"
            @click="collapsed = !collapsed"
          />
        </el-tooltip>
      </div>
    </div>

    <div v-if="errorBanner" class="error-banner">
      <el-icon><CircleClose /></el-icon>
      <span>目标进程已退出{{ errorBanner.exitCode != null ? ` (exit code ${errorBanner.exitCode})` : '' }}。请查看下方日志诊断错误原因。</span>
    </div>

    <div v-show="!collapsed" ref="logBodyRef" class="log-body" @scroll="onScroll">
      <div v-if="filteredLines.length === 0" class="empty-state">
        <el-icon><Loading /></el-icon>
        <span>{{ logs.length === 0 ? '等待目标进程输出...' : '没有匹配的日志行' }}</span>
      </div>
      <div
        v-for="(item, idx) in filteredLines"
        :key="idx"
        class="log-line"
        :class="lineClass(item.line)"
      >
        <span class="line-no">{{ item.index + 1 }}</span>
        <span class="line-text">{{ item.line }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  ArrowUp,
  Bottom,
  CircleClose,
  Delete,
  DocumentCopy,
  Loading,
  Monitor,
  Top,
} from '@element-plus/icons-vue'
import type { ProcessLogLine } from '@/composables/useApmWebSocket'

interface Props {
  logs: ProcessLogLine[]
  status: string
  errorBanner?: { exitCode?: number; tailLines?: string[] } | null
}

const props = withDefaults(defineProps<Props>(), {
  errorBanner: null,
})

defineEmits<{
  (e: 'clear'): void
}>()

const collapsed = ref(false)
const autoScroll = ref(true)
const filter = ref('')
const levelFilter = ref<'ALL' | 'ERROR' | 'WARN' | 'INFO'>('ALL')
const logBodyRef = ref<HTMLDivElement | null>(null)

const filteredLines = computed(() => {
  let regex: RegExp | null = null
  if (filter.value.trim()) {
    try {
      regex = new RegExp(filter.value, 'i')
    } catch {
      regex = null
    }
  }
  const minLevel = levelFilter.value
  return props.logs
    .map((l, i) => ({ ...l, index: i }))
    .filter((l) => {
      if (regex && !regex.test(l.line)) return false
      if (minLevel === 'ALL') return true
      if (minLevel === 'ERROR') return /\bERROR\b/.test(l.line)
      if (minLevel === 'WARN') return /\b(WARN|ERROR)\b/.test(l.line)
      if (minLevel === 'INFO') return /\b(INFO|WARN|ERROR)\b/.test(l.line)
      return true
    })
})

const statusText = computed(() => {
  switch (props.status) {
    case 'IDLE': return '空闲'
    case 'LAUNCHING': return '启动中'
    case 'READY': return '就绪'
    case 'RUNNING': return '运行中'
    case 'STOPPED': return '已停止'
    case 'ERROR': return '错误'
    default: return props.status
  }
})

const statusTagType = computed<'success' | 'warning' | 'danger' | 'info' | 'primary'>(() => {
  switch (props.status) {
    case 'READY':
    case 'RUNNING': return 'success'
    case 'LAUNCHING': return 'warning'
    case 'ERROR': return 'danger'
    case 'STOPPED': return 'info'
    default: return 'info'
  }
})

function lineClass(line: string): string {
  if (/\bERROR\b|Exception\b|\bFAIL/i.test(line)) return 'level-error'
  if (/\bWARN\b/.test(line)) return 'level-warn'
  if (/\bINFO\b/.test(line)) return 'level-info'
  if (/\bDEBUG\b/.test(line)) return 'level-debug'
  return ''
}

function onScroll(): void {
  const el = logBodyRef.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  if (!nearBottom) {
    autoScroll.value = false
  }
}

async function copyAll(): Promise<void> {
  const text = props.logs.map((l) => l.line).join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`已复制 ${props.logs.length} 行日志到剪贴板`)
  } catch {
    ElMessage.error('复制失败')
  }
}

watch(
  () => props.logs.length,
  async () => {
    if (autoScroll.value && !collapsed.value) {
      await nextTick()
      const el = logBodyRef.value
      if (el) el.scrollTop = el.scrollHeight
    }
  },
)

// Auto-expand on error
watch(
  () => props.status,
  (newStatus) => {
    if (newStatus === 'ERROR') {
      collapsed.value = false
    }
  },
)
</script>

<style scoped>
.process-log-viewer {
  display: flex;
  flex-direction: column;
  background: #1e1e1e;
  border: 1px solid #333;
  border-radius: 4px;
  overflow: hidden;
  font-family: 'Consolas', 'Monaco', monospace;
  min-height: 0;
}

.process-log-viewer.is-collapsed {
  height: auto;
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #2d2d2d;
  border-bottom: 1px solid #333;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #d4d4d4;
  font-size: 13px;
}

.title {
  font-weight: 600;
}

.line-count {
  color: #888;
  font-size: 12px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.filter-input {
  width: 200px;
}

.level-select {
  width: 100px;
}

.error-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #5c1f1f;
  color: #ffaaaa;
  font-size: 13px;
  border-bottom: 1px solid #7a2a2a;
}

.log-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
  background: #1e1e1e;
  min-height: 200px;
  max-height: 500px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: #666;
  font-size: 13px;
}

.log-line {
  display: flex;
  padding: 1px 12px;
  font-size: 12px;
  line-height: 1.5;
  color: #d4d4d4;
  white-space: pre-wrap;
  word-break: break-all;
}

.log-line:hover {
  background: #2a2a2a;
}

.line-no {
  flex-shrink: 0;
  color: #555;
  margin-right: 12px;
  user-select: none;
  min-width: 40px;
  text-align: right;
}

.line-text {
  flex: 1;
}

.log-line.level-error {
  background: rgba(244, 67, 54, 0.1);
  color: #ff6b6b;
}

.log-line.level-warn {
  color: #ffd166;
}

.log-line.level-info {
  color: #8dd0ff;
}

.log-line.level-debug {
  color: #888;
}
</style>
