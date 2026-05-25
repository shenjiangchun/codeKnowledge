<script setup lang="ts">
/**
 * ImpactOutputView — Human-readable renderer for impact analysis node output.
 *
 * Design principles:
 * - NO raw JSON display
 * - DO NOT show "involved" (search candidates are internal process noise)
 * - Use natural language to explain: what to modify, what's affected, why, and what to watch out for
 * - Risk level displayed prominently
 * - Validation warnings highlighted with human-readable text
 *
 * Section order:
 *   1. Risk badge
 *   2. Validation warnings (if any)
 *   3. Affected entry points (Controllers/APIs extracted from upstream)
 *   4. Modified methods (what to change)
 *   5. Other upstream callers (non-entry-point callers, collapsible)
 *   6. Downstream callees (what the modified methods call)
 *   7. Cross-service impacts
 */
import { computed } from 'vue'

// ─── Types ───────────────────────────────────────────────────────────────────

interface CallTreeNode {
  nodeId: string
  className: string
  methodName: string
  depth?: number
  children?: CallTreeNode[]
}

interface UpstreamEntry {
  nodeId: string
  className: string
  methodName: string
  type?: string
}

interface DownstreamEntry {
  nodeId: string
  className: string
  methodName: string
  type?: string
}

interface CrossServiceEntry {
  nodeId: string
  bridgeType: string
  target: string
  className?: string
  methodName?: string
}

interface ImpactOutput {
  involved?: unknown
  modified?: {
    tree?: CallTreeNode[]
  }
  impacted?: {
    upstream?: UpstreamEntry[]
    downstream?: DownstreamEntry[]
    crossService?: CrossServiceEntry[]
    bridges?: CrossServiceEntry[]
  }
  risk?: {
    score?: number
    level?: string
  }
  validation?: {
    passed?: boolean
    violations?: string[]
  }
}

const props = defineProps<{
  output: ImpactOutput
}>()

// ─── Constants ───────────────────────────────────────────────────────────────

/** Entry point types that should be shown in the "Affected APIs" section */
const ENTRY_POINT_TYPES = new Set([
  'CONTROLLER', 'SCHEDULED', 'MQ_LISTENER', 'FEIGN_CLIENT',
  'REST_ENDPOINT', 'WEBSOCKET', 'EVENT_LISTENER'
])

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Extract short class name from fully qualified name */
function shortName(className: string): string {
  if (!className) return ''
  const parts = className.split('.')
  return parts[parts.length - 1]
}

/** Format as ShortClassName#methodName */
function formatMethod(className: string, methodName: string): string {
  return `${shortName(className)}#${methodName}`
}

/**
 * Extract short class#method from a nodeId string.
 * NodeId format: "C:/path/to/project:com.package.Class.method.hash"
 */
function extractMethodFromNodeId(nodeId: string): string {
  if (!nodeId) return nodeId
  const colonIdx = nodeId.indexOf(':')
  if (colonIdx <= 0) return nodeId
  const qualifiedPart = nodeId.substring(colonIdx + 1)
  // Split by '.', last segment is hash, second-to-last is method, rest is class
  const segments = qualifiedPart.split('.')
  if (segments.length < 3) return qualifiedPart
  // Remove hash (last segment)
  const hash = segments[segments.length - 1]
  if (/^[0-9a-f]{6,}$/i.test(hash)) {
    segments.pop()
  }
  const methodName = segments.pop() ?? ''
  const className = segments.join('.')
  return formatMethod(className, methodName)
}

/**
 * Humanize a validation violation string for display.
 * Replaces raw nodeIds with short class#method names.
 */
function humanizeViolation(violation: string): { label: string; detail: string } {
  // Pattern: "Entry not reachable as a root entry: <nodeId>"
  const entryMatch = violation.match(/^Entry not reachable as a root entry:\s*(.+)$/)
  if (entryMatch) {
    const method = extractMethodFromNodeId(entryMatch[1].trim())
    return {
      label: `入口不可达：${method}`,
      detail: '此方法在知识图谱中被标记为入口点，但无法从任何根入口追溯到达'
    }
  }
  // Pattern: "Impl missing from modified ring: <nodeId>"
  const implMatch = violation.match(/^Impl missing from modified ring:\s*(.+)$/)
  if (implMatch) {
    const method = extractMethodFromNodeId(implMatch[1].trim())
    return {
      label: `实现类缺失：${method}`,
      detail: '接口的实现类未包含在修改范围内，可能需要同步修改'
    }
  }
  // Generic: try to replace any nodeId-like patterns
  const genericNodeId = violation.match(/([A-Za-z]:[\\/].+?:[a-zA-Z][\w.]+\.[0-9a-f]{6,})/g)
  if (genericNodeId) {
    let humanized = violation
    for (const nid of genericNodeId) {
      humanized = humanized.replace(nid, extractMethodFromNodeId(nid))
    }
    return { label: humanized, detail: '' }
  }
  return { label: violation, detail: '' }
}

