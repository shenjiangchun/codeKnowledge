<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRamChatStore } from '@/stores/ramChatStore'
import StepCard from './StepCard.vue'
import MermaidBlock from './MermaidBlock.vue'
import { Document, Loading } from '@element-plus/icons-vue'
import { renderMarkdown as renderMarkdownSafe } from '@/utils/markdown'

type AssistantSegment =
  | { kind: 'markdown'; content: string }
  | { kind: 'mermaid'; content: string }

// Matches ```mermaid ... ``` fenced code blocks (case-insensitive language tag).
const MERMAID_FENCE = /```[ \t]*mermaid[ \t]*\r?\n([\s\S]*?)```/gi

function splitAssistantText(text: string): AssistantSegment[] {
  if (!text) return []
  const segments: AssistantSegment[] = []
  let cursor = 0
  MERMAID_FENCE.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = MERMAID_FENCE.exec(text)) !== null) {
    if (match.index > cursor) {
      segments.push({ kind: 'markdown', content: text.slice(cursor, match.index) })
    }
    segments.push({ kind: 'mermaid', content: match[1] })
    cursor = match.index + match[0].length
  }
  if (cursor < text.length) {
    segments.push({ kind: 'markdown', content: text.slice(cursor) })
  }
  return segments
}

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

const turns = computed<Turn[]>(() => {
  const turnMap = new Map<string, Turn>()
  for (const ev of store.events) {
    let payload: Record<string, unknown> = {}
    try { payload = JSON.parse(ev.payload) } catch { continue }
    // 跳过没有 turnId 的控制消息（如 WS "connected"），避免产生幽灵 "思考中" turn
    const tid = payload.turnId as string | undefined
    if (!tid) continue
    if (!turnMap.has(tid)) {
      turnMap.set(tid, { turnId: tid, userText: '', toolSteps: [], assistantText: '', status: 'streaming' })
    }
    const turn = turnMap.get(tid)!
    // 归一化 type 大小写：WS 推送是小写 snake_case，DB EventType.name() 是大写；统一转小写匹配
    const t = (ev.type || '').toLowerCase()
    switch (t) {
      case 'user_msg':
        turn.userText = (payload.text as string) || ''
        break
      case 'assistant_delta':
        turn.assistantText += (payload.delta as string) || ''
        break
      case 'tool_use_start':           // 后端 WS 推送的就是 tool_use_start（不是 tool_use）
        turn.toolSteps.push({
          toolName: (payload.toolName as string) || '',
          input: JSON.stringify(payload.input || {}),
          result: '',
          status: 'done'
        })
        break
      case 'tool_result':
        if (turn.toolSteps.length > 0) {
          const last = turn.toolSteps[turn.toolSteps.length - 1]
          if (last.toolName === payload.toolName) {
            last.result = (payload.result as string) || ''
          }
        }
        break
      case 'checkpoint':
        turn.status = 'done'
        if (payload.finalText) turn.assistantText = payload.finalText as string
        break
      case 'error':
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
          <template v-else>
            <template v-for="(seg, idx) in splitAssistantText(turn.assistantText)" :key="idx">
              <MermaidBlock v-if="seg.kind === 'mermaid'" :source="seg.content" />
              <div v-else class="markdown-content" v-html="renderMarkdownSafe(seg.content)"></div>
            </template>
          </template>
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
