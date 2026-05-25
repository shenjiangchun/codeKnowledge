<script setup lang="ts">
/**
 * Modal for inter-node HITL confirmation. Shown when the orchestrator pauses
 * after a node completes, waiting for user approval before proceeding.
 *
 * Three actions:
 * - Approve: continue to the next node
 * - Reject: provide feedback and re-run the node
 * - Edit: modify the node output directly (advanced)
 */
import { computed, ref, watch } from 'vue'
import type { HitlSchema } from '@/types/ram'

const props = defineProps<{
  schema: HitlSchema | null
  visible: boolean
  title?: string
}>()

const emit = defineEmits<{
  confirm: [
    action: 'approve' | 'reject' | 'edit',
    feedback?: string,
    editedOutput?: Record<string, unknown>
  ]
  cancel: []
  'update:visible': [value: boolean]
}>()

const mode = ref<'view' | 'reject' | 'edit'>('view')
const feedback = ref('')
const editJson = ref('')
const editError = ref('')

const dialogTitle = computed(() => {
  if (props.title) return props.title
  const nodeLabels: Record<string, string> = {
    clarify: '澄清',
    impact: '影响分析',
    implement: '实现方案',
    verify: '验证清单'
  }
  const name = props.schema?.nodeName ?? ''
  const label = nodeLabels[name] ?? name
  return `节点「${label}」执行完成 — 请确认`
})

const outputMarkdown = computed(() => {
  if (!props.schema?.output) return '(无输出)'
  const out = props.schema.output
  // Try to extract markdown from common output shapes
  if (typeof out['markdown'] === 'string') return out['markdown'] as string
  if (typeof out['content'] === 'string') return out['content'] as string
  if (typeof out['summary'] === 'string') return out['summary'] as string
  // Fallback: pretty-print JSON
  return JSON.stringify(out, null, 2)
})

watch(
  () => props.visible,
  (v) => {
    if (v) {
      mode.value = 'view'
      feedback.value = ''
      editJson.value = JSON.stringify(props.schema?.output ?? {}, null, 2)
      editError.value = ''
    }
  }
)

function onApprove(): void {
  emit('confirm', 'approve')
}

function onReject(): void {
  if (mode.value !== 'reject') {
    mode.value = 'reject'
    return
  }
  emit('confirm', 'reject', feedback.value || undefined)
}

function onEdit(): void {
  if (mode.value !== 'edit') {
    mode.value = 'edit'
    return
  }
  try {
    const parsed = JSON.parse(editJson.value) as Record<string, unknown>
    editError.value = ''
    emit('confirm', 'edit', undefined, parsed)
  } catch (e) {
    editError.value = e instanceof Error ? e.message : 'JSON 解析失败'
  }
}

function onCancel(): void {
  emit('cancel')
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="720px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <div class="confirm-body">
      <!-- Node output preview -->
      <div v-if="mode === 'view' || mode === 'reject'" class="output-preview">
        <pre class="output-text">{{ outputMarkdown }}</pre>
      </div>

      <!-- Reject: feedback input -->
      <div v-if="mode === 'reject'" class="feedback-section">
        <el-divider content-position="left">驳回反馈</el-divider>
        <el-input
          v-model="feedback"
          type="textarea"
          :rows="3"
          placeholder="请描述需要修改的内容或方向..."
          maxlength="500"
          show-word-limit
        />
      </div>

      <!-- Edit: JSON editor -->
      <div v-if="mode === 'edit'" class="edit-section">
        <el-divider content-position="left">编辑输出 (JSON)</el-divider>
        <el-input
          v-model="editJson"
          type="textarea"
          :rows="12"
          class="json-editor"
        />
        <div v-if="editError" class="edit-error">
          <el-text type="danger" size="small">{{ editError }}</el-text>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="confirm-footer">
        <el-button @click="onCancel">取消</el-button>
        <el-button
          :type="mode === 'reject' ? 'danger' : 'default'"
          @click="onReject"
        >
          {{ mode === 'reject' ? '确认驳回' : '驳回' }}
        </el-button>
        <el-button
          :type="mode === 'edit' ? 'warning' : 'default'"
          @click="onEdit"
        >
          {{ mode === 'edit' ? '确认编辑' : '编辑' }}
        </el-button>
        <el-button type="primary" @click="onApprove">
          批准并继续
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.confirm-body {
  max-height: 480px;
  overflow-y: auto;
}

.output-preview {
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 16px;
  max-height: 320px;
  overflow-y: auto;
}

.output-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
}

.feedback-section {
  margin-top: 12px;
}

.edit-section {
  margin-top: 12px;
}

.json-editor :deep(.el-textarea__inner) {
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.5;
}

.edit-error {
  margin-top: 4px;
}

.confirm-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
