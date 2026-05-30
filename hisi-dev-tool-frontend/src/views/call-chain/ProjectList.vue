<template>
  <div class="project-list">
    <el-card header="选择项目">
      <el-input
        v-model="searchText"
        placeholder="搜索项目名称"
        prefix-icon="Search"
        clearable
        class="mb-4"
        style="width: 300px"
      />
      <el-table :data="filteredProjects" v-loading="loading" stripe @row-click="handleSelect">
        <el-table-column prop="name" label="项目名称" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column prop="language" label="语言" width="100" />
        <el-table-column prop="updateTime" label="更新时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { callChainApi } from '@/api/callChain'
import type { ProjectInfo } from '@/types/callchain'

const router = useRouter()
const loading = ref(false)
const searchText = ref('')
const projects = ref<ProjectInfo[]>([])

const filteredProjects = computed(() => {
  if (!searchText.value) return projects.value
  return projects.value.filter(p =>
    p.name.toLowerCase().includes(searchText.value.toLowerCase())
  )
})

const handleSelect = (row: ProjectInfo) => {
  router.push({
    path: '/call-chain/uris',
    query: { project: row.name }
  })
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await callChainApi.getProjects()
    projects.value = res.data || []
  } catch (error) {
    ElMessage.error('加载项目列表失败')
    console.error('Failed to load projects:', error)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.mb-4 {
  margin-bottom: 16px;
}
</style>