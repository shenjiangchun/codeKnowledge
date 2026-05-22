<script setup lang="ts">
import { computed, watch, nextTick, ref } from 'vue'
import { useApmStore } from '@/stores/apmStore'
import type { ApmSpan, TraceNode } from '@/types/apm'
import { buildTraceTree } from '@/types/apm'

const store = useApmStore()

const SLOW_THRESHOLD_RATIO = 0.3 // spans taking >30% of total are "slow"
const HTTP_METHOD_RE = /^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b/i

// ------------------------------------------------------------------
// Trace grouping (one group per traceId)
// ------------------------------------------------------------------

interface WaterfallRow {
  span: ApmSpan
  depth: number
  leftPercent: number
  widthPercent: number
}

interface TraceGroup {
  traceId: string
  spans: ApmSpan[]
  rootLabel: string         // human-friendly title: e.g. "GET /api/git/status"
  isHttpEntry: boolean      // true if a SERVER-kind span or HTTP-prefixed root exists
  startTimeNs: number
  endTimeNs: number
  durationMs: number
  spanCount: number
  errorCount: number
  waterfallRows: WaterfallRow[]
}

function detectHttpEntry(spans: ApmSpan[]): { root: ApmSpan | null; isHttp: boolean } {
  // Prefer a SERVER-kind span; otherwise look for an HTTP-method-prefixed span;
  // otherwise the earliest root span.
  const server = spans.find(s => s.spanKind === 'SERVER')
  if (server) return { root: server, isHttp: true }
  const httpish = spans.find(s => HTTP_METHOD_RE.test(s.operationName))
  if (httpish) return { root: httpish, isHttp: true }
  // Fallback to earliest top-level (no parent inside this group) span
  const parentIds = new Set(spans.map(s => s.spanId))
  const orphans = spans.filter(s => !s.parentSpanId || !parentIds.has(s.parentSpanId))
  const earliest = [...orphans].sort((a, b) => a.startTimeNs - b.startTimeNs)[0] || null
  return { root: earliest, isHttp: false }
}

function buildWaterfall(spans: ApmSpan[], groupStartNs: number, groupDurationNs: number): WaterfallRow[] {
  const tree = buildTraceTree(spans)
  const rows: WaterfallRow[] = []
  const safeDuration = Math.max(groupDurationNs, 1)
  function flatten(node: TraceNode): void {
    const left = ((node.span.startTimeNs - groupStartNs) / safeDuration) * 100
    const width = Math.max(((node.span.endTimeNs - node.span.startTimeNs) / safeDuration) * 100, 0.5)
    rows.push({ span: node.span, depth: node.depth, leftPercent: left, widthPercent: width })
    for (const child of node.children) flatten(child)
  }
  for (const root of tree) flatten(root)
  return rows
}

const traceGroups = computed<TraceGroup[]>(() => {
  const spans = store.wsSpans
  if (spans.length === 0) return []

  // Group by traceId
  const byId = new Map<string, ApmSpan[]>()
  for (const s of spans) {
    const arr = byId.get(s.traceId) ?? []
    arr.push(s)
    byId.set(s.traceId, arr)
  }

  const groups: TraceGroup[] = []
  for (const [traceId, gspans] of byId) {
    const start = Math.min(...gspans.map(s => s.startTimeNs))
    const end = Math.max(...gspans.map(s => s.endTimeNs))
    const duration = end - start
    const { root, isHttp } = detectHttpEntry(gspans)
    const httpMethod = root?.attributes?.['http.request.method'] ?? root?.attributes?.['http.method']
    const httpRoute = root?.attributes?.['url.path'] ?? root?.attributes?.['http.route'] ?? root?.attributes?.['http.target']
    let label: string
    if (root && httpMethod && httpRoute) {
      label = `${String(httpMethod)} ${String(httpRoute)}`
    } else if (root) {
      label = root.operationName
    } else {
      label = `trace ${traceId.slice(0, 8)}…`
    }
    groups.push({
      traceId,
      spans: gspans,
      rootLabel: label,
      isHttpEntry: isHttp,
      startTimeNs: start,
      endTimeNs: end,
      durationMs: duration / 1_000_000,
      spanCount: gspans.length,
      errorCount: gspans.filter(s => s.statusCode === 'ERROR').length,
      waterfallRows: buildWaterfall(gspans, start, duration),
    })
  }

  // Latest first
  groups.sort((a, b) => b.startTimeNs - a.startTimeNs)
  return groups
})

