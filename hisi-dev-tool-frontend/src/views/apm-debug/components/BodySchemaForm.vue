<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useApmStore } from '@/stores/apmStore'
import type { DtoField } from '@/api/knowledgeGraph'

/**
 * Schema-driven editor for the @RequestBody DTO. Renders each DTO field as an
 * input (typed by Java type), tags required/constraint info, and writes the
 * resulting JSON back into store.requestConfig.body.
 *
 * Falls back to a hint message when no schema is resolved.
 */

const store = useApmStore()

const schema = computed(() => store.bodySchema)
const loading = computed(() => store.bodySchemaLoading)

/** Local model: field name -> raw text value */
const fieldValues = ref<Record<string, string>>({})

/** Seed model from current body whenever schema or entry changes */
watch(
  [() => schema.value, () => store.selectedEntry?.nodeId],
  () => {
    fieldValues.value = {}
    if (!schema.value) return
    try {
      const parsed = JSON.parse(store.requestConfig.body || '{}')
      const seed = Array.isArray(parsed) ? (parsed[0] ?? {}) : parsed
      const next: Record<string, string> = {}
      for (const f of schema.value.fields) {
        const v = (seed as Record<string, unknown>)[f.name]
        next[f.name] = v === undefined || v === null ? '' : stringifyValue(v)
      }
      fieldValues.value = next
    } catch {
      // body is non-JSON or empty; leave empty
    }
  },
  { immediate: true },
)

function stringifyValue(v: unknown): string {
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return JSON.stringify(v)
}

function coerceForType(raw: string, type: string): unknown {
  const t = type.toLowerCase()
  if (raw === '') {
    if (t === 'boolean') return false
    if (['integer', 'int', 'long', 'short', 'byte', 'double', 'float', 'bigdecimal', 'biginteger'].includes(t)) return 0
    if (t.startsWith('list<') || t.startsWith('set<') || t.startsWith('collection<')) return []
    if (t.startsWith('map<')) return {}
    if (t === 'string') return ''
    return null
  }
  if (t === 'boolean') return raw === 'true' || raw === '1'
  if (['integer', 'int', 'long', 'short', 'byte'].includes(t)) {
    const n = parseInt(raw, 10)
    return Number.isNaN(n) ? 0 : n
  }
  if (['double', 'float', 'bigdecimal'].includes(t)) {
    const n = parseFloat(raw)
    return Number.isNaN(n) ? 0 : n
  }
  if (t.startsWith('list<') || t.startsWith('set<') || t.startsWith('collection<') || t.startsWith('map<')) {
    try { return JSON.parse(raw) } catch { return raw }
  }
  return raw
}

function syncBody(): void {
  if (!schema.value) return
  const obj: Record<string, unknown> = {}
  for (const f of schema.value.fields) {
    obj[f.name] = coerceForType(fieldValues.value[f.name] ?? '', f.type)
  }
  store.setBody(JSON.stringify(obj, null, 2))
}

function handleFieldChange(field: DtoField, value: string): void {
  fieldValues.value = { ...fieldValues.value, [field.name]: value }
  syncBody()
}

function inputType(type: string): 'textarea' | 'text' {
  const t = type.toLowerCase()
  if (t.startsWith('list<') || t.startsWith('set<') || t.startsWith('collection<') || t.startsWith('map<')) return 'textarea'
  return 'text'
}

function placeholderForType(type: string): string {
  const t = type.toLowerCase()
  if (t === 'boolean') return 'true / false'
  if (['integer', 'int', 'long', 'short', 'byte'].includes(t)) return '例: 1'
  if (['double', 'float', 'bigdecimal'].includes(t)) return '例: 0.0'
  if (t.startsWith('list<') || t.startsWith('set<') || t.startsWith('collection<')) return 'JSON 数组, 例: []'
  if (t.startsWith('map<')) return 'JSON 对象, 例: {}'
  if (t === 'string') return '输入文本'
  return type
}
</script>

<template>
  <div class="body-schema-form">
    <div v-if="loading" class="schema-loading">
      <el-skeleton :rows="3" animated />
    </div>

    <div v-else-if="!schema" class="schema-empty">
      <el-text type="info" size="small">
        未能解析该 DTO 的字段 schema —— 请在 Body 标签页直接编辑 JSON。
      </el-text>
    </div>

    <template v-else>
      <div class="schema-header">
        <el-icon><Document /></el-icon>
        <span class="schema-title">{{ schema.simpleName }}</span>
        <el-tag size="small" type="info" round>{{ schema.kind }}</el-tag>
        <el-tag size="small" type="info" round>{{ schema.fields.length }} 字段</el-tag>
      </div>

      <div
        v-for="f in schema.fields"
        :key="f.name"
        class="field-row"
      >
        <div class="field-meta">
          <span class="field-name">{{ f.name }}</span>
          <el-tag size="small" type="info" class="field-type">{{ f.type }}</el-tag>
          <el-tag v-if="f.required" size="small" type="danger" effect="plain">必填</el-tag>
          <el-tag
            v-for="c in f.constraints"
            :key="c"
            size="small"
            type="warning"
            effect="plain"
            class="field-constraint"
          >
            {{ c }}
          </el-tag>
        </div>
        <el-input
          v-if="inputType(f.type) === 'textarea'"
          :model-value="fieldValues[f.name] || ''"
          type="textarea"
          :rows="2"
          size="small"
          :placeholder="placeholderForType(f.type)"
          @update:model-value="(v: string) => handleFieldChange(f, v)"
        />
        <el-input
          v-else
          :model-value="fieldValues[f.name] || ''"
          size="small"
          :placeholder="placeholderForType(f.type)"
          @update:model-value="(v: string) => handleFieldChange(f, v)"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.body-schema-form {
  padding: 6px 4px;
}

.schema-loading,
.schema-empty {
  padding: 8px 4px;
}

.schema-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.schema-title {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.field-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px 0 6px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.field-row:last-child {
  border-bottom: none;
}

.field-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.field-name {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.field-type {
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  font-size: 10px;
}

.field-constraint {
  font-size: 10px;
}
</style>
