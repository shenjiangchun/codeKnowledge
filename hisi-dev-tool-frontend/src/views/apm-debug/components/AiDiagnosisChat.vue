<script setup lang="ts">
import { ref, computed, nextTick, onUnmounted } from 'vue'
import { useApmStore } from '@/stores/apmStore'
import { claudeApi } from '@/api/claude'
import type { StreamCallbacks } from '@/types/session'
import { renderMarkdown } from '@/utils/markdown'

const store = useApmStore()

const isOpen = ref(false)
const isMinimized = ref(false)
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const loading = ref(false)
const chatBodyRef = ref<HTMLElement | null>(null)
const sessionId = ref('')

interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number
}

// ------------------------------------------------------------------
// Drag support — both the floating FAB and the chat panel can be dragged.
// Offsets are stored as translate(x, y) relative to each element's default
// (bottom-right) anchor, so we don't fight Element Plus' fixed positioning.
// ------------------------------------------------------------------
const DRAG_THRESHOLD_PX = 5  // distinguish drag from click

const fabOffset = ref({ x: 0, y: 0 })
const panelOffset = ref({ x: 0, y: 0 })

interface DragState {
  startX: number
  startY: number
  baseX: number
  baseY: number
  moved: boolean
  target: 'fab' | 'panel'
}
let dragState: DragState | null = null
const fabSuppressClick = ref(false)

function clampToViewport(x: number, y: number, target: 'fab' | 'panel'): { x: number; y: number } {
  // FAB anchor is bottom-right (24,24). Panel anchor is bottom-right (80,24).
  // Translate offsets are subtracted from those anchors, so positive x moves
  // it LEFT (away from right edge) and positive y moves it UP (away from bottom).
  // We need to keep the visible element inside the viewport.
  const vw = window.innerWidth
  const vh = window.innerHeight
  if (target === 'fab') {
    // FAB ~120x40 with label. Anchor bottom: 24, right: 24.
    const maxXLeft = vw - 140       // can move left up to this
    const maxYUp = vh - 60          // can move up up to this
    return {
      x: Math.max(0, Math.min(x, maxXLeft)),
      y: Math.max(0, Math.min(y, maxYUp)),
    }
  } else {
    // Panel 420x520 with bottom: 80, right: 24
    const maxXLeft = vw - 440
    const maxYUp = vh - 540
    return {
      x: Math.max(0, Math.min(x, maxXLeft)),
      y: Math.max(0, Math.min(y, maxYUp)),
    }
  }
}

function startDrag(e: MouseEvent, target: 'fab' | 'panel'): void {
  // Ignore drag init when interacting with buttons inside the header.
  if (target === 'panel') {
    const el = e.target as HTMLElement
    if (el.closest('button')) return
  }
  e.preventDefault()
  const base = target === 'fab' ? fabOffset.value : panelOffset.value
  dragState = {
    startX: e.clientX,
    startY: e.clientY,
    baseX: base.x,
    baseY: base.y,
    moved: false,
    target,
  }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}

function onDragMove(e: MouseEvent): void {
  if (!dragState) return
  // translate(x,y) — but for bottom-right anchor we want LEFT/UP positive,
  // so we INVERT mouse delta:
  const dx = dragState.startX - e.clientX
  const dy = dragState.startY - e.clientY
  if (!dragState.moved && Math.hypot(dragState.startX - e.clientX, dragState.startY - e.clientY) > DRAG_THRESHOLD_PX) {
    dragState.moved = true
  }
  const next = clampToViewport(dragState.baseX + dx, dragState.baseY + dy, dragState.target)
  if (dragState.target === 'fab') {
    fabOffset.value = next
  } else {
    panelOffset.value = next
  }
}

function onDragEnd(): void {
  if (dragState?.target === 'fab' && dragState.moved) {
    // Suppress the click that follows mouseup so the FAB doesn't toggle.
    fabSuppressClick.value = true
    setTimeout(() => { fabSuppressClick.value = false }, 0)
  }
  dragState = null
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
}

const fabStyle = computed(() => ({
  transform: `translate(-${fabOffset.value.x}px, -${fabOffset.value.y}px)`,
}))
const panelStyle = computed(() => ({
  transform: `translate(-${panelOffset.value.x}px, -${panelOffset.value.y}px)`,
}))

// Quick action prompts
const quickActions = [
  { label: '诊断错误', icon: 'Warning', prompt: 'diagnose_error' },
  { label: '解释链路', icon: 'View', prompt: 'explain_trace' },
  { label: '性能建议', icon: 'Timer', prompt: 'suggest_perf' },
]

