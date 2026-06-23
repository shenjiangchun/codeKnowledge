<script setup lang="ts">
/**
 * StatusPage — project status analysis report display.
 *
 * Uses REST-first + SSE incremental pattern:
 * 1. Load report from REST API (authoritative source)
 * 2. If session still running, open SSE for live CHECKPOINT updates
 * 3. Monitor 'project_overview' CHECKPOINT events for report payload
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { renderMarkdown } from '@/utils/markdown'
import { getStatusReport, startPhase2Analysis } from '@/api/ram'
import { useRamSession } from '@/composables/useRamSession'

const route = useRoute()
const router = useRouter()
const session = useRamSession()

const sid = computed<string>(() => String(route.params.sid ?? ''))

const report = ref<Record<string, unknown> | null>(null)
const loading = ref<boolean>(true)
const error = ref<string | null>(null)
const fallbackPollTimer = ref<number | null>(null)

// Phase2 state
const showPhase2Dialog = ref<boolean>(false)
const phase2Question = ref<string>('')
const phase2Submitting = ref<boolean>(false)

// Derive status from session status and report
const status = computed(() => {
  const s = session.status.value
  if (s === 'completed') return 'DONE'
  if (s === 'error') return 'FAILED'
  if (s === 'aborted') return 'FAILED'
  if (report.value && report.value['success'] !== false) return 'DONE'
  if (report.value?.['success'] === false) return 'FAILED'
  return 'RUNNING'
})

const isRunning = computed(() => loading.value || status.value === 'RUNNING')
const isSuccess = computed(() => status.value === 'DONE' && report.value?.['success'] !== false)
const isFailed = computed(() => status.value === 'FAILED' || report.value?.['success'] === false)

const markdownReport = computed(() => {
  const md = report.value?.['markdown_report']
  return typeof md === 'string' && md.trim() ? md.trim() : null
})

const question = computed(() => {
  const q = report.value?.['question']
  return typeof q === 'string' && q.trim() ? q.trim() : null
})

const entryPointsSummary = computed(() => {
  const summary = report.value?.['entry_points_summary']
  return typeof summary === 'string' ? summary : null
})

const coreCallChains = computed(() => {
  const chains = report.value?.['core_call_chains']
  return Array.isArray(chains) ? chains : []
})

const techStack = computed(() => {
  const ts = report.value?.['tech_stack']
  return ts != null && typeof ts === 'object' && !Array.isArray(ts)
    ? (ts as Record<string, unknown>)
    : null
})

const recommendations = computed(() => {
  const recs = report.value?.['recommendations']
  return Array.isArray(recs) ? recs : []
})

const canStartPhase2 = computed(() => isSuccess.value && markdownReport.value != null)

// Track processed seq for SSE event dedup
let processedSeq = 0

/** Watch SSE events for project_overview CHECKPOINT */
watch(
  () => session.events.value,
  (events) => {
    for (const evt of events) {
      if (evt.seq <= processedSeq) continue
      processedSeq = evt.seq

      if (evt.type === 'CHECKPOINT') {
        const nodeName = evt.payload['nodeName']
        if (nodeName === 'project_overview') {
          const output = evt.payload['output']
          if (output && typeof output === 'object' && !Array.isArray(output)) {
            report.value = output as Record<string, unknown>
            loading.value = false
            stopFallbackPolling()
          }
        }
      }
    }
  },
  { deep: true }
)

/** Watch session status for completion */
watch(
  () => session.status.value,
  (s) => {
    if (s === 'completed' || s === 'error' || s === 'aborted') {
      loading.value = false
      stopFallbackPolling()
    }
    if (s === 'error') {
      error.value = '分析执行失败'
      ElMessage.error('分析失败，请查看错误信息')
    }
  }
)

