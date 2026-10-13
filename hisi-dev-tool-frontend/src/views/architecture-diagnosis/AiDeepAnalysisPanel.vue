<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Warning, Loading } from '@element-plus/icons-vue'
import { useWorkflow } from '@/composables/useWorkflow'
import { renderMarkdown } from '@/utils/markdown'
import { buildExportMarkdown } from '@/utils/exportMarkdown'
import MermaidDiagram from '@/components/ram/MermaidDiagram.vue'
import type { WorkflowEvent } from '@/types/workflow'

interface ProjectOption {
  name: string
  path: string
}

/** 后端 arch_react_agent 节点输出契约（buildOutput / buildDegradedOutput）。 */
interface Diagram {
  id: string
  name: string
  mermaid: string
}
interface ViewInsight {
  insight?: string
  diagramRefs?: string[]
}
interface RoadmapItem {
  action?: string
  impact?: string
  benefit?: string
  risk?: string
}
interface Issue {
  ref?: string
  title?: string
  severity?: string
  rootCause?: string
  evidenceRefs?: string[]
  suggestion?: string
  verdict?: string
  reason?: string
}
interface StructuredReport {
  executive_summary?: string
  views?: {
    logical?: ViewInsight
    process?: ViewInsight
    development?: ViewInsight
    physical?: ViewInsight
    scenario?: ViewInsight
  }
  roadmap?: { p0?: RoadmapItem[]; p1?: RoadmapItem[]; p2?: RoadmapItem[] }
  evidence_appendix?: string
}
interface AnalysisOutput {
  projectPaths?: string[]
  rootCauseResult?: { issues?: Issue[]; report?: StructuredReport; degraded?: boolean }
  markdownReport?: string
  diagrams?: Diagram[]
  degraded?: boolean
}

const props = defineProps<{ availableProjects: ProjectOption[] }>()

const selectedNames = ref<string[]>([])
const selectedProjectPaths = computed(() =>
  props.availableProjects.filter(p => selectedNames.value.includes(p.name)).map(p => p.path)
)

/** arch-deep-analysis 两节点（与后端 WorkflowAutoConfig 注册顺序一致）。 */
const NODES = [
  { name: 'arch_evidence_collect', label: '证据收集' },
  { name: 'arch_react_agent', label: 'ReAct 分析' },
] as const

type NodeState = 'pending' | 'running' | 'done' | 'error'

const nodeStates = reactive<Record<string, NodeState>>({
  arch_evidence_collect: 'pending',
  arch_react_agent: 'pending',
})
const rootCauseDegraded = ref(false)
const starting = ref(false)

/** NODE_DELTA 过程日志（类似 RAM 对话式流式输出）。 */
const processLog = ref<{ nodeName: string; message: string }[]>([])

const wf = useWorkflow({
  workflowType: 'arch-deep-analysis',
  onEvent: handleEvent,
})
const status = wf.status

function handleEvent(evt: WorkflowEvent) {
  const nodeName = (evt.payload?.nodeName as string) || ''
  if (!nodeName || !(nodeName in nodeStates)) return
  if (evt.type === 'NODE_START') {
    nodeStates[nodeName] = 'running'
  } else if (evt.type === 'NODE_COMPLETE') {
    nodeStates[nodeName] = 'done'
  } else if (evt.type === 'NODE_DELTA') {
    const message = (evt.payload?.message as string) || ''
    if (message) processLog.value = [...processLog.value, { nodeName, message }]
  } else if (evt.type === 'ERROR') {
    nodeStates[nodeName] = 'error'
  } else if (evt.type === 'CHECKPOINT' && nodeName === 'arch_react_agent') {
    const out = evt.payload?.output as { rootCauseResult?: { degraded?: boolean } } | undefined
    if (out?.rootCauseResult?.degraded) rootCauseDegraded.value = true
  }
}

const output = computed<AnalysisOutput>(() => (wf.report.value as AnalysisOutput) ?? {})
const markdownReport = computed(() => output.value.markdownReport ?? '')
const reportHtml = computed(() => renderMarkdown(markdownReport.value))
const diagrams = computed(() => output.value.diagrams ?? [])
const structuredReport = computed(() => output.value.rootCauseResult?.report ?? null)
const issues = computed(() => output.value.rootCauseResult?.issues ?? [])

const degraded = computed(() => {
  const r = wf.report.value as { degraded?: boolean } | null
  return rootCauseDegraded.value || !!(r?.degraded)
})

