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
import ImpactOutputView from './ImpactOutputView.vue'

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

/** Prevents repeated action clicks while the parent processes the confirmation. */
const submitting = ref(false)

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

/**
 * Known field labels for structured node outputs. Covers the clarify node
 * (intent, acceptance_criteria, constraints, ...) and other common shapes.
 */
const FIELD_LABELS: Record<string, string> = {
  intent: '需求意图',
  project_paths: '项目路径',
  acceptance_criteria: '验收标准',
  target_modules: '目标模块',
  constraints: '约束条件',
  must: '必须',
  must_not: '禁止',
  risk_level: '风险等级',
  riskLevel: '风险等级',
  affected_files: '影响文件',
  impacted_files: '影响文件',
  involved_files: '受影响的入口',
  modified_files: '修改文件',
  implementation_plan: '实现方案',
  steps: '实施步骤',
  verification: '验证项',
  notes: '备注',
  description: '描述',
  summary: '摘要'
}

/** Check if the output is a "structured" object (not a simple string wrapper). */
function isStructuredOutput(out: Record<string, unknown>): boolean {
  // If it has a known text field, it's meant for direct display
  if (typeof out['markdown'] === 'string') return false
  if (typeof out['content'] === 'string') return false
  if (typeof out['summary'] === 'string' && Object.keys(out).length <= 2) return false
  // Has multiple keys or known structured keys → structured
  return Object.keys(out).length > 1 || Object.keys(out).some((k) => k in FIELD_LABELS)
}

/** Structured output sections for template rendering */
interface OutputSection {
  label: string
  type: 'text' | 'list' | 'object' | 'constraint-pair'
  value: string | string[] | Record<string, string[]>
}

const structuredSections = computed<OutputSection[]>(() => {
  if (!props.schema?.output) return []
  const out = props.schema.output
  if (!isStructuredOutput(out)) return []

  const sections: OutputSection[] = []

  for (const [key, val] of Object.entries(out)) {
    const label = FIELD_LABELS[key] ?? key

    if (val === null || val === undefined || val === '') continue

    // constraints: { must: [...], must_not: [...] }
    if (key === 'constraints' && typeof val === 'object' && !Array.isArray(val)) {
      const cObj = val as Record<string, unknown>
      const pairs: Record<string, string[]> = {}
      for (const [ck, cv] of Object.entries(cObj)) {
        const cLabel = FIELD_LABELS[ck] ?? ck
        if (Array.isArray(cv)) {
          pairs[cLabel] = cv.map((item) => String(item))
        } else if (typeof cv === 'string') {
          pairs[cLabel] = [cv]
        }
      }
      if (Object.keys(pairs).length > 0) {
        sections.push({ label, type: 'constraint-pair', value: pairs })
      }
      continue
    }

    if (Array.isArray(val)) {
      const items = val.map((item) => (typeof item === 'string' ? item : JSON.stringify(item)))
      if (items.length > 0) {
        sections.push({ label, type: 'list', value: items })
      }
      continue
    }

    if (typeof val === 'string') {
      sections.push({ label, type: 'text', value: val })
      continue
    }

    if (typeof val === 'object') {
      // Nested object — render as sub-list
      const entries = Object.entries(val as Record<string, unknown>)
      const items = entries.map(([k, v]) => `${k}: ${typeof v === 'string' ? v : JSON.stringify(v)}`)
      sections.push({ label, type: 'list', value: items })
      continue
    }

    // Primitive fallback
    sections.push({ label, type: 'text', value: String(val) })
  }

  return sections
})

const isStructured = computed(() => structuredSections.value.length > 0)

/**
 * Detect whether the output is an impact analysis result.
 * Impact output has: modified + impacted + risk (involved is optional/ignored).
 */
const isImpactOutput = computed(() => {
  if (!props.schema?.output) return false
  const out = props.schema.output
  // New structure: methods_to_modify + affected_entries
  if (Array.isArray(out['methods_to_modify']) || typeof out['affected_entries'] === 'object') {
    return true
  }
  // Legacy structure: modified + impacted + risk
  return (
    typeof out['modified'] === 'object' &&
    out['modified'] !== null &&
    typeof out['impacted'] === 'object' &&
    out['impacted'] !== null &&
    typeof out['risk'] === 'object' &&
    out['risk'] !== null
  )
})

