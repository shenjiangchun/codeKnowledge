<script setup lang="ts">
/**
 * ImpactOutputView — Human-readable renderer for impact analysis node output.
 *
 * Design principles:
 * - NO raw JSON display
 * - DO NOT show "involved" (search candidates are internal process noise)
 * - Use natural language to explain: what to modify, what's affected, why, and what to watch out for
 * - Risk level displayed prominently
 * - Validation warnings highlighted
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

/** Extract a human-friendly file path hint from nodeId (if it contains path info) */
function extractFilePath(nodeId: string): string | undefined {
  if (!nodeId) return undefined
  // nodeId format: "C:/path/to/project:com.package.Class.method.hash"
  const colonIdx = nodeId.indexOf(':')
  if (colonIdx <= 0) return undefined
  const pathPart = nodeId.substring(0, colonIdx)
  // Get just the last few segments for brevity
  const segments = pathPart.replace(/\\/g, '/').split('/')
  if (segments.length > 3) {
    return '.../' + segments.slice(-3).join('/')
  }
  return pathPart
}

// ─── Computed ────────────────────────────────────────────────────────────────

const riskLevel = computed(() => props.output.risk?.level ?? 'UNKNOWN')
const riskScore = computed(() => props.output.risk?.score ?? 0)

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

const upstreamMethods = computed(() => props.output.impacted?.upstream ?? [])
const downstreamMethods = computed(() => props.output.impacted?.downstream ?? [])
const crossServiceItems = computed(() => {
  const cs = props.output.impacted?.crossService ?? []
  const bridges = props.output.impacted?.bridges ?? []
  // Merge both lists, deduplicate by nodeId
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
const validationViolations = computed(() => props.output.validation?.violations ?? [])
</script>

<template>
  <div class="impact-output">
    <!-- Risk Badge -->
    <div class="risk-header">
      <el-tag
        :type="riskTagType"
        size="large"
        effect="dark"
        :class="{ 'risk-critical': riskLevel === 'CRITICAL' }"
      >
        <el-icon class="risk-icon"><Warning /></el-icon>
        风险等级：{{ riskLabel }}（{{ riskScore.toFixed(2) }}）
      </el-tag>
    </div>

    <!-- Validation Warnings (show first if any) -->
    <div v-if="!validationPassed" class="section validation-section">
      <el-alert
        v-for="(violation, idx) in validationViolations"
        :key="idx"
        :title="violation"
        type="warning"
        :closable="false"
        show-icon
        class="validation-alert"
      />
    </div>

    <!-- Modified Methods (always expanded) -->
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

    <!-- Upstream (collapsible) -->
    <div v-if="upstreamMethods.length > 0" class="section">
      <el-collapse>
        <el-collapse-item>
          <template #title>
            <div class="collapse-title">
              <span class="section-icon">⬆️</span>
              <span class="section-title">上游调用方</span>
              <el-tag size="small" type="info" round>{{ upstreamMethods.length }}个</el-tag>
            </div>
          </template>
          <div class="section-desc">
            这些方法调用了待修改方法，修改后需要回归测试以确保调用方行为不变：
          </div>
          <ul class="method-list plain">
            <li v-for="(m, idx) in upstreamMethods" :key="idx" class="method-item">
              <code class="method-name">{{ formatMethod(m.className, m.methodName) }}</code>
              <el-tag v-if="m.type" size="small" class="entry-type-tag">{{ m.type }}</el-tag>
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- Downstream (collapsible) -->
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

    <!-- Cross-Service (always expanded) -->
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

/* ─── Validation ─── */
.validation-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.validation-alert {
  margin: 0;
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
</style>
