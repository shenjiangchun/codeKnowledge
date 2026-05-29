<template>
  <div class="dialog-progress-panel">
    <!-- 连接状态 -->
    <div class="connection-indicator">
      <el-tag :type="connectionTagType" size="small">
        {{ connectionStatusText }}
      </el-tag>
      <span v-if="sessionId" class="session-id">Session: {{ sessionId.slice(0, 8) }}</span>
    </div>

    <!-- 意图识别结果 -->
    <div v-if="currentIntent" class="intent-section">
      <div class="intent-header">
        <el-icon><Search /></el-icon>
        <span>意图识别</span>
        <el-tag :type="getConfidenceTagType(currentIntent.confidence)" size="small">
          置信度: {{ (currentIntent.confidence * 100).toFixed(0) }}%
        </el-tag>
      </div>
      <div class="intent-content">
        <span class="intent-type">{{ getIntentDisplayName(currentIntent.intent) }}</span>
        <div v-if="currentIntent.entities" class="intent-entities">
          <span v-if="currentIntent.entities.errorType" class="entity-item">
            错误类型: {{ currentIntent.entities.errorType }}
          </span>
          <span v-if="currentIntent.entities.focusArea" class="entity-item">
            关注区域: {{ currentIntent.entities.focusArea }}
          </span>
          <span v-if="currentIntent.entities.className" class="entity-item">
            类名: {{ currentIntent.entities.className }}
          </span>
        </div>
      </div>
    </div>

    <!-- 总体进度 -->
    <div v-if="isRunning || phases.length > 0" class="overall-progress">
      <div class="progress-header">
        <span>总体进度</span>
        <span class="progress-value">{{ overallProgress }}%</span>
      </div>
      <el-progress
        :percentage="overallProgress"
        :status="getProgressStatus()"
        :stroke-width="10"
      />
    </div>

    <!-- 当前阶段 -->
    <div v-if="currentPhase" class="current-phase">
      <div class="phase-indicator">
        <el-icon :class="getPhaseIconClass(currentPhase.status)">
          <component :is="getPhaseIcon(currentPhase.status)" />
        </el-icon>
        <span class="phase-name">{{ currentPhase.name }}</span>
        <el-tag :type="getPhaseTagType(currentPhase.status)" size="small">
          {{ getPhaseStatusText(currentPhase.status) }}
        </el-tag>
      </div>
      <div v-if="currentPhase.description" class="phase-description">
        {{ currentPhase.description }}
      </div>
      <div v-if="currentPhase.status === 'running'" class="phase-progress">
        <el-progress :percentage="currentPhase.progress" :show-text="false" />
      </div>
    </div>

    <!-- 阶段列表 -->
    <div v-if="phases.length > 1" class="phase-list">
      <div class="phase-list-header">执行阶段</div>
      <div class="phase-timeline">
        <div
          v-for="(phase, index) in phases"
          :key="phase.phaseId"
          class="phase-timeline-item"
          :class="{ active: phase.phaseId === currentPhase?.phaseId }"
        >
          <div class="timeline-dot" :class="getTimelineDotClass(phase.status)"></div>
          <div class="timeline-content">
            <span class="timeline-name">{{ phase.name }}</span>
            <span class="timeline-status">{{ phase.progress }}%</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Agent执行状态 -->
    <div v-if="agentStates && Object.keys(agentStates).length > 0" class="agent-section">
      <div class="agent-header">
        <el-icon><Cpu /></el-icon>
        <span>Agent 执行状态</span>
      </div>
      <div class="agent-grid">
        <div
          v-for="(agent, agentId) in agentStates"
          :key="agentId"
          class="agent-card"
          :class="{ active: agent.status === 'running' }"
        >
          <div class="agent-header-row">
            <span class="agent-name">{{ agent.agentName }}</span>
            <el-tag :type="getAgentTagType(agent.status)" size="small">
              {{ getAgentStatusText(agent.status) }}
            </el-tag>
          </div>
          <div v-if="agent.status === 'running'" class="agent-progress">
            <el-progress :percentage="agent.progress" :show-text="false" />
          </div>
          <div v-if="agent.confidence !== undefined" class="agent-confidence">
            置信度: {{ (agent.confidence * 100).toFixed(0) }}%
          </div>
          <div v-if="agent.output" class="agent-output-preview">
            {{ agent.output.slice(0, 50) }}...
          </div>
        </div>
      </div>
    </div>

    <!-- 流式输出 -->
    <div v-if="isStreaming || streamingContent" class="stream-output">
      <div class="stream-header">
        <el-icon><Document /></el-icon>
        <span>实时输出</span>
        <div v-if="isStreaming" class="stream-indicator">
          <span class="pulse"></span>
          <span>正在生成...</span>
        </div>
      </div>
      <div class="stream-content" v-html="renderedStreamingContent"></div>
    </div>

    <!-- 事件日志 -->
    <div v-if="eventLog.length > 0" class="event-log-section">
      <div class="log-header">
        <span>事件日志</span>
        <el-button size="small" text @click="clearEventLog">
          <el-icon><Delete /></el-icon>
          清空
        </el-button>
      </div>
      <div class="log-content">
        <div
          v-for="(event, index) in recentEvents"
          :key="index"
          class="log-item"
          :class="getEventClass(event.type)"
        >
          <span class="log-time">{{ formatTime(event.timestamp) }}</span>
          <span class="log-type">{{ getEventTypeName(event.type) }}</span>
          <span class="log-message">{{ getEventMessage(event) }}</span>
        </div>
      </div>
    </div>

    <!-- 错误显示 -->
    <div v-if="error" class="error-section">
      <el-alert :title="error" type="error" show-icon :closable="false" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  Search,
  Cpu,
  Document,
  Delete,
  Loading,
  CircleCheck,
  CircleClose,
  Warning
} from '@element-plus/icons-vue'
import type { DialogServerMessage } from '@/types/dialog'
import { DialogEventType, IntentType } from '@/types/dialog'
import { useDialogWebSocket } from '@/composables/useDialogWebSocket'

