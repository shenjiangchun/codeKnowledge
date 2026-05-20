<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useApmStore } from '@/stores/apmStore'
import type { KgMethodParam } from '@/types/apm'

const store = useApmStore()

const info = computed(() => store.selectedEntry?.parsedInfo)

/** Group parameters by annotation type for display */
const pathVars = computed<KgMethodParam[]>(() =>
  info.value?.parameters.filter(p => p.annotations.includes('PathVariable')) ?? []
)

const queryParamsKg = computed<KgMethodParam[]>(() =>
  info.value?.parameters.filter(p =>
    p.annotations.includes('RequestParam') ||
    (!p.annotations.includes('PathVariable') &&
     !p.annotations.includes('RequestBody') &&
     !p.annotations.includes('RequestHeader') &&
     p.annotations.length === 0)
  ) ?? []
)

const bodyParam = computed<KgMethodParam | null>(() =>
  info.value?.parameters.find(p => p.annotations.includes('RequestBody')) ?? null
)

const headerParams = computed<KgMethodParam[]>(() =>
  info.value?.parameters.filter(p => p.annotations.includes('RequestHeader')) ?? []
)

const hasParams = computed(() =>
  (info.value?.parameters.length ?? 0) > 0
)

// Track PathVariable values locally — they update the store URL on change
const pathVarValues = ref<Record<string, string>>({})

watch(
  () => store.selectedEntry,
  () => { pathVarValues.value = {} },
)

function handlePathVarChange(param: KgMethodParam, value: string): void {
  const name = param.aliasName || param.name
  pathVarValues.value = { ...pathVarValues.value, [name]: value }
  rebuildUrlWithPathVars()
}

function rebuildUrlWithPathVars(): void {
  const entry = store.selectedEntry
  if (!entry) return
  let url = entry.httpPath || entry.entryKey
  for (const pv of pathVars.value) {
    const name = pv.aliasName || pv.name
    const val = pathVarValues.value[name]
    if (val) {
      url = url.replace(`{${name}}`, val)
    }
  }
  store.setUrl(url)
}

function handleQueryParamChange(param: KgMethodParam, value: string): void {
  const name = param.aliasName || param.name
  const idx = store.requestConfig.queryParams.findIndex(p => p.key === name)
  if (idx >= 0) {
    store.updateQueryParam(idx, 'value', value)
  }
}

/** Simple type → placeholder hint */
function typeHint(type: string): string {
  const t = type.toLowerCase()
  if (t === 'long' || t === 'integer' || t === 'int') return '例: 1'
  if (t === 'double' || t === 'float' || t === 'bigdecimal') return '例: 0.0'
  if (t === 'boolean') return '例: true'
  if (t === 'string') return '输入文本'
  return type
}
</script>

<template>
  <div v-if="hasParams" class="parameter-form">
    <!-- Method signature header -->
    <div class="form-header">
      <el-icon><List /></el-icon>
      <span class="form-title">方法签名</span>
      <el-tag v-if="info" size="small" type="info" round class="method-tag">
        {{ info.className.split('.').pop() }}.{{ info.methodName }}()
      </el-tag>
      <el-tag v-if="info?.returnType" size="small" round class="return-tag">
        → {{ info.returnType }}
      </el-tag>
    </div>

    <!-- Path Variables -->
    <div v-if="pathVars.length > 0" class="param-section">
      <div class="section-label">
        <el-tag size="small" type="warning" effect="plain">PathVariable</el-tag>
      </div>
      <div v-for="p in pathVars" :key="p.name" class="param-item">
        <span class="param-name">{{ "{" }}{{ p.aliasName || p.name }}{{ "}" }}</span>
        <el-tag size="small" type="info" class="param-type">{{ p.type }}</el-tag>
        <el-input
          :model-value="pathVarValues[p.aliasName || p.name] || ''"
          :placeholder="typeHint(p.type)"
          size="small"
          class="param-input"
          @update:model-value="(v: string) => handlePathVarChange(p, v)"
        />
      </div>
    </div>

    <!-- Query Parameters -->
    <div v-if="queryParamsKg.length > 0" class="param-section">
      <div class="section-label">
        <el-tag size="small" type="success" effect="plain">RequestParam</el-tag>
      </div>
      <div v-for="p in queryParamsKg" :key="p.name" class="param-item">
        <span class="param-name">{{ p.aliasName || p.name }}</span>
        <el-tag size="small" type="info" class="param-type">{{ p.type }}</el-tag>
        <el-input
          :model-value="store.requestConfig.queryParams.find(q => q.key === (p.aliasName || p.name))?.value || ''"
          :placeholder="p.defaultValue ? `默认: ${p.defaultValue}` : typeHint(p.type)"
          size="small"
          class="param-input"
          @update:model-value="(v: string) => handleQueryParamChange(p, v)"
        />
        <el-tag v-if="p.required === false" size="small" type="warning" effect="plain" class="optional-badge">
          可选
        </el-tag>
      </div>
    </div>

    <!-- Request Headers -->
    <div v-if="headerParams.length > 0" class="param-section">
      <div class="section-label">
        <el-tag size="small" type="info" effect="plain">RequestHeader</el-tag>
      </div>
      <div v-for="p in headerParams" :key="p.name" class="param-item">
        <span class="param-name">{{ p.aliasName || p.name }}</span>
        <el-tag size="small" type="info" class="param-type">{{ p.type }}</el-tag>
      </div>
    </div>

    <!-- Request Body -->
    <div v-if="bodyParam" class="param-section">
      <div class="section-label">
        <el-tag size="small" type="danger" effect="plain">RequestBody</el-tag>
        <el-tag size="small" type="info" class="body-type" effect="plain">
          {{ bodyParam.type }}
        </el-tag>
        <el-text type="info" size="small" style="margin-left: auto">
          在 Body 标签页编辑 JSON
        </el-text>
      </div>
    </div>
  </div>

  <!-- Compact: no entry selected or no params -->
  <div v-else-if="store.selectedEntry && info" class="parameter-form compact">
    <div class="form-header">
      <el-icon><List /></el-icon>
      <el-tag size="small" type="info" round>
        {{ info.className.split('.').pop() }}.{{ info.methodName }}()
      </el-tag>
      <el-text type="info" size="small">无参数</el-text>
    </div>
  </div>
</template>

<style scoped>
.parameter-form {
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background-color: var(--el-fill-color-blank);
}

.parameter-form.compact {
  padding: 6px 12px;
}

.form-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.form-title {
  font-weight: 600;
  font-size: 12px;
}

.method-tag,
.return-tag {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 11px;
}

.param-section {
  margin-bottom: 6px;
}

.section-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.param-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0 2px 12px;
}

.param-name {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--el-text-color-primary);
  font-weight: 500;
  min-width: 80px;
  flex-shrink: 0;
}

.param-type {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 10px;
  flex-shrink: 0;
}

.param-input {
  flex: 1;
  max-width: 300px;
}

.optional-badge {
  font-size: 10px;
  flex-shrink: 0;
}

.body-type {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 10px;
  margin-left: 4px;
}
</style>
