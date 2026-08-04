<template>
  <div class="code-understanding-tab">
    <!-- 统计栏 -->
    <div class="stats-bar">
      <el-tag size="large">
        {{ status?.entryPointCount || 0 }} 入口
      </el-tag>
      <el-tag size="large" type="success">
        {{ status?.methodNodeCount || 0 }} 方法
      </el-tag>
      <el-tag size="large" type="warning">
        {{ status?.callRelationCount || 0 }} 调用
      </el-tag>
      <el-tag size="large" type="info">
        {{ status?.interfaceImplCount || 0 }} 接口实现
      </el-tag>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select
        v-model="selectedEntryType"
        placeholder="入口类型"
        clearable
        style="width: 150px;"
        @change="handleTypeChange"
      >
        <el-option label="HTTP 接口" value="HTTP">
          <span>🌐 HTTP 接口</span>
        </el-option>
        <el-option label="定时任务" value="SCHEDULED">
          <span>⏰ 定时任务</span>
        </el-option>
        <el-option label="MQ 消费者" value="MQ">
          <span>📨 MQ 消费者</span>
        </el-option>
        <el-option label="事件监听" value="EVENT">
          <span>📢 事件监听</span>
        </el-option>
        <el-option label="WebSocket" value="WEBSOCKET">
          <span>🔌 WebSocket</span>
        </el-option>
        <el-option label="RPC 服务" value="RPC">
          <span>🔗 RPC 服务</span>
        </el-option>
        <el-option label="生命周期" value="LIFECYCLE">
          <span>🔄 生命周期</span>
        </el-option>
        <el-option label="Feign 客户端" value="FEIGN_CLIENT">
          <span>🔗 Feign 客户端</span>
        </el-option>
      </el-select>

      <el-select
        v-model="selectedEntryKey"
        placeholder="搜索入口标识..."
        filterable
        clearable
        style="width: 350px;"
        @change="handleEntrySelect"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
        <el-option
          v-for="entry in entryPoints"
          :key="entry.nodeId"
          :label="entry.entryKey"
          :value="entry.nodeId"
        >
          <div class="entry-option">
            <span class="entry-type-tag">{{ getEntryTypeLabel(entry.entryType) }}</span>
            <span class="entry-key">{{ entry.entryKey }}</span>
          </div>
        </el-option>
      </el-select>
    </div>

    <!-- 主内容区 -->
    <div class="content-layout">
      <!-- 左侧入口列表 -->
      <div class="left-panel">
        <div class="panel-header">
          <span>入口点列表</span>
          <el-tag size="small">{{ entryPagination.total }}</el-tag>
        </div>
        <EntryPointList
          :entry-points="filteredEntryPoints"
          :entry-groups="entryGroups"
          :loading="loading"
          :pagination="entryPagination"
          :grouped-pagination="groupedPagination"
          @select="handleSelectEntry"
          @page-change="handlePageChange"
          @grouped-page-change="handleGroupedPageChange"
        />
      </div>

      <!-- 右侧入口详情 -->
      <div class="right-panel">
        <EntryDetail
          v-if="selectedEntry"
          :entry="selectedEntry"
          :project-path="projectPath"
          :project-paths="projectPaths"
        />
        <el-empty v-else description="请选择入口点查看详情" :image-size="120" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { knowledgeGraphApi, type EntryPoint, type KnowledgeGraphStatus, type ServiceEntryGroup, type EntrySummary } from '@/api/knowledgeGraph'
import EntryPointList from './EntryPointList.vue'
import EntryDetail from './EntryDetail.vue'

const props = defineProps<{
  projectPath: string
  projectPaths?: string[]
}>()

const loading = ref(false)
const status = ref<KnowledgeGraphStatus | null>(null)
const entryPoints = ref<EntryPoint[]>([])
const entryGroups = ref<ServiceEntryGroup[]>([])
const selectedEntryType = ref<string>('')
const selectedEntryKey = ref<string>('')
const selectedEntry = ref<EntryPoint | null>(null)
const entryPagination = ref({ page: 1, pageSize: 20, total: 0 })
const groupedPagination = ref({ page: 1, pageSize: 10, total: 0 })

// 入口类型标签映射
const entryTypeLabels: Record<string, string> = {
  HTTP: '🌐 HTTP',
  SCHEDULED: '⏰ 定时',
  MQ: '📨 MQ',
  EVENT: '📢 事件',
  WEBSOCKET: '🔌 WS',
  RPC: '🔗 RPC',
  LIFECYCLE: '🔄 生命周期',
  FEIGN_CLIENT: '🔗 Feign'
}

