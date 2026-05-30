<template>
  <div class="claude-session">
    <!-- 左侧会话列表 -->
    <div class="session-list-panel" :class="{ collapsed: listCollapsed }">
      <div class="panel-header">
        <span v-if="!listCollapsed">会话列表</span>
        <el-button text @click="listCollapsed = !listCollapsed">
          <el-icon><component :is="listCollapsed ? 'DArrowRight' : 'DArrowLeft'" /></el-icon>
        </el-button>
      </div>

      <template v-if="!listCollapsed">
        <div class="session-actions">
          <el-input
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
              <span>进行中 ({{ sessionStore.activeSessions.length }})</span>
            </div>
            <div v-show="expandedGroups.active" class="group-items">
              <div
                v-for="session in filteredActiveSessions"
                :key="session.id"
                class="session-item"
                :class="{ active: sessionStore.currentSessionId === session.id }"
                @click="selectSession(session.id)"
              >
                <div class="session-title">{{ session.title || getDefaultTitle(session) }}</div>
                <div class="session-meta">{{ formatDate(session.createdAt) }}</div>
              </div>
            </div>
          </div>

          <div class="session-group">
            <div class="group-header" @click="toggleGroup('archived')">
              <el-icon><FolderOpened v-if="expandedGroups.archived" /><Folder v-else /></el-icon>
              <span>已归档 ({{ sessionStore.archivedSessions.length }})</span>
            </div>
            <div v-show="expandedGroups.archived" class="group-items">
              <div
                v-for="session in filteredArchivedSessions"
                :key="session.id"
                class="session-item"
                :class="{ active: sessionStore.currentSessionId === session.id }"
                @click="selectSession(session.id)"
              >
                <div class="session-title">{{ session.title || getDefaultTitle(session) }}</div>
                <div class="session-meta">{{ formatDate(session.createdAt) }}</div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 右侧对话面板 -->
    <div class="chat-panel">
      <template v-if="sessionStore.currentSession">
        <!-- 会话标题栏 -->
        <div class="chat-header">
          <div class="session-info">
            <el-input
              v-if="editingTitle"
              v-model="newTitle"
              size="small"
              @blur="saveTitle"
              @keyup.enter="saveTitle"
            />
            <h3 v-else @click="startEditTitle">
              {{ sessionStore.currentSession.title || getDefaultTitle(sessionStore.currentSession) }}
              <el-icon class="edit-icon"><Edit /></el-icon>
            </h3>
            <el-tag size="small">{{ SCENE_NAMES[sessionStore.currentSession.scene] }}</el-tag>
          </div>
          <div class="session-actions">
            <el-button-group>
              <el-button size="small" @click="handleExport('markdown')">导出</el-button>
              <el-button size="small" @click="handleArchive">归档</el-button>
              <el-button size="small" type="danger" @click="handleDelete">删除</el-button>
            </el-button-group>
          </div>
        </div>

        <!-- 会话基本信息 -->
        <div class="session-meta-bar">
          <div class="meta-item">
            <span class="meta-label">Session ID:</span>
            <span class="meta-value" @click="copyToClipboard(sessionStore.currentSessionId ?? undefined)">
              {{ sessionStore.currentSessionId }}
              <el-icon class="copy-icon"><DocumentCopy /></el-icon>
            </span>
          </div>
          <div class="meta-item" v-if="sessionStore.currentSession.workingDirectory">
            <span class="meta-label">工作目录:</span>
            <span class="meta-value" @click="copyToClipboard(sessionStore.currentSession.workingDirectory)">
              {{ sessionStore.currentSession.workingDirectory }}
              <el-icon class="copy-icon"><DocumentCopy /></el-icon>
            </span>
          </div>
          <div class="meta-item">
            <span class="meta-label">创建时间:</span>
            <span class="meta-value">{{ formatDateTime(sessionStore.currentSession.createdAt) }}</span>
          </div>
        </div>

        <!-- 消息列表 -->
        <div class="message-list" ref="messageListRef">
          <div
            v-for="message in sessionStore.messages"
            :key="message.id"
            class="message-item"
            :class="message.role"
          >
            <div class="message-avatar">
              {{ message.role === 'user' ? '👤' : '🤖' }}
            </div>
            <div class="message-content">
              <div class="message-text" v-html="renderMarkdown(message.content)"></div>
            </div>
          </div>
          <div v-if="streaming" class="message-item assistant">
            <div class="message-avatar">🤖</div>
            <div class="message-content">
              <div class="message-text" v-html="renderMarkdown(streamingContent)"></div>
              <span class="streaming-cursor">▋</span>
            </div>
          </div>
        </div>

        <!-- 输入区域 -->
        <div class="input-area">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="3"
            placeholder="输入消息..."
            @keydown.enter.ctrl="sendMessage"
          />
          <div class="input-actions">
            <el-button type="primary" :loading="streaming" @click="sendMessage">
              发送
            </el-button>
          </div>
        </div>
      </template>

      <!-- 空状态 -->
      <div v-else class="empty-state">
        <el-empty description="选择一个会话或点击左侧「新建会话」开始" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Folder, FolderOpened, Edit, Plus, DocumentCopy } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/sessionStore'
