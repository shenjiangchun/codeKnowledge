<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useRamChatStore } from '@/stores/ramChatStore'
import { Plus, Delete } from '@element-plus/icons-vue'

const emit = defineEmits<{
  create: []
  delete: [sid: string]
}>()

const router = useRouter()
const store = useRamChatStore()

const sessions = computed(() => store.sessions)
const currentSessionId = computed(() => store.currentSessionId)

onMounted(() => {
  store.fetchSessions()
})

function selectSession(sid: string) {
  router.push(`/ram/chat/${sid}`)
}

function formatTime(ts: number) {
  if (!ts) return ''
  const d = new Date(ts * 1000)
  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return `${diffHour} 小时前`
  return d.toLocaleDateString()
}
</script>

<template>
  <div class="sidebar-container">
    <div class="sidebar-header">
      <el-button type="primary" :icon="Plus" @click="emit('create')">新建对话</el-button>
    </div>
    <div class="session-list">
      <div
        v-for="s in sessions"
        :key="s.sessionId"
        :class="['session-item', { active: s.sessionId === currentSessionId }]"
        @click="selectSession(s.sessionId)"
      >
        <div class="session-title">{{ s.projectName || s.projectPath }}</div>
        <div class="session-intent">{{ s.intent }}</div>
        <div class="session-meta">
          <span>{{ formatTime(s.lastActivityAt) }}</span>
          <el-button
            type="danger"
            :icon="Delete"
            size="small"
            link
            @click.stop="emit('delete', s.sessionId)"
          />
        </div>
      </div>
      <div v-if="sessions.length === 0" class="empty-tip">
        暂无会话
      </div>
    </div>
  </div>
</template>

<style scoped>
.sidebar-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f8f9fa;
}
.sidebar-header {
  padding: 12px;
  border-bottom: 1px solid #e5e7eb;
}
.session-list {
  flex: 1;
  overflow-y: auto;
}
.session-item {
  padding: 12px;
  cursor: pointer;
  border-bottom: 1px solid #f0f0f0;
  transition: background-color 0.2s;
}
.session-item:hover {
  background: #ecf5ff;
}
.session-item.active {
  background: #d9ecff;
  border-left: 3px solid #409eff;
}
.session-title {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-intent {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 4px;
  font-size: 11px;
  color: #c0c4cc;
}
.empty-tip {
  padding: 24px;
  text-align: center;
  color: #c0c4cc;
}
</style>
