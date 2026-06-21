<script setup lang="ts">
/**
 * Phase2Page — precise location analysis report display.
 *
 * Polls for Phase2 report and renders it as Markdown.
 * Shows detailed analysis with specific nodeId references.
 */
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { marked } from 'marked'
import { getPhase2Report } from '@/api/ram'

const route = useRoute()
const router = useRouter()

const sid = computed<string>(() => String(route.params.sid ?? ''))

const status = ref<string>('RUNNING')
const report = ref<Record<string, unknown> | null>(null)
const loading = ref<boolean>(true)
const pollTimer = ref<number | null>(null)
const error = ref<string | null>(null)

// 判断是否正在运行
const isRunning = computed(() => loading.value || status.value === 'RUNNING')

// 判断是否成功完成
const isSuccess = computed(() => status.value === 'DONE' && report.value?.['success'] !== false)

// 判断是否失败
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
    const resp = await getPhase2Report(sid.value)
    status.value = resp.status
    report.value = resp.report

    if (resp.status === 'DONE' || resp.status === 'FAILED') {
      stopPolling()
      loading.value = false
    }

    if (resp.status === 'FAILED') {
      error.value = '精确分析执行失败'
      ElMessage.error('精确分析失败，请查看错误信息')
    }

    // 检查 success=false
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

      <!-- 正在运行：显示加载动画 -->
      <div v-if="isRunning" class="loading-container">
        <el-icon class="is-loading" :size="32">
          <Loading />
        </el-icon>
        <span class="loading-text">正在执行精确位置分析（预计耗时1-3分钟）...</span>
        <span class="loading-hint">正在收集KG深度数据并生成分析报告</span>
      </div>

      <!-- 失败：显示错误信息 -->
      <div v-else-if="isFailed" class="error-container">
        <el-result icon="error" title="分析失败" :sub-title="error || '请检查日志查看详情'">
          <template #extra>
            <el-button type="primary" @click="goBack">返回重试</el-button>
          </template>
        </el-result>
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

      <!-- 其他情况：空数据 -->
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