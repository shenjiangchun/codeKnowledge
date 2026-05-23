<script setup lang="ts">
/**
 * ClarifyModal — Element Plus dialog rendering a small dynamic form for the
 * orchestrator's clarify-required interrupt.
 *
 * The schema is JSON-Schema-ish: an array of fields keyed by {@code name}.
 * Supported field types: {@code string} (default), {@code number},
 * {@code boolean}, {@code enum} (with {@code options}).
 *
 * Legacy contract: when {@code schema.questions} is an array of bare strings
 * we synthesize a list of string fields named {@code q0..qN}. This keeps
 * compatibility with the Phase-1 backend that emits open-ended questions.
 */
import { computed, reactive, watch } from 'vue'
import {
  initialAnswers,
  normalizeClarifyFields,
  type ClarifyField,
  type ClarifyModalSchema
} from './clarify'

interface Props {
  schema: ClarifyModalSchema | null
  visible: boolean
  title?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '需要澄清'
})

const emit = defineEmits<{
  (e: 'submit', answers: Record<string, unknown>): void
  (e: 'cancel'): void
  (e: 'update:visible', value: boolean): void
}>()

const fields = computed<ClarifyField[]>(() => normalizeClarifyFields(props.schema))

const answers = reactive<Record<string, unknown>>({})

watch(
  fields,
  (list) => {
    for (const key of Object.keys(answers)) {
      delete answers[key]
    }
    Object.assign(answers, initialAnswers(list))
  },
  { immediate: true }
)

const dialogVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v)
})

function onSubmit(): void {
  emit('submit', { ...answers })
}

function onCancel(): void {
  emit('cancel')
  emit('update:visible', false)
}

// Expose handlers for testing — happy-dom does not render the teleported
// dialog body reliably, so unit tests reach into onSubmit directly.
defineExpose({ onSubmit, onCancel, answers })
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    :title="props.title"
    width="520px"
    :close-on-click-modal="false"
    data-test="clarify-modal"
  >
    <p v-if="props.schema?.nodeName" class="clarify-node">
      节点：<strong>{{ props.schema.nodeName }}</strong>
    </p>
    <el-form label-position="top" @submit.prevent>
      <el-form-item
        v-for="field in fields"
        :key="field.name"
        :label="field.label ?? field.name"
        :required="field.required === true"
      >
        <el-input
          v-if="!field.type || field.type === 'string'"
          v-model="answers[field.name] as string"
          :data-test="`clarify-field-${field.name}`"
          type="textarea"
          :rows="2"
        />
        <el-input-number
          v-else-if="field.type === 'number'"
          v-model="answers[field.name] as number"
          :data-test="`clarify-field-${field.name}`"
        />
        <el-switch
          v-else-if="field.type === 'boolean'"
          v-model="answers[field.name] as boolean"
          :data-test="`clarify-field-${field.name}`"
        />
        <el-select
          v-else-if="field.type === 'enum'"
          v-model="answers[field.name] as string"
          :data-test="`clarify-field-${field.name}`"
          style="width: 100%"
        >
          <el-option
            v-for="opt in field.options ?? []"
            :key="opt"
            :value="opt"
            :label="opt"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="onCancel">取消</el-button>
      <el-button type="primary" data-test="clarify-submit" @click="onSubmit">
        提交
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.clarify-node {
  margin: 0 0 12px;
  color: #606266;
  font-size: 13px;
}
</style>
