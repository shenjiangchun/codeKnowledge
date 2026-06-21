<script setup lang="ts">
/**
 * StatusPage — project status analysis report display.
 *
 * Polls for the report and renders it as Markdown.
 * After Phase1 completion, shows button to start Phase2 precise analysis.
 */
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { marked } from 'marked'
import { getStatusReport, startPhase2Analysis } from '@/api/ram'

const route = useRoute()
const router = useRouter()

const sid = computed<string>(() => String(route.params.sid ?? ''))

const status = ref<string>('RUNNING')
const report = ref<Record<string, unknown> | null>(null)
const loading = ref<boolean>(true)
const pollTimer = ref<number | null>(null)
const error = ref<string | null>(null)

// Phase2 state
const showPhase2Dialog = ref<boolean>(false)
const phase2Question = ref<string>('')
const phase2Submitting = ref<boolean>(false)

// 判断是否正在运行（RUNNING状态 或 loading中）
const isRunning = computed(() => loading.value || status.value === 'RUNNING')

// 判断是否成功完成
const isSuccess = computed(() => status.value === 'DONE' && report.value?.['success'] !== false)

// 判断是否失败（包含 FAILED状态 或 success=false）
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

// Show Phase2 button when Phase1 is complete
const canStartPhase2 = computed(() => isSuccess.value && markdownReport.value != null)

function renderMarkdown(text: string | null): string {
  if (!text) return ''
  try {
    return marked.parse(text, { breaks: true, gfm: true }) as string
  } catch {
    return text
  }
}

async function fetchReport(): Promise<void> {
  if (!sid.value) {
    loading.value = false
    error.value = '缺少会话ID参数'
    return
  }
  try {
    const resp = await getStatusReport(sid.value)
    status.value = resp.status
    report.value = resp.report

    // 只有 DONE 或 FAILED 时才停止轮询
    if (resp.status === 'DONE' || resp.status === 'FAILED') {
      stopPolling()
      loading.value = false
    }

    if (resp.status === 'FAILED') {
      error.value = '分析执行失败'
      ElMessage.error('分析失败，请查看错误信息')
    }

    // 检查 success=false 的情况
    if (resp.report?.['success'] === false) {
      error.value = String(resp.report?.['message'] || '数据生成失败')
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '获取报告失败'
    error.value = msg
    ElMessage.error(msg)
    stopPolling()
    loading.value = false
  }
}

function startPolling(): void {
  fetchReport()
  pollTimer.value = window.setInterval(fetchReport, 3000)
}

function stopPolling(): void {
  if (pollTimer.value != null) {
    window.clearInterval(pollTimer.value)
    pollTimer.value = null
  }
}

onMounted(startPolling)
onBeforeUnmount(stopPolling)

function goBack(): void {
  router.push({ name: 'StatusSessions' })
}

/** Open Phase2 dialog */
function openPhase2Dialog(): void {
  phase2Question.value = ''
  showPhase2Dialog.value = true
}

/** Start Phase2 analysis */
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
    // Navigate to Phase2 results page
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

      <!-- 正在运行：显示加载动画 -->
      <div v-if="isRunning" class="loading-container">
        <el-icon class="is-loading" :size="32">
          <Loading />
        </el-icon>
        <span class="loading-text">正在分析项目现状（预计耗时1-3分钟）...</span>
        <span class="loading-hint">正在收集知识图谱数据并生成报告</span>
      </div>

      <!-- 失败：显示错误信息 -->
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

      <!-- 成功完成：显示报告 -->
      <div v-else-if="report && isSuccess" class="report-container">
        <!-- 完整 Markdown 报告 -->
        <div v-if="markdownReport" class="markdown-section">
          <div class="markdown-content" v-html="renderMarkdown(markdownReport)"></div>
        </div>

        <!-- 分段展示（备用） -->
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

      <!-- 其他情况：空数据 -->
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