/** Fallback polling when SSE fails */
async function fetchReportFallback(): Promise<void> {
  if (!sid.value) {
    loading.value = false
    error.value = '缺少会话ID参数'
    return
  }
  try {
    const resp = await getStatusReport(sid.value)
    if (resp.report && Object.keys(resp.report).length > 0) {
      report.value = resp.report
    }

    if (resp.status === 'DONE' || resp.status === 'FAILED') {
      stopFallbackPolling()
      loading.value = false
    }

    if (resp.status === 'FAILED') {
      error.value = '分析执行失败'
      ElMessage.error('分析失败，请查看错误信息')
    }

    if (resp.report?.['success'] === false) {
      error.value = String(resp.report?.['message'] || '数据生成失败')
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '获取报告失败'
    error.value = msg
    ElMessage.error(msg)
    stopFallbackPolling()
    loading.value = false
  }
}

function startFallbackPolling(): void {
  fetchReportFallback()
  fallbackPollTimer.value = window.setInterval(fetchReportFallback, 3000)
}

function stopFallbackPolling(): void {
  if (fallbackPollTimer.value != null) {
    window.clearInterval(fallbackPollTimer.value)
    fallbackPollTimer.value = null
  }
}

async function initSession(id: string): Promise<void> {
  if (!id) {
    ElMessage.error('缺少 session id')
    router.replace({ name: 'StatusSessions' })
    return
  }

  loading.value = true
  error.value = null
  report.value = null
  processedSeq = 0

  // Step 1: REST authoritative — load report from REST API
  try {
    const resp = await getStatusReport(id)
    if (resp.report && Object.keys(resp.report).length > 0) {
      report.value = resp.report
    }
    if (resp.status === 'DONE' || resp.status === 'FAILED') {
      loading.value = false
      return  // Session finished, no SSE needed
    }
  } catch (e) {
    console.warn('[StatusPage] Failed to load report via REST:', e)
  }

  // Step 2: Session still running, open SSE for live updates
  try {
    await session.rejoin(id, 0)
  } catch (e) {
    // SSE failed, use fallback polling
    const msg = e instanceof Error ? e.message : 'SSE连接失败'
    console.warn('[StatusPage] SSE rejoin failed, using fallback polling:', msg)
    startFallbackPolling()
  }

  // Step 3: After rejoin, if report is still empty, try REST again
  // This handles the case where getStatusReport returned RUNNING but the
  // session actually finished before rejoin() completed.
  if (!report.value || Object.keys(report.value).length === 0) {
    try {
      const resp = await getStatusReport(id)
      if (resp.report && Object.keys(resp.report).length > 0) {
        report.value = resp.report
        loading.value = false
      }
    } catch { /* non-critical */ }
  }

  // Step 4: If session reached terminal state, ensure loading is off
  const finalStatus = session.status.value
  if (finalStatus === 'completed' || finalStatus === 'error' || finalStatus === 'aborted') {
    loading.value = false
  }
}

// Watch sid for route param changes
watch(sid, async (newSid, oldSid) => {
  if (newSid && newSid !== oldSid) {
    await initSession(newSid)
  }
})

onMounted(async () => {
  await initSession(sid.value)
})

onBeforeUnmount(() => {
  session.disconnect()
  stopFallbackPolling()
})

function goBack(): void {
  router.push({ name: 'StatusSessions' })
}

function openPhase2Dialog(): void {
  phase2Question.value = ''
  showPhase2Dialog.value = true
}

async function onStartPhase2(): Promise<void> {
  if (!phase2Question.value.trim()) {
    ElMessage.warning('请输入分析问题')
    return
  }
  if (!sid.value) return

  phase2Submitting.value = true
  try {
    const resp = await startPhase2Analysis({
      sessionId: sid.value,
      question: phase2Question.value.trim()
    })
    ElMessage.success('已启动精确分析')
    showPhase2Dialog.value = false
    await router.push({ name: 'RamPhase2', params: { sid: resp.phase2SessionId } })
  } catch (e) {
    const msg = e instanceof Error ? e.message : '启动精确分析失败'
    ElMessage.error(msg)
  } finally {
    phase2Submitting.value = false
  }
}
</script>

<template>
  <div class="status-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>项目现状分析报告</span>
          <div class="header-actions">
            <el-tag :type="isSuccess ? 'success' : isFailed ? 'danger' : 'warning'">
              {{ isSuccess ? '已完成' : isFailed ? '失败' : '运行中' }}
            </el-tag>
            <el-button
              v-if="canStartPhase2"
              type="primary"
              size="small"
              @click="openPhase2Dialog"
            >
              进入精确分析
            </el-button>
            <el-button size="small" @click="goBack">返回</el-button>
          </div>
        </div>
      </template>

      <!-- Show user question if provided -->
      <div v-if="question" class="question-section">
        <el-alert type="info" :closable="false">
          <template #title>
            <span class="question-label">分析问题：</span>
            <span class="question-text">{{ question }}</span>
          </template>
        </el-alert>
      </div>

      <!-- Running: loading animation -->
      <div v-if="isRunning" class="loading-container">
        <el-icon class="is-loading" :size="32">
          <Loading />
        </el-icon>
        <span class="loading-text">正在分析项目现状（预计耗时1-3分钟）...</span>
        <span class="loading-hint">正在收集知识图谱数据并生成报告</span>
      </div>

      <!-- Failed: error message -->
      <div v-else-if="isFailed" class="error-container">
        <el-result icon="error" title="分析失败" :sub-title="error || '请检查日志查看详情'">
          <template #extra>
            <el-button type="primary" @click="goBack">返回重试</el-button>
          </template>
        </el-result>
        <div v-if="report?.['message']" class="error-detail">
          <el-alert type="warning" :closable="false">
            <template #title>详细信息</template>
            {{ report['message'] }}
          </el-alert>
        </div>
      </div>

      <!-- Success: show report -->
      <div v-else-if="report && isSuccess" class="report-container">
        <div v-if="markdownReport" class="markdown-section">
          <div class="markdown-content" v-html="renderMarkdown(markdownReport)"></div>
        </div>

        <!-- Fallback: segmented display -->
        <div v-else>
          <el-collapse>
            <el-collapse-item title="入口点概览" name="entry">
              <div v-if="entryPointsSummary" class="section-content">
                {{ entryPointsSummary }}
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="核心调用链" name="chains">
              <div v-if="coreCallChains.length > 0">
                <div v-for="(chain, idx) in coreCallChains" :key="idx" class="chain-item">
                  <strong>{{ chain['method'] }}</strong>
                  <span class="chain-desc">{{ chain['description'] }}</span>
                </div>
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="技术栈" name="tech">
              <div v-if="techStack">
                <el-descriptions :column="2" border>
                  <el-descriptions-item label="框架">{{ techStack['framework'] || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="数据库">{{ techStack['database'] || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="消息队列">{{ techStack['mq'] || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="外部服务">
                    {{ Array.isArray(techStack['external_services']) ? techStack['external_services'].join(', ') : '-' }}
                  </el-descriptions-item>
                </el-descriptions>
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="新员工建议" name="recommendations">
              <div v-if="recommendations.length > 0">
                <ul>
                  <li v-for="(rec, idx) in recommendations" :key="idx">
                    <strong>{{ rec['topic'] }}</strong>: {{ rec['detail'] }}
                  </li>
                </ul>
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>

      <!-- Empty -->
      <div v-else class="empty-container">
        <el-empty description="暂无报告数据">
          <el-button type="primary" @click="goBack">返回</el-button>
        </el-empty>
      </div>
    </el-card>

    <!-- Phase2 Dialog -->
    <el-dialog
      v-model="showPhase2Dialog"
      title="精确位置分析"
      width="500px"
    >
      <el-form label-position="top">
        <el-form-item label="分析问题" required>
          <el-input
            v-model="phase2Question"
            type="textarea"
            :rows="4"
            placeholder="输入你想深入了解的问题，如：'需求状态变更的完整调用链是什么？涉及哪些外部服务？'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showPhase2Dialog = false">取消</el-button>
        <el-button type="primary" :loading="phase2Submitting" @click="onStartPhase2">
          开始精确分析
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.status-page {
  padding: 16px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
  gap: 12px;
}
.loading-text {
  color: #606266;
  font-size: 14px;
  font-weight: 500;
}
.loading-hint {
  color: #909399;
  font-size: 12px;
}
.error-container {
  padding: 20px;
}
.error-detail {
  margin-top: 16px;
}
.report-container {
  padding: 16px;
}
.markdown-section {
  background: #fafafa;
  padding: 20px;
  border-radius: 8px;
}
.markdown-content {
  font-size: 14px;
  line-height: 1.6;
}
.markdown-content h1,
.markdown-content h2,
.markdown-content h3 {
  margin-top: 16px;
  margin-bottom: 8px;
}
.markdown-content h1 {
  font-size: 20px;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 8px;
}
.markdown-content h2 {
  font-size: 18px;
}
.markdown-content h3 {
  font-size: 16px;
}
.markdown-content ul,
.markdown-content ol {
  padding-left: 20px;
}
.markdown-content table {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}
.markdown-content th,
.markdown-content td {
  border: 1px solid #ebeef5;
  padding: 8px 12px;
}
.markdown-content th {
  background: #f5f7fa;
}
.markdown-content code {
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.markdown-content pre {
  background: #282c34;
  color: #abb2bf;
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
}
.markdown-content pre code {
  background: transparent;
  padding: 0;
}
.section-content {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}
.chain-item {
  padding: 8px 12px;
  border-bottom: 1px solid #ebeef5;
}
.chain-item:last-child {
  border-bottom: none;
}
.chain-desc {
  color: #606266;
  margin-left: 8px;
}
.empty-hint {
  color: #909399;
  text-align: center;
  padding: 16px;
}
.empty-container {
  padding: 40px 0;
}
.question-section {
  margin-bottom: 16px;
}
.question-label {
  font-weight: 600;
  color: #409eff;
}
.question-text {
  color: #303133;
}
</style>