// ------------------------------------------------------------------
// Expand/collapse state — by default only the latest HTTP-entry trace is open.
// ------------------------------------------------------------------
const expandedTraceIds = ref<Set<string>>(new Set())
const userInteracted = ref(false)

function pickDefaultExpanded(groups: TraceGroup[]): string | null {
  const latestHttp = groups.find(g => g.isHttpEntry)
  if (latestHttp) return latestHttp.traceId
  return groups[0]?.traceId ?? null
}

watch(traceGroups, (groups) => {
  if (userInteracted.value) return  // respect user choice once they toggle
  const def = pickDefaultExpanded(groups)
  const next = new Set<string>()
  if (def) next.add(def)
  expandedTraceIds.value = next
}, { immediate: true })

function isExpanded(traceId: string): boolean {
  return expandedTraceIds.value.has(traceId)
}

function toggleTrace(traceId: string): void {
  userInteracted.value = true
  const next = new Set(expandedTraceIds.value)
  if (next.has(traceId)) next.delete(traceId)
  else next.add(traceId)
  expandedTraceIds.value = next
}

function expandAll(): void {
  userInteracted.value = true
  expandedTraceIds.value = new Set(traceGroups.value.map(g => g.traceId))
}

function collapseAll(): void {
  userInteracted.value = true
  expandedTraceIds.value = new Set()
}

// ------------------------------------------------------------------
// Header aggregate counts (across all groups)
// ------------------------------------------------------------------
const errorCount = computed(() => store.wsSpans.filter(s => s.statusCode === 'ERROR').length)
const totalDurationMs = computed(() => {
  if (store.wsSpans.length === 0) return 0
  const start = Math.min(...store.wsSpans.map(s => s.startTimeNs))
  const end = Math.max(...store.wsSpans.map(s => s.endTimeNs))
  return (end - start) / 1_000_000
})
const slowCount = computed(() => {
  return store.wsSpans.filter(s => {
    // Slowness is relative to the span's own group, but for a top-level chip
    // we treat anything >30% of the whole session window as "slow".
    return s.durationMs > totalDurationMs.value * SLOW_THRESHOLD_RATIO
  }).length
})
const unmatchedCount = computed(() => store.wsSpans.filter(s => !s.kgNodeId).length)

// ------------------------------------------------------------------
// Per-row helpers (depend on the group, so we pass groupDurationMs in)
// ------------------------------------------------------------------
function isUnmatched(span: ApmSpan): boolean {
  return !span.kgNodeId
}

function getUnmatchedTooltip(span: ApmSpan): string {
  if (span.kgNodeId) return ''
  return `未在静态调用链中找到对应方法 (${span.className ?? '?'}.${span.methodName ?? span.operationName})`
}

function getBarColor(span: ApmSpan, groupDurationMs: number): string {
  if (span.statusCode === 'ERROR') return 'var(--el-color-danger)'
  if (span.durationMs > groupDurationMs * SLOW_THRESHOLD_RATIO) return 'var(--el-color-warning)'
  return 'var(--el-color-primary)'
}

function getSpanLabel(span: ApmSpan): string {
  if (span.className && span.methodName) {
    const shortClass = span.className.split('.').pop() || span.className
    return `${shortClass}.${span.methodName}()`
  }
  return span.operationName
}

function handleRowClick(span: ApmSpan): void {
  store.selectSpan(store.selectedSpan?.spanId === span.spanId ? null : span)
}

// ------------------------------------------------------------------
// Auto-scroll the active row into view when a span is selected externally.
// ------------------------------------------------------------------
const viewRoot = ref<HTMLElement | null>(null)
watch(
  () => store.selectedSpan?.spanId,
  async (spanId) => {
    if (!spanId) return
    // Make sure the trace containing this span is expanded.
    const owning = traceGroups.value.find(g => g.spans.some(s => s.spanId === spanId))
    if (owning && !expandedTraceIds.value.has(owning.traceId)) {
      userInteracted.value = true
      const next = new Set(expandedTraceIds.value)
      next.add(owning.traceId)
      expandedTraceIds.value = next
    }
    await nextTick()
    const root = viewRoot.value
    if (!root) return
    const activeRow = root.querySelector('.waterfall-row.active') as HTMLElement | null
    activeRow?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  },
)

// ------------------------------------------------------------------
// Span attributes (data flow intermediate state)
// ------------------------------------------------------------------
const selectedSpanAttributes = computed(() => {
  const span = store.selectedSpan
  if (!span?.attributes) return []
  const entries: Array<{ key: string; value: string }> = []
  for (const [key, value] of Object.entries(span.attributes)) {
    if (key.startsWith('telemetry.') || key === 'thread.id' || key === 'thread.name') continue
    entries.push({ key, value: formatAttributeValue(value) })
  }
  return entries
})