const getEntryTypeLabel = (type: string) => {
  return entryTypeLabels[type] || type
}

// 当前页的入口点（服务端已分页，直接使用）
const filteredEntryPoints = computed(() => entryPoints.value)

// 加载状态
const loadStatus = async () => {
  if (!props.projectPath) return

  try {
    const paths = props.projectPaths ?? [props.projectPath]
    const result = await knowledgeGraphApi.getStatus(paths)
    status.value = result as unknown as KnowledgeGraphStatus
  } catch (error) {
    console.error('Failed to load status:', error)
  }
}

// 加载入口点（服务端分页）
const loadEntryPoints = async () => {
  if (!props.projectPath) return

  loading.value = true
  try {
    const paths = props.projectPaths ?? [props.projectPath]
    const entryType = selectedEntryType.value || undefined
    const { page, pageSize } = entryPagination.value
    const result = await knowledgeGraphApi.getEntryPoints(paths, entryType, page, pageSize) as unknown as { items: EntryPoint[], total: number }
    entryPoints.value = result?.items ?? []
    entryPagination.value.total = result?.total ?? 0
    // 同时加载分组数据
    const { page: gPage, pageSize: gSize } = groupedPagination.value
    const groupsResult = await knowledgeGraphApi.getEntryPointsGrouped(paths, gPage, gSize) as unknown as { items: ServiceEntryGroup[], total: number }
    entryGroups.value = groupsResult?.items ?? []
    groupedPagination.value.total = groupsResult?.total ?? 0
  } catch (error: any) {
    ElMessage.error(`加载入口点失败: ${error.message || error}`)
    entryPoints.value = []
    entryGroups.value = []
  } finally {
    loading.value = false
  }
}

// 处理类型变化
const handleTypeChange = () => {
  selectedEntry.value = null
  selectedEntryKey.value = ''
  entryPagination.value.page = 1
  loadEntryPoints()
}

// 处理分页变化
const handlePageChange = (page: number) => {
  entryPagination.value.page = page
  loadEntryPoints()
}

// 处理分组分页变化
const handleGroupedPageChange = (page: number) => {
  groupedPagination.value.page = page
  loadEntryPoints()
}

// 处理下拉选择入口
const handleEntrySelect = (nodeId: string) => {
  if (!nodeId) {
    selectedEntry.value = null
    return
  }
  const entry = entryPoints.value.find(ep => ep.nodeId === nodeId)
  if (entry) {
    selectedEntry.value = entry
    // 同步更新类型筛选
    selectedEntryType.value = entry.entryType
  }
}

// 处理列表点击选择（支持 EntryPoint 或 EntrySummary）
const handleSelectEntry = (entry: EntryPoint | EntrySummary) => {
  if ('nodeId' in entry) {
    selectedEntry.value = entry as EntryPoint
    selectedEntryKey.value = entry.nodeId
    selectedEntryType.value = entry.entryType
  } else {
    // EntrySummary: 优先从 entryPoints 查找完整数据，找不到则构造
    const fullEntry = entryPoints.value.find(ep => ep.nodeId === entry.entryId)
    if (fullEntry) {
      selectedEntry.value = fullEntry
      selectedEntryKey.value = fullEntry.nodeId
      selectedEntryType.value = fullEntry.entryType
    } else {
      // 分组视图中的入口可能不在当前平铺页中，用 EntrySummary 构造
      selectedEntry.value = {
        nodeId: entry.entryId,
        methodNodeId: '',
        entryType: entry.entryType,
        entryKey: entry.entryKey,
        entryInfo: '',
        projectPath: props.projectPath,
        briefDescription: entry.briefDescription,
      }
      selectedEntryKey.value = entry.entryId
      selectedEntryType.value = entry.entryType
    }
  }
}

// 监听项目路径变化
watch(() => props.projectPath, () => {
  selectedEntry.value = null
  selectedEntryKey.value = ''
  selectedEntryType.value = ''
  loadStatus()
  loadEntryPoints()
}, { immediate: true })

onMounted(() => {
  if (props.projectPath) {
    loadStatus()
    loadEntryPoints()
  }
})
</script>

<style scoped>
.code-understanding-tab {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.stats-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.content-layout {
  flex: 1;
  display: flex;
  gap: 16px;
  min-height: 0;
}

.left-panel {
  width: 400px;
  display: flex;
  flex-direction: column;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
  font-weight: 500;
}

.right-panel {
  flex: 1;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}

.entry-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.entry-type-tag {
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
  min-width: 70px;
}

.entry-key {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: monospace;
  font-size: 13px;
}
</style>
