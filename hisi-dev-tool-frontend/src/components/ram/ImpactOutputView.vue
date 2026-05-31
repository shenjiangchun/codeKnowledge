<script setup lang="ts">
/**
 * ImpactOutputView — Human-readable renderer for impact analysis node output.
 *
 * Adapted to the redesigned ImpactNode output structure:
 * - methods_to_modify: methods needing code changes
 * - affected_entries: { direct: [...], indirect: [...] } — upstream root entry points
 * - risk: { score, level }
 * - validation: { passed, violations }
 * - reasoning: analysis step summary
 * - markdown_report: formatted MD report
 *
 * Legacy fields (involved, modified.tree, impacted.downstream/crossService/bridges)
 * are no longer rendered but handled gracefully if present.
 */
import { computed, ref } from 'vue'
import { marked } from 'marked'

// ─── Types ───────────────────────────────────────────────────────────────────

interface MethodToModify {
  nodeId?: string
  className?: string
  methodName?: string
  reason?: string
}

interface AnnotatedEntry {
  nodeId?: string
  className?: string
  methodName?: string
  type?: string
  relevance?: string
  reason?: string
  business_function?: string
  impact_mechanism?: string
  change_behavior?: string
  call_path?: string
}

interface ImpactOutput {
  // New structure
  methods_to_modify?: MethodToModify[]
  affected_entries?: {
    direct?: AnnotatedEntry[]
    indirect?: AnnotatedEntry[]
  }
  risk?: {
    score?: number
    level?: string
  }
  validation?: {
    passed?: boolean
    violations?: string[]
  }
  reasoning?: string
  markdown_report?: string
  // Legacy fallbacks
  modified?: {
    tree?: unknown[]
    methods_to_modify?: unknown[]
  }
  impacted?: {
    upstream?: Array<{
      nodeId: string
      className: string
      methodName: string
      type?: string
    }>
    downstream?: unknown[]
    crossService?: unknown[]
    bridges?: unknown[]
  }
  involved?: unknown
}

const props = defineProps<{
  output: ImpactOutput
}>()

// ─── Markdown Rendering ──────────────────────────────────────────────────────

const showMarkdownReport = ref(true)

const renderedMarkdown = computed(() => {
  const md = props.output.markdown_report
  if (!md) return ''
  return marked(md, { breaks: true }) as string
})

// ─── Helpers ─────────────────────────────────────────────────────────────────

function shortName(className: string | null | undefined): string {
  if (!className) return ''
  const parts = className.split('.')
  return parts[parts.length - 1]
}

function parseNodeId(nodeId: string): { className?: string; methodName?: string } | null {
  const parts = nodeId.split(':')
  if (parts.length < 2) return null
  const rest = parts[1]
  const components = rest.split('.')
  if (components.length < 2) return null
  const classNameParts = components.slice(0, -2)
  const methodName = components[components.length - 2]
  return {
    className: classNameParts.join('.'),
    methodName
  }
}

function formatMethod(className: string | null | undefined, methodName: string | null | undefined, nodeId?: string | null): string {
  const cls = shortName(className)
  if (cls && methodName) return `${cls}#${methodName}`
  if (methodName) return methodName
  if (cls) return cls
  if (nodeId) {
    const parsed = parseNodeId(nodeId)
    if (parsed) return formatMethod(parsed.className, parsed.methodName)
  }
  return '(未知方法)'
}

function entryTypeLabel(type: string | null | undefined): string {
  if (!type) return '接口'
  const labels: Record<string, string> = {
    CONTROLLER: 'HTTP 接口',
    REST_ENDPOINT: 'REST 接口',
    HTTP: 'HTTP 接口',
    SCHEDULED: '定时任务',
    MQ_LISTENER: '消息监听',
    MQ_CONSUMER: '消息监听',
    FEIGN_CLIENT: 'Feign 调用',
    WEBSOCKET: 'WebSocket',
    EVENT_LISTENER: '事件监听',
    GRPC: 'gRPC',
    RMI: 'RMI'
  }
  return labels[type.toUpperCase()] ?? type
}

function entryTypeIcon(type: string | null | undefined): string {
  if (!type) return '🔌'
  switch (type.toUpperCase()) {
    case 'HTTP': case 'CONTROLLER': case 'REST_ENDPOINT': return '🔌'
    case 'SCHEDULED': return '⏰'
    case 'MQ_LISTENER': case 'MQ_CONSUMER': return '📨'
    case 'FEIGN_CLIENT': case 'GRPC': case 'RMI': return '🔗'
    default: return '🔌'
  }
}

