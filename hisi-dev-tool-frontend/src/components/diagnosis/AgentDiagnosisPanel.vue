<template>
  <div class="agent-diagnosis-panel">
    <!-- 标题栏 -->
    <div class="panel-header">
      <span class="title">智能诊断</span>
      <el-tag :type="statusTagType" size="small">{{ statusText }}</el-tag>
    </div>

    <!-- 输入区 -->
    <div class="input-section">
      <el-input
        v-model="query"
        type="textarea"
        :rows="3"
        placeholder="请输入问题描述或粘贴错误信息..."
        :disabled="isRunning"
      />
      <div class="input-actions">
        <el-button
          type="primary"
          :disabled="!query.trim() || isRunning"
          @click="handleStart"
        >
          <el-icon><VideoPlay /></el-icon>
          开始诊断
        </el-button>
        <el-button
          :disabled="!isRunning"
          @click="handleCancel"
        >
          <el-icon><Close /></el-icon>
          取消
        </el-button>
      </div>
    </div>

    <!-- 当前阶段 -->
    <div v-if="currentPhase" class="phase-section">
      <span class="phase-label">当前阶段:</span>
      <span class="phase-text">{{ currentPhase }}</span>
    </div>

    <!-- 总体进度 -->
    <div v-if="isRunning || events.length > 0" class="progress-section">
      <div class="progress-header">
        <span>总体进度</span>
        <span class="progress-value">{{ overallProgress }}%</span>
      </div>
      <el-progress
        :percentage="overallProgress"
        :status="progressStatus"
        :stroke-width="8"
      />
    </div>

    <!-- Agent 列表 -->
    <div v-if="Object.keys(agentStates).length > 0" class="agent-list">
      <div class="agent-list-header">Agent 执行状态</div>
      <div
        v-for="(state, agentType) in agentStates"
        :key="agentType"
        class="agent-item"
        :class="{ active: state.status === 'RUNNING' }"
      >
        <div class="agent-info">
          <el-icon :class="getStatusClass(state.status)">
            <component :is="getStatusIcon(state.status)" />
          </el-icon>
          <span class="agent-name">{{ getAgentDisplayName(agentType) }}</span>
          <el-tag :type="getTagType(state.status)" size="small">
            {{ getStatusText(state.status) }}
          </el-tag>
        </div>
        <div v-if="state.status === 'RUNNING'" class="agent-progress">
          <el-progress :percentage="state.progress" :show-text="false" />
        </div>
        <div v-if="state.confidence !== undefined" class="agent-confidence">
          置信度: {{ (state.confidence * 100).toFixed(0) }}%
        </div>
      </div>
    </div>

    <!-- 事件日志 -->
    <div v-if="events.length > 0" class="event-log">
      <div class="log-header">
        <span>执行日志</span>
        <el-button size="small" text @click="events = []">清空</el-button>
      </div>
      <div class="log-content">
        <div
          v-for="(event, index) in recentEvents"
          :key="index"
          class="log-item"
          :class="getEventClass(event.eventType)"
        >
          <span class="log-time">{{ formatTime(event.timestamp) }}</span>
          <span class="log-agent">{{ event.agentType || '系统' }}</span>
          <span class="log-message">{{ event.message }}</span>
        </div>
      </div>
    </div>

    <!-- 最终结果 -->
    <div v-if="finalResult" class="result-section">
      <div class="result-header">
        <span>诊断结论</span>
        <el-tag :type="getConfidenceTagType(finalResult.confidence)" size="small">
          置信度: {{ (finalResult.confidence * 100).toFixed(0) }}%
        </el-tag>
      </div>

      <div class="result-summary">
        <h4>问题概要</h4>
        <p>{{ finalResult.summary }}</p>
      </div>

      <div class="result-root-cause">
        <h4>根本原因</h4>
        <p>{{ finalResult.rootCause }}</p>
      </div>

      <div v-if="finalResult.recommendations.length > 0" class="result-recommendations">
        <h4>修复建议</h4>
        <ul>
          <li v-for="(rec, index) in finalResult.recommendations" :key="index">
            {{ rec }}
          </li>
        </ul>
      </div>

      <div v-if="finalResult.relatedCode.length > 0" class="result-code">
        <h4>相关代码</h4>
        <div v-for="(loc, index) in finalResult.relatedCode" :key="index" class="code-location">
          {{ loc.filePath }}:{{ loc.lineNumber }}
          <span v-if="loc.methodName"> ({{ loc.methodName }})</span>
        </div>
      </div>
    </div>

    <!-- 错误提示 -->
    <div v-if="error" class="error-section">
      <el-alert :title="error" type="error" show-icon :closable="false" />
    </div>

    <!-- 连接状态 -->
    <div class="connection-status">
      <el-tag :type="connectionTagType" size="small">
        {{ connectionStatusText }}
      </el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { VideoPlay, Close, Loading, CircleCheck, CircleClose, Warning, Remove } from '@element-plus/icons-vue'
