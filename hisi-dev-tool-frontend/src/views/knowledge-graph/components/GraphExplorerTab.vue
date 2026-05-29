<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  knowledgeGraphApi,
  type MethodNode,
  type EntryPoint,
  type RootEntriesResponse,
  type CallChainGraphData,
  type GraphNode
} from '@/api/knowledgeGraph'

const props = defineProps<{
  projectPath: string
  projectPaths: string[]
}>()

interface MethodSummary {
  nodeId: string
  className: string
  methodName: string
  signature: string
  filePath: string
}

// ---- Browse Mode ----
const browseMode = ref<'search' | 'entryType' | 'class'>('entryType')

// ---- Search Mode ----
const searchResults = ref<MethodSummary[]>([])
const searchLoading = ref(false)
const selectedNodeId = ref('')
let searchTimer: ReturnType<typeof setTimeout> | null = null

// ---- Entry Type Mode ----
const entryTypeFilter = ref('ALL')
const entryPoints = ref<EntryPoint[]>([])
const entryLoading = ref(false)

// ---- Class Mode ----
const classList = ref<string[]>([])
const classLoading = ref(false)
const selectedClass = ref('')
const classMethods = ref<MethodNode[]>([])
const classMethodsLoading = ref(false)

// ---- Shared: Detail & Call Chains ----
const methodDetail = ref<MethodNode | null>(null)
const detailLoading = ref(false)
const upstreamData = ref<RootEntriesResponse | null>(null)
const upstreamLoading = ref(false)
const downstreamData = ref<CallChainGraphData | null>(null)
const downstreamLoading = ref(false)
const activeChainTab = ref('upstream')

// ============================================================
// Search Mode
// ============================================================

function handleRemoteSearch(query: string) {
  if (searchTimer) clearTimeout(searchTimer)
  if (!query || query.length < 2) {
    searchResults.value = []
    return
  }
  searchTimer = setTimeout(async () => {
    searchLoading.value = true
    try {
      const data = await knowledgeGraphApi.searchMethods(query, props.projectPath, 50, props.projectPaths)
      searchResults.value = (data ?? []) as unknown as MethodSummary[]
    } catch {
      searchResults.value = []
    } finally {
      searchLoading.value = false
    }
  }, 300)
}

function formatLabel(item: MethodSummary): string {
  const shortClass = item.className.split('.').pop() || item.className
  return `${shortClass}.${item.methodName}`
}

// ============================================================
// Entry Type Mode
// ============================================================

async function loadEntryPoints() {
  entryLoading.value = true
  try {
    const type = entryTypeFilter.value === 'ALL' ? undefined : entryTypeFilter.value
    entryPoints.value = await knowledgeGraphApi.getEntryPoints(props.projectPath, type, props.projectPaths) as EntryPoint[]
  } catch {
    ElMessage.error('加载入口点失败')
    entryPoints.value = []
  } finally {
    entryLoading.value = false
  }
}

function handleEntryTypeChange() {
  loadEntryPoints()
}

function handleEntryClick(entry: EntryPoint) {
  handleSelectMethod(entry.nodeId)
}

// ============================================================
// Class Mode
// ============================================================

async function loadClassList() {
  classLoading.value = true
  try {
    classList.value = await knowledgeGraphApi.getClasses(props.projectPath, props.projectPaths) as string[]
  } catch {
    classList.value = []
  } finally {
    classLoading.value = false
  }
}

async function handleClassSelect(className: string) {
  if (!className) {
    classMethods.value = []
    return
  }
  classMethodsLoading.value = true
  try {
    classMethods.value = await knowledgeGraphApi.getMethodsByClass(className, props.projectPath, props.projectPaths) as MethodNode[]
  } catch {
    ElMessage.error('加载类方法失败')
    classMethods.value = []
  } finally {
    classMethodsLoading.value = false
  }
}

function handleClassMethodClick(method: MethodNode) {
  handleSelectMethod(method.nodeId)
}

// ============================================================
// Shared: Select Method & Call Chains
// ============================================================

