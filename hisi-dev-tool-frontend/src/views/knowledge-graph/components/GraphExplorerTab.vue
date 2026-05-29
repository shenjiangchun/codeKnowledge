<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { knowledgeGraphApi, type MethodNode, type RootEntriesResponse, type CallChainGraphData, type GraphNode } from '@/api/knowledgeGraph'

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

const searchKeyword = ref('')
const searchResults = ref<MethodSummary[]>([])
const searchLoading = ref(false)
const selectedNodeId = ref('')

const methodDetail = ref<MethodNode | null>(null)
const detailLoading = ref(false)

const upstreamData = ref<RootEntriesResponse | null>(null)
const upstreamLoading = ref(false)

const downstreamData = ref<CallChainGraphData | null>(null)
const downstreamLoading = ref(false)

const activeChainTab = ref('upstream')

let searchTimer: ReturnType<typeof setTimeout> | null = null

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
  const keyword = `${className}.${methodName}`
  searchKeyword.value = ''
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
        searchKeyword.value = keyword
        handleSelectMethod(match.nodeId)
      } else if (searchResults.value.length > 0) {
        selectedNodeId.value = searchResults.value[0].nodeId
        searchKeyword.value = `${searchResults.value[0].className}.${searchResults.value[0].methodName}`
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

function formatLabel(item: MethodSummary): string {
  const shortClass = item.className.split('.').pop() || item.className
  return `${shortClass}.${item.methodName}`
}

watch(() => props.projectPath, () => {
  searchKeyword.value = ''
  selectedNodeId.value = ''
  searchResults.value = []
  methodDetail.value = null
  upstreamData.value = null
  downstreamData.value = null
})
</script>

<template>
  <div class="graph-explorer">
    <!-- Search Bar -->
    <div class="search-section">
      <el-select
        v-model="selectedNodeId"
        filterable
        remote
        reserve-keyword
        :remote-method="handleRemoteSearch"
        :loading="searchLoading"
        placeholder="输入类名或方法名搜索..."
        style="width: 100%"
        @change="handleSelectMethod"
        clearable
        @clear="() => { methodDetail = null; upstreamData = null; downstreamData = null }"
      >
        <el-option
          v-for="item in searchResults"
          :key="item.nodeId"
          :label="formatLabel(item)"
          :value="item.nodeId"
        >
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span>
              <span style="font-weight: 500">{{ item.className.split('.').pop() }}</span>.<span style="color: #409eff">{{ item.methodName }}</span>
            </span>
            <span style="color: #999; font-size: 11px; margin-left: 12px; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">
              {{ item.filePath }}
            </span>
          </div>
        </el-option>
      </el-select>
    </div>

    <!-- Method Detail -->
    <el-card v-if="methodDetail" v-loading="detailLoading" class="detail-card">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <div>
            <span class="detail-class">{{ methodDetail.className.split('.').pop() }}</span>
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

    <el-empty v-else-if="!detailLoading" description="搜索并选择一个方法节点开始探索" />

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
                    {{ row.callerClassName.split('.').pop() }}.{{ row.callerMethodName }}
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
.search-section {
  max-width: 600px;
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
