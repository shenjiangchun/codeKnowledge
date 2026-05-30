<script setup lang="ts">
import type { DtoSchema } from '@/api/knowledgeGraph'
import SchemaObjectNode from './SchemaObjectNode.vue'
import SchemaPrimitiveInput from './SchemaPrimitiveInput.vue'

/**
 * Editor for an array-typed field.
 *
 * - If element schema is known: renders a list of cards, each card is a
 *   recursive SchemaObjectNode the user can edit.
 * - If element is primitive: renders a list of inline inputs.
 * - Add / remove buttons to grow or shrink the list.
 *
 * Two-way bound through modelValue (array) / update:modelValue.
 */

interface Props {
  itemSchema: DtoSchema | null
  itemType: string
  modelValue: unknown[]
  depth: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: unknown[]): void
}>()

function defaultItem(): unknown {
  if (props.itemSchema) {
    const obj: Record<string, unknown> = {}
    for (const f of props.itemSchema.fields) {
      obj[f.jsonName || f.name] = null
    }
    return obj
  }
  const t = (props.itemType || '').toLowerCase()
  if (t === 'boolean') return false
  if (['integer', 'int', 'long', 'short', 'byte', 'double', 'float', 'bigdecimal', 'biginteger'].includes(t)) {
    return 0
  }
  return ''
}

function addItem(): void {
  emit('update:modelValue', [...props.modelValue, defaultItem()])
}

function removeItem(index: number): void {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
}

function updateObjectItem(index: number, value: Record<string, unknown>): void {
  emit('update:modelValue', props.modelValue.map((v, i) => (i === index ? value : v)))
}

function updatePrimitiveItem(index: number, value: unknown): void {
  emit('update:modelValue', props.modelValue.map((v, i) => (i === index ? value : v)))
}

function asObject(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
}
</script>

<template>
  <div class="schema-array">
    <div
      v-for="(item, idx) in modelValue"
      :key="idx"
      class="array-item"
    >
      <div class="item-header">
        <el-tag size="small" type="info">[{{ idx }}]</el-tag>
        <el-button size="small" link type="danger" @click="removeItem(idx)">
          删除
        </el-button>
      </div>
      <SchemaObjectNode
        v-if="itemSchema"
        :schema="itemSchema"
        :model-value="asObject(item)"
        :depth="depth"
        @update:model-value="(v: Record<string, unknown>) => updateObjectItem(idx, v)"
      />
      <SchemaPrimitiveInput
        v-else
        :type="itemType"
        :model-value="item"
        @update:model-value="(v: unknown) => updatePrimitiveItem(idx, v)"
      />
    </div>
    <el-button size="small" plain class="add-btn" @click="addItem">
      + 添加元素
    </el-button>
  </div>
</template>

<style scoped>
.schema-array {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
}

.array-item {
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  padding: 6px 8px;
}

.item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.add-btn {
  align-self: flex-start;
}
</style>
