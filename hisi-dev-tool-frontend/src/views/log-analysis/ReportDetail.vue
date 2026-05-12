<template>
  <div class="report-detail">
    <el-card v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>分析报告 #{{ reportId }}</span>
          <el-button @click="goBack">返回</el-button>
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
        </el-descriptions>

        <el-divider />

        <!-- 错误摘要 -->
        <div v-if="report.errorSummary" class="report-section">
          <h4 class="section-title">错误摘要</h4>
          <div class="section-content error-summary">{{ report.errorSummary }}</div>
        </div>

        <!-- 根本原因 -->
        <div v-if="report.rootCause" class="report-section">
          <h4 class="section-title">根本原因</h4>
          <div class="section-content root-cause">{{ report.rootCause }}</div>
        </div>

        <!-- 修复建议 -->
        <div v-if="report.fixSuggestions" class="report-section">
          <h4 class="section-title">修复建议</h4>
          <div class="section-content fix-suggestions">
            <pre>{{ report.fixSuggestions }}</pre>
          </div>
        </div>

        <!-- 代码片段 -->
        <div v-if="report.codeSnippets" class="report-section">
          <h4 class="section-title">相关代码</h4>
          <div class="section-content code-snippets">
            <pre>{{ report.codeSnippets }}</pre>
          </div>
        </div>

        <el-empty v-if="!report.errorSummary && !report.rootCause && !report.fixSuggestions" description="暂无分析结果" />
      </div>

      <el-empty v-else description="报告不存在" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logAnalysisApi } from '@/api/logAnalysis'
import type { DetailedAnalysisReport } from '@/types/log'

const route = useRoute()
const router = useRouter()
const reportId = route.params.id as string
const loading = ref(false)
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

onMounted(async () => {
  loading.value = true
  try {
    const res = await logAnalysisApi.getReport(Number(reportId))
    report.value = res.data
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
})
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
  color: #f56c6c;
}

.root-cause {
  background: #fdf6ec;
  border: 1px solid #faecd8;
  color: #e6a23c;
}

.fix-suggestions {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
}

.fix-suggestions pre {
  color: #67c23a;
}

.code-snippets {
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: monospace;
  font-size: 13px;
}

.code-snippets pre {
  color: #d4d4d4;
}
</style>