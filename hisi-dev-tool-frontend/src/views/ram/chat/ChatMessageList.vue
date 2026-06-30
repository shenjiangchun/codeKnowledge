<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRamChatStore } from '@/stores/ramChatStore'
import StepCard from './StepCard.vue'
import { Document, Loading } from '@element-plus/icons-vue'

const store = useRamChatStore()
const scrollRef = ref<HTMLElement | null>(null)

interface ToolStep {
  toolName: string
  input: string
  result: string
  status: 'done' | 'error'
}

interface Turn {
  turnId: string
  userText: string
  toolSteps: ToolStep[]
  assistantText: string
  status: 'streaming' | 'done' | 'error'
  errorMessage?: string
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.+?)`/g, '<code>$1</code>')
    .replace(/^- (.+)$/gm, '<li>$1</li>')
    .replace(/\n/g, '<br>')
}

const turns = computed<Turn[]>(() => {
  const turnMap = new Map<string, Turn>()
  for (const ev of store.events) {
    let payload: Record<string, unknown> = {}
    try { payload = JSON.parse(ev.payload) } catch { continue }
    const tid = (payload.turnId as string) || 'unknown'
    if (!turnMap.has(tid)) {
      turnMap.set(tid, { turnId: tid, userText: '', toolSteps: [], assistantText: '', status: 'streaming' })
    }
    const turn = turnMap.get(tid)!
    switch (ev.type) {
      case 'USER_MSG':
        turn.userText = (payload.text as string) || ''
        break
      case 'ASSISTANT_DELTA':
        turn.assistantText += (payload.delta as string) || ''
        break
      case 'TOOL_USE':
        turn.toolSteps.push({
          toolName: (payload.toolName as string) || '',
          input: JSON.stringify(payload.input || {}),
          result: '',
          status: 'done'
        })
        break
      case 'TOOL_RESULT':
        if (turn.toolSteps.length > 0) {
          const last = turn.toolSteps[turn.toolSteps.length - 1]
          if (last.toolName === payload.toolName) {
            last.result = (payload.result as string) || ''
          }
        }
        break
      case 'CHECKPOINT':
        turn.status = 'done'
        if (payload.finalText) turn.assistantText = payload.finalText as string
        break
      case 'ERROR':
        turn.status = 'error'
        turn.errorMessage = (payload.error as string) || 'Unknown error'
        break
    }
  }
  return Array.from(turnMap.values())
})

watch(turns, async () => {
  await nextTick()
  if (scrollRef.value) {
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  }
}, { deep: true })
</script>

<template>
  <div ref="scrollRef" class="message-list">
    <div v-for="turn in turns" :key="turn.turnId" class="turn-container">
      <!-- User message -->
      <div v-if="turn.userText" class="message-row user-row">
        <div class="message-bubble user-bubble">{{ turn.userText }}</div>
      </div>

      <!-- Tool steps -->
      <StepCard
        v-if="turn.toolSteps.length > 0"
        :turn-id="turn.turnId"
        :steps="turn.toolSteps"
        :turn-complete="turn.status === 'done'"
      />

      <!-- Assistant answer -->
      <div v-if="turn.assistantText || turn.status === 'streaming'" class="message-row assistant-row">
        <div class="message-bubble assistant-bubble">
          <div v-if="turn.status === 'streaming' && !turn.assistantText" class="thinking">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>思考中...</span>
          </div>
          <div v-else class="markdown-content" v-html="renderMarkdown(turn.assistantText)"></div>
        </div>
      </div>

      <!-- Error -->
      <div v-if="turn.status === 'error'" class="message-row error-row">
        <div class="message-bubble error-bubble">
          <el-icon><Document /></el-icon>
          {{ turn.errorMessage || '分析失败' }}
        </div>
      </div>
    </div>

    <div v-if="turns.length === 0" class="empty-state">
      <p>开始提问吧，输入你想了解的项目问题</p>
    </div>
  </div>
</template>

<style scoped>
.message-list {
  padding: 16px;
}
.turn-container {
  margin-bottom: 20px;
}
.message-row {
  display: flex;
  margin-bottom: 8px;
}
.user-row {
  justify-content: flex-end;
}
.assistant-row {
  justify-content: flex-start;
}
.message-bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.user-bubble {
  background: #409eff;
  color: white;
  border-bottom-right-radius: 4px;
}
.assistant-bubble {
  background: #f4f4f5;
  color: #303133;
  border-bottom-left-radius: 4px;
}
.error-bubble {
  background: #fef0f0;
  color: #f56c6c;
  font-size: 13px;
}
.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
}
.empty-state {
  text-align: center;
  color: #c0c4cc;
  padding: 40px;
}
.markdown-content :deep(code) {
  background: #e8e8e8;
  padding: 2px 4px;
  border-radius: 3px;
  font-size: 13px;
}
.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3) {
  margin: 8px 0 4px;
  font-size: 15px;
  font-weight: 600;
}
.markdown-content :deep(li) {
  margin-left: 16px;
  list-style: disc;
}
</style>