const VIEWS = [
  { key: 'logical', label: '逻辑视图' },
  { key: 'process', label: '过程视图' },
  { key: 'development', label: '开发视图' },
  { key: 'physical', label: '物理视图' },
  { key: 'scenario', label: '场景视图' },
] as const

const ROADMAP_LEVELS = [
  { key: 'p0', label: 'P0（立即）' },
  { key: 'p1', label: 'P1（近期）' },
  { key: 'p2', label: 'P2（远期）' },
] as const

function viewByKey(key: string): ViewInsight | undefined {
  const v = structuredReport.value?.views
  if (!v) return undefined
  return (v as Record<string, ViewInsight | undefined>)[key]
}

function roadmapItems(key: string): RoadmapItem[] {
  const r = structuredReport.value?.roadmap
  if (!r) return []
  const items = (r as Record<string, RoadmapItem[] | undefined>)[key]
  return Array.isArray(items) ? items : []
}

/** 4+1 视图：预取 key → 洞察，供模板直接渲染。 */
const views = computed(() => VIEWS.map(v => ({ ...v, view: viewByKey(v.key) })))

/** 路线图：仅保留非空档位。 */
const roadmap = computed(() =>
  ROADMAP_LEVELS.map(l => ({ ...l, items: roadmapItems(l.key) })).filter(l => l.items.length > 0)
)

function severityTag(s?: string): 'danger' | 'warning' | 'info' {
  if (s === 'HIGH') return 'danger'
  if (s === 'MEDIUM') return 'warning'
  return 'info'
}

function verdictLabel(v?: string): string {
  if (v === 'confirmed') return '属实'
  if (v === 'false_positive') return '误报'
  if (v === 'downgraded') return '降级'
  return v ?? ''
}

function verdictTag(v?: string): 'success' | 'info' | 'warning' {
  if (v === 'confirmed') return 'success'
  if (v === 'false_positive') return 'info'
  if (v === 'downgraded') return 'warning'
  return 'info'
}

const currentNode = computed(() => NODES.find(n => nodeStates[n.name] === 'running'))
/** 运行中（供父组件上栏按钮防呆 + 面板内按钮禁用）。 */
const isRunning = computed(() => wf.status.value === 'running')

const storageKey = computed(() => `arch-deep-analysis-session:${selectedProjectPaths.value.join('|')}`)

function nodeTagType(s: NodeState) {
  if (s === 'done') return 'success'
  if (s === 'running') return 'primary'
  if (s === 'error') return 'danger'
  return 'info'
}

function nodeTagText(s: NodeState) {
  if (s === 'pending') return '待执行'
  if (s === 'running') return '运行中'
  if (s === 'done') return '完成'
  return '失败'
}

function nodeLabel(name: string): string {
  return NODES.find(n => n.name === name)?.label ?? name
}

function resetStates() {
  for (const n of NODES) nodeStates[n.name] = 'pending'
  rootCauseDegraded.value = false
  processLog.value = []
}

async function start() {
  if (selectedProjectPaths.value.length === 0) {
    ElMessage.warning('请先选择项目')
    return
  }
  resetStates()
  starting.value = true
  try {
    const sid = await wf.start({ projectPaths: selectedProjectPaths.value })
    localStorage.setItem(storageKey.value, sid)
    ElMessage.success('深度分析已启动')
  } catch (e: unknown) {
    ElMessage.error('深度分析启动失败: ' + (e instanceof Error ? e.message : String(e)))
  } finally {
    starting.value = false
  }
}

function exportMarkdown() {
  if (!markdownReport.value) {
    ElMessage.warning('暂无报告可导出')
    return
  }
  const markdown = buildExportMarkdown(markdownReport.value, diagrams.value)
  const blob = new Blob([markdown], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'arch-deep-analysis.md'
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  const sid = localStorage.getItem(storageKey.value)
  if (sid) wf.rejoin(sid).catch(() => {})
})

defineExpose({ start, isRunning })
</script>

