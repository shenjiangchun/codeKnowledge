<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useMergeAnalysisSession } from '@/composables/useMergeAnalysisSession'
import { rerunMergeAnalysisNode } from '@/api/merge-analysis'
import { exportMergeAnalysisMd } from '@/api/merge-analysis'
import { downloadBlob } from '@/utils/download'
import type { ImpactResult, TestScopeResult, DiffResult } from '@/types/merge-analysis'

const route = useRoute()
const router = useRouter()

const projectPath = route.query.projectPath as string
const sourceBranch = route.query.sourceBranch as string
const targetBranch = route.query.targetBranch as string
const sid = route.query.sid as string | undefined

const { status, events, currentNode, start, rejoin, sessionId, lastSeq } = useMergeAnalysisSession()

const diffResult = ref<DiffResult | null>(null)
const impactResult = ref<ImpactResult | null>(null)
const testScopeResult = ref<TestScopeResult | null>(null)

const stepNodes = ['diff_extract', 'impact_analysis', 'test_scope']

const activeStep = computed(() => {
  if (status.value === 'completed') return 3
  const idx = stepNodes.indexOf(currentNode.value)
  return idx >= 0 ? idx : 0
})

function riskColor(level: string): string {
  switch (level) {
    case 'HIGH': return 'danger'
    case 'MEDIUM': return 'warning'
    case 'LOW': return 'success'
    default: return 'info'
  }
}

// Track processed events to avoid re-parsing
let processedSeq = 0

watch(events, (evts) => {
  for (const ev of evts) {
    if (ev.seq <= processedSeq) continue
    processedSeq = ev.seq

    // Handle NODES_CLEARED: reset downstream results
    if (ev.type === 'NODES_CLEARED') {
      const clearedNodes = ev.payload['clearedNodes']
      if (Array.isArray(clearedNodes)) {
        for (const nodeName of clearedNodes) {
          if (nodeName === 'diff_extract') diffResult.value = null
          if (nodeName === 'impact_analysis') impactResult.value = null
          if (nodeName === 'test_scope') testScopeResult.value = null
        }
      }
      continue
    }

    if (ev.type !== 'CHECKPOINT') continue
    const payload = ev.payload
    // DagExecutor format: nodeName + output; legacy format: node + data
    const node = (payload['nodeName'] ?? payload['node']) as string
    const output = payload['output']
    const dataStr = payload['data'] as string | undefined
    if (!node) continue

    let data: unknown = output
    if (!data && dataStr) {
      try { data = JSON.parse(dataStr) } catch { continue }
    }
    if (!data) continue

    // DagExecutor stores the merged accumulator map in `output`,
    // so each result is nested under its own key (diffResult, impactResult, etc.)
    const outMap = data as Record<string, unknown>

    switch (node) {
      case 'diff_extract':
        diffResult.value = (outMap.diffResult ?? data) as DiffResult
        break
      case 'impact_analysis':
        impactResult.value = (outMap.impactResult ?? data) as ImpactResult
        break
      case 'test_scope':
        testScopeResult.value = (outMap.testScopeResult ?? data) as TestScopeResult
        break
    }
  }
}, { deep: true })

async function handleRerun(nodeName: string): Promise<void> {
  if (!sid) return
  try {
    const resp = await rerunMergeAnalysisNode(sid, nodeName)
    const nextSeq = typeof resp['nextSeq'] === 'number' ? resp['nextSeq'] as number : 0
    processedSeq = nextSeq

    // Clear downstream results immediately
    const nodeIdx = stepNodes.indexOf(nodeName)
    for (let i = nodeIdx; i < stepNodes.length; i++) {
      if (stepNodes[i] === 'diff_extract') diffResult.value = null
      if (stepNodes[i] === 'impact_analysis') impactResult.value = null
      if (stepNodes[i] === 'test_scope') testScopeResult.value = null
    }

    rejoin(sid, nextSeq)
    ElMessage.success(`重新执行 ${nodeName}`)
  } catch {
    ElMessage.error('重新执行失败')
  }
}

function handleBack() {
  router.push({
    name: 'MergeAnalysisDiff',
    query: { projectPath, sourceBranch, targetBranch }
  })
}

const exportingMd = ref(false)

async function handleExportMd(): Promise<void> {
  if (!sid) {
    ElMessage.warning("无法导出：缺少会话ID")
    return
  }
  exportingMd.value = true
  try {
    const blob = await exportMergeAnalysisMd(sid)
    const timestamp = new Date().toISOString().slice(0, 19).replace(/[:-]/g, "")
    const filename = `merge-analysis-${sid.slice(0, 8)}-${timestamp}.md`
    downloadBlob(blob, filename)
    ElMessage.success("分析报告已导出")
  } catch (e) {
    const msg = e instanceof Error ? e.message : "导出失败"
    ElMessage.error(msg)
  } finally {
    exportingMd.value = false
  }
}

onMounted(async () => {
  // If sid is provided, rejoin an existing session (from history list)
  if (sid) {
    try {
      await rejoin(sid, 0)
    } catch {
      ElMessage.error('恢复会话失败')
    }
    return
  }
  // Otherwise start a new session (from DiffPreviewPage)
  if (!projectPath || !sourceBranch || !targetBranch) {
    ElMessage.error('缺少必要参数')
    router.push({ name: 'MergeAnalysisInput' })
    return
  }
  try {
    await start(projectPath, sourceBranch, targetBranch)
  } catch {
    ElMessage.error('启动分析失败')
  }
})
</script>

