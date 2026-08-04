<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useApmStore } from '@/stores/apmStore'

const store = useApmStore()

const httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS']
const activeTab = ref('params')

const showBody = computed(() =>
  ['POST', 'PUT', 'PATCH'].includes(store.requestConfig.method)
)

/**
 * Map of query-param key → KG-parsed param meta (for showing "可选" badge).
 * Built from selectedEntry.parsedInfo so users can tell which params are
 * optional and safely unticked.
 */
const paramMetaByKey = computed<Record<string, { required?: boolean; type?: string }>>(() => {
  const map: Record<string, { required?: boolean; type?: string }> = {}
  const params = store.selectedEntry?.parsedInfo?.parameters ?? []
  for (const p of params) {
    if (p.annotations.includes('PathVariable') ||
        p.annotations.includes('RequestBody') ||
        p.annotations.includes('RequestHeader')) {
      continue
    }
    const key = p.aliasName || p.name
    map[key] = { required: p.required, type: p.type }
  }
  return map
})

const methodTagType: Record<string, string> = {
  GET: 'success',
  POST: 'warning',
  PUT: '',
  DELETE: 'danger',
  PATCH: 'info',
  HEAD: 'info',
  OPTIONS: 'info',
}

// ============================================================
// URL Autocomplete from entry points
// ============================================================
interface UrlSuggestion {
  value: string
  method: string
  path: string
  label: string
  nodeId: string
}

/**
 * Build autocomplete suggestions from all loaded entry points.
 * Supports fuzzy match on path and entryInfo.
 */
function querySearch(queryString: string, cb: (results: UrlSuggestion[]) => void): void {
  const q = queryString.trim().toLowerCase()
  const suggestions: UrlSuggestion[] = store.entryPoints
    .filter(entry => {
      if (!q) return true
      const path = (entry.httpPath || entry.entryKey || '').toLowerCase()
      const info = (entry.entryInfo || entry.nodeId || '').toLowerCase()
      return path.includes(q) || info.includes(q)
    })
    .map(entry => ({
      value: entry.httpPath || entry.entryKey,
      method: entry.httpMethod || 'GET',
      path: entry.httpPath || entry.entryKey,
      label: `${entry.httpMethod || entry.entryType} ${entry.httpPath || entry.entryKey}`,
      nodeId: entry.nodeId,
    }))
  cb(suggestions)
}

function handleSuggestionSelect(item: UrlSuggestion): void {
  store.setUrl(item.path)
  store.setMethod(item.method)
  // Also select the corresponding entry in the left panel
  const entry = store.entryPoints.find(e => e.nodeId === item.nodeId)
  if (entry) {
    store.selectEntry(entry)
  }
}

function handleExecute(): void {
  if (store.canExecute) {
    store.executeRequest()
  }
}

function handleMethodChange(method: string): void {
  store.setMethod(method)
}

function handleUrlChange(url: string): void {
  store.setUrl(url)
}

function handleBodyChange(body: string): void {
  store.setBody(body)
}

function addHeader(): void {
  const headers = store.requestConfig.headers
  const newKey = `Header-${Object.keys(headers).length + 1}`
  store.setHeaders({ ...headers, [newKey]: '' })
}

function removeHeader(key: string): void {
  const { [key]: _, ...rest } = store.requestConfig.headers
  store.setHeaders(rest)
}

function updateHeaderKey(oldKey: string, newKey: string): void {
  if (oldKey === newKey) return
  const value = store.requestConfig.headers[oldKey]
  const { [oldKey]: _, ...rest } = store.requestConfig.headers
  store.setHeaders({ ...rest, [newKey]: value })
}

function updateHeaderValue(key: string, val: string): void {
  store.setHeaders({ ...store.requestConfig.headers, [key]: val })
}

const headerEntries = computed(() =>
  Object.entries(store.requestConfig.headers)
)

// Auto-switch to body tab when method changes to POST/PUT/PATCH
watch(() => store.requestConfig.method, (method) => {
  if (['POST', 'PUT', 'PATCH'].includes(method) && activeTab.value === 'params') {
    activeTab.value = 'body'
  }
})

function formatResponseBody(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}
</script>

