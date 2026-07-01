<template>
  <div class="fix-chat">
    <!-- Header -->
    <header class="fix-header">
      <div class="header-left">
        <el-button text @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <span class="header-title">异常修复</span>
        <el-tag v-if="session" :type="statusTagType" size="small" class="status-tag">
          {{ statusText }}
        </el-tag>
      </div>
      <div class="header-right">
        <span v-if="session?.branchName" class="branch-name">{{ session.branchName }}</span>
        <el-button
          v-if="session?.worktreePath"
          type="primary"
          size="small"
          text
          @click="openWorktree"
        >
          <el-icon><FolderOpened /></el-icon>
          打开 worktree
        </el-button>
      </div>
    </header>

    <!-- Error alert -->
    <el-alert
      v-if="session?.errorMsg"
      :title="session.errorMsg"
      type="error"
      show-icon
      :closable="false"
      class="error-alert"
    />

    <!-- Messages -->
    <div ref="messagesRef" class="messages-container">
      <div v-if="loading" class="loading-box">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>{{ loadingText }}</span>
      </div>

      <div
        v-for="msg in messages"
        :key="msg.id"
        :class="['message-row', `message-${msg.role}`]"
      >
        <div class="message-bubble">
          <div class="message-role">
            {{ msg.role === 'user' ? '我' : msg.role === 'assistant' ? 'AI 助手' : '系统' }}
          </div>
          <pre class="message-content">{{ msg.content }}</pre>
        </div>
      </div>

      <div v-if="streamingContent" class="message-row message-assistant">
        <div class="message-bubble streaming">
          <div class="message-role">AI 助手</div>
          <pre class="message-content">{{ streamingContent }}</pre>
        </div>
      </div>
    </div>

    <!-- Input area -->
    <div class="input-area">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="2"
        :placeholder="inputPlaceholder"
        :disabled="!canFollowUp"
        resize="none"
        @keydown.enter.exact.prevent="handleSend"
      />
      <el-button
        type="primary"
        :disabled="!canFollowUp || !inputText.trim()"
        :loading="sending"
        @click="handleSend"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, FolderOpened, Loading } from '@element-plus/icons-vue'
import { fixApi } from '@/api/fix'
import type { FixSession, FixChatMessage } from '@/api/fix'

const route = useRoute()
const router = useRouter()

const session = ref<FixSession | null>(null)
const messages = ref<FixChatMessage[]>([])
const inputText = ref('')
const loading = ref(false)
const sending = ref(false)
const streamingContent = ref('')
const messagesRef = ref<HTMLDivElement | null>(null)

let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempts = 0

const sessionId = computed(() => session.value?.id ?? null)

const loadingText = computed(() => {
  if (route.query.reportId) return '正在启动修复会话...'
  return '正在加载历史记录...'
})

const statusText = computed(() => {
  const map: Record<string, string> = {
    RUNNING: '进行中',
    SUCCESS: '已完成',
    FAILED: '失败',
    PAUSED: '已暂停'
  }
  return session.value ? (map[session.value.status] ?? session.value.status) : ''
})

const statusTagType = computed(() => {
  const map: Record<string, string> = {
    RUNNING: 'warning',
    SUCCESS: 'success',
    FAILED: 'danger',
    PAUSED: 'info'
  }
  return session.value ? (map[session.value.status] ?? '') : ''
})

const canFollowUp = computed(() => {
  if (!session.value) return false
  return session.value.status === 'SUCCESS' || session.value.status === 'PAUSED'
})

const inputPlaceholder = computed(() => {
  if (!session.value) return '等待会话初始化...'
  if (session.value.status === 'RUNNING') return '修复进行中，请等待完成...'
  if (session.value.status === 'FAILED') return '修复已失败，无法继续对话'
  return '输入追问消息，按 Enter 发送...'
})

function goBack() {
  router.push('/log-analysis')
}

function openWorktree() {
  if (session.value?.worktreePath) {
    // Copy path to clipboard as a convenience
    navigator.clipboard.writeText(session.value.worktreePath)
    ElMessage.success('worktree 路径已复制到剪贴板')
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

function appendMessage(msg: FixChatMessage) {
  messages.value.push(msg)
  scrollToBottom()
}

async function loadHistory(sid: number) {
  try {
    const history = await fixApi.getHistory(sid)
    messages.value = history
    scrollToBottom()
  } catch {
    ElMessage.error('加载对话历史失败')
  }
}

async function loadSession(sid: number) {
  try {
    session.value = await fixApi.getSession(sid)
  } catch {
    ElMessage.error('加载会话信息失败')
  }
}

function connectWebSocket(sid: number) {
  disconnectWs()
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const url = `${protocol}//${host}/ws/ram-chat/${sid}`

  ws = new WebSocket(url)

  ws.onopen = () => {
    reconnectAttempts = 0
  }

  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data) as Record<string, unknown>
      handleWsMessage(data)
    } catch {
      console.error('[FixChatWS] Failed to parse message')
    }
  }

  ws.onclose = () => {
    scheduleReconnect(sid)
  }

  ws.onerror = (err) => {
    console.error('[FixChatWS] error', err)
  }
}