async function handleSelectMethod(nodeId: string) {
  if (!nodeId) return
  selectedNodeId.value = nodeId
  upstreamData.value = null
  downstreamData.value = null
  detailLoading.value = true
  try {
    methodDetail.value = await knowledgeGraphApi.getMethodDetail(nodeId, props.projectPath, props.projectPaths) as MethodNode
  } catch {
    ElMessage.error('加载方法详情失败')
    methodDetail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function loadUpstream() {
  if (!methodDetail.value) return
  upstreamLoading.value = true
  activeChainTab.value = 'upstream'
  try {
    upstreamData.value = await knowledgeGraphApi.getRootEntries(
      methodDetail.value.className,
      methodDetail.value.methodName,
      props.projectPath,
      props.projectPaths
    )
  } catch {
    ElMessage.error('查询上游调用链失败')
  } finally {
    upstreamLoading.value = false
  }
}

async function loadDownstream() {
  if (!methodDetail.value) return
  downstreamLoading.value = true
  activeChainTab.value = 'downstream'
  try {
    downstreamData.value = await knowledgeGraphApi.getCalleesTree(
      methodDetail.value.className,
      methodDetail.value.methodName,
      props.projectPath,
      5,
      props.projectPaths
    )
  } catch {
    ElMessage.error('查询下游调用链失败')
  } finally {
    downstreamLoading.value = false
  }
}

function navigateToNode(className: string, methodName: string) {
  browseMode.value = 'search'
  selectedNodeId.value = ''
  methodDetail.value = null
  upstreamData.value = null
  downstreamData.value = null

  searchLoading.value = true
  knowledgeGraphApi.searchMethods(methodName, props.projectPath, 50, props.projectPaths)
    .then((data) => {
      searchResults.value = (data ?? []) as unknown as MethodSummary[]
      const match = searchResults.value.find(
        m => m.className === className && m.methodName === methodName
      )
      if (match) {
        selectedNodeId.value = match.nodeId
        handleSelectMethod(match.nodeId)
      } else if (searchResults.value.length > 0) {
        selectedNodeId.value = searchResults.value[0].nodeId
        handleSelectMethod(searchResults.value[0].nodeId)
      } else {
        ElMessage.info('未找到匹配的方法节点')
      }
    })
    .catch(() => { ElMessage.error('搜索失败') })
    .finally(() => { searchLoading.value = false })
}

function navigateToDownstreamNode(node: GraphNode) {
  const parts = node.name.split('.')
  const methodName = parts.pop() || ''
  const className = node.className || parts.join('.')
  navigateToNode(className, methodName)
}

function shortClassName(fqn: string): string {
  return fqn.split('.').pop() || fqn
}

// ============================================================
// Mode Switch & Project Change
// ============================================================

function handleModeChange() {
  if (browseMode.value === 'entryType' && entryPoints.value.length === 0) {
    loadEntryPoints()
  }
  if (browseMode.value === 'class' && classList.value.length === 0) {
    loadClassList()
  }
}

function clearDetail() {
  methodDetail.value = null
  upstreamData.value = null
  downstreamData.value = null
}

watch(() => props.projectPath, () => {
  selectedNodeId.value = ''
  searchResults.value = []
  entryPoints.value = []
  classList.value = []
  selectedClass.value = ''
  classMethods.value = []
  clearDetail()
  handleModeChange()
})

onMounted(() => {
  handleModeChange()
})
</script>

<template>
  <div class="graph-explorer">
    <!-- Mode Selector -->
    <div class="browse-bar">
      <el-radio-group v-model="browseMode" @change="handleModeChange" size="default">
        <el-radio-button value="entryType">按入口类型</el-radio-button>
        <el-radio-button value="class">按类浏览</el-radio-button>
        <el-radio-button value="search">搜索</el-radio-button>
      </el-radio-group>
    </div>

    <!-- Search Mode -->
    <div v-if="browseMode === 'search'" class="filter-section">
      <el-select
        v-model="selectedNodeId"
        filterable
        remote
        reserve-keyword
        :remote-method="handleRemoteSearch"
        :loading="searchLoading"
        placeholder="输入类名或方法名搜索..."
        style="width: 100%; max-width: 600px"
        @change="handleSelectMethod"
        clearable
        @clear="clearDetail"
      >
        <el-option
          v-for="item in searchResults"
          :key="item.nodeId"
          :label="formatLabel(item)"
          :value="item.nodeId"
        >
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span>
              <span style="font-weight: 500">{{ shortClassName(item.className) }}</span>.<span style="color: #409eff">{{ item.methodName }}</span>
            </span>
            <span style="color: #999; font-size: 11px; margin-left: 12px; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
              {{ item.filePath }}
            </span>
          </div>
        </el-option>
      </el-select>
    </div>

    <!-- Entry Type Mode -->
    <div v-if="browseMode === 'entryType'" class="filter-section">
      <el-radio-group v-model="entryTypeFilter" @change="handleEntryTypeChange" size="small">
        <el-radio-button value="ALL">全部</el-radio-button>
        <el-radio-button value="CONTROLLER">Controller</el-radio-button>
        <el-radio-button value="SCHEDULED">定时任务</el-radio-button>
        <el-radio-button value="MQ_LISTENER">MQ 监听</el-radio-button>
        <el-radio-button value="FEIGN_CLIENT">Feign</el-radio-button>
      </el-radio-group>

      <el-table
        :data="entryPoints"
        v-loading="entryLoading"
        size="small"
        stripe
        max-height="350"
        class="browse-table"
        @row-click="handleEntryClick"
        highlight-current-row
      >
        <el-table-column prop="entryType" label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.entryType === 'CONTROLLER' ? 'primary' : row.entryType === 'SCHEDULED' ? 'warning' : 'info'">
              {{ row.entryType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="entryKey" label="入口标识" min-width="300" show-overflow-tooltip />
        <el-table-column prop="entryInfo" label="描述" min-width="200" show-overflow-tooltip />
      </el-table>
      <div v-if="!entryLoading && entryPoints.length" class="browse-count">
        共 {{ entryPoints.length }} 个入口点
      </div>
    </div>

    <!-- Class Mode -->
    <div v-if="browseMode === 'class'" class="filter-section">
      <el-select
        v-model="selectedClass"
        filterable
        :loading="classLoading"
        placeholder="选择一个类..."
        style="width: 100%; max-width: 500px"
        @change="handleClassSelect"
        clearable
        @clear="() => { classMethods = [] }"
      >
        <el-option
          v-for="cls in classList"
          :key="cls"
          :label="shortClassName(cls)"
          :value="cls"
        >
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span style="font-weight: 500">{{ shortClassName(cls) }}</span>
            <span style="color: #999; font-size: 11px; margin-left: 12px; max-width: 350px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
              {{ cls }}
            </span>
          </div>
        </el-option>
      </el-select>

      <el-table
        v-if="selectedClass"
        :data="classMethods"
        v-loading="classMethodsLoading"
        size="small"
        stripe
        max-height="350"
        class="browse-table"
        @row-click="handleClassMethodClick"
        highlight-current-row
      >
        <el-table-column prop="methodName" label="方法名" min-width="180">
          <template #default="{ row }">
            <span style="color: #409eff; font-weight: 500; cursor: pointer">{{ row.methodName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="signature" label="签名" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.signature }}</span>
          </template>
        </el-table-column>
        <el-table-column label="行号" width="100">
          <template #default="{ row }">
            {{ row.startLine }}-{{ row.endLine }}
          </template>
        </el-table-column>
        <el-table-column prop="complexity" label="复杂度" width="80">
          <template #default="{ row }">
            <el-tag :type="row.complexity > 10 ? 'danger' : row.complexity > 5 ? 'warning' : 'success'" size="small">
              {{ row.complexity }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="selectedClass && !classMethodsLoading && classMethods.length" class="browse-count">
        共 {{ classMethods.length }} 个方法
      </div>
    </div>

    <!-- Method Detail -->
    <el-card v-if="methodDetail" v-loading="detailLoading" class="detail-card">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <div>
            <span class="detail-class">{{ shortClassName(methodDetail.className) }}</span>
            <span style="color: #909399; margin: 0 4px">.</span>
            <span class="detail-method">{{ methodDetail.methodName }}</span>
          </div>
          <div>
            <el-button type="primary" size="small" @click="loadUpstream" :loading="upstreamLoading">
              上游调用
            </el-button>
            <el-button type="success" size="small" @click="loadDownstream" :loading="downstreamLoading">
              下游调用
            </el-button>
          </div>
        </div>
      </template>

      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="全限定类名">
          <span class="mono">{{ methodDetail.className }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="签名">
          <span class="mono">{{ methodDetail.signature }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="文件">
          <span class="mono">{{ methodDetail.filePath }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="行号">
          {{ methodDetail.startLine }} - {{ methodDetail.endLine }}
        </el-descriptions-item>
        <el-descriptions-item label="复杂度">
          <el-tag :type="methodDetail.complexity > 10 ? 'danger' : methodDetail.complexity > 5 ? 'warning' : 'success'" size="small">
            {{ methodDetail.complexity }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="nodeId">
          <span class="mono" style="font-size: 11px; color: #999">{{ methodDetail.nodeId }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="methodDetail.methodBody" class="method-body">
        <div class="body-header">方法体</div>
        <pre class="code-block">{{ methodDetail.methodBody }}</pre>
      </div>
    </el-card>

    <el-empty v-else-if="!detailLoading" description="选择一个方法节点开始探索" />

    <!-- Call Chain Results -->
    <el-card v-if="upstreamData || downstreamData" class="chain-card">
      <el-tabs v-model="activeChainTab">
        <!-- Upstream -->
        <el-tab-pane label="上游调用" name="upstream">
          <div v-if="upstreamData" v-loading="upstreamLoading">
            <h5 v-if="upstreamData.rootEntries.length">根入口点 ({{ upstreamData.rootEntries.length }})</h5>
            <el-table
              v-if="upstreamData.rootEntries.length"
              :data="upstreamData.rootEntries"
              size="small"
              stripe
              max-height="250"
            >
              <el-table-column prop="entryType" label="类型" width="120">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.entryType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="entryKey" label="入口" min-width="250" show-overflow-tooltip />
            </el-table>

            <h5 style="margin-top: 16px">直接调用者 ({{ upstreamData.directCallers.length }})</h5>
            <el-table
              :data="upstreamData.directCallers"
              size="small"
              stripe
              max-height="300"
            >
              <el-table-column label="调用方" min-width="250">
                <template #default="{ row }">
                  <el-link type="primary" @click="navigateToNode(row.callerClassName, row.callerMethodName)">
                    {{ shortClassName(row.callerClassName) }}.{{ row.callerMethodName }}
                  </el-link>
                </template>
              </el-table-column>
              <el-table-column prop="callType" label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small" type="info">{{ row.callType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="callLine" label="行号" width="80" />
            </el-table>

            <el-empty v-if="!upstreamData.rootEntries.length && !upstreamData.directCallers.length"
              description="无上游调用" />
          </div>
        </el-tab-pane>

        <!-- Downstream -->
        <el-tab-pane label="下游调用" name="downstream">
          <div v-if="downstreamData" v-loading="downstreamLoading">
            <div style="margin-bottom: 8px; color: #909399; font-size: 12px">
              共 {{ downstreamData.totalNodes }} 个节点，最大深度 {{ downstreamData.maxDepth }}
            </div>
            <el-table
              :data="downstreamData.nodes.filter(n => n.depth > 0)"
              size="small"
              stripe
              max-height="400"
            >
              <el-table-column label="方法" min-width="250">
                <template #default="{ row }">
                  <span :style="{ paddingLeft: (row.depth - 1) * 16 + 'px' }">
                    <el-link type="primary" @click="navigateToDownstreamNode(row)">
                      {{ row.name }}
                    </el-link>
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="callType" label="调用类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small" type="info">{{ row.callType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="depth" label="深度" width="70" />
              <el-table-column label="文件" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="mono" style="font-size: 11px">{{ row.filePath }}</span>
                </template>
              </el-table-column>
            </el-table>

            <el-empty v-if="downstreamData.totalNodes <= 1" description="无下游调用" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.graph-explorer {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.browse-bar {
  display: flex;
  align-items: center;
  gap: 16px;
}
.filter-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.browse-table {
  margin-top: 4px;
  cursor: pointer;
}
.browse-count {
  font-size: 12px;
  color: #909399;
}
.detail-card {
  margin-top: 0;
}
.detail-class {
  font-weight: 600;
  font-size: 15px;
}
.detail-method {
  color: #409eff;
  font-weight: 600;
  font-size: 15px;
}
.mono {
  font-family: monospace;
  font-size: 12px;
}
.method-body {
  margin-top: 16px;
}
.body-header {
  font-weight: 500;
  margin-bottom: 8px;
  font-size: 13px;
}
.code-block {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px 16px;
  border-radius: 4px;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.5;
  overflow-x: auto;
  max-height: 400px;
  margin: 0;
  white-space: pre;
}
.chain-card {
  margin-top: 0;
}
</style>