// 使用 composable
const {
  sessionId,
  connectionStatus,
  connectionStatusText,
  connectionTagType,
  currentIntent,
  currentPhase,
  phases,
  agentStates,
  streamingContent,
  isStreaming,
  isRunning,
  error,
  eventLog,
  overallProgress,
  clearEventLog
} = useDialogWebSocket()

// 计算属性
const recentEvents = computed(() => eventLog.value.slice(-20))

const renderedStreamingContent = computed(() => {
  // 简单的 markdown 渲染（实际应用中应使用 markdown-it 等库）
  let content = streamingContent.value
  // 处理代码块
  content = content.replace(/```(\w+)?\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>')
  // 处理行内代码
  content = content.replace(/`([^`]+)`/g, '<code>$1</code>')
  // 处理粗体
  content = content.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // 处理换行
  content = content.replace(/\n/g, '<br>')
  return content
})

// 方法
function getIntentDisplayName(intent: IntentType): string {
  const names: Record<IntentType, string> = {
    [IntentType.DIAGNOSE_LOG]: '日志诊断',
    [IntentType.QUERY_CODE]: '代码查询',
    [IntentType.EXPLAIN_ERROR]: '错误解释',
    [IntentType.INTERVENE]: '用户干预',
    [IntentType.FOLLOW_UP]: '追问',
    [IntentType.UNKNOWN]: '未知意图'
  }
  return names[intent] || intent
}

function getConfidenceTagType(confidence: number): 'success' | 'warning' | 'danger' {
  if (confidence >= 0.8) return 'success'
  if (confidence >= 0.5) return 'warning'
  return 'danger'
}

function getProgressStatus(): '' | 'success' | 'exception' | 'warning' {
  if (error.value) return 'exception'
  if (!isRunning.value && overallProgress.value >= 100) return 'success'
  return ''
}

function getPhaseIconClass(status: string): string {
  switch (status) {
    case 'running': return 'icon-running'
    case 'completed': return 'icon-completed'
    case 'failed': return 'icon-failed'
    case 'interrupted': return 'icon-interrupted'
    default: return 'icon-pending'
  }
}

function getPhaseIcon(status: string) {
  switch (status) {
    case 'running': return Loading
    case 'completed': return CircleCheck
    case 'failed': return CircleClose
    case 'interrupted': return Warning
    default: return Warning
  }
}

function getPhaseTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'running': return 'warning'
    case 'completed': return 'success'
    case 'failed': return 'danger'
    case 'interrupted': return 'warning'
    default: return 'info'
  }
}

