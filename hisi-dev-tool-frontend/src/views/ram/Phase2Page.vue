<script setup lang="ts">
/**
 * Phase2Page — precise location analysis report display.
 *
 * Uses REST-first + SSE incremental pattern:
 * 1. Load report from REST API (authoritative source)
 * 2. If session still running, open SSE for live CHECKPOINT updates
 * 3. Monitor 'phase2_analysis' CHECKPOINT events for report payload
 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { renderMarkdown } from '@/utils/markdown'
import { getPhase2Report } from '@/api/ram'
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
  return typeof md === 'string' ? md : null
})

const analysisSummary = computed(() => {
  const summary = report.value?.['analysis_summary']
  return typeof summary === 'string' ? summary : null
})

const coreMethods = computed(() => {
  const methods = report.value?.['core_methods']
  return Array.isArray(methods) ? methods : []
})

const upstreamChains = computed(() => {
  const chains = report.value?.['upstream_chains']
  return Array.isArray(chains) ? chains : []
})

const downstreamChains = computed(() => {
  const chains = report.value?.['downstream_chains']
  return Array.isArray(chains) ? chains : []
})

const rootEntries = computed(() => {
  const entries = report.value?.['root_entries']
  return Array.isArray(entries) ? entries : []
})

const bridgePoints = computed(() => {
  const bridges = report.value?.['bridge_points']
  return Array.isArray(bridges) ? bridges : []
})

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
    const resp = await getPhase2Report(sid.value)
    if (resp.report && Object.keys(resp.report).length > 0) {
      report.value = resp.report
    }

    if (resp.status === 'DONE' || resp.status === 'FAILED') {
      stopFallbackPolling()
      loading.value = false
    }

    if (resp.status === 'FAILED') {
      error.value = '精确分析执行失败'
      ElMessage.error('精确分析失败，请查看错误信息')
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
    router.replace({ name: 'RamInput' })
    return
  }

  loading.value = true
  error.value = null
  report.value = null
  processedSeq = 0

  // Step 1: REST authoritative — load report from REST API
  try {
    const resp = await getPhase2Report(id)
    if (resp.report && Object.keys(resp.report).length > 0) {
      report.value = resp.report
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
      const resp = await getPhase2Report(id)
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
  router.push({ name: 'RamInput' })
}
</script>

<template>
  <div class="phase2-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>精确位置分析报告</span>
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
        <span class="loading-text">正在执行精确位置分析（预计耗时1-3分钟）...</span>
        <span class="loading-hint">正在收集KG深度数据并生成分析报告</span>
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
        <div v-if="markdownReport" class="markdown-section">
          <div class="markdown-content" v-html="renderMarkdown(markdownReport)"></div>
        </div>

        <!-- Fallback: segmented display -->
        <div v-else>
          <el-collapse>
            <el-collapse-item title="分析摘要" name="summary">
              <div v-if="analysisSummary" class="section-content">
                {{ analysisSummary }}
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="核心方法" name="methods">
              <div v-if="coreMethods.length > 0">
                <ul>
                  <li v-for="(m, idx) in coreMethods" :key="idx">
                    <strong>{{ m['nodeId'] || m['summary'] }}</strong>
                    <span v-if="m['className']" class="method-meta">
                      {{ m['className'] }}#{{ m['methodName'] }}
                    </span>
                  </li>
                </ul>
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="上游调用链" name="upstream">
              <div v-if="upstreamChains.length > 0">
                <ul>
                  <li v-for="(e, idx) in upstreamChains" :key="idx">
                    {{ e['className'] }}#{{ e['methodName'] }}
                    <el-tag size="small" type="info">{{ e['type'] }}</el-tag>
                  </li>
                </ul>
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="下游调用链" name="downstream">
              <div v-if="downstreamChains.length > 0">
                <ul>
                  <li v-for="(c, idx) in downstreamChains" :key="idx">
                    {{ c['className'] }}#{{ c['methodName'] }}
                    <span class="chain-depth">深度: {{ c['depth'] }}</span>
                  </li>
                </ul>
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="入口点来源" name="entries">
              <div v-if="rootEntries.length > 0">
                <ul>
                  <li v-for="(e, idx) in rootEntries" :key="idx">
                    <el-tag size="small" :type="e['type'] === 'CONTROLLER' ? 'primary' : e['type'] === 'MQ_LISTENER' ? 'warning' : 'info'">
                      {{ e['type'] }}
                    </el-tag>
                    {{ e['className'] }}#{{ e['methodName'] }}
                  </li>
                </ul>
              </div>
              <div v-else class="empty-hint">无数据</div>
            </el-collapse-item>

            <el-collapse-item title="桥接点（跨服务调用）" name="bridges">
              <div v-if="bridgePoints.length > 0">
                <ul>
                  <li v-for="(b, idx) in bridgePoints" :key="idx">
                    <el-tag size="small" :type="b['bridgeType'] === 'FEIGN' ? 'success' : b['bridgeType'] === 'MQ' ? 'warning' : 'info'">
                      {{ b['bridgeType'] }}
                    </el-tag>
                    {{ b['sourceNode'] }} → {{ b['targetNode'] }}
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
.method-meta {
  color: #606266;
  margin-left: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.chain-depth {
  color: #909399;
  margin-left: 8px;
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
</style>
