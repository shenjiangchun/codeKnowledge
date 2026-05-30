<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { ArrowRight, Loading, WarningFilled } from '@element-plus/icons-vue'
import { useApmStore } from '@/stores/apmStore'
import { knowledgeGraphApi } from '@/api/knowledgeGraph'

interface CallTreeNode {
  nodeId: string
  className: string
  methodName: string
  signature: string
  depth: number
  children: CallTreeNode[]
}

interface FlatCallNode {
  nodeId: string
  className: string
  methodName: string
  depth: number
}

/** Runtime span that could not match any KG node */
interface UnmatchedSpanInfo {
  spanId: string
  operationName: string
  className: string | null
  methodName: string | null
  kgMatchLevel: number
}

const store = useApmStore()

const loading = ref(false)
const callTree = ref<CallTreeNode[]>([])
const expanded = ref(true)
const mismatchExpanded = ref(false)
const errorMsg = ref('')

// Reload call chain preview when selected entry changes
watch(
  () => store.selectedEntry,
  async (entry) => {
    callTree.value = []
    errorMsg.value = ''
    if (!entry || !store.selectedProject) return

    const parsed = parseEntryInfo(entry.entryInfo)
    if (!parsed) return

    loading.value = true
    try {
      const result = await knowledgeGraphApi.getCalleesTree(
        parsed.className,
        parsed.methodName,
        [store.selectedProject.projectPath],
      ) as unknown as CalleeTreeResponse
      callTree.value = buildTree(result)
    } catch {
      errorMsg.value = '调用链预览加载失败'
    } finally {
      loading.value = false
    }
  },
)

// Matches CallChainGraphData from knowledgeGraph.ts (unwrapped by interceptor)
interface CalleeTreeResponse {
  nodes: Array<{
    id: string
    name: string     // methodName
    className: string
    depth: number
    signature?: string
  }>
  edges: Array<{
    source: string
    target: string
  }>
}