/**
 * Humanize a validation violation string for display.
 */
function humanizeViolation(violation: string): { label: string; detail: string } {
  const entryMatch = violation.match(/^Entry not reachable as a root entry:\s*(.+)$/)
  if (entryMatch) {
    return {
      label: `入口不可达：${entryMatch[1]}`,
      detail: '此方法在知识图谱中被标记为入口点，但无法从任何根入口追溯到达'
    }
  }
  const implMatch = violation.match(/^Impl missing from modified ring:\s*(.+)$/)
  if (implMatch) {
    return {
      label: `实现类缺失：${implMatch[1]}`,
      detail: '接口的实现类未包含在修改范围内，可能需要同步修改'
    }
  }
  return { label: violation, detail: '' }
}

// ─── Computed ────────────────────────────────────────────────────────────────

const riskLevel = computed(() => props.output.risk?.level ?? 'UNKNOWN')
const riskScore = computed(() => props.output.risk?.score ?? 0)

const riskScoreDisplay = computed(() => {
  const score = riskScore.value
  if (score > 1) return `${score.toFixed(1)}/100`
  return score.toFixed(2)
})

const riskTagType = computed(() => {
  switch (riskLevel.value) {
    case 'LOW': return 'success'
    case 'MEDIUM': return 'warning'
    case 'HIGH': return 'danger'
    case 'CRITICAL': return 'danger'
    default: return 'info'
  }
})

const riskLabel = computed(() => {
  const labels: Record<string, string> = {
    LOW: '低风险',
    MEDIUM: '中风险',
    HIGH: '高风险',
    CRITICAL: '极高风险'
  }
  return labels[riskLevel.value] ?? '未知'
})

/** Methods to modify — from new structure, with legacy fallback */
const modifiedMethods = computed(() => {
  const explicit = props.output.methods_to_modify
  if (explicit && explicit.length > 0) return explicit
  // Legacy: check modified.methods_to_modify
  const legacy = props.output.modified?.methods_to_modify
  if (legacy && Array.isArray(legacy) && legacy.length > 0) {
    return legacy as MethodToModify[]
  }
  return []
})

/** Direct affected entries */
const directEntries = computed(() => props.output.affected_entries?.direct ?? [])

/** Indirect affected entries */
const indirectEntries = computed(() => props.output.affected_entries?.indirect ?? [])

const validationPassed = computed(() => props.output.validation?.passed !== false)
const validationViolations = computed(() => {
  const raw = props.output.validation?.violations ?? []
  return raw.map(humanizeViolation)
})

const hasReasoning = computed(() => !!props.output.reasoning && props.output.reasoning.trim().length > 0)
const showReasoning = ref(false)

const hasMarkdownReport = computed(() => !!props.output.markdown_report && props.output.markdown_report.trim().length > 0)
</script>

