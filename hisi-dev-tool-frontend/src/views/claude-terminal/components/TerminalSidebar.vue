<template>
  <div class="terminal-sidebar">
    <!-- 会话信息区 -->
    <div class="info-section">
      <div class="section-header">
        <el-icon><InfoFilled /></el-icon>
        <span>会话信息</span>
      </div>
      <div class="info-content">
        <div class="info-row">
          <span class="info-label">标题</span>
          <span class="info-value">{{ sessionTitle || '新会话' }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">Session ID</span>
          <span class="info-value monospace">{{ sessionId || '-' }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">工作目录</span>
          <span class="info-value monospace working-dir">{{ workingDirectory || '-' }}</span>
        </div>
        <div v-if="gitStatus" class="info-row git-status-row">
          <span class="info-label">
            <el-icon class="branch-icon"><Link /></el-icon>
            Git 状态
          </span>
          <div class="git-status-info">
            <span class="git-branch">{{ gitStatus.branch }}</span>
            <span v-if="gitStatus.modifiedCount > 0" class="git-modified">
              {{ gitStatus.modifiedCount }} 个修改
            </span>
            <span v-else class="git-clean">干净</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 终端状态区 -->
    <div class="status-section">
      <div class="section-header">
        <el-icon><Monitor /></el-icon>
        <span>终端状态</span>
      </div>
      <div class="status-content">
        <div class="status-row">
          <span class="status-label">连接状态</span>
          <el-tag :type="statusTagType" size="small">{{ statusText }}</el-tag>
        </div>
        <div class="status-row">
          <span class="status-label">终端尺寸</span>
          <span class="status-value">{{ terminalCols }} x {{ terminalRows }}</span>
        </div>
        <div class="status-row">
          <span class="status-label">运行时长</span>
          <span class="status-value">{{ formatDuration(sessionDuration) }}</span>
        </div>
        <div class="status-actions">
          <el-button size="small" :disabled="connectionStatus === 'connected'" @click="handleReconnect">
            <el-icon><RefreshRight /></el-icon>
            重连
          </el-button>
          <el-button size="small" @click="handleClear">
            <el-icon><Delete /></el-icon>
            清屏
          </el-button>
        </div>
      </div>
    </div>

    <!-- 快捷操作区 -->
    <div class="actions-section">
      <div class="section-header">
        <el-icon><Operation /></el-icon>
        <span>快捷命令</span>
      </div>
      <div class="actions-content">
        <el-button
          v-for="action in quickActions"
          :key="action.command"
          size="small"
          @click="handleExecuteCommand(action.command)"
        >
          {{ action.label }}
        </el-button>
      </div>
    </div>

    <!-- 会话统计区 -->
    <div class="stats-section">
      <div class="section-header">
        <el-icon><DataAnalysis /></el-icon>
        <span>会话统计</span>
      </div>
      <div class="stats-content">
        <div class="stat-item">
          <div class="stat-value">{{ activeSessionCount }}</div>
          <div class="stat-label">活跃会话</div>
        </div>
        <div class="stat-item">
          <div class="stat-value">{{ archivedSessionCount }}</div>
          <div class="stat-label">归档会话</div>
        </div>
      </div>
    </div>

    <!-- 主题设置区 -->
    <div class="theme-section">
      <div class="section-header">
        <el-icon><Brush /></el-icon>
        <span>主题设置</span>
      </div>
      <ThemeSelector />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { InfoFilled, Monitor, RefreshRight, Delete, Operation, DataAnalysis, Link, Brush } from '@element-plus/icons-vue'
import type { TerminalConnectionStatus } from '@/types/terminal'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { gitApi } from '@/api/git'
import ThemeSelector from './ThemeSelector.vue'

interface Props {
  connectionStatus: TerminalConnectionStatus
  terminalCols?: number
  terminalRows?: number
  sessionDuration?: number
}

const props = withDefaults(defineProps<Props>(), {
  terminalCols: 120,
  terminalRows: 30,
  sessionDuration: 0
})

const emit = defineEmits<{
  executeCommand: [command: string]
  reconnect: []
  clear: []
}>()

const workspaceStore = useWorkspaceStore()

// Computed properties (must be defined before using in watch)
const sessionTitle = computed(() => workspaceStore.currentSession?.title)
const sessionId = computed(() => workspaceStore.currentSession?.claudeSessionId)
const workingDirectory = computed(() => workspaceStore.currentSession?.workingDirectory)
const activeSessionCount = computed(() => workspaceStore.activeSessions.length)
const archivedSessionCount = computed(() => workspaceStore.archivedSessions.length)

// Git status
const gitStatus = ref<{
  branch: string
  clean: boolean
  modifiedCount: number
} | null>(null)

async function loadGitStatus() {
  if (!workingDirectory.value) {
    gitStatus.value = null
    return
  }
  try {
    const status = await gitApi.getStatus(workingDirectory.value)
    gitStatus.value = {
      branch: status.branch,
      clean: status.clean,
      modifiedCount: (status.modified?.length || 0) + (status.untracked?.length || 0)
    }
  } catch {
    gitStatus.value = null
  }
}

// Watch for working directory changes
watch(workingDirectory, () => {
  loadGitStatus()
}, { immediate: true })

const quickActions = [
  { label: '/help', command: '/help' },
  { label: '/plugin', command: '/plugin' },
  { label: '/config', command: '/config' },
  { label: '/clear', command: '/clear' },
  { label: '分析日志错误', command: '帮我分析这个日志错误' },
  { label: '查询代码实现', command: '查询相关代码实现' },
  { label: '解释错误原因', command: '请解释这个错误的原因' },
  { label: '深入分析', command: '请深入分析这个问题' },
]

const statusText = computed(() => {
  switch (props.connectionStatus) {
    case 'connected': return '已连接'
    case 'connecting': return '连接中...'
    case 'disconnected': return '已断开'
    case 'error': return '连接错误'
    default: return '未知'
  }
})

const statusTagType = computed(() => {
  switch (props.connectionStatus) {
    case 'connected': return 'success'
    case 'connecting': return 'warning'
    case 'disconnected': return 'info'
    case 'error': return 'danger'
    default: return 'info'
  }
})

function formatDuration(seconds: number): string {
  if (!seconds || seconds <= 0) return '00:00'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = seconds % 60
  if (hours > 0) {
    return `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
  }
  return `${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
}

function handleExecuteCommand(command: string) {
  emit('executeCommand', command)
}

function handleReconnect() {
  emit('reconnect')
}

function handleClear() {
  emit('clear')
}
</script>

<style scoped>
/* CSS 变量默认值 */
.terminal-sidebar {
  --ct-bg-level-1: #1a1a1a;
  --ct-bg-level-2: #1e1e1e;
  --ct-bg-level-3: #252526;
  --ct-bg-level-4: #404040;
  --ct-text-primary: #e0e0e0;
  --ct-text-secondary: #909399;
  --ct-text-muted: #666666;
  --ct-accent-primary: #409eff;
  --ct-accent-success: #67c23a;
  --ct-accent-warning: #e6a23c;
  --ct-accent-danger: #f56c6c;
  --ct-text-on-accent: #ffffff;
  --ct-text-on-accent-secondary: rgba(255, 255, 255, 0.8);
  --ct-success-light-bg: rgba(103, 194, 58, 0.1);
  --ct-border-hover: #505050;
  --ct-success-text-on-accent: #a5d6a7;
}

.terminal-sidebar {
  width: 360px;
  background-color: var(--ct-bg-level-2);
  border-radius: 12px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--ct-bg-level-3);
  border-radius: 8px;
  color: var(--ct-text-primary);
  font-weight: 500;
  font-size: 13px;
}

.section-header :deep(.el-icon) {
  color: var(--ct-text-secondary);
}

.info-section,
.status-section,
.actions-section,
.stats-section,
.theme-section {
  display: flex;
  flex-direction: column;
}

.info-content,
.status-content,
.actions-content,
.stats-content {
  background: var(--ct-bg-level-3);
  border-radius: 8px;
  padding: 12px;
}

.info-row,
.status-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}

.info-row:not(:last-child),
.status-row:not(:last-child) {
  border-bottom: 1px solid var(--ct-bg-level-4);
}

.info-label,
.status-label {
  color: var(--ct-text-secondary);
  font-size: 12px;
}

.info-value,
.status-value {
  color: var(--ct-text-primary);
  font-size: 12px;
}

.info-value.monospace,
.status-value.monospace {
  font-family: 'JetBrains Mono', monospace;
}

.working-dir {
  color: var(--ct-accent-success);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.status-actions :deep(.el-button) {
  background: var(--ct-bg-level-2);
  border-color: var(--ct-bg-level-4);
  color: var(--ct-text-primary);
}

.status-actions :deep(.el-button:hover) {
  background: var(--ct-bg-level-3);
  border-color: var(--ct-border-hover);
}

.status-actions :deep(.el-button:disabled) {
  background: var(--ct-bg-level-3);
  border-color: var(--ct-bg-level-4);
  color: var(--ct-text-secondary);
}

.actions-content {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.actions-content :deep(.el-button) {
  background: var(--ct-bg-level-2);
  border-color: var(--ct-bg-level-4);
  color: var(--ct-text-primary);
}

.actions-content :deep(.el-button:hover) {
  background: var(--ct-accent-primary);
  border-color: var(--ct-accent-primary);
}

.stats-content {
  display: flex;
  gap: 16px;
}

.stat-item {
  flex: 1;
  text-align: center;
  padding: 12px;
  background: var(--ct-bg-level-2);
  border-radius: 6px;
}

.stat-value {
  color: var(--ct-accent-primary);
  font-size: 24px;
  font-weight: 600;
}

.stat-label {
  color: var(--ct-text-secondary);
  font-size: 12px;
  margin-top: 4px;
}

/* Git Status Styles */
.git-status-row {
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.git-status-row .info-label {
  display: flex;
  align-items: center;
  gap: 4px;
}

.branch-icon {
  font-size: 14px;
  color: var(--ct-accent-primary);
}

.git-status-info {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.git-branch {
  font-family: 'JetBrains Mono', monospace;
  color: var(--ct-accent-primary);
  font-size: 12px;
  background: var(--ct-bg-level-4);
  padding: 2px 8px;
  border-radius: 4px;
}

.git-modified {
  color: var(--ct-accent-warning);
  font-size: 11px;
}

.git-clean {
  color: var(--ct-accent-success);
  font-size: 11px;
}
</style>