function formatAttributeValue(val: unknown): string {
  if (val === null || val === undefined) return '-'
  if (typeof val === 'object') {
    try { return JSON.stringify(val, null, 2) } catch { return String(val) }
  }
  return String(val)
}

function isJsonString(s: string): boolean {
  if (!s.startsWith('{') && !s.startsWith('[')) return false
  try { JSON.parse(s); return true } catch { return false }
}

function formatJson(s: string): string {
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}

// Slow threshold for the selected span (uses its own group's duration)
const selectedSpanGroupDurationMs = computed(() => {
  const span = store.selectedSpan
  if (!span) return totalDurationMs.value
  const g = traceGroups.value.find(gr => gr.traceId === span.traceId)
  return g?.durationMs ?? totalDurationMs.value
})
</script>

<template>
  <div class="trace-view" ref="viewRoot">
    <!-- Header -->
    <div class="trace-header">
      <span class="trace-title">调用链路</span>
      <div class="trace-meta">
        <el-tag size="small" type="info" round>
          {{ traceGroups.length }} traces / {{ store.wsSpans.length }} spans
        </el-tag>
        <el-tag v-if="errorCount > 0" size="small" type="danger" round>
          {{ errorCount }} errors
        </el-tag>
        <el-tag v-if="slowCount > 0" size="small" type="warning" round>
          {{ slowCount }} slow
        </el-tag>
        <el-tooltip
          v-if="unmatchedCount > 0"
          content="这些 span 未匹配到静态调用链节点，可能是 KG 未涵盖该方法（建议重新生成 KG）"
          placement="top"
        >
          <el-tag size="small" type="info" round effect="plain">
            ⊘ {{ unmatchedCount }} 未匹配 KG
          </el-tag>
        </el-tooltip>
        <el-button size="small" link @click="expandAll" v-if="traceGroups.length > 1">展开全部</el-button>
        <el-button size="small" link @click="collapseAll" v-if="traceGroups.length > 1">折叠全部</el-button>
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="traceGroups.length === 0" class="empty-state">
      <el-empty :image-size="80" description="执行请求后展示调用链路" />
    </div>

    <!-- Trace groups -->
    <el-scrollbar v-else class="groups-scrollbar">
      <div class="groups-container">
        <div
          v-for="(group, idx) in traceGroups"
          :key="group.traceId"
          class="trace-group"
          :class="{ collapsed: !isExpanded(group.traceId) }"
        >
          <!-- Group header (clickable to toggle) -->
          <div
            class="group-header"
            @click="toggleTrace(group.traceId)"
            :title="`Trace ID: ${group.traceId}`"
          >
            <span class="toggle-icon" :class="{ rotated: isExpanded(group.traceId) }">▸</span>
            <el-tag
              v-if="group.isHttpEntry"
              size="small"
              type="success"
              effect="plain"
            >HTTP</el-tag>
            <el-tag
              v-else
              size="small"
              type="info"
              effect="plain"
            >启动期</el-tag>
            <span class="group-label" :class="{ 'startup-label': !group.isHttpEntry }">
              {{ group.rootLabel }}
            </span>
            <span class="group-meta">
              <span class="meta-chip">{{ group.spanCount }} spans</span>
              <span class="meta-chip">{{ group.durationMs.toFixed(1) }}ms</span>
              <el-tag v-if="group.errorCount > 0" size="small" type="danger" effect="plain">
                {{ group.errorCount }} errors
              </el-tag>
              <span class="meta-chip latest-chip" v-if="idx === 0">最新</span>
            </span>
          </div>

          <!-- Waterfall body (only when expanded) -->
          <div v-if="isExpanded(group.traceId)" class="group-body">
            <!-- Time ruler -->
            <div class="time-ruler">
              <span class="time-mark" style="left: 0">0ms</span>
              <span class="time-mark" style="left: 25%">{{ (group.durationMs * 0.25).toFixed(0) }}ms</span>
              <span class="time-mark" style="left: 50%">{{ (group.durationMs * 0.5).toFixed(0) }}ms</span>
              <span class="time-mark" style="left: 75%">{{ (group.durationMs * 0.75).toFixed(0) }}ms</span>
              <span class="time-mark" style="left: 100%">{{ group.durationMs.toFixed(0) }}ms</span>
            </div>

            <!-- Span rows -->
            <div
              v-for="row in group.waterfallRows"
              :key="row.span.spanId"
              class="waterfall-row"
              :class="{
                active: store.selectedSpan?.spanId === row.span.spanId,
                error: row.span.statusCode === 'ERROR',
                slow: row.span.statusCode !== 'ERROR' && row.span.durationMs > group.durationMs * SLOW_THRESHOLD_RATIO,
                unmatched: isUnmatched(row.span),
              }"
              :title="getUnmatchedTooltip(row.span)"
              @click="handleRowClick(row.span)"
            >
              <!-- Label column -->
              <div
                class="row-label"
                :style="{ paddingLeft: `${row.depth * 16 + 8}px` }"
                :title="row.span.operationName"
              >
                <span class="span-kind-dot" :class="row.span.spanKind.toLowerCase()" />
                <span v-if="isUnmatched(row.span)" class="unmatched-icon" title="未匹配 KG">⊘</span>
                <span class="span-label">{{ getSpanLabel(row.span) }}</span>
              </div>

              <!-- Bar column -->
              <div class="row-bar-container">
                <div
                  class="span-bar"
                  :class="{ 'span-bar-unmatched': isUnmatched(row.span) }"
                  :style="{
                    left: `${row.leftPercent}%`,
                    width: `${row.widthPercent}%`,
                    backgroundColor: getBarColor(row.span, group.durationMs),
                  }"
                >
                  <span class="bar-duration">{{ row.span.durationMs }}ms</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-scrollbar>

    <!-- Selected span detail -->
    <div v-if="store.selectedSpan" class="span-detail-panel">
      <div class="detail-header">
        <span class="detail-title">Span 详情</span>
        <el-button size="small" text @click="store.selectSpan(null)">
          <el-icon><Close /></el-icon>
        </el-button>
      </div>
      <el-scrollbar class="detail-scrollbar">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="操作">
            {{ store.selectedSpan.operationName }}
          </el-descriptions-item>
          <el-descriptions-item label="类名">
            <code v-if="store.selectedSpan.className">{{ store.selectedSpan.className }}</code>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="方法名">
            <code v-if="store.selectedSpan.methodName">{{ store.selectedSpan.methodName }}</code>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">
            <span :class="{
              'text-danger': store.selectedSpan.statusCode === 'ERROR',
              'text-warning': store.selectedSpan.durationMs > selectedSpanGroupDurationMs * SLOW_THRESHOLD_RATIO,
            }">
              {{ store.selectedSpan.durationMs }}ms
              <el-tag
                v-if="store.selectedSpan.durationMs > selectedSpanGroupDurationMs * SLOW_THRESHOLD_RATIO && store.selectedSpan.statusCode !== 'ERROR'"
                size="small" type="warning" effect="plain" style="margin-left: 4px"
              >
                慢
              </el-tag>
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag
              :type="store.selectedSpan.statusCode === 'OK' ? 'success' : store.selectedSpan.statusCode === 'ERROR' ? 'danger' : 'info'"
              size="small"
            >
              {{ store.selectedSpan.statusCode }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="store.selectedSpan.statusMessage" label="错误信息">
            <span class="text-danger error-message">{{ store.selectedSpan.statusMessage }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Span 类型">
            <el-tag size="small" effect="plain">{{ store.selectedSpan.spanKind }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Span ID">
            <code class="id-code">{{ store.selectedSpan.spanId }}</code>
          </el-descriptions-item>
          <el-descriptions-item v-if="store.selectedSpan.parentSpanId" label="Parent ID">
            <code class="id-code">{{ store.selectedSpan.parentSpanId }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Trace ID">
            <code class="id-code">{{ store.selectedSpan.traceId }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="KG 匹配">
            <el-tag
              v-if="store.selectedSpan.kgNodeId"
              type="success"
              size="small"
            >
              已匹配 (Level {{ store.selectedSpan.kgMatchLevel }})
            </el-tag>
            <el-tag v-else type="info" size="small">未匹配</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <!-- Span Attributes (data flow intermediate state) -->
        <div v-if="selectedSpanAttributes.length > 0" class="attributes-section">
          <div class="section-title">Attributes (数据流)</div>
          <div
            v-for="attr in selectedSpanAttributes"
            :key="attr.key"
            class="attribute-row"
          >
            <span class="attr-key">{{ attr.key }}</span>
            <div class="attr-value">
              <pre v-if="isJsonString(attr.value)" class="attr-json">{{ formatJson(attr.value) }}</pre>
              <code v-else>{{ attr.value }}</code>
            </div>
          </div>
        </div>
      </el-scrollbar>
    </div>
  </div>
</template>

<style scoped>
.trace-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.trace-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.trace-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.trace-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* Trace groups */
.groups-scrollbar {
  flex: 1;
}

.groups-container {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.trace-group {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background-color: var(--el-bg-color);
  overflow: hidden;
}

.trace-group.collapsed {
  background-color: var(--el-fill-color-lighter);
}

.group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  transition: background-color 0.15s;
}

.group-header:hover {
  background-color: var(--el-fill-color-light);
}

.toggle-icon {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  transition: transform 0.2s;
  display: inline-block;
  width: 12px;
  text-align: center;
}

.toggle-icon.rotated {
  transform: rotate(90deg);
}

.group-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
}

.group-label.startup-label {
  font-weight: 500;
  color: var(--el-text-color-secondary);
  font-style: italic;
}

.group-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.meta-chip {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  padding: 1px 6px;
  background-color: var(--el-fill-color-light);
  border-radius: 8px;
}

.latest-chip {
  color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
  font-weight: 600;
}

.group-body {
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 8px 12px;
}

/* Waterfall (within a group) */
.time-ruler {
  position: relative;
  height: 24px;
  margin-left: 160px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 4px;
}

.time-mark {
  position: absolute;
  bottom: 2px;
  font-size: 10px;
  color: var(--el-text-color-placeholder);
  transform: translateX(-50%);
}

.time-mark:first-child { transform: translateX(0); }
.time-mark:last-child { transform: translateX(-100%); }

.waterfall-row {
  display: flex;
  align-items: center;
  height: 28px;
  cursor: pointer;
  border-radius: 3px;
  transition: background-color 0.15s;
}

.waterfall-row:hover {
  background-color: var(--el-fill-color-light);
}

.waterfall-row.active {
  background-color: var(--el-color-primary-light-9);
}

.waterfall-row.error .span-label { color: var(--el-color-danger); }
.waterfall-row.slow .span-label { color: var(--el-color-warning); }

.waterfall-row.unmatched {
  background-color: var(--el-fill-color-lighter);
}

.waterfall-row.unmatched .span-label {
  color: var(--el-text-color-placeholder);
  font-style: italic;
}

.unmatched-icon {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-right: 2px;
}

.span-bar-unmatched {
  opacity: 0.55;
  border: 1px dashed var(--el-text-color-placeholder) !important;
  background-color: var(--el-color-info-light-5) !important;
}

.row-label {
  width: 160px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
}

.span-kind-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
  background-color: var(--el-color-primary);
}

.span-kind-dot.server { background-color: var(--el-color-success); }
.span-kind-dot.client { background-color: var(--el-color-warning); }
.span-kind-dot.internal { background-color: var(--el-color-info); }

.span-label {
  font-size: 11px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--el-text-color-regular);
}

.row-bar-container {
  flex: 1;
  position: relative;
  height: 16px;
}

.span-bar {
  position: absolute;
  height: 100%;
  border-radius: 2px;
  min-width: 2px;
  display: flex;
  align-items: center;
  padding: 0 4px;
}

.bar-duration {
  font-size: 9px;
  color: #fff;
  white-space: nowrap;
  text-shadow: 0 0 2px rgba(0, 0, 0, 0.3);
}

/* Span detail panel */
.span-detail-panel {
  border-top: 1px solid var(--el-border-color-lighter);
  max-height: 280px;
  display: flex;
  flex-direction: column;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.detail-title {
  font-size: 12px;
  font-weight: 600;
}

.detail-scrollbar {
  flex: 1;
  padding: 8px 12px;
}

.id-code {
  font-size: 11px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 2px;
}

.text-muted { color: var(--el-text-color-placeholder); }
.text-danger { color: var(--el-color-danger); }
.text-warning { color: var(--el-color-warning); }

.error-message {
  word-break: break-all;
  font-size: 12px;
}

/* Attributes section */
.attributes-section {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 8px;
}

.attribute-row {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
  line-height: 1.5;
}

.attr-key {
  min-width: 120px;
  max-width: 180px;
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  word-break: break-all;
}

.attr-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.attr-value code {
  font-size: 11px;
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 2px;
  word-break: break-all;
}

.attr-json {
  font-size: 11px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  background: var(--el-fill-color-light);
  padding: 4px 6px;
  border-radius: 4px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 120px;
  overflow: auto;
}
</style>