<template>
  <div class="request-editor">
    <!-- URL Bar -->
    <div class="url-bar">
      <el-select
        :model-value="store.requestConfig.method"
        class="method-select"
        @change="handleMethodChange"
      >
        <el-option
          v-for="m in httpMethods"
          :key="m"
          :label="m"
          :value="m"
        >
          <el-tag
            :type="(methodTagType[m] as any) || 'info'"
            size="small"
            effect="dark"
          >
            {{ m }}
          </el-tag>
        </el-option>
      </el-select>
      <el-autocomplete
        :model-value="store.requestConfig.url"
        :fetch-suggestions="querySearch"
        placeholder="/api/... (输入搜索或选择 API)"
        class="url-input"
        clearable
        :trigger-on-focus="true"
        :highlight-first-item="true"
        @update:model-value="handleUrlChange"
        @select="handleSuggestionSelect"
        @keyup.enter="handleExecute"
      >
        <template #prefix>
          <span class="url-prefix">{{ store.targetPort ? `localhost:${store.targetPort}` : '' }}</span>
        </template>
        <template #default="{ item }">
          <div class="url-suggestion">
            <el-tag
              :type="(methodTagType[item.method] as any) || 'info'"
              size="small"
              effect="dark"
              class="suggestion-method"
            >
              {{ item.method }}
            </el-tag>
            <span class="suggestion-path">{{ item.path }}</span>
          </div>
        </template>
      </el-autocomplete>
      <el-button
        type="primary"
        :disabled="!store.canExecute"
        :loading="store.status === 'EXECUTING'"
        @click="handleExecute"
      >
        <el-icon v-if="store.status !== 'EXECUTING'"><CaretRight /></el-icon>
        发送
      </el-button>
    </div>

    <!-- Tabs: Params / Headers / Body -->
    <el-tabs v-model="activeTab" class="editor-tabs">
      <!-- Params Tab -->
      <el-tab-pane label="Params" name="params">
        <div class="param-list">
          <div
            v-for="(param, index) in store.requestConfig.queryParams"
            :key="index"
            class="param-row"
          >
            <el-checkbox
              :model-value="param.enabled"
              @change="(val: boolean) => store.updateQueryParam(index, 'enabled', val)"
            />
            <el-input
              :model-value="param.key"
              placeholder="参数名"
              size="small"
              class="param-key"
              @update:model-value="(val: string) => store.updateQueryParam(index, 'key', val)"
            />
            <el-input
              :model-value="param.value"
              placeholder="值"
              size="small"
              class="param-value"
              @update:model-value="(val: string) => store.updateQueryParam(index, 'value', val)"
            />
            <el-tag
              v-if="paramMetaByKey[param.key]?.required === false"
              size="small"
              type="warning"
              effect="plain"
              class="optional-badge"
            >
              可选
            </el-tag>
            <el-button
              size="small"
              text
              type="danger"
              @click="store.removeQueryParam(index)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-button size="small" text type="primary" @click="store.addQueryParam()">
            + 添加参数
          </el-button>
        </div>
      </el-tab-pane>

      <!-- Headers Tab -->
      <el-tab-pane label="Headers" name="headers">
        <div class="param-list">
          <div
            v-for="[key, value] in headerEntries"
            :key="key"
            class="param-row"
          >
            <el-input
              :model-value="key"
              placeholder="Header 名"
              size="small"
              class="param-key"
              @change="(newKey: string) => updateHeaderKey(key, newKey)"
            />
            <el-input
              :model-value="value"
              placeholder="值"
              size="small"
              class="param-value"
              @update:model-value="(val: string) => updateHeaderValue(key, val)"
            />
            <el-button
              size="small"
              text
              type="danger"
              @click="removeHeader(key)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-button size="small" text type="primary" @click="addHeader">
            + 添加 Header
          </el-button>
        </div>
      </el-tab-pane>

      <!-- Body Tab -->
      <el-tab-pane label="Body" name="body" :disabled="!showBody">
        <el-input
          :model-value="store.requestConfig.body"
          type="textarea"
          :rows="8"
          placeholder='{"key": "value"}'
          class="body-editor"
          resize="vertical"
          @update:model-value="handleBodyChange"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- Response Panel -->
    <div v-if="store.lastResponse" class="response-panel">
      <div class="response-header">
        <span class="response-title">响应</span>
        <el-tag
          :type="store.lastResponse.httpStatus < 400 ? 'success' : 'danger'"
          size="small"
          effect="dark"
        >
          {{ store.lastResponse.httpStatus }}
        </el-tag>
        <span class="response-duration">{{ store.lastResponse.durationMs }}ms</span>
      </div>
      <el-input
        :model-value="formatResponseBody(store.lastResponse.responseBody)"
        type="textarea"
        :rows="10"
        readonly
        class="response-body"
        resize="vertical"
      />
    </div>

    <!-- Streaming status -->
    <div v-else-if="store.status === 'STREAMING'" class="streaming-status">
      <el-icon class="streaming-icon"><Loading /></el-icon>
      <span>采集 Span 数据中... ({{ store.wsSpans.length }} spans)</span>
      <el-button size="small" type="primary" @click="store.fetchReport()">
        获取报告
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.request-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.url-bar {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.method-select {
  width: 110px;
  flex-shrink: 0;
}

.url-input {
  flex: 1;
}

.url-suggestion {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
}

.suggestion-method {
  flex-shrink: 0;
  min-width: 48px;
  text-align: center;
  font-size: 10px;
}

.suggestion-path {
  font-size: 13px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.url-prefix {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.editor-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.editor-tabs :deep(.el-tabs__header) {
  padding: 0 12px;
  margin-bottom: 0;
}

.editor-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
  padding: 12px;
}

.param-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.param-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.param-key {
  flex: 1;
}

.param-value {
  flex: 2;
}

.optional-badge {
  font-size: 10px;
  flex-shrink: 0;
}

.body-editor {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
}

.body-editor :deep(textarea) {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 13px;
}

.response-panel {
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 12px;
}

.response-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.response-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.response-duration {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.response-body {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
}

.response-body :deep(textarea) {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 12px;
}

.streaming-status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.streaming-icon {
  animation: rotate 1.5s linear infinite;
}

@keyframes rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
