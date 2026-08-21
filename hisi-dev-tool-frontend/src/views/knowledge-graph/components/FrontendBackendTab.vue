<template>
  <div class="frontend-backend-tab">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="前后端跨层关系"
      description="查询前端组件 ↔ 后端接口的静态 URL 匹配结果。前端图与后端图均建图完成后，跨层 INVOKES_API 边会自动构建。"
      style="margin-bottom: 16px"
    />

    <el-tabs v-model="activeMode">
      <!-- 查后端接口的前端调用方 -->
      <el-tab-pane label="查前端调用方" name="consumers">
        <el-form inline @submit.prevent>
          <el-form-item label="后端 EntryPoint ID">
            <el-input
              v-model="entryId"
              placeholder="如 D:/proj:HTTP_xxx.method"
              style="width: 420px"
              clearable
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loadingConsumers" @click="loadConsumers">
              查询前端调用方
            </el-button>
          </el-form-item>
        </el-form>

        <el-table :data="consumers" border stripe style="margin-top: 12px" empty-text="暂无跨层关系">
          <el-table-column prop="url" label="URL" min-width="200" show-overflow-tooltip />
          <el-table-column prop="componentName" label="组件" width="160" show-overflow-tooltip />
          <el-table-column prop="sourceFile" label="源文件" min-width="220" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <!-- 查前端组件的后端依赖 -->
      <el-tab-pane label="查后端依赖" name="deps">
        <el-form inline @submit.prevent>
          <el-form-item label="前端 ApiClient ID">
            <el-input
              v-model="apiClientId"
              placeholder="如 D:/fe:api/x.ts:GET /api"
              style="width: 420px"
              clearable
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loadingDeps" @click="loadDeps">
              查询后端依赖
            </el-button>
          </el-form-item>
        </el-form>

        <el-table :data="deps" border stripe style="margin-top: 12px" empty-text="暂无跨层关系">
          <el-table-column prop="entryKey" label="后端接口" min-width="260" show-overflow-tooltip />
          <el-table-column prop="entryType" label="类型" width="120" />
          <el-table-column prop="entryId" label="EntryPoint ID" min-width="220" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { frontendBackendApi, type ApiConsumer, type BackendDep } from '@/api/frontendBackend'

const activeMode = ref<'consumers' | 'deps'>('consumers')

const entryId = ref('')
const apiClientId = ref('')
const consumers = ref<ApiConsumer[]>([])
const deps = ref<BackendDep[]>([])
const loadingConsumers = ref(false)
const loadingDeps = ref(false)

async function loadConsumers() {
  if (!entryId.value.trim()) {
    ElMessage.warning('请输入后端 EntryPoint ID')
    return
  }
  loadingConsumers.value = true
  try {
    const resp = await frontendBackendApi.getApiConsumers(entryId.value.trim())
    consumers.value = resp.consumers ?? []
    if (consumers.value.length === 0) {
      ElMessage.info('未找到该接口的前端调用方')
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '查询失败'
    ElMessage.error('查询前端调用方失败: ' + msg)
  } finally {
    loadingConsumers.value = false
  }
}

async function loadDeps() {
  if (!apiClientId.value.trim()) {
    ElMessage.warning('请输入前端 ApiClient ID')
    return
  }
  loadingDeps.value = true
  try {
    const resp = await frontendBackendApi.getBackendDeps(apiClientId.value.trim())
    deps.value = resp.deps ?? []
    if (deps.value.length === 0) {
      ElMessage.info('未找到该前端调用点的后端依赖')
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '查询失败'
    ElMessage.error('查询后端依赖失败: ' + msg)
  } finally {
    loadingDeps.value = false
  }
}
</script>

<style scoped>
.frontend-backend-tab {
  padding: 4px 0;
}
</style>