function getPhaseStatusText(status: string): string {
  switch (status) {
    case 'running': return '执行中'
    case 'completed': return '已完成'
    case 'failed': return '失败'
    case 'interrupted': return '已中断'
    default: return '待执行'
  }
}

function getTimelineDotClass(status: string): string {
  switch (status) {
    case 'running': return 'dot-running'
    case 'completed': return 'dot-completed'
    case 'failed': return 'dot-failed'
    default: return 'dot-pending'
  }
}

function getAgentTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'running': return 'warning'
    case 'completed': return 'success'
    case 'failed': return 'danger'
    case 'skipped': return 'info'
    default: return 'info'
  }
}

function getAgentStatusText(status: string): string {
  switch (status) {
    case 'running': return '执行中'
    case 'completed': return '已完成'
    case 'failed': return '失败'
    case 'skipped': return '已跳过'
    case 'dispatched': return '已调度'
    default: return '待执行'
  }
}

function getEventClass(eventType: DialogEventType): string {
  switch (eventType) {
    case DialogEventType.PHASE_STARTED:
    case DialogEventType.AGENT_DISPATCHED:
      return 'event-start'
    case DialogEventType.PHASE_COMPLETED:
    case DialogEventType.AGENT_RESULT:
    case DialogEventType.FINAL_RESULT:
      return 'event-success'
    case DialogEventType.ERROR:
    case DialogEventType.PHASE_FAILED:
      return 'event-error'
    case DialogEventType.INTERVENTION_REQUESTED:
      return 'event-intervention'
    default:
      return 'event-info'
  }
}

function getEventTypeName(eventType: DialogEventType): string {
  const names: Partial<Record<DialogEventType, string>> = {
    [DialogEventType.SESSION_CREATED]: '会话创建',
    [DialogEventType.INTENT_PARSED]: '意图识别',
    [DialogEventType.PHASE_STARTED]: '阶段开始',
    [DialogEventType.PHASE_PROGRESS]: '阶段进度',
    [DialogEventType.PHASE_COMPLETED]: '阶段完成',
    [DialogEventType.AGENT_DISPATCHED]: 'Agent调度',
    [DialogEventType.AGENT_UPDATE]: 'Agent更新',
    [DialogEventType.AGENT_RESULT]: 'Agent结果',
    [DialogEventType.INTERVENTION_REQUESTED]: '干预请求',
    [DialogEventType.STREAM_OUTPUT]: '流式输出',
    [DialogEventType.FINAL_RESULT]: '最终结果',
    [DialogEventType.ERROR]: '错误'
  }
  return names[eventType] || eventType
}

function getEventMessage(event: DialogServerMessage): string {
  switch (event.type) {
    case DialogEventType.INTENT_PARSED:
      return event.intentResult ? getIntentDisplayName(event.intentResult.intent) : ''
    case DialogEventType.PHASE_STARTED:
    case DialogEventType.PHASE_PROGRESS:
    case DialogEventType.PHASE_COMPLETED:
      return event.phase?.description || event.phase?.name || ''
    case DialogEventType.AGENT_DISPATCHED:
    case DialogEventType.AGENT_UPDATE:
    case DialogEventType.AGENT_RESULT:
      return event.agentStatus?.agentName || ''
    case DialogEventType.ERROR:
      return event.error?.message || ''
    default:
      return ''
  }
}

