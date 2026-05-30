<template>
  <div class="entry-detail">
    <!-- 入口基本信息 -->
    <div class="entry-header">
      <div class="entry-info">
        <el-tag :type="getEntryTagType(entry.entryType)" size="large">
          {{ getEntryIcon(entry.entryType) }} {{ getEntryLabel(entry.entryType) }}
        </el-tag>
        <span class="entry-key">{{ entry.entryKey }}</span>
      </div>
      <div class="entry-actions">
        <el-button type="primary" size="small" @click="loadChain" :loading="loading">
          查看调用链
        </el-button>
        <el-button type="success" size="small" @click="handleAIAnalysis" :disabled="!chainData">
          AI 分析
        </el-button>
      </div>
    </div>

    <!-- 调用链统计 -->
    <div class="chain-stats" v-if="chainData">
      <el-descriptions :column="5" border size="small">
        <el-descriptions-item label="最大深度">
          {{ chainData.maxDepth }}
        </el-descriptions-item>
        <el-descriptions-item label="节点总数">
          {{ chainData.totalNodes }}
        </el-descriptions-item>
        <el-descriptions-item label="入口类型">
          {{ chainData.entryType }}
        </el-descriptions-item>
        <el-descriptions-item label="入口标识">
          {{ chainData.entryKey }}
        </el-descriptions-item>
        <el-descriptions-item label="环数量">
          <el-tag :type="cycleCount > 0 ? 'danger' : 'success'" size="small">
            {{ cycleCount }} 个环
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 调用链展示 -->
    <div class="chain-view" v-if="chainData">
      <ChainChart
        :data="treeData"
        :loading="loading"
        :project-path="projectPath"
        :project-paths="[projectPath]"
        @node-contextmenu="handleContextMenu"
      />
    </div>

    <!-- 空状态 -->
    <div class="empty-chain" v-else-if="!loading">
      <el-empty description="点击「查看调用链」按钮加载调用链数据" :image-size="100" />
    </div>

    <!-- 右键菜单 -->
    <ContextMenu
      :visible="contextMenuVisible"
      :x="contextMenuX"
      :y="contextMenuY"
      :node="contextMenuNode"
      @close="closeContextMenu"
      @action="handleMenuAction"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { knowledgeGraphApi, type EntryPoint, type CallChainView, type CallChainNode, type CallChainGraphData } from '@/api/knowledgeGraph'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { useRouter } from 'vue-router'
import { getEntryTagType, getEntryIcon, getEntryLabel } from '../utils/entryTypeUtils'
import ChainChart from '@/views/call-chain/components/ChainChart.vue'
import ContextMenu from '@/views/call-chain/components/ContextMenu.vue'

const props = defineProps<{
  entry: EntryPoint
  projectPath: string
  projectPaths?: string[]
}>()

const router = useRouter()
const workspaceStore = useWorkspaceStore()

const loading = ref(false)
const chainData = ref<CallChainView | null>(null)
const graphData = ref<CallChainGraphData | null>(null)
const cycleCount = ref(0)

// 右键菜单状态
const contextMenuVisible = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const contextMenuNode = ref<any>(null)

// 将 CallChainView 转换为 ChainChart 需要的树形结构
interface TreeNode {
  id: string
  name: string
  className?: string
  methodSignature?: string
  methodBody?: string
  description?: string
  isNoMatch?: boolean
  bridgeType?: any
  bridgeInfo?: any
  depth: number
  children?: TreeNode[]
}

// 从 graphData 的 edges 构建邻接表
const buildAdjacencyFromEdges = () => {
  const adjacency = new Map<string, string[]>()
  if (graphData.value?.edges) {
    for (const edge of graphData.value.edges) {
      if (!adjacency.has(edge.source)) {
        adjacency.set(edge.source, [])
      }
      adjacency.get(edge.source)!.push(edge.target)
    }
  }
  return adjacency
}

// 从 graphData 的 nodes 构建节点映射
const buildNodeMap = () => {
  const map = new Map<string, {
    name: string;
    className: string;
    depth: number;
    description?: string;
    isNoMatch?: boolean;
    bridgeType?: any;
    bridgeInfo?: any;
    methodSignature?: string;
    methodBody?: string;
  }>()
  if (graphData.value?.nodes) {
    for (const node of graphData.value.nodes) {
      map.set(node.id, {
        name: node.name,
        className: node.className,
        depth: node.depth,
        description: node.description,
        isNoMatch: node.isNoMatch,
        bridgeType: node.bridgeType,
        bridgeInfo: node.bridgeInfo,
        methodSignature: node.methodSignature,
        methodBody: node.methodBody
      })
    }
  }
  return map
}

