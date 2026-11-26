<template>
  <div class="report-detail">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>分析报告 #{{ reportId }}</span>
          <div>
            <el-button type="success" :loading="exporting" @click="handleExportMd">
              导出 MD
            </el-button>
            <el-button
              v-if="canAutoFix"
              type="warning"
              @click="handleAutoFix"
            >
              <el-icon style="margin-right: 4px"><SetUp /></el-icon>
              自动修复
            </el-button>
            <el-button type="primary" :loading="reanalyzing" :disabled="analyzing" @click="handleReanalyze">重新分析</el-button>
            <el-button @click="goBack">返回</el-button>
          </div>
        </div>
      </template>

      <!-- DAG 实时进度条：重新分析期间，或报告仍在处理中时显示 -->
      <NodeTimeline v-if="isProcessing" :events="nodeEvents" />

      <div v-if="report">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="报告ID">{{ report.reportId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(report.status)">{{ report.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(report.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(report.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="出现次数" v-if="report.occurrenceCount && report.occurrenceCount > 1">
            <el-tag type="warning">{{ report.occurrenceCount }} 次合并</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider />

        <!-- 模式识别标签 (v3) -->
        <div v-if="report.patternType && report.patternType !== 'UNKNOWN'" class="pattern-tag-bar">
          <el-tag :type="getPatternTagType(report.patternType)" effect="dark" size="large">
            {{ getPatternLabel(report.patternType) }}
          </el-tag>
          <el-tag v-if="report.patternConfidence" :type="getConfidenceType(String(report.patternConfidence))" effect="plain" class="confidence-tag">
            置信度: {{ report.patternConfidence }}
          </el-tag>
          <el-tag v-if="report.analysisVersion" type="info" effect="plain" class="version-tag">
            v{{ report.analysisVersion }}
          </el-tag>
        </div>

        <!-- 错误摘要：后端返回的是对象；早期报告可能存成字符串，那时按 markdown 渲染 -->
        <div v-if="report.errorSummary" class="report-section">
          <h4 class="section-title">错误摘要</h4>
          <el-descriptions v-if="errorSummary" :column="1" border size="small">
            <el-descriptions-item v-if="errorSummary.errorType" label="异常类型">{{ errorSummary.errorType }}</el-descriptions-item>
            <el-descriptions-item v-if="errorSummary.rootCauseException" label="根因异常">{{ errorSummary.rootCauseException }}</el-descriptions-item>
            <el-descriptions-item v-if="errorSummary.message" label="消息">{{ errorSummary.message }}</el-descriptions-item>
            <el-descriptions-item v-if="errorSummary.stackTracePreview" label="堆栈预览">
              <pre class="stack-preview">{{ errorSummary.stackTracePreview }}</pre>
            </el-descriptions-item>
          </el-descriptions>
          <div v-else class="markdown-content error-summary" v-html="renderMarkdown(String(report.errorSummary))"></div>
        </div>

        <!-- 根本原因：正文是 markdown；v2 的报告另带验证结果与证据 -->
        <div v-if="report.rootCause" class="report-section">
          <h4 class="section-title">
            根本原因
            <el-tag v-if="rootCause?.confidence" :type="getConfidenceType(String(rootCause.confidence))" effect="plain" size="small">
              置信度: {{ rootCause.confidence }}
            </el-tag>
          </h4>
          <template v-if="rootCause">
            <div v-if="rootCause.markdown" class="markdown-content root-cause" v-html="renderMarkdown(displayMarkdown(rootCause.markdown))"></div>
            <div v-if="hypotheses.length" class="sub-section">
              <div class="sub-title">验证结果</div>
              <el-table :data="hypotheses" size="small" border>
                <el-table-column label="结论" min-width="300">
                  <template #default="{ row }">
                    <div>{{ row.title }}</div>
                    <div v-for="(reason, i) in row.reasons || []" :key="i" class="verify-reason">{{ reason }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="是否保留" width="92">
                  <template #default="{ row }">{{ row.kept ? '保留' : '排除' }}</template>
                </el-table-column>
                <el-table-column label="置信度" width="84">
                  <template #default="{ row }">{{ row.confidence || '-' }}</template>
                </el-table-column>
                <el-table-column label="因果链" width="150">
                  <template #default="{ row }">{{ chainStatusLabel(row.chainStatus) }}</template>
                </el-table-column>
              </el-table>
            </div>
            <div v-if="rootCause.evidenceAnchors?.length" class="sub-section">
              <div class="sub-title">证据</div>
              <el-table :data="rootCause.evidenceAnchors" size="small" border>
                <el-table-column prop="id" label="编号" width="72" />
                <el-table-column label="类型" width="140">
                  <template #default="{ row }">{{ row.kindLabel || row.kind }}</template>
                </el-table-column>
                <el-table-column prop="summary" label="说明" min-width="320" />
              </el-table>
            </div>
          </template>
          <div v-else class="markdown-content root-cause" v-html="renderMarkdown(String(report.rootCause))"></div>
        </div>

        <!-- 修复建议：带优先级的列表 -->
        <div v-if="hasFixSuggestions" class="report-section">
          <h4 class="section-title">修复建议</h4>
          <ul v-if="fixSuggestions.length" class="fix-list">
            <li v-for="(fix, idx) in fixSuggestions" :key="idx" class="fix-item">
              <div class="fix-head">
                <el-tag :type="getPriorityType(fix.priority)" size="small" effect="dark">{{ fix.priority || 'P2' }}</el-tag>
                <span class="fix-desc">{{ fix.description }}</span>
              </div>
              <div v-if="fix.affectedCode" class="fix-code">{{ fix.affectedCode }}</div>
              <div v-if="fix.expectedEffect" class="fix-effect">预期效果：{{ fix.expectedEffect }}</div>
            </li>
          </ul>
          <div v-else class="markdown-content fix-suggestions" v-html="renderMarkdown(String(report.fixSuggestions))"></div>
        </div>

        <!-- 关键堆栈帧 -->
        <div v-if="hasCodeSnippets" class="report-section">
          <h4 class="section-title">关键堆栈帧</h4>
          <ul v-if="codeSnippets.length" class="frame-list">
            <li v-for="(frame, idx) in codeSnippets" :key="idx" class="frame-item">
              <code>{{ frame.className }}.{{ frame.methodName }}()</code>
              <span v-if="frame.fileName" class="frame-loc">{{ frame.fileName }}<template v-if="frame.lineNumber">:{{ frame.lineNumber }}</template></span>
            </li>
          </ul>
          <div v-else class="markdown-content code-snippets" v-html="renderMarkdown(String(report.codeSnippets))"></div>
        </div>

        <el-empty v-if="!report.errorSummary && !report.rootCause && !hasFixSuggestions" description="暂无分析结果" />

        <!-- 追问对话面板 (completed 状态) -->
        <FollowupPanel v-if="isCompleted" :report-id="reportId" />

        <!-- 历史修复会话列表 -->
        <div v-if="fixSessions.length > 0" class="report-section">
          <h3 class="section-title">修复历史</h3>
          <el-table :data="fixSessions" stripe size="small">
            <el-table-column prop="id" label="会话ID" width="120" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="getFixStatusType(row.status)" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="branchName" label="分支" min-width="150" />
            <el-table-column prop="throwPointSig" label="异常位置" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="openFixSession(row.id)">
                  查看
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <el-empty v-else description="报告不存在" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logAnalysisApi } from '@/api/logAnalysis'
import { fixApi } from '@/api/fix'
import type { FixSession } from '@/api/fix'
import { SetUp } from '@element-plus/icons-vue'
import type {
  DetailedAnalysisReport, ErrorSummary, RootCauseSection, FixSuggestion, StackFrameRef, HypothesisVerification
} from '@/types/log'
import { renderMarkdown } from '@/utils/markdown'
import { downloadBlob } from '@/utils/download'
import { useLogAnalysisWebSocket } from '@/composables/useLogAnalysisWebSocket'
import NodeTimeline from '@/components/log-analysis/NodeTimeline.vue'
import FollowupPanel from '@/components/log-analysis/FollowupPanel.vue'

const route = useRoute()
const router = useRouter()
const reportId = computed(() => route.params.id as string)
const loading = ref(false)
const reanalyzing = ref(false)
const exporting = ref(false)
const report = ref<DetailedAnalysisReport | null>(null)
const fixSessions = ref<FixSession[]>([])

// Real-time DAG progress via WebSocket
const { events: nodeEvents, connected: wsConnected, connect: connectWs } = useLogAnalysisWebSocket(() => reportId.value)
// 本页触发的「重新分析」进行中：此时报告详情接口会返回「尚未完成」，进度靠节点事件和任务状态轮询
const analyzing = ref(false)
const isProcessing = computed(() => {
  const s = report.value?.status?.toLowerCase()
  return analyzing.value || s === 'processing' || s === 'pending'
})
const isCompleted = computed(() => report.value?.status?.toLowerCase() === 'completed')

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

// 报告的四个内容字段都是结构化数据（对象 / 数组）；早期报告可能存成字符串，那时退回按 markdown 渲染
const errorSummary = computed<ErrorSummary | null>(() =>
  isRecord(report.value?.errorSummary) ? report.value!.errorSummary as ErrorSummary : null)
const rootCause = computed<RootCauseSection | null>(() =>
  isRecord(report.value?.rootCause) ? report.value!.rootCause as RootCauseSection : null)
const fixSuggestions = computed<FixSuggestion[]>(() =>
  Array.isArray(report.value?.fixSuggestions) ? report.value!.fixSuggestions as FixSuggestion[] : [])
const codeSnippets = computed<StackFrameRef[]>(() =>
  Array.isArray(report.value?.codeSnippets) ? report.value!.codeSnippets as StackFrameRef[] : [])
const hypotheses = computed<HypothesisVerification[]>(() => rootCause.value?.verification?.hypotheses ?? [])
const hasFixSuggestions = computed(() =>
  !!report.value?.fixSuggestions && (typeof report.value.fixSuggestions === 'string' || fixSuggestions.value.length > 0))
const hasCodeSnippets = computed(() =>
  !!report.value?.codeSnippets && (typeof report.value.codeSnippets === 'string' || codeSnippets.value.length > 0))

/** 报告正文里的证据引用写成 [[E3]]，展示时转成 [E3]，与证据表的编号一致 */
const displayMarkdown = (text: string) =>
  text.replace(/\[\[\s*E\s*(\d{1,3})\s*\]\]/g, '[E$1]').replace(/\[\[\s*未验证\s*\]\]/g, '[未验证引用]')

const CHAIN_STATUS_LABELS: Record<string, string> = {
  verified: '已由调用图证实',
  stack_verified: '堆栈已证实',
  at_throw_site: '根因落在抛出点',
  unverified: '未证实',
  unknown: '无法判断'
}
const chainStatusLabel = (status?: string) => (status && CHAIN_STATUS_LABELS[status]) || status || '-'

const getPriorityType = (priority?: string) =>
  priority === 'P0' ? 'danger' : priority === 'P1' ? 'warning' : 'info'

const canAutoFix = computed(() => {
  const s = report.value?.status?.toLowerCase()
  return s === 'completed' || s === 'error'
})

const getStatusType = (status: string) => {
  const types: Record<string, string> = {
    completed: 'success',
    processing: 'warning',
    failed: 'danger',
    pending: 'info'
  }
  return types[status?.toLowerCase()] || ''
}

const formatTime = (time: string | undefined) => {
  if (!time) return '-'
  try {
    return new Date(time).toLocaleString('zh-CN')
  } catch {
    return time
  }
}

const goBack = () => {
  router.push('/log-analysis')
}

const handleAutoFix = () => {
  router.push(`/fix/chat?reportId=${reportId.value}`)
}

const loadFixSessions = async () => {
  try {
    const sessions = await fixApi.listByReport(reportId.value)
    fixSessions.value = sessions
  } catch {
    // 静默失败：报告可能没有修复历史
  }
}

const openFixSession = (sid: string) => {
  router.push(`/fix/chat/${sid}`)
}

const getFixStatusType = (status: string): string => {
  const map: Record<string, string> = {
    RUNNING: 'warning',
    SUCCESS: 'success',
    FAILED: 'danger',
    REPRO_FAILED: 'danger',
    TEST_NOT_PASSED: 'danger',
    ERROR: 'danger'
  }
  return map[status] || 'info'
}

const getPatternLabel = (pattern: string): string => {
  const labels: Record<string, string> = {
    LOCK_AVALANCHE: '锁雪崩',
    OOM_CASCADE: 'OOM 级联',
    NPE_CHAIN: 'NPE 级联',
    CONNECTION_EXHAUSTION: '连接池耗尽',
    BROKEN_PIPE: '管道断裂',
    SLOW_QUERY: '慢查询阻塞',
    CONFIG_ERROR: '配置错误',
    DATA_INCONSISTENCY: '数据不一致'
  }
  return labels[pattern] || pattern
}

const getPatternTagType = (pattern: string): string => {
  const types: Record<string, string> = {
    LOCK_AVALANCHE: 'danger',
    OOM_CASCADE: 'danger',
    NPE_CHAIN: 'warning',
    CONNECTION_EXHAUSTION: 'warning',
    BROKEN_PIPE: '',
    SLOW_QUERY: 'warning',
    CONFIG_ERROR: 'info',
    DATA_INCONSISTENCY: 'info'
  }
  return types[pattern] || ''
}

const getConfidenceType = (confidence: string): string => {
  if (confidence === 'high') return 'success'
  if (confidence === 'medium') return 'warning'
  return 'info'
}

const loadReport = async () => {
  loading.value = true
  try {
    const res = await logAnalysisApi.getReport(reportId.value)
    report.value = res as DetailedAnalysisReport
    // Connect WebSocket for real-time DAG progress if report is still processing
    if (res.status?.toLowerCase() === 'processing' || res.status?.toLowerCase() === 'pending') {
      connectWs()
    }
  } catch (error: unknown) {
    const err = error as { response?: { status?: number }; message?: string }
    if (err.response?.status === 400 || err.message?.includes('尚未完成')) {
      ElMessage.warning('报告正在处理中，请稍后再试')
    } else {
      ElMessage.error('加载报告失败')
    }
    console.error('Failed to load report:', error)
  } finally {
    loading.value = false
  }
}

let statusTimer: ReturnType<typeof setTimeout> | null = null

const stopStatusPolling = () => {
  if (statusTimer) {
    clearTimeout(statusTimer)
    statusTimer = null
  }
}

const scheduleStatusPoll = (delay: number) => {
  stopStatusPolling()
  statusTimer = setTimeout(pollStatus, delay)
}

/** 轮询任务状态，分析结束后重新加载报告。 */
const pollStatus = async () => {
  try {
    const st = await logAnalysisApi.getStatus(reportId.value)
    if (st.status === 'completed' || st.status === 'failed' || st.status === 'error') {
      analyzing.value = false
      if (st.status === 'completed') {
        ElMessage.success('重新分析完成')
      } else {
        ElMessage.error('重新分析失败')
      }
      await loadReport()
      return
    }
  } catch {
    // 单次状态查询失败不影响轮询，下一轮重试
  }
  scheduleStatusPoll(3000)
}

/** 等 WebSocket 连上（最多 2 秒），避免漏掉第一个节点的事件。 */
const waitForWs = async () => {
  for (let i = 0; i < 20 && !wsConnected.value; i++) {
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
}

const startTracking = () => {
  analyzing.value = true
  scheduleStatusPoll(1000)
}

const handleReanalyze = async () => {
  reanalyzing.value = true
  try {
    // 清掉上一轮的节点事件，连上进度通道后再触发，页面上能看到节点逐个执行
    nodeEvents.value = []
    connectWs()
    await waitForWs()
    await logAnalysisApi.reanalyze(reportId.value)
    startTracking()
    ElMessage.success('已触发重新分析')
  } catch {
    analyzing.value = false
    stopStatusPolling()
    ElMessage.error('触发重新分析失败')
  } finally {
    reanalyzing.value = false
  }
}

const handleExportMd = async () => {
  exporting.value = true
  try {
    const blob = await logAnalysisApi.exportReportMd(reportId.value)
    // Include occurrence count in filename when > 1
    const countSuffix = (report.value?.occurrenceCount && report.value.occurrenceCount > 1)
      ? `-x${report.value.occurrenceCount}`
      : ''
    const filename = `log-report-${reportId.value}${countSuffix}.md`
    downloadBlob(blob, filename)
    ElMessage.success('报告已导出')
  } catch (error: any) {
    ElMessage.error('导出失败: ' + (error.message || '请稍后重试'))
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  // 报告可能仍在分析中（例如分析期间刷新了页面）：此时详情接口会返回「尚未完成」，先看任务状态
  try {
    const st = await logAnalysisApi.getStatus(reportId.value)
    if (st?.status === 'processing' || st?.status === 'pending') {
      connectWs()
      startTracking()
    } else {
      await loadReport()
    }
  } catch {
    await loadReport()
  }
  loadFixSessions()
})

onUnmounted(stopStatusPolling)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.report-section {
  margin-top: 20px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
  padding-left: 10px;
  border-left: 3px solid #409eff;
}

/* 结构化展示：错误摘要 / 验证结果 / 证据 / 修复建议 / 关键堆栈帧 */
.stack-preview {
  margin: 0;
  max-height: 260px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.sub-section {
  margin-top: 12px;
}
.sub-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}
.verify-reason {
  font-size: 12px;
  color: #e6a23c;
}
.fix-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.fix-item {
  padding: 10px 12px;
  margin-bottom: 8px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fff;
}
.fix-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.fix-desc {
  flex: 1;
  line-height: 1.6;
}
.fix-code {
  margin-top: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}
.fix-effect {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.frame-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.frame-item {
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
}
.frame-item:last-child {
  border-bottom: none;
}
.frame-loc {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

.section-content {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 6px;
  font-size: 14px;
  line-height: 1.6;
}

.section-content pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
}

.error-summary {
  background: #fef0f0;
  border: 1px solid #fde2e2;
}

.root-cause {
  background: #fdf6ec;
  border: 1px solid #faecd8;
}

.fix-suggestions {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
}

.code-snippets {
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: monospace;
  font-size: 13px;
}

/* Markdown 渲染样式 */
.markdown-content {
  padding: 16px;
  border-radius: 6px;
  font-size: 14px;
  line-height: 1.6;
  max-height: 400px;
  overflow-y: auto;
}

.markdown-content h1, .markdown-content h2, .markdown-content h3,
.markdown-content h4, .markdown-content h5, .markdown-content h6 {
  margin: 0 0 12px 0;
  color: #303133;
}

.markdown-content h1 { font-size: 18px; }
.markdown-content h2 { font-size: 16px; }
.markdown-content h3 { font-size: 15px; }

.markdown-content p { margin: 0 0 12px 0; }

.markdown-content ul, .markdown-content ol {
  margin: 0 0 12px 0;
  padding-left: 20px;
}

.markdown-content li { margin-bottom: 6px; }

.markdown-content code {
  background: #e4e7ed;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
}

.markdown-content pre {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 12px 0;
}

.markdown-content pre code {
  background: transparent;
  color: inherit;
  padding: 0;
}

.markdown-content strong {
  color: #303133;
  font-weight: 600;
}

.error-summary.markdown-content {
  background: #fef0f0;
  border: 1px solid #fbc4c4;
}

.pattern-tag-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.confidence-tag,
.version-tag {
  font-size: 12px;
}

.root-cause.markdown-content {
  background: #fdf6ec;
  border: 1px solid #faecd8;
}

.fix-suggestions.markdown-content {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
}

.code-snippets.markdown-content {
  background: #1e1e1e;
  color: #d4d4d4;
}

.code-snippets.markdown-content h1, .code-snippets.markdown-content h2,
.code-snippets.markdown-content h3, .code-snippets.markdown-content h4,
.code-snippets.markdown-content h5, .code-snippets.markdown-content h6,
.code-snippets.markdown-content strong {
  color: #e5c07b;
}

.code-snippets.markdown-content p {
  color: #d4d4d4;
}
</style>