import { useDiagnosis } from '@/composables/useDiagnosis'
import { AgentEventType } from '@/types/agent'

// 使用 composable
const {
  connectionStatus,
  events,
  agentStates,
  finalResult,
  currentPhase,
  isRunning,
  error,
  overallProgress,
  startDiagnosis,
  cancelDiagnosis
} = useDiagnosis()

// 本地状态
const query = ref('')

// 计算属性
const statusText = computed(() => {
  if (isRunning.value) return '诊断中...'
  if (finalResult.value) return '诊断完成'
  if (error.value) return '诊断失败'
  return '等待输入'
})

const statusTagType = computed(() => {
  if (isRunning.value) return 'warning'
  if (finalResult.value) return 'success'
  if (error.value) return 'danger'
  return 'info'
})

const progressStatus = computed(() => {
  if (finalResult.value) return 'success'
  if (error.value) return 'exception'
  return undefined
})

const connectionTagType = computed(() => {
  switch (connectionStatus.value) {
    case 'connected': return 'success'
    case 'connecting': return 'warning'
    case 'error': return 'danger'
    default: return 'info'
  }
})

const connectionStatusText = computed(() => {
  switch (connectionStatus.value) {
    case 'connected': return '已连接'
    case 'connecting': return '连接中...'
    case 'error': return '连接错误'
    default: return '未连接'
  }
})

const recentEvents = computed(() => events.value.slice(-15))

// 方法
function handleStart() {
  if (!query.value.trim()) return
  startDiagnosis({ query: query.value })
}

function handleCancel() {
  cancelDiagnosis()
}

function getAgentDisplayName(agentType: string): string {
  const names: Record<string, string> = {
    'STACK_TRACE': '堆栈追踪',
    'CODE_CONTEXT': '代码上下文',
    'GIT_HISTORY': 'Git历史',
    'CONSENSUS': '综合分析'
  }
  return names[agentType] || agentType
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'status-running'
    case 'COMPLETED': return 'status-completed'
    case 'FAILED': return 'status-failed'
    case 'SKIPPED': return 'status-skipped'
    default: return 'status-idle'
  }
}

function getStatusIcon(status: string) {
  switch (status) {
    case 'RUNNING': return Loading
    case 'COMPLETED': return CircleCheck
    case 'FAILED': return CircleClose
    case 'SKIPPED': return Remove
    default: return Warning
  }
}

function getStatusText(status: string): string {
  switch (status) {
    case 'RUNNING': return '执行中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'SKIPPED': return '已跳过'
    default: return '待执行'
  }
}

function getTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'RUNNING': return 'warning'
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

function getConfidenceTagType(confidence: number): 'success' | 'warning' | 'danger' {
  if (confidence >= 0.8) return 'success'
  if (confidence >= 0.5) return 'warning'
  return 'danger'
}

function getEventClass(eventType: AgentEventType): string {
  switch (eventType) {
    case AgentEventType.AGENT_STARTED:
    case AgentEventType.ORCHESTRATION_START:
    case AgentEventType.REQUEST_RECEIVED:
      return 'event-start'
    case AgentEventType.AGENT_COMPLETED:
    case AgentEventType.ORCHESTRATION_END:
    case AgentEventType.FINAL_RESULT:
      return 'event-success'
    case AgentEventType.AGENT_FAILED:
      return 'event-error'
    case AgentEventType.AGENT_SKIPPED:
      return 'event-skipped'
    default:
      return 'event-info'
  }
}

