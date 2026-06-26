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
            <el-button type="primary" :loading="reanalyzing" @click="handleReanalyze">重新分析</el-button>
            <el-button @click="goBack">返回</el-button>
          </div>
        </div>
      </template>

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

        <!-- 错误摘要 -->
        <div v-if="report.errorSummary" class="report-section">
          <h4 class="section-title">错误摘要</h4>
          <div class="markdown-content error-summary" v-html="renderMarkdown(report.errorSummary)"></div>
        </div>

        <!-- 根本原因 -->
        <div v-if="report.rootCause" class="report-section">
          <h4 class="section-title">根本原因</h4>
          <div class="markdown-content root-cause" v-html="renderMarkdown(report.rootCause)"></div>
        </div>

        <!-- 修复建议 -->
        <div v-if="report.fixSuggestions" class="report-section">
          <h4 class="section-title">修复建议</h4>
          <div class="markdown-content fix-suggestions" v-html="renderMarkdown(report.fixSuggestions)"></div>
        </div>

        <!-- 代码片段 -->
        <div v-if="report.codeSnippets" class="report-section">
          <h4 class="section-title">相关代码</h4>
          <div class="markdown-content code-snippets" v-html="renderMarkdown(report.codeSnippets)"></div>
        </div>

        <el-empty v-if="!report.errorSummary && !report.rootCause && !report.fixSuggestions" description="暂无分析结果" />
      </div>

      <el-empty v-else description="报告不存在" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logAnalysisApi } from '@/api/logAnalysis'
import type { DetailedAnalysisReport } from '@/types/log'
import { renderMarkdown } from '@/utils/markdown'
import { downloadBlob } from '@/utils/download'

const route = useRoute()
const router = useRouter()
const reportId = computed(() => route.params.id as string)
const loading = ref(false)
const reanalyzing = ref(false)
const exporting = ref(false)
const report = ref<DetailedAnalysisReport | null>(null)

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


const loadReport = async () => {
  loading.value = true
  try {
    const res = await logAnalysisApi.getReport(reportId.value)
    report.value = res
  } catch (error: any) {
    if (error.response?.status === 400 || error.message?.includes('尚未完成')) {
      ElMessage.warning('报告正在处理中，请稍后再试')
    } else {
      ElMessage.error('加载报告失败')
    }
    console.error('Failed to load report:', error)
  } finally {
    loading.value = false
  }
}

const handleReanalyze = async () => {
  reanalyzing.value = true
  try {
    await logAnalysisApi.reanalyze(reportId.value)
    ElMessage.success('已触发重新分析，请稍后刷新查看结果')
    setTimeout(loadReport, 3000)
  } catch {
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

onMounted(loadReport)
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
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
  padding-left: 10px;
  border-left: 3px solid #409eff;
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
