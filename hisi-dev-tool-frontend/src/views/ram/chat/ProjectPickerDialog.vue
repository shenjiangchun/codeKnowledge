<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRamChatStore } from '@/stores/ramChatStore'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [val: boolean]
  created: [data: { sessionId: string }]
}>()

const store = useRamChatStore()
const visible = ref(props.modelValue)
const projects = ref<Array<{ path: string; name: string }>>([])
const selectedPath = ref('')
const selectedName = ref('')
const creating = ref(false)

watch(() => props.modelValue, (val) => {
  visible.value = val
  if (val) loadProjects()
})

watch(visible, (val) => {
  emit('update:modelValue', val)
})

async function loadProjects() {
  try {
    // axios 拦截器已解包，response 直接是数据对象
    const data = await request.get('/projects') as unknown as Array<Record<string, unknown>>
    if (Array.isArray(data)) {
      projects.value = data.map((p) => ({
        path: (p.projectPath || p.path || '') as string,
        name: (p.projectName || p.name || '') as string
      }))
    }
  } catch (e) {
    console.error('[ProjectPicker] load projects failed', e)
  }
}

async function create() {
  if (!selectedPath.value) {
    ElMessage.warning('请选择项目')
    return
  }
  creating.value = true
  try {
    const data = await store.createSession(selectedPath.value, selectedName.value)
    emit('created', data)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    ElMessage.error('创建会话失败: ' + msg)
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="新建 RAM 对话" width="500px">
    <el-form label-width="80px">
      <el-form-item label="选择项目">
        <el-select
          v-model="selectedPath"
          filterable
          placeholder="请选择项目"
          style="width: 100%"
          @change="(val: string) => {
            const p = projects.find(p => p.path === val)
            selectedName = p?.name || ''
          }"
        >
          <el-option
            v-for="p in projects"
            :key="p.path"
            :label="p.name || p.path"
            :value="p.path"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="creating" @click="create">创建</el-button>
    </template>
  </el-dialog>
</template>
