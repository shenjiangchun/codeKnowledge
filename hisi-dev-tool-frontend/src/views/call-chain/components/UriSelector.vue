<template>
  <div class="uri-selector">
    <el-select
      v-model="selectedUri"
      filterable
      remote
      clearable
      placeholder="选择或搜索URI"
      :remote-method="handleSearch"
      :loading="loading"
      @change="handleChange"
      style="width: 100%"
    >
      <el-option
        v-for="uri in filteredUris"
        :key="uri"
        :label="uri"
        :value="uri"
      />
    </el-select>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { knowledgeGraphApi } from '@/api/knowledgeGraph'
import { useAppStore } from '@/stores/app'

const props = defineProps<{
  project: string
  modelValue?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'change', value: string): void
}>()

const appStore = useAppStore()
const loading = ref(false)
const uris = ref<string[]>([])
const searchText = ref('')
const selectedUri = ref(props.modelValue || '')

const filteredUris = computed(() => {
  if (!searchText.value) return uris.value
  return uris.value.filter(uri =>
    uri.toLowerCase().includes(searchText.value.toLowerCase())
  )
})

const handleSearch = (query: string) => {
  searchText.value = query
}

const handleChange = (value: string) => {
  emit('update:modelValue', value)
  emit('change', value)
}

const loadUris = async () => {
  if (!props.project) return
  loading.value = true
  try {
    // 构建完整的项目路径
    let projectPath = props.project
    if (appStore.projectDir && !props.project.includes(':') && !props.project.startsWith('/')) {
      projectPath = `${appStore.projectDir}\\${props.project}`
    }
    const res = await knowledgeGraphApi.getEntryPoints(projectPath, 'HTTP')
    // 从 entryKey 中提取 URI
    uris.value = (res || []).map((ep: any) => ep.entryKey)
  } catch (error) {
    ElMessage.error('加载URI列表失败')
  } finally {
    loading.value = false
  }
}

watch(() => props.project, loadUris, { immediate: true })
watch(() => props.modelValue, (val) => {
  selectedUri.value = val || ''
})
</script>

<style scoped>
.uri-selector {
  width: 100%;
}
</style>