<script setup lang="ts">
/**
 * StatusSessionListPage — 项目现状分析历史会话列表.
 * Route: /ram/status
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listRamSessions, type SessionSummary } from '@/api/ram'

const router = useRouter()

const sessions = ref<SessionSummary[]>([])
const loading = ref(false)

async function loadSessions(): Promise<void> {
  loading.value = true
  try {
    sessions.value = await listRamSessions(50, 'STATUS')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '加载历史会话失败'
    ElMessage.warning(msg)
  } finally {
    loading.value = false
  }
}

onMounted(loadSessions)

function gotoNew(): void {
  router.push({ name: 'RamStatusInput' })
}

function gotoDetail(sid: string | null): void {
  if (!sid) return
  router.push({ name: 'RamStatus', params: { sid } })
}

function statusTagType(status: string | null): '' | 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'DONE': return 'success'
    case 'RUNNING': return 'warning'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

function statusLabel(status: string | null): string {
  switch (status) {
    case 'DONE': return '已完成'
    case 'RUNNING': return '运行中'
    case 'FAILED': return '失败'
    default: return status ?? '—'
  }
}

function formatTime(ts: number): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleString()
}

function truncate(s: string | null, max = 80): string {
  if (!s) return '—'
  return s.length > max ? s.slice(0, max) + '...' : s
}
</script>

<template>
  <div class="status-session-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>项目现状分析 · 历史会话</span>
          <el-button type="primary" size="small" @click="gotoNew">
            创建新分析
          </el-button>
        </div>
      </template>

      <div v-if="loading" class="loading-hint">加载中...</div>

      <div v-else-if="sessions.length === 0" class="empty-hint">
        暂无历史会话，点击「创建新分析」开始
      </div>

      <div v-else class="session-cards">
        <div class="session-count">共 {{ sessions.length }} 条记录</div>
        <div
          v-for="s in sessions"
          :key="s.sessionId ?? s.createdAt"
          class="session-card"
          @click="gotoDetail(s.sessionId)"
        >
          <div class="card-main">
            <span class="card-intent">{{ truncate(s.intent) }}</span>
            <span v-if="s.projectPaths" class="card-paths">{{ truncate(s.projectPaths, 120) }}</span>
          </div>
          <div class="card-meta">
            <el-tag size="small" :type="statusTagType(s.status)">{{ statusLabel(s.status) }}</el-tag>
            <span class="card-time">{{ formatTime(s.updatedAt || s.createdAt) }}</span>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.status-session-list {
  padding: 16px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.loading-hint,
.empty-hint {
  text-align: center;
  padding: 40px 0;
  color: #909399;
  font-size: 14px;
}
.session-count {
  font-size: 12px;
  color: #909399;
  margin-bottom: 12px;
}
.session-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.session-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.session-card:hover {
  border-color: #409eff;
  background: #f0f7ff;
}
.card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.card-intent {
  font-weight: 500;
  color: #303133;
  font-size: 14px;
}
.card-paths {
  font-size: 12px;
  color: #909399;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: 16px;
}
.card-time {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}
</style>