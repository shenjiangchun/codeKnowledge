<template>
  <el-dialog
    v-model="visible"
    title="构建前后端完整图谱"
    width="720px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="显式绑定前后端目录"
      description="选择一个后端目录与一个前端目录建立绑定，一键构建前端实体 + 前端向量 + 跨层链接。前端目录需包含 package.json。"
      style="margin-bottom: 16px"
    />

    <!-- 新建绑定 + 构建 -->
    <el-form inline>
      <el-form-item label="后端目录">
        <el-select
          v-model="backendPath"
          filterable
          placeholder="选择后端目录"
          style="width: 260px"
        >
          <el-option
            v-for="proj in projects"
            :key="proj.path"
            :label="proj.name"
            :value="proj.path"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="前端目录">
        <el-input
          v-model="frontendPath"
          placeholder="输入前端目录绝对路径，如 C:/xxx/frontend"
          style="width: 260px"
        />
      </el-form-item>
    </el-form>
    <div style="display: flex; gap: 8px; margin-bottom: 20px;">
      <el-button type="primary" :loading="building" @click="handleBuild">构建前后端完整图谱</el-button>
    </div>

    <!-- 已有绑定列表 -->
    <el-divider content-position="left">已有绑定</el-divider>
    <el-table :data="bindings" border stripe size="small" empty-text="暂无绑定">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="backendPath" label="后端目录" min-width="200" show-overflow-tooltip />
      <el-table-column prop="frontendPath" label="前端目录" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bindingApi, type ProjectBinding } from '@/api/binding'

const props = defineProps<{
  modelValue: boolean
  projects: Array<{ name: string; path: string }>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'built', result: unknown): void
}>()

const visible = ref(props.modelValue)
const backendPath = ref('')
const frontendPath = ref('')
const bindings = ref<ProjectBinding[]>([])
const building = ref(false)

watch(
  () => props.modelValue,
  (val) => {
    visible.value = val
    if (val) {
      loadBindings()
      if (props.projects.length > 0) {
        backendPath.value = props.projects[0].path
      }
    }
  }
)

watch(visible, (val) => emit('update:modelValue', val))

async function loadBindings() {
  try {
    bindings.value = await bindingApi.list()
  } catch {
    bindings.value = []
  }
}

async function handleBuild() {
  if (!backendPath.value.trim()) {
    ElMessage.warning('请选择后端目录')
    return
  }
  if (!frontendPath.value.trim()) {
    ElMessage.warning('请输入前端目录')
    return
  }
  building.value = true
  try {
    const result = await bindingApi.build(backendPath.value.trim(), frontendPath.value.trim())
    ElMessage.success('构建完成')
    emit('built', result)
    await loadBindings()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '构建失败'
    ElMessage.error('构建失败: ' + msg)
  } finally {
    building.value = false
  }
}

async function handleDelete(row: ProjectBinding) {
  try {
    await ElMessageBox.confirm(`确认删除绑定 #${row.id}？`, '删除确认', { type: 'warning' })
    await bindingApi.delete(row.id)
    ElMessage.success('已删除')
    await loadBindings()
  } catch (e: unknown) {
    if (e !== 'cancel') {
      const msg = e instanceof Error ? e.message : '删除失败'
      ElMessage.error('删除失败: ' + msg)
    }
  }
}
</script>
