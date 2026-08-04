<template>
  <div class="fix-chat">
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
          v-if="session?.reportId"
          size="small"
          text
          @click="startNewSession"
        >
          <el-icon><Plus /></el-icon>
          新建会话
        </el-button>
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

    <el-alert
      v-if="session?.errorMsg"
      :title="session.errorMsg"
      type="error"
      show-icon
      :closable="false"
      class="error-alert"
    />

    <div class="chat-body">
      <div v-if="loading" class="loading-box">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>{{ loadingText }}</span>
      </div>
      <ChatMessageList v-else />
    </div>

    <ChatInputBox :send-handler="handleSend" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, FolderOpened, Loading, Plus } from '@element-plus/icons-vue'
import { fixApi } from '@/api/fix'
import type { FixSession } from '@/api/fix'
import { useRamChatStore } from '@/stores/ramChatStore'
import { useRamChatWebSocket } from '@/composables/useRamChatWebSocket'
import ChatMessageList from '@/views/ram/chat/ChatMessageList.vue'
import ChatInputBox from '@/views/ram/chat/ChatInputBox.vue'

const route = useRoute()
const router = useRouter()
const store = useRamChatStore()
const { connect, disconnect } = useRamChatWebSocket()

const session = ref<FixSession | null>(null)
const loading = ref(false)

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

function goBack() {
  router.push('/log-analysis')
}

function openWorktree() {
  if (session.value?.worktreePath) {
    navigator.clipboard.writeText(session.value.worktreePath)
    ElMessage.success('worktree 路径已复制到剪贴板')
  }
}

async function handleSend(text: string) {
  if (!session.value) return
  await fixApi.followUp(session.value.id, text)
}

async function startNewSession() {
  const reportId = session.value?.reportId
  if (!reportId) {
    ElMessage.warning('当前会话缺少 reportId，无法新建')
    return
  }
  disconnect()
  session.value = null
  await router.replace({ name: 'FixChat', query: { reportId } })
  initByReportId(reportId, true)
}

async function initByReportId(reportId: string, forceNew = false) {
  loading.value = true
  try {
    let sid: string | undefined
    if (!forceNew) {
      try {
        const existing = await fixApi.listByReport(reportId)
        if (existing.length > 0) {
          sid = existing[0].id
        }
      } catch {
        // 静默，落到新建流程
      }
    }
    if (!sid) {
      sid = String(await fixApi.startSession(reportId))
    }
    await loadSession(sid)
    await bindChatChannel()
    router.replace({ name: 'FixChatSession', params: { sid } })
  } catch (e) {
    console.error('启动修复会话失败:', e)
    ElMessage.error('启动修复会话失败')
  } finally {
    loading.value = false
  }
}

async function initBySessionId(sid: string) {
  loading.value = true
  try {
    await loadSession(sid)
    await bindChatChannel()
  } catch {
    ElMessage.error('加载修复会话失败')
  } finally {
    loading.value = false
  }
}

async function loadSession(sid: string) {
  session.value = await fixApi.getSession(sid)
}

async function bindChatChannel() {
  const chatSessionId = session.value?.chatSessionId
  if (!chatSessionId) {
    ElMessage.warning('会话缺少 chatSessionId')
    return
  }
  await store.selectSession(chatSessionId)
  connect(chatSessionId)
}

onMounted(() => {
  const paramSid = route.params.sid as string | undefined
  const reportIdParam = route.query.reportId
  const sessionIdParam = route.query.sessionId

  if (paramSid) {
    initBySessionId(paramSid)
  } else if (reportIdParam) {
    initByReportId(String(reportIdParam))
  } else if (sessionIdParam) {
    initBySessionId(String(sessionIdParam))
  } else {
    ElMessage.warning('缺少 reportId 或 sessionId 参数')
  }
})

onUnmounted(() => {
  disconnect()
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

.error-alert {
  margin: 0;
  flex-shrink: 0;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
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
</style>
