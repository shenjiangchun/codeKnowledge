<script setup lang="ts">
import { ref, computed } from 'vue'

defineProps<{
  sessionId: string
}>()

const emit = defineEmits<{
  execute: [params: { method: string; path: string; body?: string }]
}>()

const httpMethod = ref('GET')
const requestPath = ref('')
const requestBody = ref('')

const httpMethods = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']

const showBody = computed(() =>
  ['POST', 'PUT', 'PATCH'].includes(httpMethod.value)
)

const canExecute = computed(() => requestPath.value.trim().length > 0)

function handleExecute(): void {
  if (!canExecute.value) return

  emit('execute', {
    method: httpMethod.value,
    path: requestPath.value.trim(),
    body: showBody.value ? requestBody.value : undefined,
  })
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <span>请求配置</span>
    </template>

    <el-form label-width="80px" @submit.prevent="handleExecute">
      <el-form-item label="方法">
        <el-select v-model="httpMethod" style="width: 120px">
          <el-option
            v-for="m in httpMethods"
            :key="m"
            :label="m"
            :value="m"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="路径">
        <el-input
          v-model="requestPath"
          placeholder="/api/..."
          clearable
          @keyup.enter="handleExecute"
        />
      </el-form-item>

      <el-form-item v-if="showBody" label="请求体">
        <el-input
          v-model="requestBody"
          type="textarea"
          :rows="4"
          placeholder="JSON 请求体"
        />
      </el-form-item>

      <el-form-item>
        <el-button
          type="success"
          :disabled="!canExecute"
          @click="handleExecute"
        >
          执行请求
        </el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>