function formatTime(timestamp: string): string {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.agent-diagnosis-panel {
  background: #1a1a1a;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  overflow-y: auto;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title {
  color: #e0e0e0;
  font-weight: 500;
  font-size: 16px;
}

.input-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.input-section :deep(.el-textarea__inner) {
  background: #2d2d2d;
  border-color: #404040;
  color: #e0e0e0;
}

.input-actions {
  display: flex;
  gap: 8px;
}

.phase-section {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #2d2d2d;
  border-radius: 6px;
}

.phase-label {
  color: #909399;
  font-size: 12px;
}

.phase-text {
  color: #409eff;
  font-size: 13px;
}

.progress-section {
  background: #2d2d2d;
  border-radius: 6px;
  padding: 12px;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
}

.progress-value {
  color: #e0e0e0;
}

.agent-list {
  background: #2d2d2d;
  border-radius: 6px;
  padding: 12px;
}

.agent-list-header {
  color: #909399;
  font-size: 12px;
  margin-bottom: 12px;
}

.agent-item {
  padding: 10px;
  margin-bottom: 8px;
  background: #1a1a1a;
  border-radius: 4px;
  border-left: 3px solid #404040;
}

.agent-item.active {
  border-left-color: #e6a23c;
}

.agent-item:last-child {
  margin-bottom: 0;
}

.agent-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-name {
  color: #e0e0e0;
  font-size: 13px;
  flex: 1;
}

.agent-progress {
  margin-top: 8px;
}

.agent-confidence {
  color: #67c23a;
  font-size: 11px;
  margin-top: 4px;
}

.status-running {
  color: #e6a23c;
  animation: spin 1s linear infinite;
}

.status-completed {
  color: #67c23a;
}

.status-failed {
  color: #f56c6c;
}

.status-skipped {
  color: #909399;
}

.status-idle {
  color: #909399;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.event-log {
  background: #2d2d2d;
  border-radius: 6px;
  padding: 12px;
}

.log-header {
  display: flex;
  justify-content: space-between;
  color: #e0e0e0;
  font-size: 12px;
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

.log-agent {
  color: #409eff;
  min-width: 90px;
}

.log-message {
  color: #c0c0c0;
  flex: 1;
}

.event-start { border-left: 2px solid #409eff; padding-left: 6px; }
.event-success { border-left: 2px solid #67c23a; padding-left: 6px; }
.event-error { border-left: 2px solid #f56c6c; padding-left: 6px; }
.event-skipped { border-left: 2px solid #909399; padding-left: 6px; }
.event-info { border-left: 2px solid #909399; padding-left: 6px; }

.result-section {
  background: #2d2d2d;
  border-radius: 6px;
  padding: 12px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #e0e0e0;
  font-size: 13px;
  margin-bottom: 12px;
}

.result-section h4 {
  color: #909399;
  font-size: 12px;
  margin: 12px 0 6px 0;
}

.result-section p {
  color: #c0c0c0;
  font-size: 13px;
  margin: 0;
  line-height: 1.5;
}

.result-summary p {
  background: rgba(64, 158, 255, 0.1);
  padding: 8px;
  border-radius: 4px;
}

.result-root-cause p {
  background: rgba(245, 108, 108, 0.1);
  padding: 8px;
  border-radius: 4px;
  border-left: 3px solid #f56c6c;
}

.result-recommendations ul {
  list-style: none;
  padding: 0;
  margin: 0;
}

.result-recommendations li {
  color: #c0c0c0;
  font-size: 12px;
  padding: 6px 0;
  border-bottom: 1px solid #404040;
}

.result-recommendations li:last-child {
  border-bottom: none;
}

.code-location {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: #409eff;
  padding: 4px 0;
}

.error-section {
  margin-top: 8px;
}

.connection-status {
  text-align: center;
  margin-top: auto;
  padding-top: 8px;
}
</style>