<template>
  <div class="ai-deep-analysis">
    <!-- 项目多选：跨项目聚合分析入口 -->
    <div class="project-bar">
      <el-select
        v-model="selectedNames"
        placeholder="选择项目（可多选，跨项目聚合）"
        filterable
        multiple
        collapse-tags
        collapse-tags-tooltip
        style="width: 100%"
      >
        <el-option
          v-for="proj in availableProjects"
          :key="proj.name"
          :label="proj.name"
          :value="proj.name"
        />
      </el-select>
    </div>

    <!-- 未发起：空态 -->
    <el-empty
      v-if="status === 'idle' && !markdownReport"
      description="尚未发起 AI 深度分析"
    >
      <el-button type="primary" :loading="starting" @click="start">开始分析</el-button>
    </el-empty>

    <!-- 进行中 / 已完成 -->
    <template v-else>
      <!-- 两节点进度卡 -->
      <div class="node-progress">
        <div v-for="(n, i) in NODES" :key="n.name" class="node-item">
          <div class="node-dot">
            <el-icon v-if="nodeStates[n.name] === 'done'" class="done"><Check /></el-icon>
            <el-icon v-else-if="nodeStates[n.name] === 'error'" class="error"><Warning /></el-icon>
            <el-icon v-else-if="nodeStates[n.name] === 'running'" class="running is-loading"><Loading /></el-icon>
            <span v-else class="pending">{{ i + 1 }}</span>
          </div>
          <div class="node-meta">
            <div class="node-label">{{ n.label }}</div>
            <el-tag :type="nodeTagType(nodeStates[n.name])" size="small">
              {{ nodeTagText(nodeStates[n.name]) }}
            </el-tag>
          </div>
        </div>
      </div>

      <!-- 过程日志（NODE_DELTA 流式输出） -->
      <div v-if="processLog.length" class="process-log">
        <div v-for="(line, i) in processLog" :key="i" class="log-line">
          <span class="log-node">{{ nodeLabel(line.nodeName) }}</span>
          <span class="log-msg">{{ line.message }}</span>
        </div>
      </div>

      <!-- 当前节点提示 -->
      <el-alert
        v-if="currentNode && status === 'running'"
        type="info"
        :closable="false"
        :title="`正在执行：${currentNode.label}`"
        class="hint"
      />

      <!-- 降级说明 -->
      <el-alert
        v-if="degraded && (status === 'completed' || status === 'error')"
        type="warning"
        :closable="false"
        class="hint"
      >
        <template #title>
          部分分析已降级：根因分析或报告生成未获得 LLM 有效结果，已回退为模板输出。
        </template>
      </el-alert>

      <!-- 操作栏 -->
      <div v-if="markdownReport" class="report-actions">
        <el-button type="primary" size="small" @click="exportMarkdown">导出 Markdown</el-button>
        <el-button size="small" :loading="starting || isRunning" @click="start">重新分析</el-button>
      </div>

      <!-- 架构图（确定性生成，降级时仍保留） -->
      <section v-if="diagrams.length" class="report-section">
        <h3 class="section-title">架构图（{{ diagrams.length }}）</h3>
        <div class="diagram-list">
          <MermaidDiagram
            v-for="d in diagrams"
            :key="d.id"
            :source="d.mermaid"
            :title="d.name"
          />
        </div>
      </section>

      <!-- 结构化报告（AI 收敛成功） -->
      <template v-if="structuredReport">
        <!-- 执行摘要 -->
        <section v-if="structuredReport.executive_summary" class="report-section">
          <h3 class="section-title">执行摘要</h3>
          <p class="summary-text">{{ structuredReport.executive_summary }}</p>
        </section>

        <!-- 4+1 视图 -->
        <section v-if="views.some(v => v.view?.insight)" class="report-section">
          <h3 class="section-title">4+1 视图</h3>
          <div class="view-grid">
            <el-card
              v-for="v in views.filter(v => v.view?.insight)"
              :key="v.key"
              class="view-card"
              shadow="never"
            >
              <template #header>{{ v.label }}</template>
              <p class="view-insight">{{ v.view?.insight }}</p>
              <div v-if="v.view?.diagramRefs?.length" class="diagram-refs">
                <el-tag
                  v-for="ref in v.view!.diagramRefs"
                  :key="ref"
                  size="small"
                  effect="plain"
                  type="info"
                >
                  引用图：{{ ref }}
                </el-tag>
              </div>
            </el-card>
          </div>
        </section>

        <!-- 问题分析（灰度判定） -->
        <section v-if="issues.length" class="report-section">
          <h3 class="section-title">问题分析（{{ issues.length }}）</h3>
          <div class="issue-list">
            <el-card v-for="(issue, i) in issues" :key="issue.ref ?? i" class="issue-card" shadow="never">
              <div class="issue-header">
                <span class="issue-title">{{ issue.title }}</span>
                <el-tag :type="severityTag(issue.severity)" size="small">{{ issue.severity }}</el-tag>
                <el-tag v-if="issue.verdict" :type="verdictTag(issue.verdict)" size="small">
                  {{ verdictLabel(issue.verdict) }}
                </el-tag>
              </div>
              <p v-if="issue.rootCause" class="issue-line"><b>根因：</b>{{ issue.rootCause }}</p>
              <p v-if="issue.suggestion" class="issue-line"><b>建议：</b>{{ issue.suggestion }}</p>
              <p v-if="issue.reason" class="issue-line"><b>判定理由：</b>{{ issue.reason }}</p>
              <div v-if="issue.evidenceRefs?.length" class="diagram-refs">
                <el-tag
                  v-for="ref in issue.evidenceRefs"
                  :key="ref"
                  size="small"
                  effect="plain"
                  type="info"
                >
                  {{ ref }}
                </el-tag>
              </div>
            </el-card>
          </div>
        </section>

        <!-- 重构路线图 -->
        <section v-if="roadmap.length" class="report-section">
          <h3 class="section-title">重构路线图</h3>
          <div v-for="level in roadmap" :key="level.key" class="roadmap-level">
            <h4 class="roadmap-level-title">{{ level.label }}</h4>
            <div class="roadmap-list">
              <el-card
                v-for="(item, i) in level.items"
                :key="i"
                class="roadmap-item"
                shadow="never"
              >
                <div class="roadmap-action">{{ item.action }}</div>
                <div v-if="item.impact" class="issue-line"><b>影响：</b>{{ item.impact }}</div>
                <div v-if="item.benefit" class="issue-line"><b>收益：</b>{{ item.benefit }}</div>
                <div v-if="item.risk" class="issue-line"><b>风险：</b>{{ item.risk }}</div>
              </el-card>
            </div>
          </div>
        </section>

        <!-- 证据附录 -->
        <section v-if="structuredReport.evidence_appendix" class="report-section">
          <h3 class="section-title">证据附录</h3>
          <p class="summary-text">{{ structuredReport.evidence_appendix }}</p>
        </section>
      </template>

      <!-- 降级兜底：无结构化报告时回退 markdown 渲染 -->
      <div v-else-if="reportHtml" class="markdown-content" v-html="reportHtml" />
    </template>
  </div>
