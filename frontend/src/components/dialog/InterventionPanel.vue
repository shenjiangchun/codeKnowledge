<template>
  <div class="intervention-panel">
    <!-- 干预请求提示 -->
    <div v-if="isInterventionPending && pendingRequest" class="intervention-request">
      <div class="request-header">
        <el-icon class="warning-icon"><WarningFilled /></el-icon>
        <span>系统请求干预</span>
      </div>
      <div class="request-message">{{ pendingRequest.message }}</div>
      <div class="request-type">
        <el-tag :type="getInterventionTypeColor(pendingRequest.interventionType)" size="small">
          {{ getInterventionTypeName(pendingRequest.interventionType) }}
        </el-tag>
      </div>

      <!-- 根据干预类型显示不同的选项 -->
      <div class="intervention-options">
        <!-- 调整焦点 -->
        <div v-if="pendingRequest.interventionType === 'adjust_focus'" class="option-section">
          <el-input
            v-model="focusInput"
            placeholder="输入新的关注区域..."
            size="small"
          />
        </div>

        <!-- 跳过Agent -->
        <div v-if="pendingRequest.interventionType === 'skip_agent'" class="option-section">
          <div v-if="pendingRequest.context?.targetAgent" class="target-agent">
            目标Agent: {{ pendingRequest.context.targetAgent }}
          </div>
          <el-checkbox v-model="skipConfirm">确认跳过此Agent</el-checkbox>
        </div>

        <!-- 改变策略 -->
        <div v-if="pendingRequest.interventionType === 'change_strategy'" class="option-section">
          <el-select v-model="selectedStrategy" placeholder="选择新策略" size="small">
            <el-option label="深度分析" value="deep_analysis" />
            <el-option label="快速扫描" value="quick_scan" />
            <el-option label="聚焦关键路径" value="focus_critical" />
            <el-option label="扩展搜索范围" value="expand_search" />
          </el-select>
        </div>

        <!-- 提供提示 -->
        <div v-if="pendingRequest.interventionType === 'provide_hint'" class="option-section">
          <el-input
            v-model="hintInput"
            type="textarea"
            :rows="2"
            placeholder="输入您的提示信息..."
            size="small"
          />
        </div>

        <!-- 取消操作 -->
        <div v-if="pendingRequest.interventionType === 'cancel'" class="option-section">
          <div class="cancel-warning">
            <el-alert
              title="确认取消当前分析？"
              type="warning"
              :closable="false"
              show-icon
            />
          </div>
        </div>
      </div>

      <!-- 操作按钮 -->
      <div class="intervention-actions">
        <el-button type="primary" size="small" @click="handleAccept">
          <el-icon><Check /></el-icon>
          确认干预
        </el-button>
        <el-button size="small" @click="handleReject">
          <el-icon><Close /></el-icon>
          继续原流程
        </el-button>
      </div>
    </div>

    <!-- 主动干预面板 -->
    <div v-else-if="isRunning" class="active-intervention">
      <div class="active-header">
        <span>主动干预</span>
        <el-tooltip content="在分析过程中进行干预" placement="top">
          <el-icon><QuestionFilled /></el-icon>
        </el-tooltip>
      </div>

      <!-- 快捷干预按钮 -->
      <div class="quick-actions">
        <el-button-group>
          <el-button size="small" @click="quickIntervene('adjust_focus')">
            <el-icon><Aim /></el-icon>
            调整焦点
          </el-button>
          <el-button size="small" @click="quickIntervene('skip_agent')">
            <el-icon><RemoveFilled /></el-icon>
            跳过Agent
          </el-button>
          <el-button size="small" @click="quickIntervene('change_strategy')">
            <el-icon><Switch /></el-icon>
            改变策略
          </el-button>
        </el-button-group>
      </div>

      <!-- 自定义干预输入 -->
      <div class="custom-intervention">
        <el-input
          v-model="customInterventionText"
          type="textarea"
          :rows="2"
          placeholder="输入自定义干预指令..."
          :disabled="!isRunning"
        />
        <el-button
          type="primary"
          size="small"
          :disabled="!customInterventionText.trim() || !isRunning"
          @click="sendCustomIntervention"
        >
          发送干预
        </el-button>
      </div>

      <!-- 取消按钮 -->
      <div class="cancel-section">
        <el-button
          type="danger"
          size="small"
          plain
          :disabled="!isRunning"
          @click="handleCancel"
        >
          <el-icon><Close /></el-icon>
          取消分析
        </el-button>
      </div>
    </div>

    <!-- 无运行中的任务 -->
    <div v-else class="no-intervention">
      <div class="empty-state">
        <el-icon><Warning /></el-icon>
        <span>当前没有运行中的分析任务</span>
      </div>
    </div>

    <!-- 干预历史 -->
    <div v-if="interventionHistory.length > 0" class="intervention-history">
      <div class="history-header">
        <span>干预历史</span>
      </div>
      <div class="history-list">
        <div
          v-for="(item, index) in interventionHistory"
          :key="index"
          class="history-item"
          :class="{ success: item.applied, rejected: !item.applied }"
        >
          <div class="history-type">{{ getInterventionTypeName(item.type) }}</div>
          <div class="history-message">{{ item.message }}</div>
          <div class="history-time">{{ formatTime(item.timestamp) }}</div>
          <el-tag :type="item.applied ? 'success' : 'info'" size="small">
            {{ item.applied ? '已应用' : '已拒绝' }}
          </el-tag>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import {
  WarningFilled,
  Warning,
  Check,
  Close,
  QuestionFilled,
  Aim,
  RemoveFilled,
  Switch
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { InterventionRequest } from '@/types/dialog'
import { useDialogWebSocket } from '@/composables/useDialogWebSocket'

// 使用 composable
const {
  isRunning,
  isInterventionPending,
  pendingInterventionRequest: pendingRequest,
  sessionId,
  respondToIntervention,
  sendIntervention,
  cancel
} = useDialogWebSocket()

// 本地状态
const focusInput = ref('')
const skipConfirm = ref(false)
const selectedStrategy = ref('')
const hintInput = ref('')
const customInterventionText = ref('')
const interventionHistory = ref<{
  type: string
  message: string
  applied: boolean
  timestamp: string
}[]>([])

// 计算属性
const canAccept = computed(() => {
  if (!pendingRequest.value) return false

  switch (pendingRequest.value.interventionType) {
    case 'adjust_focus':
      return focusInput.value.trim().length > 0
    case 'skip_agent':
      return skipConfirm.value
    case 'change_strategy':
      return selectedStrategy.value.length > 0
    case 'provide_hint':
      return hintInput.value.trim().length > 0
    case 'cancel':
      return true
    default:
      return true
  }
})

// 监听 pendingRequest 清空输入
watch(pendingRequest, (newRequest) => {
  if (newRequest) {
    focusInput.value = newRequest.context?.newFocus || ''
    hintInput.value = newRequest.context?.hint || ''
  }
})

// 方法
function getInterventionTypeName(type: string): string {
  const names: Record<string, string> = {
    'adjust_focus': '调整焦点',
    'skip_agent': '跳过Agent',
    'change_strategy': '改变策略',
    'provide_hint': '提供提示',
    'cancel': '取消操作'
  }
  return names[type] || type
}

function getInterventionTypeColor(type: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (type) {
    case 'adjust_focus': return 'info'
    case 'skip_agent': return 'warning'
    case 'change_strategy': return 'warning'
    case 'provide_hint': return 'success'
    case 'cancel': return 'danger'
    default: return 'info'
  }
}

function handleAccept() {
  if (!pendingRequest.value || !canAccept.value) return

  let message = ''
  let context: InterventionRequest['context'] = {}

  switch (pendingRequest.value.interventionType) {
    case 'adjust_focus':
      message = `调整焦点为: ${focusInput.value}`
      context = { newFocus: focusInput.value }
      break
    case 'skip_agent':
      message = '跳过当前Agent'
      context = pendingRequest.value.context
      break
    case 'change_strategy':
      message = `切换策略: ${selectedStrategy.value}`
      break
    case 'provide_hint':
      message = hintInput.value
      context = { hint: hintInput.value }
      break
    case 'cancel':
      cancel()
      addToHistory('cancel', '取消分析', true)
      return
  }

  respondToIntervention(true, message)

  // 记录历史
  addToHistory(pendingRequest.value.interventionType, message, true)

  // 清空输入
  clearInputs()

  ElMessage.success('干预已发送')
}

function handleReject() {
  respondToIntervention(false, '用户拒绝干预，继续原流程')

  if (pendingRequest.value) {
    addToHistory(pendingRequest.value.interventionType, '用户拒绝', false)
  }

  clearInputs()
  ElMessage.info('继续原流程')
}

function quickIntervene(type: InterventionRequest['interventionType']) {
  // 显示快捷干预提示
  ElMessage.info(`准备进行: ${getInterventionTypeName(type)}`)
  // 这里可以打开一个对话框让用户填写详细信息
  sendIntervention(type, '用户请求干预', {})
  addToHistory(type, '快捷干预', true)
}

function sendCustomIntervention() {
  if (!customInterventionText.value.trim()) return

  sendIntervention('provide_hint', customInterventionText.value, {
    hint: customInterventionText.value
  })

  addToHistory('provide_hint', customInterventionText.value, true)
  customInterventionText.value = ''

  ElMessage.success('自定义干预已发送')
}

function handleCancel() {
  cancel()
  addToHistory('cancel', '用户取消分析', true)
  ElMessage.warning('分析已取消')
}

function addToHistory(type: string, message: string, applied: boolean) {
  interventionHistory.value.unshift({
    type,
    message,
    applied,
    timestamp: new Date().toISOString()
  })

  // 限制历史长度
  if (interventionHistory.value.length > 20) {
    interventionHistory.value = interventionHistory.value.slice(0, 20)
  }
}

function clearInputs() {
  focusInput.value = ''
  skipConfirm.value = false
  selectedStrategy.value = ''
  hintInput.value = ''
}

function formatTime(timestamp: string): string {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.intervention-panel {
  background: #1e1e1e;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 干预请求提示 */
.intervention-request {
  background: #252526;
  border-radius: 6px;
  padding: 16px;
  border: 1px solid #e6a23c;
}

.request-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e6a23c;
  font-weight: 500;
  margin-bottom: 12px;
}

.warning-icon {
  color: #e6a23c;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.request-message {
  color: #e0e0e0;
  margin-bottom: 8px;
  line-height: 1.5;
}

.request-type {
  margin-bottom: 16px;
}

.intervention-options {
  margin-bottom: 16px;
}

.option-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.target-agent {
  color: #909399;
  font-size: 12px;
  padding: 8px;
  background: #1e1e1e;
  border-radius: 4px;
}

.cancel-warning {
  margin-bottom: 8px;
}

.intervention-actions {
  display: flex;
  gap: 8px;
}

/* 主动干预面板 */
.active-intervention {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.active-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e0e0e0;
  margin-bottom: 12px;
}

.quick-actions {
  margin-bottom: 12px;
}

.custom-intervention {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.custom-intervention :deep(.el-textarea__inner) {
  background: #1e1e1e;
  border-color: #404040;
  color: #e0e0e0;
}

.cancel-section {
  margin-top: 12px;
  text-align: right;
}

/* 无运行中任务 */
.no-intervention {
  background: #252526;
  border-radius: 6px;
  padding: 16px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #909399;
}

/* 干预历史 */
.intervention-history {
  background: #252526;
  border-radius: 6px;
  padding: 12px;
}

.history-header {
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
}

.history-list {
  max-height: 120px;
  overflow-y: auto;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 12px;
}

.history-item.success {
  border-left: 2px solid #67c23a;
  padding-left: 8px;
}

.history-item.rejected {
  border-left: 2px solid #909399;
  padding-left: 8px;
}

.history-type {
  color: #409eff;
  min-width: 80px;
}

.history-message {
  color: #e0e0e0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.history-time {
  color: #909399;
  font-size: 11px;
  min-width: 65px;
}
</style>