import { claudeApi } from '@/api/claude'
import { useAppStore } from '@/stores/app'
import { usePromptStore } from '@/stores/promptStore'
import type { Session } from '@/types/session'
import { SCENE_NAMES } from '@/types/session'

const route = useRoute()
const sessionStore = useSessionStore()
const appStore = useAppStore()
const promptStore = usePromptStore()

const projectName = computed(() => appStore.selectedProject || '')

// 状态
const listCollapsed = ref(false)
const searchKeyword = ref('')
const expandedGroups = ref({ active: true, archived: false })
const editingTitle = ref(false)
const newTitle = ref('')
const inputMessage = ref('')
const messageListRef = ref<HTMLElement | null>(null)

// 计算当前会话的流式状态
const streaming = computed(() => {
  if (!sessionStore.currentSessionId) return false
  return sessionStore.isSessionStreaming(sessionStore.currentSessionId)
})

// 计算当前会话的流式内容
const streamingContent = computed(() => {
  if (!sessionStore.currentSessionId) return ''
  return sessionStore.getStreamingContent(sessionStore.currentSessionId)
})

// 监听流式内容变化，自动滚动到底部
watch(streamingContent, () => {
  scrollToBottom()
})

// 计算属性
const filteredActiveSessions = computed(() => {
  const keyword = searchKeyword.value.toLowerCase()
  return sessionStore.activeSessions.filter(s =>
    (s.title || '').toLowerCase().includes(keyword) ||
    s.scene.toLowerCase().includes(keyword)
  )
})

const filteredArchivedSessions = computed(() => {
  const keyword = searchKeyword.value.toLowerCase()
  return sessionStore.archivedSessions.filter(s =>
    (s.title || '').toLowerCase().includes(keyword) ||
    s.scene.toLowerCase().includes(keyword)
  )
})

// 方法
function toggleGroup(group: 'active' | 'archived') {
  expandedGroups.value[group] = !expandedGroups.value[group]
}

async function selectSession(sessionId: string) {
  // 使用 switchSession 来关闭当前会话并切换到新会话
  await sessionStore.switchSession(sessionId)
}