function toggleChat(): void {
  if (fabSuppressClick.value) return
  if (isMinimized.value) {
    isMinimized.value = false
    return
  }
  isOpen.value = !isOpen.value
  if (isOpen.value && messages.value.length === 0) {
    messages.value = [{
      role: 'system',
      content: 'AI 诊断助手已就绪。可点击快捷操作或输入问题。',
      timestamp: Date.now(),
    }]
  }
}

function minimize(): void {
  isMinimized.value = true
}

function closeChat(): void {
  isOpen.value = false
  isMinimized.value = false
}

function buildDiagnosisContext(): string {
  const parts: string[] = []

  // Project info
  if (store.selectedProject) {
    parts.push(`## 项目\n${store.selectedProject.projectPath}`)
  }

  // Request info
  if (store.requestConfig.url) {
    parts.push(`## 请求\n${store.requestConfig.method} ${store.requestConfig.url}`)
    if (store.requestConfig.body) {
      parts.push(`### Body\n\`\`\`json\n${store.requestConfig.body}\n\`\`\``)
    }
  }

  // Response info
  if (store.lastResponse) {
    parts.push(`## 响应\nHTTP ${store.lastResponse.httpStatus} (${store.lastResponse.durationMs}ms)`)
    const body = store.lastResponse.responseBody
    if (body) {
      const truncated = body.length > 1000 ? body.substring(0, 1000) + '...' : body
      parts.push(`### Response Body\n\`\`\`json\n${truncated}\n\`\`\``)
    }
  }

  // Trace info
  if (store.wsSpans.length > 0) {
    parts.push(`## 调用链路 (${store.wsSpans.length} spans)`)

    // Error spans
    const errorSpans = store.wsSpans.filter(s => s.statusCode === 'ERROR')
    if (errorSpans.length > 0) {
      parts.push('### 错误 Spans')
      for (const s of errorSpans) {
        parts.push(`- ${s.operationName} (${s.durationMs}ms): ${s.statusMessage || 'ERROR'}`)
        if (s.className && s.methodName) {
          parts.push(`  类: ${s.className}.${s.methodName}()`)
        }
      }
    }

    // Top 5 slowest spans
    const sorted = [...store.wsSpans].sort((a, b) => b.durationMs - a.durationMs)
    const top5 = sorted.slice(0, 5)
    parts.push('### Top 5 慢 Spans')
    for (const s of top5) {
      const label = s.className && s.methodName
        ? `${s.className.split('.').pop()}.${s.methodName}()`
        : s.operationName
      parts.push(`- ${label}: ${s.durationMs}ms [${s.statusCode}]`)
    }
  }

  // Process console logs — capture ERROR/WARN/Exception lines + tail
  if (store.wsProcessLogs && store.wsProcessLogs.length > 0) {
    const allLines = store.wsProcessLogs
    const errorPattern = /\b(ERROR|WARN|Exception|Caused by|\sat\s+[\w.$]+\()/i
    const errorLines = allLines.filter(l => errorPattern.test(l.line))
    // Cap error lines (most recent first)
    const errorTail = errorLines.slice(-40)
    // Tail of all logs for general context
    const generalTail = allLines.slice(-30)

    parts.push(`## 进程控制台日志 (共 ${allLines.length} 行)`)
    if (errorTail.length > 0) {
      parts.push(`### 错误/警告行 (最近 ${errorTail.length} 条)`)
      parts.push('```\n' + errorTail.map(l => l.line).join('\n') + '\n```')
    }
    parts.push('### 末尾日志 (最近 30 行)')
    parts.push('```\n' + generalTail.map(l => l.line).join('\n') + '\n```')
  }

  // Process error metadata (exit code + tail lines on abnormal exit)
  if (store.wsProcessError) {
    parts.push(`## 进程异常退出`)
    if (store.wsProcessError.exitCode !== undefined) {
      parts.push(`Exit code: ${store.wsProcessError.exitCode}`)
    }
    if (store.wsProcessError.tailLines && store.wsProcessError.tailLines.length > 0) {
      parts.push('```\n' + store.wsProcessError.tailLines.join('\n') + '\n```')
    }
  }

  // Selected span
  if (store.selectedSpan) {
    parts.push(`## 当前选中 Span\n${store.selectedSpan.operationName} (${store.selectedSpan.durationMs}ms, ${store.selectedSpan.statusCode})`)
    if (store.selectedSpan.statusMessage) {
      parts.push(`错误信息: ${store.selectedSpan.statusMessage}`)
    }
    if (store.selectedSpan.attributes) {
      parts.push(`Attributes: ${JSON.stringify(store.selectedSpan.attributes, null, 2)}`)
    }
  }

  return parts.join('\n\n')
}

function buildQuickActionPrompt(actionKey: string): string {
  const context = buildDiagnosisContext()
  switch (actionKey) {
    case 'diagnose_error':
      return `请诊断以下 APM 调试会话中的错误，分析根本原因并给出修复建议：\n\n${context}`
    case 'explain_trace':
      return `请解释以下调用链路，分析每个关键 Span 的作用和调用关系：\n\n${context}`
    case 'suggest_perf':
      return `请分析以下调用链路的性能瓶颈，提出优化建议：\n\n${context}`
    default:
      return context
  }
}

async function handleQuickAction(actionKey: string): Promise<void> {
  const prompt = buildQuickActionPrompt(actionKey)
  await sendMessage(prompt)
}

async function handleSend(): Promise<void> {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  // Prepend context if this is a question that benefits from it
  const context = buildDiagnosisContext()
  const fullPrompt = context
    ? `${text}\n\n---\n\n以下是当前 APM 调试会话的上下文信息：\n\n${context}`
    : text

  inputText.value = ''
  await sendMessage(fullPrompt, text) // Show only user's text in chat
}

async function sendMessage(prompt: string, displayText?: string): Promise<void> {
  if (loading.value) return

  // Add user message
  messages.value = [
    ...messages.value,
    {
      role: 'user',
      content: displayText || prompt.substring(0, 200) + (prompt.length > 200 ? '...' : ''),
      timestamp: Date.now(),
    },
  ]

  // Add assistant placeholder
  messages.value = [
    ...messages.value,
    {
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    },
  ]

  loading.value = true
  scrollToBottom()

  const callbacks: StreamCallbacks = {
    onSession: (sid: string) => {
      sessionId.value = sid
    },
    onOutput: (content: string) => {
      // Append to last message (immutable update)
      const lastIdx = messages.value.length - 1
      messages.value = messages.value.map((m, i) =>
        i === lastIdx ? { ...m, content: m.content + content } : m
      )
      scrollToBottom()
    },
    onDone: () => {
      loading.value = false
      scrollToBottom()
    },
    onError: (error: string) => {
      // Update last message with error
      const lastIdx = messages.value.length - 1
      messages.value = messages.value.map((m, i) =>
        i === lastIdx ? { ...m, content: m.content || `错误: ${error}`, role: 'system' as const } : m
      )
      loading.value = false
    },
  }

  try {
    await claudeApi.universalChat(
      {
        sessionId: sessionId.value || undefined,
        prompt,
        scene: 'APM_DIAGNOSIS',
        metadata: {
          projectPath: store.selectedProject?.projectPath,
          requestUrl: store.requestConfig.url,
          spanCount: store.wsSpans.length,
        },
      },
      callbacks,
    )
  } catch {
    loading.value = false
  }
}

function scrollToBottom(): void {
  nextTick(() => {
    if (chatBodyRef.value) {
      chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
    }
  })
}

function clearChat(): void {
  messages.value = [{
    role: 'system',
    content: 'AI 诊断助手已就绪。可点击快捷操作或输入问题。',
    timestamp: Date.now(),
  }]
  sessionId.value = ''
}

const hasErrors = computed(() =>
  store.wsSpans.some(s => s.statusCode === 'ERROR')
)

onUnmounted(() => {
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
})
</script>

<template>
  <!-- Floating action button -->
  <div
    class="ai-diagnosis-fab"
    :style="fabStyle"
    @mousedown="startDrag($event, 'fab')"
    @click="toggleChat"
  >
    <el-badge :is-dot="hasErrors" :hidden="!hasErrors">
      <el-button
        type="primary"
        circle
        size="large"
        :class="{ 'fab-active': isOpen }"
      >
        <el-icon :size="20"><ChatDotRound /></el-icon>
      </el-button>
    </el-badge>
    <span v-if="!isOpen" class="fab-label">AI 诊断</span>
  </div>

  <!-- Chat panel -->
  <Teleport to="body">
    <transition name="chat-slide">
      <div
        v-if="isOpen && !isMinimized"
        class="ai-chat-panel"
        :style="panelStyle"
      >
        <!-- Header (drag handle) -->
        <div class="chat-header" @mousedown="startDrag($event, 'panel')">
          <div class="chat-header-left">
            <el-icon :size="16"><ChatDotRound /></el-icon>
            <span class="chat-title">AI 诊断助手</span>
            <el-tag v-if="sessionId" size="small" type="info" effect="plain">会话中</el-tag>
          </div>
          <div class="chat-header-actions">
            <el-button text size="small" @click="clearChat" title="清空对话">
              <el-icon><Delete /></el-icon>
            </el-button>
            <el-button text size="small" @click="minimize" title="最小化">
              <el-icon><Minus /></el-icon>
            </el-button>
            <el-button text size="small" @click="closeChat" title="关闭">
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
        </div>

        <!-- Quick actions -->
        <div class="quick-actions">
          <el-button
            v-for="action in quickActions"
            :key="action.prompt"
            size="small"
            round
            :disabled="loading"
            @click="handleQuickAction(action.prompt)"
          >
            {{ action.label }}
          </el-button>
        </div>

        <!-- Messages -->
        <div ref="chatBodyRef" class="chat-body">
          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            class="chat-message"
            :class="msg.role"
          >
            <div class="message-bubble">
              <div v-if="msg.role === 'system'" class="system-message">
                {{ msg.content }}
              </div>
              <div v-else class="message-content" v-html="renderMarkdown(msg.content)" />
            </div>
          </div>

          <!-- Loading indicator -->
          <div v-if="loading" class="chat-message assistant">
            <div class="message-bubble">
              <div class="typing-indicator">
                <span /><span /><span />
              </div>
            </div>
          </div>
        </div>

        <!-- Input -->
        <div class="chat-input">
          <el-input
            v-model="inputText"
            placeholder="输入问题..."
            :disabled="loading"
            @keyup.enter="handleSend"
          >
            <template #append>
              <el-button
                :disabled="!inputText.trim() || loading"
                @click="handleSend"
              >
                <el-icon><Promotion /></el-icon>
              </el-button>
            </template>
          </el-input>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
/* FAB */
.ai-diagnosis-fab {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 9000;
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: grab;
  user-select: none;
}

.ai-diagnosis-fab:active {
  cursor: grabbing;
}

.fab-label {
  font-size: 12px;
  color: var(--el-color-primary);
  font-weight: 500;
  white-space: nowrap;
  background: var(--el-bg-color);
  padding: 4px 8px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.fab-active {
  transform: rotate(180deg);
  transition: transform 0.3s;
}

/* Chat panel */
.ai-chat-panel {
  position: fixed;
  bottom: 80px;
  right: 24px;
  width: 420px;
  height: 520px;
  background: var(--el-bg-color);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 9001;
  border: 1px solid var(--el-border-color-lighter);
}

/* Header */
.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  background: var(--el-color-primary);
  color: #fff;
  cursor: move;
  user-select: none;
}

.chat-header-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.chat-title {
  font-size: 13px;
  font-weight: 600;
}

.chat-header-actions {
  display: flex;
  gap: 2px;
}

.chat-header-actions .el-button {
  color: rgba(255, 255, 255, 0.8);
}

.chat-header-actions .el-button:hover {
  color: #fff;
}

/* Quick actions */
.quick-actions {
  display: flex;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-wrap: wrap;
}

/* Chat body */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chat-message {
  display: flex;
}

.chat-message.user {
  justify-content: flex-end;
}

.chat-message.assistant,
.chat-message.system {
  justify-content: flex-start;
}

.message-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.chat-message.user .message-bubble {
  background: var(--el-color-primary);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.chat-message.assistant .message-bubble {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  border-bottom-left-radius: 4px;
}

.chat-message.system .message-bubble {
  background: transparent;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  text-align: center;
  max-width: 100%;
}

.system-message {
  font-style: italic;
}

.message-content :deep(.code-block) {
  background: var(--el-fill-color-darker);
  padding: 8px;
  border-radius: 6px;
  font-size: 12px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  overflow-x: auto;
  margin: 4px 0;
  white-space: pre-wrap;
}

.message-content :deep(.inline-code) {
  background: var(--el-fill-color);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
}

/* Typing indicator */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.typing-indicator span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  animation: typing 1.4s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typing {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

/* Input */
.chat-input {
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

/* Transitions */
.chat-slide-enter-active,
.chat-slide-leave-active {
  transition: all 0.3s ease;
}

.chat-slide-enter-from,
.chat-slide-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
}
</style>
