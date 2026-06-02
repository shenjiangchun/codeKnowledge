<template>
  <div class="cross-service-bridge-tab">
    <!-- Statistics Cards -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="4">
        <el-card shadow="hover">
          <el-statistic title="总桥接数" :value="stats.totalBridges" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover">
          <el-statistic title="Feign 调用" :value="stats.feignCount" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover">
          <el-statistic title="HTTP 调用" :value="stats.httpCount" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover">
          <el-statistic title="MQ 消息" :value="stats.mqCount" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover">
          <el-statistic title="Mapper" :value="stats.mapperCount" />
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover">
          <el-statistic title="JPA" :value="stats.jpaCount" />
        </el-card>
      </el-col>
    </el-row>

    <!-- External Service Calls -->
    <el-card v-if="externalServices.length > 0" class="section-card">
      <template #header>
        <span>外部服务调用分布</span>
      </template>
      <div class="tag-list">
        <el-tag
          v-for="svc in externalServices"
          :key="svc.name"
          type="primary"
          class="service-tag"
        >
          {{ svc.name }}
          <el-badge :value="svc.count" :max="999" class="tag-badge" />
        </el-tag>
      </div>
    </el-card>

    <!-- MQ Topics -->
    <el-card v-if="mqTopics.length > 0" class="section-card">
      <template #header>
        <span>MQ Topic 分布</span>
      </template>
      <div class="tag-list">
        <el-tag
          v-for="topic in mqTopics"
          :key="topic.name"
          type="warning"
          class="service-tag"
        >
          {{ topic.name }}
          <el-badge :value="topic.count" :max="999" class="tag-badge" />
        </el-tag>
      </div>
    </el-card>

    <!-- Bridge Relationships Table -->
    <el-card class="section-card">
      <template #header>
        <div class="table-header">
          <span>桥接关系列表</span>
          <el-select
            v-model="filterType"
            placeholder="按类型筛选"
            clearable
            style="width: 160px"
          >
            <el-option label="全部" value="" />
            <el-option label="Feign" value="FEIGN" />
            <el-option label="HTTP" value="HTTP" />
            <el-option label="MQ" value="MQ" />
            <el-option label="Mapper" value="MAPPER" />
            <el-option label="JPA" value="JPA" />
            <el-option label="AOP" value="ASPECT" />
          </el-select>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="filteredBridges"
        stripe
        border
        max-height="500"
        empty-text="暂无桥接关系数据"
      >
        <el-table-column label="源方法" min-width="220">
          <template #default="{ row }">
            <span class="method-name">
              {{ row.sourceClassName }}.{{ row.sourceMethodName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="目标方法" min-width="220">
          <template #default="{ row }">
            <span v-if="row.targetClassName" class="method-name">
              {{ row.targetClassName }}.{{ row.targetMethodName }}
            </span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="桥接类型" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="getBridgeTagType(row.bridgeType)" size="small">
              {{ row.bridgeType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="详情" min-width="200">
          <template #default="{ row }">
            <span v-if="row.targetService">
              服务: {{ row.targetService }}
            </span>
            <span v-if="row.targetEndpoint">
              {{ row.targetService ? ' | ' : '' }}端点: {{ row.targetEndpoint }}
            </span>
            <span v-if="row.topic">
              Topic: {{ row.topic }}
            </span>
            <span v-if="row.sqlId">
              SQL: {{ row.sqlId }}
            </span>
            <span v-if="!row.targetService && !row.targetEndpoint && !row.topic && !row.sqlId" class="text-muted">
              —
            </span>
          </template>
        </el-table-column>
        <el-table-column label="行号" width="80" align="center" prop="callLine" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { knowledgeGraphApi, type BridgeStats } from '@/api/knowledgeGraph'

interface Props {
  projectPath: string
  projectPaths?: string[]
}

const props = defineProps<Props>()

interface BridgeRow {
  sourceClassName: string
  sourceMethodName: string
  targetClassName?: string
  targetMethodName?: string
  bridgeType: string
  targetService?: string
  targetEndpoint?: string
  topic?: string
  sqlId?: string
  callLine?: number
}

interface NameCount {
  name: string
  count: number
}

const loading = ref(false)
const filterType = ref('')

const stats = ref<BridgeStats>({
  mapperCount: 0,
  jpaCount: 0,
  mqCount: 0,
  feignCount: 0,
  httpCount: 0,
  aspectCount: 0,
  totalBridges: 0
})

const bridges = ref<BridgeRow[]>([])
const externalServices = ref<NameCount[]>([])
const mqTopics = ref<NameCount[]>([])

const filteredBridges = computed(() => {
  if (!filterType.value) return bridges.value
  return bridges.value.filter(b => b.bridgeType === filterType.value)
})

function getBridgeTagType(type: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
    FEIGN: 'primary',
    HTTP: 'success',
    MQ: 'warning',
    MAPPER: 'info',
    JPA: 'info',
    ASPECT: 'danger'
  }
  return map[type] ?? 'info'
}

async function loadBridgeStats(): Promise<void> {
  if (!props.projectPath) return

  loading.value = true
  try {
    const res = await knowledgeGraphApi.getBridgeStats(
      props.projectPaths
    )
    const data = res as unknown as Record<string, unknown>

    stats.value = {
      totalBridges: (data.totalBridges as number) ?? 0,
      mapperCount: (data.mapperCallCount as number) ?? (data.mapperCount as number) ?? 0,
      jpaCount: (data.jpaCallCount as number) ?? (data.jpaCount as number) ?? 0,
      mqCount: (data.mqCallCount as number) ?? (data.mqCount as number) ?? 0,
      feignCount: (data.feignCallCount as number) ?? (data.feignCount as number) ?? 0,
      httpCount: (data.httpCallCount as number) ?? (data.httpCount as number) ?? 0,
      aspectCount: (data.aspectCallCount as number) ?? (data.aspectCount as number) ?? 0
    }

    // Parse external service calls
    const svcMap = (data.externalServiceCalls as Record<string, number>) ?? {}
    externalServices.value = Object.entries(svcMap).map(([name, count]) => ({ name, count }))

    // Parse MQ topics
    const topicMap = (data.mqTopicCalls as Record<string, number>) ?? {}
    mqTopics.value = Object.entries(topicMap).map(([name, count]) => ({ name, count }))

    // Parse bridge list from feign/mq data embedded in stats, or load separately
    await loadBridgeList()
  } catch (error: unknown) {
    console.error('[CrossServiceBridge] Failed to load bridge stats:', error)
    ElMessage.error('加载桥接统计失败')
  } finally {
    loading.value = false
  }
}

async function loadBridgeList(): Promise<void> {
  const rows: BridgeRow[] = []
  const bridgeTypes = ['FEIGN', 'HTTP', 'MQ', 'MAPPER', 'JPA', 'ASPECT']

  const promises = bridgeTypes.map(async (type) => {
    try {
      const data = await knowledgeGraphApi.getBridgesByType(
        type,
        props.projectPaths
      ) as unknown as Array<{
        callerClassName: string
        callerMethodName: string
        calleeClassName: string
        calleeMethodName: string
        bridgeType: string
        callLine: number | null
        targetService: string | null
        targetEndpoint: string | null
        sqlId: string | null
      }>

      for (const item of data ?? []) {
        rows.push({
          sourceClassName: item.callerClassName ?? '',
          sourceMethodName: item.callerMethodName ?? '',
          targetClassName: item.calleeClassName ?? '',
          targetMethodName: item.calleeMethodName ?? '',
          bridgeType: item.bridgeType ?? type,
          targetService: item.targetService ?? undefined,
          targetEndpoint: item.targetEndpoint ?? undefined,
          sqlId: item.sqlId ?? undefined,
          callLine: item.callLine ?? undefined
        })
      }
    } catch {
      // This bridge type may not have data
    }
  })

  await Promise.all(promises)
  bridges.value = rows
}

watch(
  () => [props.projectPath, props.projectPaths],
  () => { loadBridgeStats() },
  { deep: true }
)

onMounted(() => {
  loadBridgeStats()
})
</script>

<style scoped>
.cross-service-bridge-tab {
  padding: 8px 0;
}

.stats-row {
  margin-bottom: 16px;
}

.stats-row :deep(.el-card) {
  border-radius: 10px;
  border: 1px solid #ebeef5;
}

.stats-row :deep(.el-statistic__head) {
  font-size: 12px;
  color: #909399;
}

.stats-row :deep(.el-statistic__content) {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
}

.section-card {
  margin-bottom: 16px;
  border-radius: 10px;
}

.section-card :deep(.el-card__header) {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
  background: #fafbfc;
  border-bottom: 1px solid #f0f2f5;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.service-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 13px;
}

.tag-badge {
  margin-left: 4px;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.method-name {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #5b8ff9;
}

.text-muted {
  color: #c0c4cc;
}
</style>