<template>
  <div class="analysis-page">
    <el-page-header @back="handleBack" style="margin-bottom: 20px">
      <template #content>
        <span>合入影响分析: {{ sourceBranch || '—' }} → {{ targetBranch || '—' }}</span>
        <el-tag v-if="sessionId" size="small" effect="plain" style="margin-left: 8px; font-family: monospace">
          #{{ sessionId.slice(0, 8) }}
        </el-tag>
      </template>
    </el-page-header>

    <div style="margin-bottom: 20px; display: flex; justify-content: flex-end">
      <el-button
        type="success"
        :loading="exportingMd"
        :disabled="!sid || status !== 'completed'"
        @click="handleExportMd"
      >
        导出 MD
      </el-button>
    </div>

    <!-- Progress Steps -->
    <el-steps :active="activeStep" finish-status="success" align-center style="margin-bottom: 30px">
      <el-step title="Diff 提取" description="分析代码变更" />
      <el-step title="影响分析" description="KG + LLM 影响评估" />
      <el-step title="测试范围" description="生成测试建议" />
    </el-steps>

    <div v-if="status === 'running'" class="running-hint">
      <el-icon class="is-loading" :size="20"><Loading /></el-icon>
      <span style="margin-left: 8px">正在执行: {{ currentNode }}</span>
    </div>

    <div v-if="status === 'error'" style="margin-bottom: 20px">
      <el-alert title="分析失败" type="error" show-icon :closable="false" />
    </div>

    <!-- Results -->
    <el-row :gutter="20" v-if="impactResult || testScopeResult">
      <!-- Left: Impact -->
      <el-col :span="12">
        <el-card v-if="impactResult">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <h4 style="margin: 0">影响分析</h4>
              <div>
                <el-tag :type="riskColor(impactResult.riskLevel)" style="margin-right: 8px">
                  {{ impactResult.riskLevel }}
                </el-tag>
                <el-button
                  v-if="status === 'completed' && sid"
                  size="small"
                  type="warning"
                  plain
                  @click="handleRerun('impact_analysis')"
                >重新执行</el-button>
              </div>
            </div>
          </template>

          <p class="impact-summary">{{ impactResult.businessImpactSummary }}</p>

          <h5>受影响的入口点 ({{ impactResult.affectedEntryPoints.length }})</h5>
          <el-table
            :data="impactResult.affectedEntryPoints"
            size="small"
            max-height="300"
            stripe
          >
            <el-table-column prop="entryType" label="类型" width="120">
              <template #default="{ row }">
                <el-tag size="small">{{ row.entryType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="方法" min-width="200">
              <template #default="{ row }">
                <span class="mono">{{ row.className }}.{{ row.methodName }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="urlPattern" label="URL" min-width="150">
              <template #default="{ row }">
                <span v-if="row.urlPattern" class="mono">{{ row.urlPattern }}</span>
                <span v-else style="color: #999">—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- Right: Test Scope -->
      <el-col :span="12">
        <el-card v-if="testScopeResult">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <h4 style="margin: 0">测试范围建议</h4>
              <el-button
                v-if="status === 'completed' && sid"
                size="small"
                type="warning"
                plain
                @click="handleRerun('test_scope')"
              >重新执行</el-button>
            </div>
          </template>

          <div v-for="group in testScopeResult.groups" :key="group.urlRoot || group.entryPointName" class="test-group">
            <div class="group-header">
              <span class="mono">{{ group.urlRoot || group.entryPointName }}</span>
              <div style="display: flex; align-items: center; gap: 6px">
                <el-tag v-if="group.coveredEntryCount" size="small" type="info">
                  {{ group.coveredEntryCount }} 个入口
                </el-tag>
                <el-tag :type="riskColor(group.riskLevel)" size="small">
                  {{ group.riskLevel }}
                </el-tag>
              </div>
            </div>
            <div v-if="group.coveredMethods" class="group-methods">覆盖方法: {{ group.coveredMethods }}</div>
            <div v-else-if="group.urlPattern" class="group-url">{{ group.urlPattern }}</div>
            <ul class="test-cases">
              <li v-for="(tc, i) in group.testCases" :key="i">
                <el-tag :type="riskColor(tc.riskLevel)" size="small" style="margin-right: 6px">
                  {{ tc.riskLevel }}
                </el-tag>
                {{ tc.description }}
                <span v-if="tc.reason" class="case-reason">— {{ tc.reason }}</span>
              </li>
            </ul>
          </div>

          <el-divider v-if="testScopeResult.regressionSuggestions.length" />
          <div v-if="testScopeResult.regressionSuggestions.length">
            <h5>回归建议</h5>
            <ul>
              <li v-for="(s, i) in testScopeResult.regressionSuggestions" :key="i">{{ s }}</li>
            </ul>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script lang="ts">
import { Loading } from '@element-plus/icons-vue'
export default { components: { Loading } }
</script>

<style scoped>
.analysis-page {
  max-width: 1200px;
  margin: 20px auto;
}
.running-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  color: #409eff;
}
.impact-summary {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 16px;
  line-height: 1.6;
}
.mono {
  font-family: monospace;
  font-size: 12px;
}
.test-group {
  margin-bottom: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
}
.test-group:last-child {
  border-bottom: none;
}
.group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.group-url {
  font-family: monospace;
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}
.group-methods {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
  margin-bottom: 8px;
  padding: 4px 8px;
  background: #f5f7fa;
  border-radius: 4px;
}
.test-cases {
  margin: 0;
  padding-left: 20px;
}
.test-cases li {
  margin-bottom: 6px;
  line-height: 1.6;
}
.case-reason {
  color: #909399;
  font-size: 12px;
}
</style>