function formatTime(timestamp: string): string {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.dialog-progress-panel {
  background: #1e1e1e;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  font-size: 13px;
}

.connection-indicator {
  display: flex;
  align-items: center;
  gap: 12px;
}

.session-id {
  color: #909399;
  font-size: 11px;
  font-family: 'JetBrains Mono', monospace;
}

.intent-section {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.intent-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e0e0e0;
  margin-bottom: 8px;
}

.intent-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.intent-type {
  color: #409eff;
  font-weight: 500;
}

.intent-entities {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.entity-item {
  color: #909399;
  font-size: 12px;
  padding: 2px 8px;
  background: #1e1e1e;
  border-radius: 4px;
}

.overall-progress {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  color: #909399;
  margin-bottom: 8px;
}

.progress-value {
  color: #e0e0e0;
  font-weight: 500;
}

.current-phase {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
  border-left: 3px solid #409eff;
}

.phase-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
}

.phase-name {
  color: #e0e0e0;
  font-weight: 500;
}

.phase-description {
  color: #909399;
  font-size: 12px;
  margin-top: 6px;
}

.phase-progress {
  margin-top: 8px;
}

.icon-running { color: #e6a23c; animation: spin 1s linear infinite; }
.icon-completed { color: #67c23a; }
.icon-failed { color: #f56c6c; }
.icon-interrupted { color: #e6a23c; }
.icon-pending { color: #909399; }

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.phase-list {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.phase-list-header {
  color: #909399;
  font-size: 12px;
  margin-bottom: 12px;
}

.phase-timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.phase-timeline-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}

.phase-timeline-item.active {
  background: rgba(64, 158, 255, 0.1);
  border-radius: 4px;
}

.timeline-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot-running { background: #e6a23c; }
.dot-completed { background: #67c23a; }
.dot-failed { background: #f56c6c; }
.dot-pending { background: #909399; }

.timeline-content {
  display: flex;
  justify-content: space-between;
  flex: 1;
}

.timeline-name {
  color: #e0e0e0;
}

.timeline-status {
  color: #909399;
  font-size: 12px;
}

.agent-section {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.agent-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e0e0e0;
  margin-bottom: 12px;
}

.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 8px;
}

.agent-card {
  background: #1e1e1e;
  border-radius: 6px;
  padding: 10px;
  border-left: 3px solid #404040;
}

.agent-card.active {
  border-left-color: #e6a23c;
}

.agent-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.agent-name {
  color: #e0e0e0;
  font-size: 12px;
}

.agent-progress {
  margin-top: 8px;
}

.agent-confidence {
  color: #67c23a;
  font-size: 11px;
  margin-top: 4px;
}

.agent-output-preview {
  color: #909399;
  font-size: 11px;
  margin-top: 6px;
  font-family: 'JetBrains Mono', monospace;
}

.stream-output {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.stream-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e0e0e0;
  margin-bottom: 12px;
}

.stream-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #e6a23c;
  font-size: 12px;
}

.pulse {
  width: 8px;
  height: 8px;
  background: #e6a23c;
  border-radius: 50%;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.stream-content {
  color: #c0c0c0;
  line-height: 1.6;
  max-height: 200px;
  overflow-y: auto;
}

.stream-content :deep(pre) {
  background: #1e1e1e;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
}

.stream-content :deep(code) {
  font-family: 'JetBrains Mono', monospace;
  background: rgba(64, 158, 255, 0.1);
  padding: 2px 4px;
  border-radius: 2px;
}

.event-log-section {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.log-header {
  display: flex;
  justify-content: space-between;
  color: #e0e0e0;
  margin-bottom: 8px;
}

.log-content {
  max-height: 150px;
  overflow-y: auto;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
}

.log-item {
  display: flex;
  gap: 8px;
  padding: 3px 0;
}

.log-time {
  color: #909399;
  min-width: 65px;
}

.log-type {
  color: #409eff;
  min-width: 80px;
}

.log-message {
  color: #c0c0c0;
}

.event-start { border-left: 2px solid #409eff; padding-left: 6px; }
.event-success { border-left: 2px solid #67c23a; padding-left: 6px; }
.event-error { border-left: 2px solid #f56c6c; padding-left: 6px; }
.event-intervention { border-left: 2px solid #e6a23c; padding-left: 6px; }
.event-info { border-left: 2px solid #909399; padding-left: 6px; }

.error-section {
  margin-top: 8px;
}
</style>