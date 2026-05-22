<script setup lang="ts">
import { computed } from 'vue'

/**
 * Single-input editor for a primitive / scalar / Map field.
 *
 * Coerces raw input text to the proper JS type on each change so the parent
 * receives `123` (number) rather than `"123"` (string) for numeric fields,
 * keeping the resulting JSON well-typed for Jackson on the backend.
 */

interface Props {
  type: string
  modelValue: unknown
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: unknown): void
}>()

const lowerType = computed(() => (props.type || '').toLowerCase().trim())

const useTextarea = computed(() => {
  const t = lowerType.value
  return t.startsWith('map<') || t === 'object'
})

const isBoolean = computed(() => lowerType.value === 'boolean')

const isNumber = computed(() => {
  const t = lowerType.value
  return ['integer', 'int', 'long', 'short', 'byte', 'double', 'float', 'bigdecimal', 'biginteger'].includes(t)
})

/** String rendering for whatever the model currently holds. */
const displayValue = computed<string>(() => {
  const v = props.modelValue
  if (v === null || v === undefined) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  try { return JSON.stringify(v) } catch { return String(v) }
})

const booleanValue = computed<boolean>(() => props.modelValue === true || props.modelValue === 'true')

function onTextChange(v: string): void {
  if (isNumber.value) {
    const trimmed = v.trim()
    if (trimmed === '') {
      emit('update:modelValue', null)
      return
    }
    const isInt = ['integer', 'int', 'long', 'short', 'byte'].includes(lowerType.value)
    const n = isInt ? parseInt(trimmed, 10) : parseFloat(trimmed)
    emit('update:modelValue', Number.isNaN(n) ? trimmed : n)
    return
  }
  if (useTextarea.value) {
    // Try JSON-parse for Map; on fail, keep raw text so user can fix.
    try { emit('update:modelValue', JSON.parse(v)) } catch { emit('update:modelValue', v) }
    return
  }
  emit('update:modelValue', v)
}

function onBoolChange(v: boolean | string | number): void {
  emit('update:modelValue', Boolean(v))
}

function placeholderForType(): string {
  const t = lowerType.value
  if (t === 'string') return '输入文本'
  if (['integer', 'int', 'long', 'short', 'byte'].includes(t)) return '例: 1'
  if (['double', 'float', 'bigdecimal'].includes(t)) return '例: 0.0'
  if (t.startsWith('map<')) return 'JSON 对象, 例: {}'
  return props.type
}
</script>

<template>
  <el-switch
    v-if="isBoolean"
    :model-value="booleanValue"
    size="small"
    @update:model-value="onBoolChange"
  />
  <el-input
    v-else-if="useTextarea"
    :model-value="displayValue"
    type="textarea"
    :rows="2"
    size="small"
    :placeholder="placeholderForType()"
    @update:model-value="onTextChange"
  />
  <el-input
    v-else
    :model-value="displayValue"
    size="small"
    :placeholder="placeholderForType()"
    @update:model-value="onTextChange"
  />
</template>