const treeData = computed((): TreeNode | null => {
  // 优先使用 graphData 构建树（包含完整的边信息）
  if (graphData.value && graphData.value.nodes.length > 0) {
    const nodeMap = buildNodeMap()
    const adjacency = buildAdjacencyFromEdges()

    // 找到入口节点（depth=0）
    const entryNode = graphData.value.nodes.find(n => n.depth === 0)
    if (!entryNode) return null

    console.log('[treeData] Using graphData, entryNode:', entryNode.name, 'id:', entryNode.id)
    console.log('[treeData] graphData stats:', {
      nodes: graphData.value.nodes.length,
      edges: graphData.value.edges.length,
      maxDepth: graphData.value.maxDepth
    })

    // 使用 DFS 构建树，避免重复节点（DAG 转 树）
    const buildTreeFromGraph = (nodeId: string, visited: Set<string>): TreeNode | null => {
      if (visited.has(nodeId)) return null
      visited.add(nodeId)

      const nodeInfo = nodeMap.get(nodeId)
      if (!nodeInfo) return null

      const treeNode: TreeNode = {
        id: nodeId,
        name: nodeInfo.name,
        className: nodeInfo.className,
        depth: nodeInfo.depth,
        description: nodeInfo.description,
        isNoMatch: nodeInfo.isNoMatch,
        bridgeType: nodeInfo.bridgeType,
        bridgeInfo: nodeInfo.bridgeInfo,
        methodSignature: nodeInfo.methodSignature,
        methodBody: nodeInfo.methodBody
      }

      const callees = adjacency.get(nodeId) || []
      const children: TreeNode[] = []
      for (const calleeId of callees) {
        const child = buildTreeFromGraph(calleeId, visited)
        if (child) {
          children.push(child)
        }
      }

      if (children.length > 0) {
        treeNode.children = children
      }

      return treeNode
    }

    return buildTreeFromGraph(entryNode.id, new Set<string>())
  }

  // 后备方案：使用 chainData 的 chain 结构
  if (!chainData.value) return null

  const chain = chainData.value.chain
  // 防御性检查：chain 可能为 undefined 或没有数据
  if (!chain) return null

  const depth0 = chain[0]
  if (!depth0 || depth0.length === 0) return null

  // 找到入口节点
  const entryNode = depth0[0]

  return buildTreeNode(entryNode, chain)
})

// 构建树节点（后备方案）
const buildTreeNode = (node: CallChainNode, chain: Record<number, CallChainNode[]>): TreeNode => {
  const treeNode: TreeNode = {
    id: node.nodeId,
    name: node.methodName,
    className: node.className,
    methodSignature: node.signature,
    methodBody: node.methodBody,
    description: node.description,
    isNoMatch: node.isNoMatch,
    bridgeType: node.bridgeType,
    bridgeInfo: node.bridgeInfo,
    depth: node.depth
  }

  // 找到该节点的子节点
  const childDepth = node.depth + 1
  const children = chain[childDepth]?.filter(n => n.callerId === node.nodeId) || []

  if (children.length > 0) {
    treeNode.children = children.map(child => buildTreeNode(child, chain))
  }

  return treeNode
}

// 加载调用链
const loadChain = async () => {
  if (!props.entry.entryKey || !props.projectPath) return

  loading.value = true
  try {
    // 同时加载调用链和DAG图数据
    const [chainResult, graphResult] = await Promise.all([
      knowledgeGraphApi.getCallChainByKey(
        props.entry.entryKey,
        props.projectPaths
      ),
      knowledgeGraphApi.getCallChainGraph(
        props.entry.entryKey,
        props.projectPaths,
        true, // includeCycles
        50 // maxDepth
      )
    ])

    chainData.value = chainResult as unknown as CallChainView
    graphData.value = graphResult as unknown as CallChainGraphData

    // 设置环数量
    if (graphData.value) {
      cycleCount.value = graphData.value.cycleCount || 0
    }
  } catch (error: any) {
    ElMessage.error(`加载调用链失败: ${error.message || error}`)
  } finally {
    loading.value = false
  }
}

// AI 分析
// 混合模式：后端从 Neo4j 组装完整调用链数据 → 前端创建 workspace session → PTY 终端
const handleAIAnalysis = async () => {
  if (!chainData.value) return

  try {
    // 1. 调后端接口，从 Neo4j 拉取完整调用链数据组装为富提示词
    const { aiAnalysisApi } = await import('@/api/aiAnalysis')
    const result = await aiAnalysisApi.buildCallChainPrompt({
      entryKey: props.entry.entryKey,
      projectPath: props.projectPath
    }) as any

    const prompt = result?.prompt || result
    if (!prompt || (typeof prompt === 'string' && prompt.length < 10)) {
      ElMessage.warning('未找到调用链数据，请先生成知识图谱')
      return
    }

    // 2. 创建 workspace session，通过 PTY 终端发送到 Claude CLI
    const session = await workspaceStore.createSession(
      'call-chain-analysis',
      typeof prompt === 'string' ? prompt : JSON.stringify(prompt),
      props.projectPath || undefined
    )
    router.push({ name: 'ClaudeTerminal', query: { sessionId: session.id } })
  } catch (error: any) {
    ElMessage.error(`创建 AI 分析会话失败: ${error.message || error}`)
  }
}

// 右键菜单
const handleContextMenu = (event: MouseEvent, node: any) => {
  contextMenuVisible.value = true
  contextMenuX.value = event.clientX
  contextMenuY.value = event.clientY
  contextMenuNode.value = node
}

const closeContextMenu = () => {
  contextMenuVisible.value = false
}

const handleMenuAction = (_action: string, _node: any) => {
  // 复用调用链页面的菜单逻辑
  closeContextMenu()
}

// 监听 entry 变化，重置数据
watch(() => props.entry, () => {
  chainData.value = null
  graphData.value = null
  cycleCount.value = 0
}, { immediate: true })
</script>

<style scoped>
.entry-detail {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.entry-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 12px;
}

.entry-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.entry-key {
  font-family: monospace;
  font-size: 14px;
  font-weight: 500;
}

.entry-actions {
  display: flex;
  gap: 8px;
}

.chain-stats {
  margin-bottom: 12px;
}

.chain-view {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.empty-chain {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