/** Flatten a CallTreeNode[] into a flat method list (DFS) */
function flattenTree(nodes: CallTreeNode[]): Array<{ className: string; methodName: string; filePath?: string }> {
  const result: Array<{ className: string; methodName: string; filePath?: string }> = []
  const visited = new Set<string>()

  function dfs(node: CallTreeNode): void {
    const key = `${node.className}.${node.methodName}`
    if (visited.has(key)) return
    visited.add(key)
    result.push({
      className: node.className,
      methodName: node.methodName,
      filePath: extractFilePath(node.nodeId)
    })
    if (node.children) {
      for (const child of node.children) {
        dfs(child)
      }
    }
  }

  for (const node of nodes) {
    dfs(node)
  }
  return result
}

/** Extract a human-friendly file path hint from nodeId */
function extractFilePath(nodeId: string): string | undefined {
  if (!nodeId) return undefined
  const colonIdx = nodeId.indexOf(':')
  if (colonIdx <= 0) return undefined
  const pathPart = nodeId.substring(0, colonIdx)
  const segments = pathPart.replace(/\\/g, '/').split('/')
  if (segments.length > 3) {
    return '.../' + segments.slice(-3).join('/')
  }
  return pathPart
}

/** Friendly label for entry point types */
function entryTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    CONTROLLER: 'HTTP 接口',
    REST_ENDPOINT: 'REST 接口',
    SCHEDULED: '定时任务',
    MQ_LISTENER: '消息监听',
    FEIGN_CLIENT: 'Feign 调用',
    WEBSOCKET: 'WebSocket',
    EVENT_LISTENER: '事件监听'
  }
  return labels[type] ?? type
}

// ─── Computed ────────────────────────────────────────────────────────────────

const riskLevel = computed(() => props.output.risk?.level ?? 'UNKNOWN')
const riskScore = computed(() => props.output.risk?.score ?? 0)

