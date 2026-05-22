<script setup lang="ts">
import { computed } from 'vue'
import type { DtoField, DtoSchema } from '@/api/knowledgeGraph'
import SchemaArrayNode from './SchemaArrayNode.vue'
import SchemaPrimitiveInput from './SchemaPrimitiveInput.vue'

/**
 * Renders one DTO object: iterates its fields, dispatches each field to:
 *   - SchemaPrimitiveInput  (string/number/bool/Map/unknown)
 *   - SchemaObjectNode (self, recursive)  for nested DTO
 *   - SchemaArrayNode  for collection of primitives or DTOs
 *
 * Two-way binding via modelValue / update:modelValue. Updates are immutable —
 * every change emits a brand-new object.
 */

interface Props {
  schema: DtoSchema
  modelValue: Record<string, unknown>
  depth: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, unknown>): void
}>()

/** Cap nesting visually to avoid runaway indentation on cyclic-but-not-detected schemas. */
const MAX_VISUAL_DEPTH = 6
const isMaxDepth = computed(() => props.depth >= MAX_VISUAL_DEPTH)

function jsonKey(f: DtoField): string {
  return f.jsonName || f.name
}

function valueFor(f: DtoField): unknown {
  return props.modelValue?.[jsonKey(f)]
}

function emitFieldChange(f: DtoField, value: unknown): void {
  emit('update:modelValue', { ...props.modelValue, [jsonKey(f)]: value })
}

/** Decide which sub-component to render for a field. */
function fieldKind(f: DtoField): 'primitive' | 'object' | 'array' {
  if (f.isCollection) return 'array'
  if (f.nested) return 'object'
  return 'primitive'
}

/** Safe accessors for typed slots. */
function objectValue(f: DtoField): Record<string, unknown> {
  const v = valueFor(f)
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
}

function arrayValue(f: DtoField): unknown[] {
  const v = valueFor(f)
  return Array.isArray(v) ? v : []
}
</script>

<template>
  <div class="schema-object" :class="{ 'is-nested': depth > 0 }">
    <div
      v-for="f in schema.fields"
      :key="f.name"
      class="field-row"
    >
      <div class="field-meta">
        <span class="field-name">{{ jsonKey(f) }}</span>
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

      <!-- Render strategy by kind -->
      <template v-if="fieldKind(f) === 'primitive'">
        <SchemaPrimitiveInput
          :type="f.type"
          :model-value="valueFor(f)"
          @update:model-value="(v: unknown) => emitFieldChange(f, v)"
        />
      </template>

      <template v-else-if="fieldKind(f) === 'object'">
        <div v-if="isMaxDepth" class="depth-cap">
          <el-text type="warning" size="small">嵌套层级过深,请直接编辑 JSON</el-text>
        </div>
        <SchemaObjectNode
          v-else-if="f.nested"
          :schema="f.nested"
          :model-value="objectValue(f)"
          :depth="depth + 1"
          @update:model-value="(v: Record<string, unknown>) => emitFieldChange(f, v)"
        />
      </template>

      <template v-else>
        <SchemaArrayNode
          :item-schema="f.itemSchema || null"
          :item-type="f.itemType || f.type"
          :model-value="arrayValue(f)"
          :depth="depth + 1"
          @update:model-value="(v: unknown[]) => emitFieldChange(f, v)"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.schema-object {
  display: flex;
  flex-direction: column;
}

.schema-object.is-nested {
  padding: 6px 8px;
  margin: 4px 0;
  border-left: 2px solid var(--el-border-color-light);
  background-color: var(--el-fill-color-blank);
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

.depth-cap {
  padding: 4px 8px;
}
</style>