const outputMarkdown = computed(() => {
  if (!props.schema?.output) return '(无输出)'
  const out = props.schema.output
  // Try to extract markdown from common output shapes
  if (typeof out['markdown'] === 'string') return out['markdown'] as string
  if (typeof out['content'] === 'string') return out['content'] as string
  if (typeof out['summary'] === 'string' && Object.keys(out).length <= 2) return out['summary'] as string
  // If structured, template handles rendering; this is only for non-structured fallback
  if (isStructured.value) return ''
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
      submitting.value = false
    }
  }
)

function onApprove(): void {
  if (submitting.value) return
  submitting.value = true
  emit('confirm', 'approve')
}

function onReject(): void {
  if (mode.value !== 'reject') {
    mode.value = 'reject'
    return
  }
  if (submitting.value) return
  submitting.value = true
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
    if (submitting.value) return
    submitting.value = true
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
        <!-- Impact analysis: human-readable specialized renderer -->
        <ImpactOutputView v-if="isImpactOutput" :output="schema!.output as any" />

        <!-- Structured output: render as labeled sections -->
        <div v-else-if="isStructured" class="structured-output">
          <div
            v-for="(section, idx) in structuredSections"
            :key="idx"
            class="output-section"
          >
            <div class="section-label">{{ section.label }}</div>

            <!-- Text field -->
            <div v-if="section.type === 'text'" class="section-text">
              {{ section.value }}
            </div>

            <!-- List field -->
            <ul v-else-if="section.type === 'list'" class="section-list">
              <li
                v-for="(item, i) in (section.value as string[])"
                :key="i"
              >
                {{ item }}
              </li>
            </ul>

            <!-- Constraint pair: { must: [...], must_not: [...] } -->
            <div
              v-else-if="section.type === 'constraint-pair'"
              class="constraint-pairs"
            >
              <div
                v-for="(items, subLabel) in (section.value as Record<string, string[]>)"
                :key="subLabel"
                class="constraint-group"
              >
                <el-tag
                  :type="subLabel === '禁止' ? 'danger' : 'success'"
                  size="small"
                  class="constraint-tag"
                >
                  {{ subLabel }}
                </el-tag>
                <ul class="section-list constraint-list">
                  <li v-for="(item, i) in items" :key="i">{{ item }}</li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        <!-- Fallback: plain text / JSON -->
        <pre v-else class="output-text">{{ outputMarkdown }}</pre>
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
        <el-button :disabled="submitting" @click="onCancel">取消</el-button>
        <el-button
          :type="mode === 'reject' ? 'danger' : 'default'"
          :disabled="submitting && mode !== 'reject'"
          :loading="submitting && mode === 'reject'"
          @click="onReject"
        >
          {{ mode === 'reject' ? '确认驳回' : '驳回' }}
        </el-button>
        <el-button
          :type="mode === 'edit' ? 'warning' : 'default'"
          :disabled="submitting && mode !== 'edit'"
          :loading="submitting && mode === 'edit'"
          @click="onEdit"
        >
          {{ mode === 'edit' ? '确认编辑' : '编辑' }}
        </el-button>
        <el-button
          type="primary"
          :disabled="submitting && mode !== 'view'"
          :loading="submitting && mode === 'view'"
          @click="onApprove"
        >
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

/* ---- Structured output sections ---- */

.structured-output {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.output-section {
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 12px;
}

.output-section:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.section-label {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 6px;
  letter-spacing: 0.5px;
}

.section-text {
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
  padding: 4px 0;
}

.section-list {
  margin: 0;
  padding-left: 20px;
  list-style: disc;
}

.section-list li {
  font-size: 13px;
  line-height: 1.8;
  color: #303133;
}

.constraint-pairs {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.constraint-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.constraint-tag {
  align-self: flex-start;
}

.constraint-list {
  margin-top: 2px;
}
</style>