function parseEntryInfo(info: string): { className: string; methodName: string } | null {
  const match = info.match(/^(.+)\.(\w+)\(/)
  if (match) {
    return { className: match[1], methodName: match[2] }
  }
  return null
}

function buildTree(response: CalleeTreeResponse): CallTreeNode[] {
  if (!response?.nodes || response.nodes.length === 0) return []

  const nodeMap = new Map<string, CallTreeNode>()
  for (const n of response.nodes) {
    nodeMap.set(n.id, {
      nodeId: n.id,
      className: n.className,
      methodName: n.name,
      signature: n.signature || '',
      depth: n.depth,
      children: [],
    })
  }

  const targetIds = new Set<string>()
  if (response.edges) {
    for (const e of response.edges) {
      const parent = nodeMap.get(e.source)
      const child = nodeMap.get(e.target)
      if (parent && child) {
        parent.children.push(child)
        targetIds.add(e.target)
      }
    }
  }

  // Roots = not targeted by any edge
  const roots: CallTreeNode[] = []
  for (const n of response.nodes) {
    if (!targetIds.has(n.id)) {
      const node = nodeMap.get(n.id)
      if (node) roots.push(node)
    }
  }

  return roots.length > 0 ? roots : Array.from(nodeMap.values()).filter(n => n.depth === 0)
}

// Flatten tree for rendering
const flatNodes = computed<FlatCallNode[]>(() => {
  const result: FlatCallNode[] = []
  function walk(nodes: CallTreeNode[], depth: number): void {
    for (const n of nodes) {
      result.push({
        nodeId: n.nodeId,
        className: n.className,
        methodName: n.methodName,
        depth,
      })
      walk(n.children, depth + 1)
    }
  }
  walk(callTree.value, 0)
  return result
})

function getShortClassName(fullName: string): string {
  const parts = fullName.split('.')
  return parts[parts.length - 1] || fullName
}

function isSpanMatched(nodeId: string): boolean {
  return store.wsSpans.some(s => s.kgNodeId === nodeId)
}

/** True if this KG node corresponds to the currently selected span (bidirectional highlight). */
function isCurrentlySelected(nodeId: string): boolean {
  return store.selectedSpan?.kgNodeId === nodeId
}

/**
 * Click a KG node -> select the first matching runtime span (if any),
 * so TraceView highlights it. If no span matches yet (preview-only),
 * do nothing visible — user gets the "matched/unmatched" dot to know
 * whether the node was hit.
 */
function handleNodeClick(nodeId: string): void {
  const span = store.wsSpans.find(s => s.kgNodeId === nodeId)
  if (span) {
    store.selectSpan(store.selectedSpan?.spanId === span.spanId ? null : span)
  }
}

const hasSpans = computed(() => store.wsSpans.length > 0)

// ============================================================
// Format Mismatch Detection (Decision 4)
// Show runtime spans that couldn't match any KG node, so the user
// can see the format difference and optimize KG generation.
// ============================================================

/** Spans that have no kgNodeId (failed to match static call chain) */
const unmatchedSpans = computed<UnmatchedSpanInfo[]>(() => {
  if (!hasSpans.value) return []
  return store.wsSpans
    .filter(s => !s.kgNodeId || s.kgMatchLevel === 0)
    .map(s => ({
      spanId: s.spanId,
      operationName: s.operationName,
      className: s.className,
      methodName: s.methodName,
      kgMatchLevel: s.kgMatchLevel,
    }))
})

/** Match statistics */
const matchStats = computed(() => {
  if (!hasSpans.value || flatNodes.value.length === 0) return null
  const totalKgNodes = flatNodes.value.length
  const matchedKgNodes = flatNodes.value.filter(n => isSpanMatched(n.nodeId)).length
  const totalSpans = store.wsSpans.length
  const unmatchedSpanCount = unmatchedSpans.value.length
  return {
    totalKgNodes,
    matchedKgNodes,
    totalSpans,
    unmatchedSpanCount,
    matchRate: totalKgNodes > 0 ? Math.round((matchedKgNodes / totalKgNodes) * 100) : 0,
  }
})

/** Whether there's a format mismatch worth showing */
const hasMismatch = computed(() => {
  const stats = matchStats.value
  if (!stats) return false
  return stats.unmatchedSpanCount > 0 || stats.matchedKgNodes < stats.totalKgNodes
})
</script>

<template>
  <div v-if="store.selectedEntry" class="call-chain-preview">
    <!-- Header -->
    <div class="preview-header" @click="expanded = !expanded">
      <el-icon class="expand-icon" :class="{ rotated: expanded }">
        <ArrowRight />
      </el-icon>
      <span class="preview-title">预期调用链</span>
      <el-tag v-if="flatNodes.length > 0" size="small" type="info" effect="plain">
        {{ flatNodes.length }} nodes
      </el-tag>
      <!-- Match rate badge after execution -->
      <el-tag
        v-if="matchStats"
        size="small"
        :type="matchStats.matchRate >= 80 ? 'success' : matchStats.matchRate >= 50 ? 'warning' : 'danger'"
        effect="plain"
      >
        {{ matchStats.matchRate }}% matched
      </el-tag>
    </div>

    <!-- Content -->
    <el-collapse-transition>
      <div v-if="expanded" class="preview-content">
        <!-- Loading -->
        <div v-if="loading" class="preview-loading">
          <el-icon class="loading-spin"><Loading /></el-icon>
          <span>加载调用链...</span>
        </div>

        <!-- Error -->
        <div v-else-if="errorMsg" class="preview-message">
          <el-text type="info" size="small">{{ errorMsg }}</el-text>
        </div>

        <!-- Empty -->
        <div v-else-if="flatNodes.length === 0" class="preview-message">
          <el-text type="info" size="small">无下游调用信息</el-text>
        </div>

        <!-- Flat tree -->
        <el-scrollbar v-else class="preview-tree" max-height="200px">
          <div
            v-for="node in flatNodes"
            :key="node.nodeId"
            class="tree-node"
            :class="{ 'tree-node-clickable': hasSpans && isSpanMatched(node.nodeId) }"
            :style="{ paddingLeft: `${node.depth * 16 + 4}px` }"
            @click="handleNodeClick(node.nodeId)"
          >
            <span
              class="tree-node-label"
              :class="{
                matched: hasSpans && isSpanMatched(node.nodeId),
                unmatched: hasSpans && !isSpanMatched(node.nodeId),
                current: isCurrentlySelected(node.nodeId),
              }"
            >
              <span class="node-dot" :class="{ 'matched-dot': hasSpans && isSpanMatched(node.nodeId) }" />
              <span class="node-text">{{ getShortClassName(node.className) }}.{{ node.methodName }}()</span>
            </span>
          </div>
        </el-scrollbar>

        <!-- Format Mismatch Panel -->
        <div v-if="hasMismatch" class="mismatch-panel">
          <div class="mismatch-header" @click="mismatchExpanded = !mismatchExpanded">
            <el-icon class="expand-icon" :class="{ rotated: mismatchExpanded }">
              <ArrowRight />
            </el-icon>
            <el-icon color="var(--el-color-warning)"><WarningFilled /></el-icon>
            <span class="mismatch-title">格式不匹配 ({{ unmatchedSpans.length }} spans)</span>
          </div>

          <el-collapse-transition>
            <div v-if="mismatchExpanded" class="mismatch-content">
              <!-- Summary -->
              <div v-if="matchStats" class="mismatch-summary">
                <div class="stat-row">
                  <span class="stat-label">KG 静态节点:</span>
                  <span class="stat-value">{{ matchStats.matchedKgNodes }} / {{ matchStats.totalKgNodes }} matched</span>
                </div>
                <div class="stat-row">
                  <span class="stat-label">运行时 Span:</span>
                  <span class="stat-value">{{ matchStats.totalSpans }} total, {{ matchStats.unmatchedSpanCount }} unmatched</span>
                </div>
              </div>

              <!-- Unmatched spans detail -->
              <div class="mismatch-hint">
                <el-text type="info" size="small">
                  以下运行时 Span 未匹配到静态调用链节点，可能是 KG 节点命名格式与 OTel span 名称不一致：
                </el-text>
              </div>
              <el-scrollbar max-height="150px">
                <div
                  v-for="span in unmatchedSpans"
                  :key="span.spanId"
                  class="unmatched-span-item"
                >
                  <div class="span-operation">{{ span.operationName }}</div>
                  <div class="span-detail">
                    <span v-if="span.className" class="span-class">{{ span.className }}</span>
                    <span v-if="span.methodName" class="span-method">.{{ span.methodName }}()</span>
                    <span v-if="!span.className && !span.methodName" class="span-no-info">无类/方法信息</span>
                    <el-tag size="small" type="info" effect="plain" class="match-level-tag">
                      matchLevel={{ span.kgMatchLevel }}
                    </el-tag>
                  </div>
                </div>
              </el-scrollbar>
              <div class="mismatch-footer">
                <el-text type="info" size="small">
                  提示: 请优化知识图谱生成，使节点 ID/类名格式与 OTel Span 的 className/methodName 对齐
                </el-text>
              </div>
            </div>
          </el-collapse-transition>
        </div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<style scoped>
