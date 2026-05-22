<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useApmStore } from '@/stores/apmStore'
import type { DtoSchema } from '@/api/knowledgeGraph'
import SchemaObjectNode from './SchemaObjectNode.vue'

/**
 * Schema-driven editor for the @RequestBody DTO. Top-level wrapper:
 *  - Reads schema + initial JSON from the store
 *  - Delegates per-field rendering to the recursive SchemaObjectNode component
 *  - Serializes back to store.requestConfig.body on every change
 *
 * Falls back to a hint when the resolver returned nothing — caller can edit raw
 * JSON via the Body tab.
 */

const store = useApmStore()

const schema = computed<DtoSchema | null>(() => store.bodySchema)
const loading = computed(() => store.bodySchemaLoading)

/** Root object model. For collection bodies (List<DTO>), backend hint comes as `[{...}]`. */
const rootModel = ref<Record<string, unknown> | Array<Record<string, unknown>>>({})

/** Whether the body is wrapped in a top-level JSON array. */
const isArrayBody = ref(false)

/** Reseed model when schema or selected entry changes. */
watch(
  [() => schema.value, () => store.selectedEntry?.nodeId],
  () => {
    if (!schema.value) {
      rootModel.value = {}
      isArrayBody.value = false
      return
    }
    try {
      const parsed = JSON.parse(store.requestConfig.body || '{}')
      if (Array.isArray(parsed)) {
        isArrayBody.value = true
        rootModel.value = parsed.length > 0 && typeof parsed[0] === 'object'
          ? [parsed[0] as Record<string, unknown>]
          : [{}]
      } else if (parsed && typeof parsed === 'object') {
        isArrayBody.value = false
        rootModel.value = parsed as Record<string, unknown>
      } else {
        isArrayBody.value = false
        rootModel.value = {}
      }
    } catch {
      isArrayBody.value = false
      rootModel.value = {}
    }
  },
  { immediate: true },
)

function handleRootChange(updated: Record<string, unknown>): void {
  if (isArrayBody.value) {
    rootModel.value = [updated]
    store.setBody(JSON.stringify([updated], null, 2))
  } else {
    rootModel.value = updated
    store.setBody(JSON.stringify(updated, null, 2))
  }
}

/** Model passed down to the recursive node. Always a single object. */
const nodeModel = computed<Record<string, unknown>>(() => {
  if (isArrayBody.value) {
    const arr = rootModel.value as Array<Record<string, unknown>>
    return arr[0] ?? {}
  }
  return rootModel.value as Record<string, unknown>
})
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
        <el-tag v-if="isArrayBody" size="small" type="warning" round>List body</el-tag>
        <el-tag size="small" type="info" round>{{ schema.fields.length }} 字段</el-tag>
      </div>

      <SchemaObjectNode
        :schema="schema"
        :model-value="nodeModel"
        :depth="0"
        @update:model-value="handleRootChange"
      />
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
</style>