<template>
  <div class="impact-output">
    <!-- ① Risk Badge -->
    <div class="risk-header">
      <el-tag
        :type="riskTagType"
        size="large"
        effect="dark"
        :class="{ 'risk-critical': riskLevel === 'CRITICAL' }"
      >
        <el-icon class="risk-icon"><Warning /></el-icon>
        风险等级：{{ riskLabel }}（{{ riskScoreDisplay }}）
      </el-tag>
    </div>

    <!-- ② Methods to Modify -->
    <div v-if="modifiedMethods.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">📝</span>
        <span class="section-title">需要修改的方法</span>
        <el-tag size="small" type="info" round>{{ modifiedMethods.length }}个</el-tag>
      </div>
      <div class="section-desc">以下方法需要进行代码修改：</div>
      <table class="method-table">
        <thead>
          <tr><th>#</th><th>方法</th><th>说明</th></tr>
        </thead>
        <tbody>
          <tr v-for="(m, idx) in modifiedMethods" :key="idx">
            <td class="col-num">{{ idx + 1 }}</td>
            <td><code class="method-name">{{ formatMethod(m.className, m.methodName, m.nodeId) }}</code></td>
            <td class="col-reason">{{ m.reason ?? '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="section">
      <div class="section-header">
        <span class="section-icon">📝</span>
        <span class="section-title">需要修改的方法</span>
      </div>
      <div class="empty-hint">无需修改的方法</div>
    </div>

    <!-- ③ Affected Entries — Direct -->
    <div v-if="directEntries.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">🔌</span>
        <span class="section-title">受影响的入口 — 直接相关</span>
        <el-tag size="small" type="danger" round>{{ directEntries.length }}个</el-tag>
      </div>
      <div class="section-desc">
        以下入口的功能与需求直接相关，修改后行为会直接体现：
      </div>
      <ul class="method-list plain">
        <li v-for="(ae, idx) in directEntries" :key="idx" class="method-item">
          <div class="entry-header">
            <span class="entry-icon">{{ entryTypeIcon(ae.type) }}</span>
            <el-tag size="small" type="primary" class="entry-type-tag">
              {{ entryTypeLabel(ae.type) }}
            </el-tag>
            <code class="method-name">{{ formatMethod(ae.className, ae.methodName, ae.nodeId) }}</code>
            <span v-if="ae.reason" class="entry-reason">— {{ ae.reason }}</span>
          </div>
          <!-- Deep analysis detail -->
          <div v-if="ae.business_function || ae.impact_mechanism || ae.change_behavior || ae.call_path" class="entry-detail">
            <div v-if="ae.business_function" class="entry-subtitle">{{ ae.business_function }}</div>
            <div v-if="ae.impact_mechanism" class="entry-mechanism">
              <span class="detail-label">影响机制：</span>{{ ae.impact_mechanism }}
            </div>
            <div v-if="ae.change_behavior" class="entry-behavior">
              <span class="detail-label">行为变化：</span>{{ ae.change_behavior }}
            </div>
            <div v-if="ae.call_path" class="entry-callpath">
              <span class="detail-label">调用路径：</span><code>{{ ae.call_path }}</code>
            </div>
          </div>
        </li>
      </ul>
    </div>

    <!-- ④ Affected Entries — Indirect -->
    <div v-if="indirectEntries.length > 0" class="section">
      <el-collapse>
        <el-collapse-item>
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">🔗</span>
              <span class="section-title">受影响的入口 — 间接相关</span>
              <el-tag size="small" type="info" round>{{ indirectEntries.length }}个</el-tag>
            </div>
          </template>
          <div class="section-desc">
            以下入口通过调用链间接受影响：
          </div>
          <ul class="method-list plain">
            <li v-for="(ae, idx) in indirectEntries" :key="idx" class="method-item">
              <span class="entry-icon">{{ entryTypeIcon(ae.type) }}</span>
              <el-tag size="small" type="info" class="entry-type-tag">
                {{ entryTypeLabel(ae.type) }}
              </el-tag>
              <code class="method-name">{{ formatMethod(ae.className, ae.methodName, ae.nodeId) }}</code>
              <span v-if="ae.reason" class="entry-reason">— {{ ae.reason }}</span>
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- ⑤ Validation Warnings -->
    <div v-if="!validationPassed" class="section validation-section">
      <div class="section-header">
        <span class="section-icon">⚠️</span>
        <span class="section-title">验证警告</span>
        <el-tag size="small" type="warning" round>{{ validationViolations.length }}项</el-tag>
      </div>
      <div
        v-for="(v, idx) in validationViolations"
        :key="idx"
        class="violation-item"
      >
        <div class="violation-label">{{ v.label }}</div>
        <div v-if="v.detail" class="violation-detail">{{ v.detail }}</div>
      </div>
    </div>

    <!-- Validation passed indicator -->
    <div v-if="validationPassed && validationViolations.length === 0" class="section validation-passed">
      <el-tag type="success" effect="plain" size="small">
        <el-icon><Check /></el-icon>
        验证通过，无结构性问题
      </el-tag>
    </div>

    <!-- ⑥ Reasoning (collapsible) -->
    <div v-if="hasReasoning" class="section">
      <el-collapse v-model="showReasoning">
        <el-collapse-item name="reasoning">
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">💭</span>
              <span class="section-title">分析过程</span>
            </div>
          </template>
          <pre class="reasoning-text">{{ output.reasoning }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- ⑦ Markdown Report (collapsible) -->
    <div v-if="hasMarkdownReport" class="section">
      <el-collapse v-model="showMarkdownReport">
        <el-collapse-item name="report">
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">📊</span>
              <span class="section-title">格式化报告</span>
            </div>
          </template>
          <div class="markdown-body" v-html="renderedMarkdown"></div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<script lang="ts">
import { Warning, Check } from '@element-plus/icons-vue'

export default {
  components: { Warning, Check }
}
</script>

<style scoped>
.impact-output {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ─── Risk Header ─── */
.risk-header {
  display: flex;
  align-items: center;
}

.risk-icon {
  margin-right: 4px;
}

.risk-critical {
  font-weight: 700;
  animation: pulse-danger 1.5s infinite;
}

@keyframes pulse-danger {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

/* ─── Sections ─── */
.section {
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 12px;
}

.section:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.section-icon {
  font-size: 16px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.section-desc {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  line-height: 1.5;
}

/* ─── Method Table ─── */
.method-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.method-table th {
  text-align: left;
  font-weight: 500;
  color: #909399;
  border-bottom: 1px solid #ebeef5;
  padding: 4px 8px;
  font-size: 12px;
}

.method-table td {
  padding: 6px 8px;
  border-bottom: 1px solid #f5f7fa;
  vertical-align: top;
}

.col-num {
  width: 30px;
  color: #909399;
}

.col-reason {
  color: #606266;
  font-size: 12px;
}

/* ─── Method Lists ─── */
.method-list {
  margin: 0;
  padding-left: 20px;
}

.method-list.plain {
  list-style: none;
  padding-left: 0;
}

.method-item {
  font-size: 13px;
  line-height: 2;
  color: #303133;
}

.entry-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.entry-detail {
  margin-top: 4px;
  padding: 6px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  width: 100%;
  line-height: 1.6;
}

.entry-subtitle {
  font-size: 12px;
  color: #606266;
  margin-bottom: 4px;
}

.entry-mechanism, .entry-behavior {
  font-size: 12px;
  color: #303133;
}

.entry-callpath {
  font-size: 12px;
  color: #303133;
}

.entry-callpath code {
  font-size: 11px;
  background: #ecf5ff;
  padding: 1px 4px;
  border-radius: 2px;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
}

.detail-label {
  font-weight: 500;
  color: #909399;
}

.method-name {
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  background: #f0f2f5;
  padding: 2px 6px;
  border-radius: 3px;
  color: #409eff;
}

.entry-icon {
  font-size: 14px;
}

.entry-type-tag {
  font-size: 10px;
}

.entry-reason {
  font-size: 12px;
  color: #909399;
}

.empty-hint {
  font-size: 13px;
  color: #c0c4cc;
  font-style: italic;
}

/* ─── Validation ─── */
.validation-section {
  background: #fdf6ec;
  border-radius: 6px;
  padding: 12px;
  border: 1px solid #faecd8;
}

.violation-item {
  padding: 6px 0;
  border-bottom: 1px dashed #faecd8;
}

.violation-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.violation-label {
  font-size: 13px;
  font-weight: 500;
  color: #e6a23c;
  line-height: 1.5;
}

.violation-detail {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  line-height: 1.4;
}

.validation-passed {
  display: flex;
  align-items: center;
  border-bottom: none;
  padding-bottom: 0;
}

.validation-passed .el-icon {
  margin-right: 4px;
}

/* ─── Collapse overrides ─── */
.collapse-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

:deep(.el-collapse) {
  border: none;
}

:deep(.el-collapse-item__header) {
  border-bottom: none;
  height: 36px;
  line-height: 36px;
  background: transparent;
}

:deep(.el-collapse-item__wrap) {
  border-bottom: none;
  background: transparent;
}

:deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

/* ─── Reasoning ─── */
.reasoning-text {
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
  background: #f5f7fa;
  padding: 10px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-family: inherit;
}

/* ─── Markdown Report ─── */
.markdown-body {
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
}

.markdown-body :deep(h2) {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}

.markdown-body :deep(h3) {
  font-size: 14px;
  font-weight: 600;
  margin: 12px 0 8px;
}

.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 12px;
}

.markdown-body :deep(th) {
  text-align: left;
  background: #f5f7fa;
  padding: 6px 8px;
  border: 1px solid #ebeef5;
  font-weight: 500;
}

.markdown-body :deep(td) {
  padding: 6px 8px;
  border: 1px solid #ebeef5;
}

.markdown-body :deep(code) {
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  background: #f0f2f5;
  padding: 1px 4px;
  border-radius: 3px;
  color: #409eff;
}

.markdown-body :deep(ul) {
  padding-left: 20px;
}

.markdown-body :deep(li) {
  margin: 4px 0;
  line-height: 1.5;
}

.markdown-body :deep(strong) {
  font-weight: 600;
}
</style>