function handleWsMessage(data: Record<string, unknown>) {
  const type = data.type as string | undefined

  if (type === 'message') {
    // A complete message from the assistant
    streamingContent.value = ''
    appendMessage({
      id: (data.id as number) ?? Date.now(),
      role: (data.role as FixChatMessage['role']) ?? 'assistant',
      content: (data.content as string) ?? '',
      createdAt: (data.createdAt as number) ?? Date.now()
    })
  } else if (type === 'stream' || type === 'token') {
    // Streaming token
    const token = (data.content as string) ?? (data.token as string) ?? ''
    streamingContent.value += token
    scrollToBottom()
  } else if (type === 'stream_end') {
    // Stream finished — promote streaming content to a real message
    if (streamingContent.value) {
      appendMessage({
        id: (data.id as number) ?? Date.now(),
        role: 'assistant',
        content: streamingContent.value,
        createdAt: Date.now()
      })
      streamingContent.value = ''
    }
  } else if (type === 'status') {
    // Session status update
    const newStatus = data.status as FixSession['status'] | undefined
    if (newStatus && session.value) {
      session.value = { ...session.value, status: newStatus }
    }
  }
}

function disconnectWs() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (ws) {
    ws.onclose = null
    ws.close()
    ws = null
  }
}

function scheduleReconnect(sid: number) {
  if (reconnectAttempts >= 5) return
  const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 30000)
  reconnectAttempts++
  reconnectTimer = setTimeout(() => connectWebSocket(sid), delay)
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || !sessionId.value || !canFollowUp.value) return

  // Optimistic local append
  appendMessage({
    id: Date.now(),
    role: 'user',
    content: text,
    createdAt: Date.now()
  })
  inputText.value = ''
  sending.value = true

  try {
    await fixApi.followUp(sessionId.value, text)
  } catch {
    ElMessage.error('发送失败，请重试')
  } finally {
    sending.value = false
  }
}

async function initByReportId(reportId: number) {
  loading.value = true
  try {
    const sid = await fixApi.startSession(reportId)
    session.value = await fixApi.getSession(sid)
    await loadHistory(sid)
    connectWebSocket(sid)
    // Update URL to include sessionId for refresh resilience
    router.replace({ query: { sessionId: String(sid) } })
  } catch {
    ElMessage.error('启动修复会话失败')
  } finally {
    loading.value = false
  }
}

async function initBySessionId(sid: number) {
  loading.value = true
  try {
    await loadSession(sid)
    await loadHistory(sid)
    connectWebSocket(sid)
  } catch {
    ElMessage.error('加载修复会话失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const reportIdParam = route.query.reportId
  const sessionIdParam = route.query.sessionId

  if (reportIdParam) {
    initByReportId(Number(reportIdParam))
  } else if (sessionIdParam) {
    initBySessionId(Number(sessionIdParam))
  } else {
    ElMessage.warning('缺少 reportId 或 sessionId 参数')
  }
})

onUnmounted(() => {
  disconnectWs()
})
</script>

<style scoped>
.fix-chat {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px);
  overflow: hidden;
  background: #f5f7fa;
}

/* Header */
.fix-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.status-tag {
  margin-left: 4px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.branch-name {
  font-size: 13px;
  color: #606266;
  font-family: monospace;
  background: #f0f2f5;
  padding: 2px 8px;
  border-radius: 4px;
}

/* Error alert */
.error-alert {
  margin: 0;
  flex-shrink: 0;
}

/* Messages */
.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 0;
  color: #909399;
}

.message-row {
  display: flex;
}

.message-user {
  justify-content: flex-end;
}

.message-assistant,
.message-system {
  justify-content: flex-start;
}

.message-bubble {
  max-width: 80%;
  padding: 12px 16px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}

.message-user .message-bubble {
  background: #ecf5ff;
  border: 1px solid #d9ecff;
}

.message-system .message-bubble {
  background: #f4f4f5;
  border: 1px solid #e9e9eb;
}

.message-bubble.streaming {
  border-left: 3px solid #409eff;
}

.message-role {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.message-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
  font-family: inherit;
}

/* Input area */
.input-area {
  display: flex;
  gap: 12px;
  padding: 12px 20px;
  background: #fff;
  border-top: 1px solid #e5e7eb;
  flex-shrink: 0;
  align-items: flex-end;
}

.input-area .el-input {
  flex: 1;
}
</style>
