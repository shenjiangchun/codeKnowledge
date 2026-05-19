<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { apmApi } from '@/api/apmDebug'
import { useApmWebSocket } from '@/composables/useApmWebSocket'
import SessionControlBar from './components/SessionControlBar.vue'
import EntryPointSelector from './components/EntryPointSelector.vue'
import SpanFlowChart from './components/SpanFlowChart.vue'
import ExecutionReport from './components/ExecutionReport.vue'
import SpanDetailDrawer from './components/SpanDetailDrawer.vue'
import type { ApmSessionStatus, ApmSpan, DebugReport, LaunchResult } from '@/types/apm'

const { connected, spans, events, connect, disconnect, reset: resetWs } = useApmWebSocket()

const status = ref<ApmSessionStatus>('IDLE')
const sessionId = ref('')
const serviceName = ref('')
const targetPort = ref(0)
const projectPath = ref('')
const report = ref<DebugReport | null>(null)
const errorMessage = ref('')
const selectedSpan = ref<ApmSpan | null>(null)
const drawerVisible = ref(false)

const stateStepIndex = computed(() => {
  const mapping: Record<ApmSessionStatus, number> = {
    IDLE: 0,
    LAUNCHING: 1,
    READY: 2,
    EXECUTING: 3,
    STREAMING: 3,
    COMPLETE: 4,
    ERROR: -1,
  }
  return mapping[status.value]
})

const showEntrySelector = computed(() => status.value === 'READY')
const showSpanChart = computed(() =>
  status.value === 'EXECUTING' || status.value === 'STREAMING' || status.value === 'COMPLETE'
)
const showReport = computed(() => status.value === 'COMPLETE' && report.value !== null)

async function handleLaunch(path: string): Promise<void> {
  projectPath.value = path
  status.value = 'LAUNCHING'
  errorMessage.value = ''

  try {
    const result = await apmApi.launch({ projectPath: path }) as unknown as LaunchResult
    sessionId.value = result.sessionId
    serviceName.value = result.serviceName
    targetPort.value = result.targetPort
    status.value = 'READY'
    connect(result.sessionId)
  } catch (err: unknown) {
    status.value = 'ERROR'
    errorMessage.value = err instanceof Error ? err.message : '启动失败'
    ElMessage.error(errorMessage.value)
  }
}

async function handleExecute(params: { method: string; path: string; body?: string }): Promise<void> {
  if (!sessionId.value) return

  status.value = 'EXECUTING'
  errorMessage.value = ''

  try {
    await apmApi.execute({
      sessionId: sessionId.value,
      method: params.method,
      path: params.path,
      body: params.body,
    })

    status.value = 'STREAMING'
    // Report fetch is triggered by WebSocket EXECUTION_COMPLETE event (see watch below)
  } catch (err: unknown) {
    status.value = 'ERROR'
    errorMessage.value = err instanceof Error ? err.message : '执行失败'
    ElMessage.error(errorMessage.value)
  }
}

// Watch for WebSocket events to auto-fetch report when execution completes
watch(events, (newEvents) => {
  if (status.value !== 'STREAMING') return
  const lastEvent = newEvents[newEvents.length - 1]
  if (lastEvent?.type === 'EXECUTION_COMPLETE') {
    handleFetchReport()
  }
}, { deep: false })

async function handleStop(): Promise<void> {
  if (!sessionId.value) return

  try {
    await apmApi.stop(sessionId.value)
    disconnect()
    status.value = 'IDLE'
    ElMessage.success('已停止')
  } catch (err: unknown) {
    ElMessage.error(err instanceof Error ? err.message : '停止失败')
  }
}

function handleReset(): void {
  disconnect()
  resetWs()
  sessionId.value = ''
  serviceName.value = ''
  targetPort.value = 0
  projectPath.value = ''
  report.value = null
  errorMessage.value = ''
  selectedSpan.value = null
  drawerVisible.value = false
  status.value = 'IDLE'
}

function handleSpanClick(span: ApmSpan): void {
  selectedSpan.value = span
  drawerVisible.value = true
}

function handleDrawerClose(): void {
  drawerVisible.value = false
  selectedSpan.value = null
}

async function handleFetchReport(): Promise<void> {
  if (!sessionId.value) return
  try {
    const rpt = await apmApi.getReport(sessionId.value) as unknown as DebugReport
    report.value = rpt
    status.value = 'COMPLETE'
  } catch (err: unknown) {
    ElMessage.error(err instanceof Error ? err.message : '获取报告失败')
  }
}
</script>

<template>
  <div class="apm-debug-view">
    <!-- State machine steps -->
    <el-card class="state-card" shadow="never">
      <el-steps :active="stateStepIndex" finish-status="success" align-center>
        <el-step title="空闲" description="选择项目" />
        <el-step title="启动中" description="编译部署" />
        <el-step title="就绪" description="配置请求" />
        <el-step title="执行中" description="采集 Span" />
        <el-step title="完成" description="查看报告" />
      </el-steps>
    </el-card>

    <!-- Error alert -->
    <el-alert
      v-if="status === 'ERROR'"
      :title="errorMessage || '发生错误'"
      type="error"
      show-icon
      closable
      class="error-alert"
      @close="handleReset"
    />

    <!-- Control bar -->
    <SessionControlBar
      :status="status"
      :session-id="sessionId"
      :service-name="serviceName"
      :target-port="targetPort"
      :project-path="projectPath"
      :connected="connected"
      @launch="handleLaunch"
      @stop="handleStop"
      @reset="handleReset"
    />

    <!-- Main content -->
    <el-row :gutter="16" class="main-content">
      <!-- Left: Entry point selector -->
      <el-col :span="showSpanChart ? 8 : 24">
        <EntryPointSelector
          v-if="showEntrySelector"
          :session-id="sessionId"
          @execute="handleExecute"
        />
      </el-col>

      <!-- Right: Span flow chart -->
      <el-col v-if="showSpanChart" :span="showEntrySelector ? 16 : 24">
        <SpanFlowChart
          :spans="spans"
          @span-click="handleSpanClick"
        />
      </el-col>
    </el-row>

    <!-- Report section -->
    <div v-if="showReport" class="report-section">
      <ExecutionReport :report="report" />
    </div>

    <!-- Fetch report button (when streaming) -->
    <div v-if="status === 'STREAMING'" class="fetch-report-section">
      <el-button type="primary" @click="handleFetchReport">
        获取调试报告
      </el-button>
    </div>

    <!-- Span detail drawer -->
    <SpanDetailDrawer
      :span="selectedSpan"
      :visible="drawerVisible"
      @close="handleDrawerClose"
    />
  </div>
</template>

<style scoped>
.apm-debug-view {
  padding: 20px;
}

.state-card {
  margin-bottom: 16px;
}

.error-alert {
  margin-bottom: 16px;
}

.main-content {
  margin-bottom: 16px;
}

.report-section {
  margin-top: 16px;
}

.fetch-report-section {
  text-align: center;
  margin-top: 16px;
}
</style>
