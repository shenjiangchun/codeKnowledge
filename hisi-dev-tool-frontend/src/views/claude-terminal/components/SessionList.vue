<template>
  <div class="session-list-panel" :class="{ collapsed: collapsed }">
    <div class="panel-header">
      <span v-if="!collapsed">会话列表</span>
      <el-button text @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? 'DArrowRight' : 'DArrowLeft'" /></el-icon>
      </el-button>
    </div>

    <template v-if="!collapsed">
      <div class="session-actions">
        <el-input
          ref="searchInputRef"
          v-model="searchKeyword"
          placeholder="搜索会话"
          prefix-icon="Search"
          clearable
          size="small"
        />
        <el-button
          type="primary"
          size="small"
          style="margin-top: 8px; width: 100%;"
          @click="handleNewSession"
        >
          <el-icon><Plus /></el-icon>
          新建会话
        </el-button>
      </div>

      <div class="session-groups">
        <div class="session-group">
          <div class="group-header" @click="toggleGroup('active')">
            <el-icon><FolderOpened v-if="expandedGroups.active" /><Folder v-else /></el-icon>
            <span>进行中 ({{ workspaceStore.activeSessions.length }})</span>
          </div>
          <div v-show="expandedGroups.active" class="group-items">
            <div
              v-for="session in filteredActiveSessions"
              :key="session.id"
              class="session-item"
              :class="{ active: workspaceStore.currentSessionId === session.id }"
              @click="selectSession(session.id)"
            >
              <div class="session-info">
                <div class="session-title">{{ session.title || '新会话' }}</div>
                <div class="session-meta">
                  <span v-if="session.workingDirectory" class="working-dir" :title="session.workingDirectory">
                    {{ truncatePath(session.workingDirectory) }}
                  </span>
                  <span class="session-time">{{ formatDate(session.createdAt) }}</span>
                </div>
              </div>
              <div class="session-actions-row" @click.stop>
                <el-button
                  text
                  size="small"
                  @click="handleArchive(session.id)"
                  title="归档"
                >
                  <el-icon><FolderOpened /></el-icon>
                </el-button>
                <el-button
                  text
                  size="small"
                  type="danger"
                  @click="handleDelete(session.id)"
                  title="删除"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <div class="session-group">
          <div class="group-header" @click="toggleGroup('archived')">
            <el-icon><FolderOpened v-if="expandedGroups.archived" /><Folder v-else /></el-icon>
            <span>已归档 ({{ workspaceStore.archivedSessions.length }})</span>
          </div>
          <div v-show="expandedGroups.archived" class="group-items">
            <div
              v-for="session in filteredArchivedSessions"
              :key="session.id"
              class="session-item"
              :class="{ active: workspaceStore.currentSessionId === session.id }"
              @click="selectSession(session.id)"
            >
              <div class="session-info">
                <div class="session-title">{{ session.title || '新会话' }}</div>
                <div class="session-meta">
                  <span v-if="session.workingDirectory" class="working-dir" :title="session.workingDirectory">
                    {{ truncatePath(session.workingDirectory) }}
                  </span>
                  <span class="session-time">{{ formatDate(session.createdAt) }}</span>
                </div>
              </div>
              <div class="session-actions-row" @click.stop>
                <el-button
                  text
                  size="small"
                  @click="handleRestore(session.id)"
                  title="恢复"
                >
                  <el-icon><RefreshRight /></el-icon>
                </el-button>
                <el-button
                  text
                  size="small"
                  type="danger"
                  @click="handleDelete(session.id)"
                  title="删除"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { FolderOpened, Folder, Plus, Delete, RefreshRight } from '@element-plus/icons-vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import type { InputInstance } from 'element-plus'

const emit = defineEmits<{
  newSession: []
  selectSession: [sessionId: string]
  deleteSession: [sessionId: string]
}>()

const workspaceStore = useWorkspaceStore()

const collapsed = ref(false)
const searchKeyword = ref('')
const searchInputRef = ref<InputInstance | null>(null)
const expandedGroups = ref({ active: true, archived: false })

const filteredActiveSessions = computed(() => {
  if (!searchKeyword.value) return workspaceStore.activeSessions
  return workspaceStore.activeSessions.filter(s =>
    s.title?.toLowerCase().includes(searchKeyword.value.toLowerCase())
  )
})

const filteredArchivedSessions = computed(() => {
  if (!searchKeyword.value) return workspaceStore.archivedSessions
  return workspaceStore.archivedSessions.filter(s =>
    s.title?.toLowerCase().includes(searchKeyword.value.toLowerCase())
  )
})

function toggleGroup(group: 'active' | 'archived') {
  expandedGroups.value[group] = !expandedGroups.value[group]
}

function handleNewSession() {
  emit('newSession')
}

function selectSession(sessionId: string) {
  emit('selectSession', sessionId)
}

