<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useRamChatStore } from '@/stores/ramChatStore'
import { useRamChatWebSocket } from '@/composables/useRamChatWebSocket'
import ChatSidebar from './chat/ChatSidebar.vue'
import ChatMessageList from './chat/ChatMessageList.vue'
import ChatInputBox from './chat/ChatInputBox.vue'
import ProjectPickerDialog from './chat/ProjectPickerDialog.vue'

const route = useRoute()
const router = useRouter()
const store = useRamChatStore()
const { connect, disconnect } = useRamChatWebSocket()

const showPicker = ref(false)

const currentSession = computed(() =>
  store.sessions.find(s => s.sessionId === store.currentSessionId)
)

const currentSessionId = computed(() => store.currentSessionId)

onMounted(async () => {
  await store.fetchSessions()
  const sid = route.params.sid as string
  if (sid) {
    await store.selectSession(sid)
    connect(sid)
  }
})

watch(() => route.params.sid, async (sid) => {
  if (sid && typeof sid === 'string') {
    disconnect()
    await store.selectSession(sid)
    connect(sid)
  }
})

async function onSessionCreated(data: { sessionId: string }) {
  showPicker.value = false
  await router.push(`/ram/chat/${data.sessionId}`)
}

async function onDeleteSession(sid: string) {
  await store.deleteSession(sid)
  if (store.sessions.length > 0) {
    await router.push(`/ram/chat/${store.sessions[0].sessionId}`)
  } else {
    await router.push('/ram/chat')
  }
}
</script>

<template>
  <div class="ram-chat-container">
    <ChatSidebar
      class="chat-sidebar"
      @create="showPicker = true"
      @delete="onDeleteSession"
    />
    <div class="chat-main">
      <header v-if="currentSession" class="chat-header">
        <h2>{{ currentSession.projectName || currentSession.projectPath }}</h2>
        <span class="text-xs text-slate-400">{{ currentSession.intent }}</span>
      </header>
      <div v-if="currentSessionId" class="chat-body">
        <ChatMessageList class="chat-messages" />
        <ChatInputBox class="chat-input" />
      </div>
      <div v-else class="chat-empty">
        <p>请从左侧选择会话，或创建新对话</p>
        <el-button type="primary" @click="showPicker = true">新建对话</el-button>
      </div>
    </div>
    <ProjectPickerDialog v-model="showPicker" @created="onSessionCreated" />
  </div>
</template>

<style scoped>
.ram-chat-container {
  display: flex;
  height: calc(100vh - 60px);
  overflow: hidden;
}
.chat-sidebar {
  width: 280px;
  border-right: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.chat-header {
  padding: 12px 20px;
  border-bottom: 1px solid #e5e7eb;
}
.chat-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.chat-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-messages {
  flex: 1;
  overflow-y: auto;
}
.chat-input {
  border-top: 1px solid #e5e7eb;
}
.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
}
</style>
