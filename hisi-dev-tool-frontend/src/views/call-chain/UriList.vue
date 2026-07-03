<template>
  <div class="uri-list">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>URI 列表 - {{ projectName }}</span>
          <el-button @click="goBack">返回</el-button>
        </div>
      </template>
      <el-input
        v-model="searchText"
        placeholder="搜索URI"
        prefix-icon="Search"
        clearable
        class="mb-4"
        style="width: 400px"
      />
      <el-table :data="filteredUris" v-loading="loading" stripe>
        <el-table-column prop="uri" label="URI" show-overflow-tooltip />
        <el-table-column prop="method" label="方法" width="80" />
        <el-table-column prop="callCount" label="调用次数" width="100" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="primary" link @click="viewCallChain(row)">
              查看调用链
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { callChainApi } from '@/api/callChain'
import type { UriInfo } from '@/types/callchain'

const route = useRoute()
const router = useRouter()
const projectName = route.query.project as string || ''
const loading = ref(false)
const searchText = ref('')
const uris = ref<UriInfo[]>([])

const filteredUris = computed(() => {
  if (!searchText.value) return uris.value
  return uris.value.filter(u =>
    u.uri.toLowerCase().includes(searchText.value.toLowerCase())
  )
})

const goBack = () => {
  router.push('/call-chain')
}

const viewCallChain = (row: UriInfo) => {
  router.push({
    path: '/call-chain/graph',
    query: { project: projectName, uri: row.uri }
  })
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await callChainApi.getUris({ project: projectName })
    // axios 拦截器已解包，res 直接是数据数组
    uris.value = (res as UriInfo[]) || []
  } catch (error) {
    ElMessage.error('加载URI列表失败')
    console.error('Failed to load URIs:', error)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>