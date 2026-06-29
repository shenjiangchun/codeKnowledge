<script setup lang="ts">
/**
 * Phase2Page — V2 multi-agent orchestration report display.
 *
 * REST-first pattern:
 * 1. Load report from V2 REST API (authoritative source)
 * 2. If session still running, poll REST API until report is available
 * 3. Render SummaryLayer + DetailLayer structured report
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { getPhase2V2Report } from '@/api/ram'
import { useRamSession } from '@/composables/useRamSession'

const route = useRoute()
const router = useRouter()
const session = useRamSession()

const sid = computed<string>(() => String(route.params.sid ?? ''))

const report = ref<Record<string, unknown> | null>(null)
const loading = ref<boolean>(true)
const error = ref<string | null>(null)
const fallbackPollTimer = ref<number | null>(null)

// Derive status from session status and report
const status = computed(() => {
  // V2 report has its own status field
  if (report.value?.status) {
    return report.value.status as string
  }
  const s = session.status.value
  if (s === 'completed') return 'DONE'
  if (s === 'error') return 'FAILED'
  if (s === 'aborted') return 'FAILED'
  return 'RUNNING'
})

const isRunning = computed(() => loading.value || status.value === 'RUNNING')
const isSuccess = computed(() => status.value === 'DONE')
const isFailed = computed(() => status.value === 'FAILED')

// ── V2 SummaryLayer computed ──
const domainOverview = computed(() => {
  const val = report.value?.summaryLayer?.domainOverview
  return typeof val === 'string' ? val : null
})

const overallFlowDiagramSvg = computed(() => {
  const val = report.value?.summaryLayer?.overallFlowDiagramSvg
  return typeof val === 'string' ? val : null
})

const keyFindings = computed(() => {
  const val = report.value?.summaryLayer?.keyFindings
  return Array.isArray(val) ? val : []
})

const crossChainImpacts = computed(() => {
  const val = report.value?.summaryLayer?.crossChainImpacts
  return Array.isArray(val) ? val : []
})

const overallRecommendations = computed(() => {
  const val = report.value?.summaryLayer?.overallRecommendations
  return Array.isArray(val) ? val : []
})

// ── V2 DetailLayer computed ──
const chains = computed(() => {
  const val = report.value?.detailLayer?.chains
  return Array.isArray(val) ? val : []
})

const chainCount = computed(() => report.value?.detailLayer?.chainCount ?? 0)
const totalMethodsAnalyzed = computed(() => report.value?.detailLayer?.totalMethodsAnalyzed ?? 0)
const totalCodeSnippets = computed(() => report.value?.detailLayer?.totalCodeSnippets ?? 0)

// Track processed seq for SSE event dedup
let processedSeq = 0

/** Watch SSE events for phase2_analysis CHECKPOINT */
watch(
  () => session.events.value,
  (events) => {
    for (const evt of events) {
      if (evt.seq <= processedSeq) continue
      processedSeq = evt.seq

      if (evt.type === 'CHECKPOINT') {
        const nodeName = evt.payload['nodeName']
        if (nodeName === 'phase2_analysis') {
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
      error.value = '精确分析执行失败'
      ElMessage.error('精确分析失败，请查看错误信息')
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
    const resp = await getPhase2V2Report(sid.value)
    if (resp.summaryLayer || resp.detailLayer) {
      report.value = resp as unknown as Record<string, unknown>
    }

    if (resp.status === 'DONE' || resp.status === 'FAILED') {
      stopFallbackPolling()
      loading.value = false
    }

    if (resp.status === 'FAILED') {
      error.value = '精确分析执行失败'
      ElMessage.error('精确分析失败，请查看错误信息')
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
    router.replace({ name: 'RamInput' })
    return
  }

  loading.value = true
  error.value = null
  report.value = null
  processedSeq = 0

  // Step 1: REST authoritative — load report from REST API (V2)
  try {
    const resp = await getPhase2V2Report(id)
    if (resp.summaryLayer || resp.detailLayer) {
      report.value = resp as unknown as Record<string, unknown>
    }
    if (resp.status === 'DONE' || resp.status === 'FAILED') {
      loading.value = false
      return  // Session finished, no SSE needed
    }
  } catch (e) {
    console.warn('[Phase2Page] Failed to load report via REST:', e)
  }

  // Step 2: Session still running, open SSE for live updates
  try {
    await session.rejoin(id, 0)
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'SSE连接失败'
    console.warn('[Phase2Page] SSE rejoin failed, using fallback polling:', msg)
    startFallbackPolling()
  }

  // Step 3: After rejoin, if report is still empty, try REST again
  if (!report.value || Object.keys(report.value).length === 0) {
    try {
      const resp = await getPhase2V2Report(id)
      if (resp.summaryLayer || resp.detailLayer) {
        report.value = resp as unknown as Record<string, unknown>
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
  router.push({ name: 'RamInput' })
}
</script>

<template>
  <div class="phase2-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Phase2 V2 多Agent协作分析报告</span>
          <div class="header-actions">
            <el-tag :type="isSuccess ? 'success' : isFailed ? 'danger' : 'warning'">
              {{ isSuccess ? '已完成' : isFailed ? '失败' : '运行中' }}
            </el-tag>
            <el-button size="small" @click="goBack">返回</el-button>
          </div>
        </div>
      </template>

      <!-- Running: loading animation -->
      <div v-if="isRunning" class="loading-container">
        <el-icon class="is-loading" :size="32">
          <Loading />
        </el-icon>
        <span class="loading-text">正在执行V2多Agent协作分析（预计耗时2-5分钟）...</span>
        <span class="loading-hint">ChainSplitter 拆分链路 → ChainAnalysisAgent 并行分析 → ResultMerger 合并报告</span>
      </div>

      <!-- Failed: error message -->
      <div v-else-if="isFailed" class="error-container">
        <el-result icon="error" title="分析失败" :sub-title="error || '请检查日志查看详情'">
          <template #extra>
            <el-button type="primary" @click="goBack">返回重试</el-button>
          </template>
        </el-result>
      </div>

      <!-- Success: show report -->
      <div v-else-if="report && isSuccess" class="report-container">
        <!-- V2 分层报告 -->

        <!-- SummaryLayer: 领域概览 -->
        <el-card shadow="never" class="layer-card">
          <template #header>
            <span class="layer-title">第一层：领域概览</span>
          </template>

          <!-- 领域概览描述 -->
          <div v-if="domainOverview" class="section-content overview-text">
            {{ domainOverview }}
          </div>
          <div v-else class="empty-hint">暂无领域概览</div>

          <!-- 整体流程图 -->
          <div v-if="overallFlowDiagramSvg" class="svg-section" v-html="overallFlowDiagramSvg"></div>

          <!-- 关键发现 -->
          <div v-if="keyFindings.length > 0" class="findings-section">
            <h4>关键发现</h4>
            <div v-for="finding in keyFindings" :key="finding.id" class="finding-item">
              <el-tag size="small" :type="finding.type === 'CRITICAL' ? 'danger' : finding.type === 'WARNING' ? 'warning' : 'info'">
                {{ finding.type }}
              </el-tag>
              <span class="finding-desc">{{ finding.description }}</span>
              <div v-if="finding.chains?.length" class="finding-chains">
                <el-tag v-for="c in finding.chains" :key="c" size="small" type="info" class="chain-tag">{{ c }}</el-tag>
              </div>
            </div>
          </div>

          <!-- 跨链路影响 -->
          <div v-if="crossChainImpacts.length > 0" class="impacts-section">
            <h4>跨链路影响分析</h4>
            <div v-for="(impact, idx) in crossChainImpacts" :key="idx" class="impact-item">
              <span class="impact-from">{{ impact.fromChain }}</span>
              <el-icon><span>→</span></el-icon>
              <span class="impact-to">{{ impact.toChain }}</span>
              <el-tag size="small" type="warning">{{ impact.relation }}</el-tag>
              <span class="impact-desc">{{ impact.description }}</span>
            </div>
          </div>

          <!-- 整体建议 -->
          <div v-if="overallRecommendations.length > 0" class="recommendations-section">
            <h4>整体建议</h4>
            <ol>
              <li v-for="(rec, idx) in overallRecommendations" :key="idx">{{ rec }}</li>
            </ol>
          </div>
        </el-card>

        <!-- DetailLayer: 链路详情 -->
        <el-card shadow="never" class="layer-card" style="margin-top: 16px;">
          <template #header>
            <div class="detail-header">
              <span class="layer-title">第二层：链路详情</span>
              <div class="detail-stats">
                <el-tag type="info" size="small">链路: {{ chainCount }}</el-tag>
                <el-tag type="info" size="small">方法: {{ totalMethodsAnalyzed }}</el-tag>
                <el-tag type="info" size="small">代码片段: {{ totalCodeSnippets }}</el-tag>
              </div>
            </div>
          </template>

          <div v-if="chains.length > 0">
            <el-collapse>
              <el-collapse-item
                v-for="chain in chains"
                :key="chain.chainId"
                :title="chain.chainName || chain.chainId"
                :name="chain.chainId"
              >
                <div class="chain-summary">{{ chain.summary || '暂无摘要' }}</div>
                <el-tag v-if="chain.expandable" size="small" type="success" class="expandable-tag">可展开详情</el-tag>
              </el-collapse-item>
            </el-collapse>
          </div>
          <div v-else class="empty-hint">暂无链路分析数据</div>
        </el-card>
      </div>

      <!-- Empty -->
      <div v-else class="empty-container">
        <el-empty description="暂无报告数据">
          <el-button type="primary" @click="goBack">返回</el-button>
        </el-empty>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.phase2-page {
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
.report-container {
  padding: 16px;
}
.section-content {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 6px;
}
.empty-hint {
  color: #909399;
  text-align: center;
  padding: 16px;
}
.empty-container {
  text-align: center;
  padding: 40px;
  color: #909399;
}
/* V2 layered report styles */
.layer-card {
  margin-bottom: 0;
}
.layer-title {
  font-weight: 600;
  font-size: 16px;
  color: #303133;
}
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.detail-stats {
  display: flex;
  gap: 8px;
}
.overview-text {
  white-space: pre-wrap;
  line-height: 1.8;
}
.svg-section {
  margin: 16px 0;
  text-align: center;
}
.svg-section :deep(svg) {
  max-width: 100%;
  height: auto;
}
.findings-section,
.impacts-section,
.recommendations-section {
  margin-top: 16px;
}
.findings-section h4,
.impacts-section h4,
.recommendations-section h4 {
  margin: 0 0 12px;
  font-size: 14px;
  color: #606266;
}
.finding-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}
.finding-desc {
  color: #303133;
}
.finding-chains {
  display: flex;
  gap: 4px;
  margin-left: auto;
}
.chain-tag {
  margin-left: 4px;
}
.impact-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
}
.impact-from,
.impact-to {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  color: #409eff;
}
.impact-desc {
  color: #606266;
}
.chain-summary {
  padding: 8px 0;
  color: #303133;
  line-height: 1.6;
}
.expandable-tag {
  margin-top: 8px;
}
</style>