/** Display risk score: if > 1 treat as percentage, otherwise as 0-1 ratio */
const riskScoreDisplay = computed(() => {
  const score = riskScore.value
  if (score > 1) return `${score.toFixed(1)}%`
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

const modifiedMethods = computed(() => {
  const tree = props.output.modified?.tree
  if (!tree || tree.length === 0) return []
  return flattenTree(tree)
})

/** Split upstream into entry points vs. other callers */
const entryPointMethods = computed(() => {
  const all = props.output.impacted?.upstream ?? []
  return all.filter((m) => m.type && ENTRY_POINT_TYPES.has(m.type))
})

const otherUpstreamMethods = computed(() => {
  const all = props.output.impacted?.upstream ?? []
  return all.filter((m) => !m.type || !ENTRY_POINT_TYPES.has(m.type))
})

const downstreamMethods = computed(() => props.output.impacted?.downstream ?? [])

const crossServiceItems = computed(() => {
  const cs = props.output.impacted?.crossService ?? []
  const bridges = props.output.impacted?.bridges ?? []
  const seen = new Set<string>()
  const result: CrossServiceEntry[] = []
  for (const item of [...cs, ...bridges]) {
    if (!seen.has(item.nodeId)) {
      seen.add(item.nodeId)
      result.push(item)
    }
  }
  return result
})

const validationPassed = computed(() => props.output.validation?.passed !== false)
const validationViolations = computed(() => {
  const raw = props.output.validation?.violations ?? []
  return raw.map(humanizeViolation)
})
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

    <!-- ② Validation Warnings -->
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

    <!-- ③ Affected Entry Points (Controllers/APIs) — BEFORE modified -->
    <div v-if="entryPointMethods.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">🔌</span>
        <span class="section-title">受影响的接口</span>
        <el-tag size="small" type="danger" round>{{ entryPointMethods.length }}个</el-tag>
      </div>
      <div class="section-desc">
        以下入口接口的调用链经过待修改方法，修改后这些接口的行为可能发生变化：
      </div>
      <ul class="method-list plain">
        <li v-for="(m, idx) in entryPointMethods" :key="idx" class="method-item">
          <el-tag size="small" :type="m.type === 'CONTROLLER' ? 'primary' : 'info'" class="entry-type-tag">
            {{ entryTypeLabel(m.type ?? '') }}
          </el-tag>
          <code class="method-name">{{ formatMethod(m.className, m.methodName) }}</code>
        </li>
      </ul>
    </div>

    <!-- ④ Modified Methods (always expanded) -->
    <div class="section">
      <div class="section-header">
        <span class="section-icon">📝</span>
        <span class="section-title">需要修改的方法</span>
        <el-tag size="small" type="info" round>{{ modifiedMethods.length }}个</el-tag>
      </div>
      <div class="section-desc">以下方法需要进行代码修改：</div>
      <ol class="method-list">
        <li v-for="(m, idx) in modifiedMethods" :key="idx" class="method-item">
          <code class="method-name">{{ formatMethod(m.className, m.methodName) }}</code>
          <span v-if="m.filePath" class="method-file">{{ m.filePath }}</span>
        </li>
      </ol>
      <div v-if="modifiedMethods.length === 0" class="empty-hint">无需修改的方法</div>
    </div>

    <!-- ⑤ Other Upstream Callers (collapsible, non-entry-point) -->
    <div v-if="otherUpstreamMethods.length > 0" class="section">
      <el-collapse>
        <el-collapse-item>
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">⬆️</span>
              <span class="section-title">其他上游调用方</span>
              <el-tag size="small" type="info" round>{{ otherUpstreamMethods.length }}个</el-tag>
            </div>
          </template>
          <div class="section-desc">
            这些方法调用了待修改方法，修改后需要回归测试以确保调用方行为不变：
          </div>
          <ul class="method-list plain">
            <li v-for="(m, idx) in otherUpstreamMethods" :key="idx" class="method-item">
              <code class="method-name">{{ formatMethod(m.className, m.methodName) }}</code>
              <el-tag v-if="m.type" size="small" class="entry-type-tag">{{ m.type }}</el-tag>
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- ⑥ Downstream (collapsible) -->
    <div v-if="downstreamMethods.length > 0" class="section">
      <el-collapse>
        <el-collapse-item>
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">⬇️</span>
              <span class="section-title">下游被调方</span>
              <el-tag size="small" type="info" round>{{ downstreamMethods.length }}个</el-tag>
            </div>
          </template>
          <div class="section-desc">
            这些方法被待修改方法调用，需确认接口兼容性（参数、返回值是否发生变更）：
          </div>
          <ul class="method-list plain">
            <li v-for="(m, idx) in downstreamMethods" :key="idx" class="method-item">
              <code class="method-name">{{ formatMethod(m.className, m.methodName) }}</code>
              <el-tag v-if="m.type" size="small" class="entry-type-tag">{{ m.type }}</el-tag>
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- ⑦ Cross-Service (always expanded) -->
    <div v-if="crossServiceItems.length > 0" class="section">
      <div class="section-header">
        <span class="section-icon">🌐</span>
        <span class="section-title">跨服务影响</span>
        <el-tag size="small" type="danger" round>{{ crossServiceItems.length }}个</el-tag>
      </div>
      <div class="section-desc">
        涉及微服务间调用，修改后需要协调相关服务同步变更：
      </div>
      <ul class="method-list plain cross-service-list">
        <li v-for="(item, idx) in crossServiceItems" :key="idx" class="method-item cross-service-item">
          <el-tag size="small" :type="item.bridgeType === 'FEIGN' ? 'primary' : 'warning'">
            {{ item.bridgeType }}
          </el-tag>
          <span class="cross-target">→ {{ item.target }}</span>
          <code v-if="item.className && item.methodName" class="method-name">
            {{ formatMethod(item.className, item.methodName) }}
          </code>
        </li>
      </ul>
    </div>

    <!-- Validation passed indicator -->
    <div v-if="validationPassed && validationViolations.length === 0" class="section validation-passed">
      <el-tag type="success" effect="plain" size="small">
        <el-icon><Check /></el-icon>
        验证通过，无结构性问题
      </el-tag>
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

/* ─── Method Lists ─── */
.method-list {
  margin: 0;
  padding-left: 20px;
}

.method-list.plain {
  list-style: disc;
}

.method-item {
  font-size: 13px;
  line-height: 2;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.method-name {
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  background: #f0f2f5;
  padding: 2px 6px;
  border-radius: 3px;
  color: #409eff;
}

.method-file {
  font-size: 11px;
  color: #909399;
}

.entry-type-tag {
  font-size: 10px;
}

.empty-hint {
  font-size: 13px;
  color: #c0c4cc;
  font-style: italic;
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

/* ─── Cross-Service ─── */
.cross-service-list {
  list-style: none;
  padding-left: 0;
}

.cross-service-item {
  gap: 6px;
}

.cross-target {
  font-size: 13px;
  font-weight: 500;
  color: #606266;
}

/* ─── Validation Passed ─── */
.validation-passed {
  display: flex;
  align-items: center;
  border-bottom: none;
  padding-bottom: 0;
}

.validation-passed .el-icon {
  margin-right: 4px;
}
</style>
