<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useRamChatStore } from '@/stores/ramChatStore'
import { projectApi } from '@/api/project'
import { listRemoteProjects } from '@/api/remote-project'
import type { RemoteProject } from '@/types/remote-project'
import type { GitRepositoryInfo } from '@/types/callchain'
import { ElMessage } from 'element-plus'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [val: boolean]
  created: [data: { sessionId: string }]
}>()

const store = useRamChatStore()
const visible = ref(props.modelValue)
const projects = ref<Array<{ path: string; name: string }>>([])
const selectedPaths = ref<string[]>([])
const creating = ref(false)

const selectedName = computed(() => {
  if (selectedPaths.value.length === 0) return ''
  const first = projects.value.find(p => p.path === selectedPaths.value[0])
  if (selectedPaths.value.length === 1) {
    return first?.name || ''
  }
  return (first?.name || selectedPaths.value[0]) + ` +${selectedPaths.value.length - 1}`
})

watch(() => props.modelValue, (val) => {
  visible.value = val
  if (val) loadProjects()
})

watch(visible, (val) => {
  emit('update:modelValue', val)
})

async function loadProjects() {
  try {
    const [localResult, remoteResult] = await Promise.allSettled([
      projectApi.scanGitRepos() as Promise<unknown>,
      listRemoteProjects() as Promise<unknown>
    ])

    const local = localResult.status === 'fulfilled' && Array.isArray(localResult.value)
      ? localResult.value as GitRepositoryInfo[]
      : []

    const cloned = remoteResult.status === 'fulfilled' && Array.isArray(remoteResult.value)
      ? (remoteResult.value as RemoteProject[])
          .filter(r => r.cloneStatus === 'CLONED')
          .map(r => ({ name: r.name, path: r.fullPath || r.localPath }))
      : []

    const clonedNames = new Set(cloned.map(p => p.name))
    const dedupedLocal = local
      .filter(p => !clonedNames.has(p.name))
      .map(p => ({ path: p.path, name: p.name }))

    projects.value = [...cloned, ...dedupedLocal]
  } catch (e) {
    console.error('[ProjectPicker] load projects failed', e)
  }
}

async function create() {
  if (selectedPaths.value.length === 0) {
    ElMessage.warning('请选择至少一个项目')
    return
  }
  creating.value = true
  try {
    const data = await store.createSession(selectedPaths.value, selectedName.value)
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
  <el-dialog v-model="visible" title="新建 RAM 对话" width="540px">
    <el-form label-width="80px">
      <el-form-item label="选择项目">
        <el-select
          v-model="selectedPaths"
          multiple
          collapse-tags
          collapse-tags-tooltip
          filterable
          placeholder="请选择项目（可多选）"
          style="width: 100%"
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
