<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getDiff } from '@/api/merge-analysis'
import type { DiffResult, FileDiff } from '@/types/merge-analysis'

const route = useRoute()
const router = useRouter()

const projectPath = route.query.projectPath as string
const sourceBranch = route.query.sourceBranch as string
const targetBranch = route.query.targetBranch as string

const diffResult = ref<DiffResult | null>(null)
const loading = ref(false)
const expandedFiles = ref<string[]>([])

function changeTypeColor(type: string): string {
  switch (type) {
    case 'ADD': return 'success'
    case 'DELETE': return 'danger'
    case 'RENAME': return 'warning'
    default: return 'primary'
  }
}

function toggleFile(filePath: string) {
  const idx = expandedFiles.value.indexOf(filePath)
  if (idx >= 0) {
    expandedFiles.value = expandedFiles.value.filter(f => f !== filePath)
  } else {
    expandedFiles.value = [...expandedFiles.value, filePath]
  }
}

function handleStartAnalysis() {
  router.push({
    name: 'MergeAnalysisResult',
    query: { projectPath, sourceBranch, targetBranch }
  })
}

function handleBack() {
  router.push({ name: 'MergeAnalysisInput' })
}

onMounted(async () => {
  if (!projectPath || !sourceBranch || !targetBranch) {
    ElMessage.error('缺少必要参数')
    router.push({ name: 'MergeAnalysisInput' })
    return
  }
  loading.value = true
  try {
    diffResult.value = await getDiff({ projectPath, sourceBranch, targetBranch })
  } catch {
    ElMessage.error('获取 Diff 失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="diff-preview">
    <el-page-header @back="handleBack" style="margin-bottom: 20px">
      <template #content>
        <span>Diff 预览: {{ sourceBranch }} → {{ targetBranch }}</span>
      </template>
    </el-page-header>

    <div v-if="loading" v-loading="true" style="min-height: 200px" />

    <template v-else-if="diffResult">
      <el-card class="stats-bar" shadow="never">
        <el-row :gutter="20">
          <el-col :span="8">
            <el-statistic title="变更文件数" :value="diffResult.totalFiles" />
          </el-col>
          <el-col :span="8">
            <el-statistic title="新增行" :value="diffResult.totalAdditions">
              <template #prefix><span style="color: #67c23a">+</span></template>
            </el-statistic>
          </el-col>
          <el-col :span="8">
            <el-statistic title="删除行" :value="diffResult.totalDeletions">
              <template #prefix><span style="color: #f56c6c">-</span></template>
            </el-statistic>
          </el-col>
        </el-row>
      </el-card>

      <div class="file-list">
        <el-card
          v-for="file in diffResult.files"
          :key="file.filePath"
          class="file-card"
          shadow="hover"
          @click="toggleFile(file.filePath)"
        >
          <template #header>
            <div class="file-header">
              <div>
                <el-tag :type="changeTypeColor(file.changeType)" size="small">
                  {{ file.changeType }}
                </el-tag>
                <span class="file-path">{{ file.filePath }}</span>
              </div>
              <span class="file-stats">
                <span style="color: #67c23a">+{{ file.additions }}</span>
                /
                <span style="color: #f56c6c">-{{ file.deletions }}</span>
              </span>
            </div>
          </template>
          <pre
            v-if="expandedFiles.includes(file.filePath) && file.patch"
            class="diff-patch"
          >{{ file.patch }}</pre>
        </el-card>
      </div>

      <div class="action-bar">
        <el-button @click="handleBack">上一步</el-button>
        <el-button type="primary" @click="handleStartAnalysis">
          开始分析
        </el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.diff-preview {
  max-width: 1000px;
  margin: 20px auto;
}
.stats-bar {
  margin-bottom: 20px;
}
.file-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.file-card {
  cursor: pointer;
}
.file-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.file-path {
  margin-left: 8px;
  font-family: monospace;
  font-size: 13px;
}
.file-stats {
  font-family: monospace;
  font-size: 13px;
}
.diff-patch {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 500px;
  overflow-y: auto;
}
.action-bar {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