.call-chain-preview {
  border-top: 1px solid var(--el-border-color-lighter);
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
}

.preview-header:hover {
  background-color: var(--el-fill-color-light);
}

.expand-icon {
  transition: transform 0.2s;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.expand-icon.rotated {
  transform: rotate(90deg);
}

.preview-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.preview-content {
  padding: 0 12px 8px;
}

.preview-loading,
.preview-message {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.loading-spin {
  animation: rotate 1.5s linear infinite;
}

@keyframes rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.tree-node {
  padding: 2px 0;
}

.tree-node-clickable {
  cursor: pointer;
}

.tree-node-clickable:hover {
  background-color: var(--el-fill-color-light);
}

.tree-node-label.current {
  outline: 1px solid var(--el-color-primary);
  background-color: var(--el-color-primary-light-9) !important;
  color: var(--el-color-primary) !important;
}

.tree-node-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 4px;
  border-radius: 3px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 11px;
  color: var(--el-text-color-regular);
}

.tree-node-label.matched {
  background-color: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.tree-node-label.unmatched {
  color: var(--el-text-color-placeholder);
  text-decoration: line-through;
  opacity: 0.6;
}

.node-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background-color: var(--el-text-color-placeholder);
  flex-shrink: 0;
}

.node-dot.matched-dot {
  background-color: var(--el-color-success);
}

.node-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ============================================================
   Format Mismatch Panel Styles
   ============================================================ */
.mismatch-panel {
  margin-top: 8px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 4px;
  background-color: var(--el-color-warning-light-9);
}

.mismatch-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  cursor: pointer;
  user-select: none;
}

.mismatch-header:hover {
  background-color: var(--el-color-warning-light-8);
  border-radius: 4px;
}

.mismatch-title {
  font-size: 11px;
  font-weight: 500;
  color: var(--el-color-warning-dark-2);
}

.mismatch-content {
  padding: 4px 8px 8px;
  border-top: 1px solid var(--el-color-warning-light-5);
}

.mismatch-summary {
  margin-bottom: 6px;
}

.stat-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: 11px;
}

.stat-label {
  color: var(--el-text-color-secondary);
  min-width: 80px;
}

.stat-value {
  color: var(--el-text-color-primary);
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
}

.mismatch-hint {
  margin-bottom: 4px;
}

.unmatched-span-item {
  padding: 4px 6px;
  margin: 2px 0;
  border-radius: 3px;
  background-color: var(--el-fill-color-lighter);
  border-left: 2px solid var(--el-color-warning);
}

.span-operation {
  font-size: 11px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  word-break: break-all;
}

.span-detail {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 10px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.span-class {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  color: var(--el-text-color-regular);
}

.span-method {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  color: var(--el-color-primary);
}

.span-no-info {
  font-style: italic;
  color: var(--el-text-color-placeholder);
}

.match-level-tag {
  margin-left: 6px;
  font-size: 10px;
}

.mismatch-footer {
  margin-top: 6px;
  padding-top: 4px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
</style>
