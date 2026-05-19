<script setup lang="ts">
import { computed } from 'vue'
import type { ApmSessionStatus } from '@/types/apm'

const props = defineProps<{
  status: ApmSessionStatus
  sessionId: string
  serviceName: string
  targetPort: number
  projectPath: string
  connected: boolean
}>()

const emit = defineEmits<{
  launch: [path: string]
  stop: []
  reset: []
}>()

const projectInput = defineModel<string>('projectPath', { default: '' })

const statusTagType = computed(() => {
  const mapping: Record<ApmSessionStatus, string> = {
    IDLE: 'info',
    LAUNCHING: 'warning',
    READY: 'success',
    EXECUTING: 'primary',
    STREAMING: 'primary',
    COMPLETE: 'success',
    ERROR: 'danger',
  }
  return mapping[props.status]
})

const statusLabel = computed(() => {
  const mapping: Record<ApmSessionStatus, string> = {
    IDLE: '空闲',
    LAUNCHING: '启动中...',
    READY: '就绪',
    EXECUTING: '执行中',
    STREAMING: '采集中',
    COMPLETE: '已完成',
    ERROR: '错误',
  }
  return mapping[props.status]
})

const isActive = computed(() =>
  props.status !== 'IDLE' && props.status !== 'COMPLETE' && props.status !== 'ERROR'
)

function handleLaunch(): void {
  if (projectInput.value.trim()) {
    emit('launch', projectInput.value.trim())
  }
}
</script>

<template>
  <el-card class="session-control-bar" shadow="never">
    <el-row :gutter="12" align="middle">
      <!-- Project path input (IDLE state) -->
      <el-col v-if="status === 'IDLE'" :span="14">
        <el-input
          v-model="projectInput"
          placeholder="输入项目路径，例如 /path/to/project"
          clearable
          @keyup.enter="handleLaunch"
        />
      </el-col>

      <!-- Launch button (IDLE state) -->
      <el-col v-if="status === 'IDLE'" :span="4">
        <el-button
          type="primary"
          :disabled="!projectInput.trim()"
          @click="handleLaunch"
        >
          启动调试
        </el-button>
      </el-col>

      <!-- Session info (non-IDLE state) -->
      <el-col v-if="status !== 'IDLE'" :span="12">
        <span class="session-info">
          <strong>{{ serviceName || '服务' }}</strong>
          <span v-if="targetPort" class="port-badge">:{{ targetPort }}</span>
          <span v-if="sessionId" class="session-id">{{ sessionId.substring(0, 8) }}...</span>
        </span>
      </el-col>

      <!-- Status badge -->
      <el-col :span="status === 'IDLE' ? 3 : 4">
        <el-tag :type="statusTagType" effect="dark" round>
          {{ statusLabel }}
        </el-tag>
        <el-tag
          v-if="status !== 'IDLE'"
          :type="connected ? 'success' : 'danger'"
          effect="plain"
          size="small"
          class="ws-tag"
        >
          WS {{ connected ? '已连接' : '断开' }}
        </el-tag>
      </el-col>

      <!-- Action buttons -->
      <el-col :span="status === 'IDLE' ? 3 : 8" class="action-buttons">
        <el-button
          v-if="isActive"
          type="danger"
          plain
          @click="emit('stop')"
        >
          停止
        </el-button>
        <el-button
          v-if="status === 'COMPLETE' || status === 'ERROR'"
          @click="emit('reset')"
        >
          重置
        </el-button>
      </el-col>
    </el-row>
  </el-card>
</template>

<style scoped>
.session-control-bar {
  margin-bottom: 16px;
}

.session-info {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.port-badge {
  color: #409eff;
  font-weight: bold;
}

.session-id {
  color: #909399;
  font-size: 12px;
  font-family: monospace;
}

.ws-tag {
  margin-left: 8px;
}

.action-buttons {
  text-align: right;
}
</style>