async function handleDelete(sessionId: string) {
  const session = workspaceStore.sessions.find(s => s.id === sessionId)
  try {
    await ElMessageBox.confirm(
      `确定要删除会话 "${session?.title || '新会话'}" 吗？`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
    await workspaceStore.deleteSession(sessionId)
    ElMessage.success('会话已删除')
    // If deleted session was current, emit event to parent
    if (workspaceStore.currentSessionId === null) {
      emit('deleteSession', sessionId)
    }
  } catch {
    // User cancelled
  }
}

async function handleArchive(sessionId: string) {
  await workspaceStore.archiveSession(sessionId)
  ElMessage.success('会话已归档')
}

async function handleRestore(sessionId: string) {
  await workspaceStore.updateSession(sessionId, undefined, 'active')
  ElMessage.success('会话已恢复')
}

function formatDate(date: Date): string {
  return new Date(date).toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function truncatePath(path: string): string {
  if (!path) return ''
  const parts = path.replace(/\\/g, '/').split('/')
  const lastTwo = parts.slice(-2).join('/')
  return '.../' + lastTwo
}

// Focus search input (for keyboard shortcut Ctrl+F)
function focusSearch() {
  if (collapsed.value) {
    collapsed.value = false
  }
  // Focus the input element
  const inputEl = searchInputRef.value?.$el?.querySelector('input')
  if (inputEl) {
    inputEl.focus()
  }
}

// Expose method for parent component
defineExpose({
  focusSearch
})
</script>

<style scoped>
/* CSS 变量默认值 */
.session-list-panel {
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

.session-list-panel {
  width: 220px;
  background-color: var(--ct-bg-level-2);
  border-radius: 12px;
  height: 100%;
  display: flex;
  flex-direction: column;
  transition: width 0.3s;
}

.session-list-panel.collapsed {
  width: 40px;
}

.panel-header {
  padding: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--ct-bg-level-4);
  color: var(--ct-text-primary);
}

.panel-header :deep(.el-button) {
  color: var(--ct-text-secondary);
}

.panel-header :deep(.el-button:hover) {
  color: var(--ct-text-primary);
}

.session-actions {
  padding: 12px;
}

.session-actions :deep(.el-input__wrapper) {
  background-color: var(--ct-bg-level-3);
  border: 1px solid var(--ct-bg-level-4);
  box-shadow: none;
}

.session-actions :deep(.el-input__inner) {
  color: var(--ct-text-primary);
}

.session-actions :deep(.el-input__inner::placeholder) {
  color: var(--ct-text-secondary);
}

.session-actions :deep(.el-input__prefix) {
  color: var(--ct-text-secondary);
}

.session-groups {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-groups::-webkit-scrollbar {
  width: 6px;
}

.session-groups::-webkit-scrollbar-track {
  background: var(--ct-bg-level-2);
}

.session-groups::-webkit-scrollbar-thumb {
  background: var(--ct-bg-level-4);
  border-radius: 3px;
}

.session-groups::-webkit-scrollbar-thumb:hover {
  background: var(--ct-border-hover);
}

.session-group {
  margin-bottom: 8px;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  cursor: pointer;
  font-weight: 500;
  color: var(--ct-text-primary);
  border-radius: 6px;
}

.group-header:hover {
  background-color: var(--ct-bg-level-3);
}

.group-items {
  padding-left: 8px;
}

.session-item {
  padding: 8px 12px;
  cursor: pointer;
  border-radius: 6px;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.session-item:hover {
  background-color: var(--ct-bg-level-3);
}

.session-item.active {
  background-color: var(--ct-accent-primary);
  color: var(--ct-text-on-accent);
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-actions-row {
  display: none;
  gap: 4px;
}

.session-item:hover .session-actions-row {
  display: flex;
}

.session-actions-row :deep(.el-button) {
  color: var(--ct-text-secondary);
}

.session-actions-row :deep(.el-button:hover) {
  color: var(--ct-text-primary);
}

.session-actions-row :deep(.el-button.el-button--danger:hover) {
  color: var(--ct-accent-danger);
}

.session-title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ct-text-primary);
}

.session-meta {
  font-size: 12px;
  color: var(--ct-text-secondary);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.working-dir {
  font-family: monospace;
  font-size: 11px;
  color: var(--ct-accent-success);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-time {
  font-size: 11px;
}

.session-item.active .session-title {
  color: var(--ct-text-on-accent);
}

.session-item.active .session-meta {
  color: var(--ct-text-on-accent-secondary);
}

.session-item.active .working-dir {
  color: var(--ct-success-text-on-accent);
}

.session-item.active .session-actions-row :deep(.el-button) {
  color: var(--ct-text-on-accent-secondary);
}

.session-item.active .session-actions-row :deep(.el-button:hover) {
  color: var(--ct-text-on-accent);
}
</style>