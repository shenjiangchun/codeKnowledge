<script setup lang="ts">
/**
 * FollowupPanel — 报告追问对话面板
 *
 * 在日志分析报告下方展示，用户可以输入后续问题，
 * 通过 WebSocket 实时接收 Claude 的回答（含工具调用展示）。
 */
import { ref, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Loading } from '@element-plus/icons-vue'
import { logAnalysisApi } from '@/api/logAnalysis'
import { useLogFollowupWebSocket } from '@/composables/useLogFollowupWebSocket'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  reportId: string
}>()

const inputText = ref('')
const sending = ref(false)
const followupSessionId = ref('')
const messagesContainer = ref<HTMLElement | null>(null)

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  toolCalls?: Array<{ name: string; input?: unknown; result?: unknown }>
}

const messages = ref<ChatMessage[]>([])

const { events, connected, assistantText, connect, resetText } =
  useLogFollowupWebSocket(() => followupSessionId.value)

// Process incoming WebSocket events
watch(events, (newEvents) => {
  const lastEvent = newEvents[newEvents.length - 1]
  if (!lastEvent) return

  if (lastEvent.type === 'tool_use') {
    // Find or create the current assistant message and add tool call
    const lastMsg = messages.value[messages.value.length - 1]
    if (lastMsg?.role === 'assistant') {
      if (!lastMsg.toolCalls) lastMsg.toolCalls = []
      lastMsg.toolCalls.push({ name: lastEvent.toolName || '', input: lastEvent.input })
    }
  }

  if (lastEvent.type === 'tool_result') {
    const lastMsg = messages.value[messages.value.length - 1]
    if (lastMsg?.role === 'assistant' && lastMsg.toolCalls) {
      const tc = lastMsg.toolCalls.find(t => t.name === lastEvent.toolName)
      if (tc) tc.result = lastEvent.result
    }
  }

  if (lastEvent.type === 'turn_complete') {
    // Finalize the assistant message
    const lastMsg = messages.value[messages.value.length - 1]
    if (lastMsg?.role === 'assistant') {
      lastMsg.content = lastEvent.text || assistantText.value
    }
    sending.value = false
  }

  if (lastEvent.type === 'error') {
    ElMessage.error('追问失败: ' + (lastEvent.error || '未知错误'))
    sending.value = false
  }

  scrollToBottom()
}, { deep: true })

// Update streaming text
watch(assistantText, (text) => {
  if (!text) return
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg?.role === 'assistant') {
    lastMsg.content = text
  }
  scrollToBottom()
})

async function sendMessage(): Promise<void> {
  const text = inputText.value.trim()
  if (!text || sending.value) return

  // Add user message
  messages.value = [...messages.value, { role: 'user', content: text }]
  inputText.value = ''
  sending.value = true

  // Add placeholder assistant message for streaming
  messages.value = [...messages.value, { role: 'assistant', content: '' }]
  resetText()

  try {
    if (!followupSessionId.value) {
      // First message: start follow-up session
      const resp = await logAnalysisApi.startFollowup(props.reportId, text)
      followupSessionId.value = resp.sessionId
      connect()
    } else {
      // Continue existing session
      await logAnalysisApi.continueFollowup(followupSessionId.value, text)
    }
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '发送失败'
    ElMessage.error(msg)
    sending.value = false
    // Remove placeholder
    messages.value = messages.value.slice(0, -1)
  }

  scrollToBottom()
}

function scrollToBottom(): void {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}
</script>

<template>
  <div class="followup-panel">
    <div class="panel-header">
      <el-icon><ChatDotRound /></el-icon>
      <span class="panel-title">追问分析</span>
      <el-tag v-if="connected" type="success" size="small">已连接</el-tag>
    </div>

    <div ref="messagesContainer" class="messages-container">
      <div v-if="messages.length === 0" class="empty-hint">
        对报告有疑问？输入问题继续深入分析
      </div>

      <div v-for="(msg, idx) in messages" :key="idx" class="message" :class="[`role-${msg.role}`]">
        <div class="message-role">{{ msg.role === 'user' ? '你' : 'AI' }}</div>
        <div class="message-body">
          <!-- Tool calls -->
          <div v-if="msg.toolCalls?.length" class="tool-calls">
            <div v-for="(tc, tcIdx) in msg.toolCalls" :key="tcIdx" class="tool-call">
              <el-tag size="small" type="info">🔧 {{ tc.name }}</el-tag>
            </div>
          </div>
          <!-- Content -->
          <div v-if="msg.content" class="message-content" v-html="renderMarkdown(msg.content)"></div>
          <!-- Streaming indicator -->
          <el-icon v-if="msg.role === 'assistant' && sending && idx === messages.length - 1 && !msg.content" class="is-loading">
            <Loading />
          </el-icon>
        </div>
      </div>
    </div>

    <div class="input-area">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="2"
        placeholder="输入追问，如：这个 NPE 的完整调用链是什么？"
        :disabled="sending"
        @keyup.ctrl.enter="sendMessage"
      />
      <el-button
        type="primary"
        :loading="sending"
        :disabled="!inputText.trim()"
        @click="sendMessage"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.followup-panel {
  margin-top: 16px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
}
.panel-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
  flex: 1;
}
.messages-container {
  max-height: 400px;
  overflow-y: auto;
  padding: 16px;
}
.empty-hint {
  text-align: center;
  color: #909399;
  padding: 24px;
  font-size: 13px;
}
.message {
  margin-bottom: 16px;
}
.message-role {
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  margin-bottom: 4px;
}
.role-user .message-role { color: #409eff; }
.role-assistant .message-role { color: #67c23a; }
.message-body {
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
}
.role-user .message-body {
  background: #ecf5ff;
  border: 1px solid #d9ecff;
}
.role-assistant .message-body {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
}
.tool-calls {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}
.message-content {
  word-break: break-word;
}
.message-content :deep(pre) {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 8px 0;
}
.message-content :deep(code) {
  background: #f5f7fa;
  padding: 2px 4px;
  border-radius: 3px;
  font-size: 13px;
}
.message-content :deep(pre code) {
  background: transparent;
  padding: 0;
}
.input-area {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
  background: #fafafa;
  align-items: flex-end;
}
.input-area .el-input {
  flex: 1;
}
</style>