function getDefaultTitle(session: Session): string {
  return `${SCENE_NAMES[session.scene]} - ${formatDate(session.createdAt)}`
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatDateTime(dateStr: string): string {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

async function copyToClipboard(text: string | undefined) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

function startEditTitle() {
  newTitle.value = sessionStore.currentSession?.title || ''
  editingTitle.value = true
}

async function saveTitle() {
  if (sessionStore.currentSessionId && newTitle.value.trim()) {
    await sessionStore.updateSessionTitle(sessionStore.currentSessionId, newTitle.value.trim())
  }
  editingTitle.value = false
}

async function sendMessage() {
  if (!inputMessage.value.trim() || !sessionStore.currentSessionId) return

  const message = inputMessage.value.trim()
  const currentSessionId = sessionStore.currentSessionId // 保存当前会话 ID

  inputMessage.value = ''

  // 先添加用户消息
  sessionStore.addMessageToSession(currentSessionId, {
    id: Date.now(),
    sessionId: currentSessionId,
    role: 'user',
    content: message,
    createdAt: new Date().toISOString()
  })

  // 设置正在流式传输的会话（会初始化该会话的流式状态）
  sessionStore.setStreamingSession(currentSessionId)

  try {
    await claudeApi.universalChat(
      {
        sessionId: currentSessionId,
        prompt: message,
        workingDirectory: appStore.projectDir  // 传递工作目录
      },
      {
        onOutput: (content) => {
          // 追加流式内容到指定会话
          sessionStore.appendStreamingContent(currentSessionId, content)
        },
        onDone: () => {
          // 获取该会话的流式内容
          const fullContent = sessionStore.getStreamingContent(currentSessionId)
          // 添加助手消息到正确的会话
          sessionStore.addMessageToSession(currentSessionId, {
            id: Date.now() + 1,
            sessionId: currentSessionId,
            role: 'assistant',
            content: fullContent,
            createdAt: new Date().toISOString()
          })
          // 清除该会话的流式状态
          sessionStore.clearStreamingContent(currentSessionId)
        },
        onError: (error) => {
          sessionStore.clearStreamingContent(currentSessionId)
          ElMessage.error(`发送失败: ${error}`)
        }
      }
    )
  } catch (error) {
    sessionStore.clearStreamingContent(currentSessionId)
    ElMessage.error('发送失败')
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
}

function renderMarkdown(content: string): string {
  // 简单的 Markdown 渲染，实际项目中应使用 marked 或 markdown-it
  return content
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code class="language-$1">$2</code></pre>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/\n/g, '<br>')
}

async function handleExport(format: 'markdown' | 'json') {
  if (sessionStore.currentSessionId) {
    await sessionStore.exportSession(sessionStore.currentSessionId, format)
  }
}

async function handleArchive() {
  if (!sessionStore.currentSessionId) return

  try {
    await ElMessageBox.confirm('确定要归档此会话吗？', '提示', {
      type: 'warning'
    })
    // 先关闭会话保存会话码
    await sessionStore.closeSession(sessionStore.currentSessionId)
    await sessionStore.archiveSession(sessionStore.currentSessionId)
    ElMessage.success('归档成功')
  } catch {}
}

async function handleDelete() {
  if (!sessionStore.currentSessionId) return

  try {
    await ElMessageBox.confirm('确定要删除此会话吗？此操作不可恢复。', '警告', {
      type: 'error'
    })
    // 先关闭会话
    await sessionStore.closeSession(sessionStore.currentSessionId)
    await sessionStore.deleteSession(sessionStore.currentSessionId)
    ElMessage.success('删除成功')
  } catch {}
}

// Handle new session creation
async function handleNewSession() {
  if (!appStore.projectSelected) {
    ElMessage.warning('请先选择一个项目')
    return
  }

  try {
    await promptStore.loadTemplates()
    const prompt = promptStore.render('free-chat', {
      projectName: projectName.value,
      projectPath: appStore.projectDir
    }) || `你好，我正在分析项目 ${projectName.value}，请问我有什么可以帮助你的？`

    let newSessionId = ''

    await claudeApi.universalChat(
      {
        prompt,
        scene: 'free-chat',
        metadata: {
          projectName: projectName.value
        },
        workingDirectory: appStore.projectDir  // 传递工作目录
      },
      {
        onSession: async (sessionId) => {
          // 收到 sessionId 后立即刷新列表并切换
          newSessionId = sessionId
          // 设置流式状态
          sessionStore.setStreamingSession(sessionId)
          await sessionStore.loadSessions()
          sessionStore.setCurrentSession(sessionId)
          ElMessage.success('已创建新会话')
        },
        onOutput: (content) => {
          if (newSessionId) {
            sessionStore.appendStreamingContent(newSessionId, content)
          }
        },
        onDone: () => {
          if (newSessionId) {
            const fullContent = sessionStore.getStreamingContent(newSessionId)
            sessionStore.addMessageToSession(newSessionId, {
              id: Date.now(),
              sessionId: newSessionId,
              role: 'assistant',
              content: fullContent,
              createdAt: new Date().toISOString()
            })
            sessionStore.clearStreamingContent(newSessionId)
          }
        },
        onError: (error) => {
          if (newSessionId) {
            sessionStore.clearStreamingContent(newSessionId)
          }
          ElMessage.error(`创建会话失败: ${error}`)
        }
      }
    )

    // 如果 onSession 没被调用，兜底处理
    if (!newSessionId) {
      await sessionStore.loadSessions()
    }
  } catch (error) {
    ElMessage.error('创建会话失败')
  }
}

// 监听路由参数
watch(
  () => route.query.sessionId,
  (sessionId) => {
    if (sessionId) {
      sessionStore.setCurrentSession(sessionId as string)
    }
  },
  { immediate: true }
)

// 初始化
onMounted(() => {
  sessionStore.loadSessions()
})
</script>

<style scoped>
.claude-session {
  display: flex;
  height: 100%;
  background: #f5f7fa;
}

.session-list-panel {
  width: 280px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  transition: width 0.3s;
}

.session-list-panel.collapsed {
  width: 50px;
}

.panel-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
}

.session-actions {
  padding: 12px 16px;
}

.session-groups {
  flex: 1;
  overflow-y: auto;
}

.session-group {
  margin-bottom: 8px;
}

.group-header {
  padding: 12px 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
  color: #606266;
}

.group-header:hover {
  background: #f5f7fa;
}

.group-items {
  padding: 0 8px;
}

.session-item {
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
}

.session-item:hover {
  background: #ecf5ff;
}

.session-item.active {
  background: #409eff;
  color: #fff;
}

.session-item.active .session-meta {
  color: rgba(255, 255, 255, 0.8);
}

.session-title {
  font-size: 14px;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  font-size: 12px;
  color: #909399;
}

.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-header {
  padding: 16px 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.session-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.session-info h3 {
  margin: 0;
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
}

.edit-icon {
  font-size: 14px;
  color: #909399;
  opacity: 0;
  transition: opacity 0.2s;
}

.session-info h3:hover .edit-icon {
  opacity: 1;
}

.session-meta-bar {
  padding: 12px 24px;
  background: #f9fafc;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.meta-label {
  font-size: 12px;
  color: #909399;
}

.meta-value {
  font-size: 12px;
  color: #606266;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border-radius: 4px;
  transition: background 0.2s;
}

.meta-value:hover {
  background: #ecf5ff;
  color: #409eff;
}

.copy-icon {
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.2s;
}

.meta-value:hover .copy-icon {
  opacity: 1;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.message-item {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #f0f2f5;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.message-content {
  max-width: 70%;
  background: #fff;
  padding: 16px;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

.message-item.user .message-content {
  background: #409eff;
  color: #fff;
}

.message-text {
  line-height: 1.6;
  white-space: pre-wrap;
  word-wrap: break-word;
  word-break: break-word;
}

.message-text :deep(pre) {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
}

.message-item.user .message-text :deep(pre) {
  background: rgba(255, 255, 255, 0.2);
}

.streaming-cursor {
  animation: blink 1s infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.input-area {
  padding: 16px 24px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.input-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
