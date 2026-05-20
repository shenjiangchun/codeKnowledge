<script setup lang="ts">
import { computed } from 'vue'
import { useApmStore } from '@/stores/apmStore'
import type { ApmSpan, TraceNode } from '@/types/apm'

const store = useApmStore()

const SLOW_THRESHOLD_RATIO = 0.3 // spans taking >30% of total are "slow"

// Flatten trace tree for waterfall display
interface WaterfallRow {
  span: ApmSpan
  depth: number
  leftPercent: number   // start position as percentage of total duration
  widthPercent: number  // width as percentage of total duration
}

const traceStart = computed(() => {
  if (store.wsSpans.length === 0) return 0
  return Math.min(...store.wsSpans.map(s => s.startTimeNs))
})

const traceDuration = computed(() => {
  if (store.wsSpans.length === 0) return 1
  const end = Math.max(...store.wsSpans.map(s => s.endTimeNs))
  return Math.max(end - traceStart.value, 1)
})

const waterfallRows = computed<WaterfallRow[]>(() => {
  const rows: WaterfallRow[] = []

  function flatten(node: TraceNode): void {
    const left = ((node.span.startTimeNs - traceStart.value) / traceDuration.value) * 100
    const width = Math.max(((node.span.endTimeNs - node.span.startTimeNs) / traceDuration.value) * 100, 0.5)

    rows.push({
      span: node.span,
      depth: node.depth,
      leftPercent: left,
      widthPercent: width,
    })

    for (const child of node.children) {
      flatten(child)
    }
  }

  for (const root of store.traceTree) {
    flatten(root)
  }

  return rows
})

const totalDurationMs = computed(() => traceDuration.value / 1_000_000)

const errorCount = computed(() => store.wsSpans.filter(s => s.statusCode === 'ERROR').length)
const slowCount = computed(() =>
  store.wsSpans.filter(s => s.durationMs > totalDurationMs.value * SLOW_THRESHOLD_RATIO).length
)
const unmatchedCount = computed(() =>
  store.wsSpans.filter(s => !s.kgNodeId).length
)

function isUnmatched(span: ApmSpan): boolean {
  return !span.kgNodeId
}

function getUnmatchedTooltip(span: ApmSpan): string {
  if (span.kgNodeId) return ''
  return `未在静态调用链中找到对应方法 (${span.className ?? '?'}.${span.methodName ?? span.operationName})`
}

function getBarColor(span: ApmSpan): string {
  if (span.statusCode === 'ERROR') return 'var(--el-color-danger)'
  if (span.durationMs > totalDurationMs.value * SLOW_THRESHOLD_RATIO) return 'var(--el-color-warning)'
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

// Extract useful attributes for display
const selectedSpanAttributes = computed(() => {
  const span = store.selectedSpan
  if (!span?.attributes) return []
  const entries: Array<{ key: string; value: string }> = []
  for (const [key, value] of Object.entries(span.attributes)) {
    // Skip internal/boring attributes
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
</script>

<template>
  <div class="trace-view">
    <!-- Header -->
    <div class="trace-header">
      <span class="trace-title">调用链路</span>
      <div class="trace-meta">
        <el-tag size="small" type="info" round>
          {{ store.wsSpans.length }} spans
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
        <span v-if="totalDurationMs > 0" class="total-duration">
          {{ totalDurationMs.toFixed(1) }}ms
        </span>
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="waterfallRows.length === 0" class="empty-state">
      <el-empty
        :image-size="80"
        description="执行请求后展示调用链路"
      />
    </div>

    <!-- Waterfall chart -->
    <el-scrollbar v-else class="waterfall-scrollbar">
      <div class="waterfall-container">
        <!-- Time ruler -->
        <div class="time-ruler">
          <span class="time-mark" style="left: 0">0ms</span>
          <span class="time-mark" style="left: 25%">{{ (totalDurationMs * 0.25).toFixed(0) }}ms</span>
          <span class="time-mark" style="left: 50%">{{ (totalDurationMs * 0.5).toFixed(0) }}ms</span>
          <span class="time-mark" style="left: 75%">{{ (totalDurationMs * 0.75).toFixed(0) }}ms</span>
          <span class="time-mark" style="left: 100%">{{ totalDurationMs.toFixed(0) }}ms</span>
        </div>

        <!-- Span rows -->
        <div
          v-for="row in waterfallRows"
          :key="row.span.spanId"
          class="waterfall-row"
          :class="{
            active: store.selectedSpan?.spanId === row.span.spanId,
            error: row.span.statusCode === 'ERROR',
            slow: row.span.statusCode !== 'ERROR' && row.span.durationMs > totalDurationMs * SLOW_THRESHOLD_RATIO,
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
                backgroundColor: getBarColor(row.span),
              }"
            >
              <span class="bar-duration">{{ row.span.durationMs }}ms</span>
            </div>
          </div>
        </div>
      </div>
    </el-scrollbar>

    <!-- Selected span detail (enhanced) -->
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
              'text-warning': store.selectedSpan.durationMs > totalDurationMs * SLOW_THRESHOLD_RATIO,
            }">
              {{ store.selectedSpan.durationMs }}ms
              <el-tag
                v-if="store.selectedSpan.durationMs > totalDurationMs * SLOW_THRESHOLD_RATIO && store.selectedSpan.statusCode !== 'ERROR'"
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

.total-duration {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.waterfall-scrollbar {
  flex: 1;
}

.waterfall-container {
  padding: 8px 12px;
  min-width: 300px;
}

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

.time-mark:first-child {
  transform: translateX(0);
}

.time-mark:last-child {
  transform: translateX(-100%);
}

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

.waterfall-row.error .span-label {
  color: var(--el-color-danger);
}

.waterfall-row.slow .span-label {
  color: var(--el-color-warning);
}

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

.span-kind-dot.server {
  background-color: var(--el-color-success);
}

.span-kind-dot.client {
  background-color: var(--el-color-warning);
}

.span-kind-dot.internal {
  background-color: var(--el-color-info);
}

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

.text-muted {
  color: var(--el-text-color-placeholder);
}

.text-danger {
  color: var(--el-color-danger);
}

.text-warning {
  color: var(--el-color-warning);
}

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