</template>

<style scoped>
.ai-deep-analysis {
  min-height: 200px;
}

.project-bar {
  margin-bottom: 12px;
}

.node-progress {
  display: flex;
  gap: 16px;
  padding: 12px 0;
  margin-bottom: 8px;
}

.node-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fafafa;
}

.node-dot {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 12px;
  color: #909399;
  border: 1px solid #dcdfe6;
}

.node-dot .done { color: #67c23a; }
.node-dot .error { color: #f56c6c; }
.node-dot .running { color: #409eff; }

.node-label {
  font-size: 13px;
  color: #303133;
  margin-bottom: 2px;
}

.process-log {
  margin: 8px 0;
  padding: 8px 12px;
  background: #f7f9fc;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  max-height: 220px;
  overflow-y: auto;
  font-size: 13px;
}

.log-line {
  display: flex;
  gap: 8px;
  padding: 2px 0;
  line-height: 1.6;
}

.log-node {
  flex-shrink: 0;
  color: #409eff;
  font-weight: 500;
}

.log-msg {
  color: #606266;
}

.hint {
  margin-bottom: 8px;
}

.report-actions {
  display: flex;
  gap: 8px;
  margin: 8px 0;
}

.report-section {
  margin-top: 16px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}

.summary-text {
  margin: 0;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
}

.diagram-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.view-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

.view-insight {
  margin: 0;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
}

.diagram-refs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.issue-list,
.roadmap-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.issue-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.issue-title {
  font-weight: 600;
  color: #303133;
}

.issue-line {
  margin: 4px 0;
  line-height: 1.6;
  color: #606266;
}

.roadmap-level {
  margin-bottom: 12px;
}

.roadmap-level-title {
  font-size: 14px;
  font-weight: 600;
  color: #409eff;
  margin: 0 0 8px;
}

.roadmap-action {
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}

.markdown-content {
  line-height: 1.7;
  color: #303133;
}

.markdown-content :deep(h2) {
  font-size: 18px;
  margin: 20px 0 8px;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 4px;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  padding-left: 20px;
}

.markdown-content :deep(p) {
  margin: 6px 0;
}
</style>
