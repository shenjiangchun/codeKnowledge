<template>
  <div class="call-chain-graph">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>接口调用链查询</span>
          <el-select
            v-model="selectedProject"
            placeholder="选择项目"
            filterable
            @change="handleProjectChange"
            style="width: 250px;"
          >
            <el-option
              v-for="proj in projects"
              :key="proj"
              :label="proj"
              :value="proj"
            />
          </el-select>
        </div>
      </template>

      <div class="toolbar">
        <UriSelector
          :project="selectedProject"
          v-model="selectedUri"
          @change="handleUriChange"
          style="width: 400px;"
        />
        <el-button type="primary" @click="loadCallChain" :loading="loading" :disabled="!selectedUri || !selectedProject">
          查询
        </el-button>
        <el-button
          type="success"
          @click="handleAIAnalysis"
          :loading="analysisLoading"
          :disabled="!chainData"
        >
          <el-icon><ChatDotRound /></el-icon>
          AI 调用链分析
        </el-button>
      </div>

      <ChainChart
        :data="chainData"
        :loading="loading"
        :project-path="effectiveProjectPath"
        :project-paths="effectiveProjectPath ? [effectiveProjectPath] : appStore.getSelectedProjectPaths()"
        @node-contextmenu="handleContextMenu"
        @navigate-method-ref="handleNavigateMethodRef"
      />
    </el-card>

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
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { knowledgeGraphApi, type GraphNode, type GraphEdge } from '@/api/knowledgeGraph'
import { aiAnalysisApi } from '@/api/aiAnalysis'
import { useAppStore } from '@/stores/app'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import UriSelector from './components/UriSelector.vue'
import ChainChart from './components/ChainChart.vue'
import ContextMenu from './components/ContextMenu.vue'

interface ChainNode {
  id?: string
  name: string
  className?: string
  methodSignature?: string
  methodBody?: string
  description?: string
  isNoMatch?: boolean
  depth?: number
  children?: ChainNode[]
}

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const workspaceStore = useWorkspaceStore()

const projectName = computed(() => appStore.selectedProject || '')
const projects = ref<string[]>([])
const selectedProject = ref(route.query.project as string || '')
const selectedUri = ref(route.query.uri as string || '')
const loading = ref(false)
const analysisLoading = ref(false)
const chainData = ref<ChainNode | null>(null)

// 提供给子组件的有效 projectPath（处理相对/绝对路径拼接）
const effectiveProjectPath = computed(() => {
  const sel = selectedProject.value
  if (!sel) return ''
  if (sel.includes(':') || sel.startsWith('/')) return sel
  return appStore.projectDir ? `${appStore.projectDir}\\${sel}` : sel
})

// 右键菜单状态
const contextMenuVisible = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const contextMenuNode = ref<ChainNode | null>(null)

const loadProjects = async () => {
  try {
    const res = await knowledgeGraphApi.getProjects()
    // axios 拦截器已解包，res 直接是数据数组
    projects.value = res || []
    // Auto select first project if only one
    if (projects.value.length === 1 && !selectedProject.value) {
      selectedProject.value = projects.value[0]
    }
  } catch (error) {
    ElMessage.error('加载项目列表失败')
  }
}

const handleProjectChange = () => {
  selectedUri.value = ''
  chainData.value = null
}

const handleUriChange = (uri: string) => {
  selectedUri.value = uri
}

/**
 * 将图谱数据（nodes + edges）转换为树形结构
 * 注意：在调用链中，一个方法可能被多个方法调用（多入边），
 * 为了在树形视图中完整展示所有调用路径，需要复制节点
 */
const convertGraphToTree = (nodes: GraphNode[], edges: GraphEdge[]): ChainNode | null => {
  if (!nodes || nodes.length === 0) return null

  // 创建节点映射（原始节点数据）
  const nodeDataMap = new Map<string, GraphNode>()
  nodes.forEach(node => nodeDataMap.set(node.id, node))

  // 构建边关系：source -> targets（出边）
  const outgoingEdges = new Map<string, GraphEdge[]>()
  edges.forEach(edge => {
    if (!outgoingEdges.has(edge.source)) {
      outgoingEdges.set(edge.source, [])
    }
    outgoingEdges.get(edge.source)!.push(edge)
  })

  // 找到根节点（depth=0 的节点）
  let rootNodeId: string | null = null
  for (const node of nodes) {
    if (node.depth === 0) {
      rootNodeId = node.id
      break
    }
  }

  if (!rootNodeId) {
    // 如果没有 depth=0 的节点，找没有入边的节点
    const targetIds = new Set(edges.map(e => e.target))
    for (const node of nodes) {
      if (!targetIds.has(node.id)) {
        rootNodeId = node.id
        break
      }
    }
  }

  if (!rootNodeId) {
    rootNodeId = nodes[0]?.id || null
  }

  if (!rootNodeId) return null

  // 使用计数器确保每个节点实例有唯一 ID
  let instanceCounter = 0

  // 递归构建树，允许多次访问同一方法（复制节点）
  const buildTree = (nodeId: string, visitedInPath: Set<string>): ChainNode | null => {
    const nodeData = nodeDataMap.get(nodeId)
    if (!nodeData) return null

    // 创建新的节点实例
    const treeNode: ChainNode = {
      id: `${nodeId}_inst_${instanceCounter++}`,
      name: nodeData.name,
      className: nodeData.className,
      methodSignature: nodeData.signature,
      description: nodeData.description,
      depth: nodeData.depth,
      isNoMatch: false,
      children: []
    }

    // 检查当前路径是否有环
    if (visitedInPath.has(nodeId)) {
      // 环检测，不再继续展开
      return treeNode
    }

    // 添加当前节点到路径
    const newPath = new Set(visitedInPath)
    newPath.add(nodeId)

    // 递归处理子节点
    const childrenEdges = outgoingEdges.get(nodeId) || []
    childrenEdges.forEach(edge => {
      const childNode = buildTree(edge.target, newPath)
      if (childNode && treeNode.children) {
        treeNode.children.push(childNode)
      }
    })

    return treeNode
  }

  return buildTree(rootNodeId, new Set())
}

const loadCallChain = async () => {
  if (!selectedUri.value || !selectedProject.value) return

  loading.value = true
  try {
    // 构建完整的项目路径
    let projectPath = selectedProject.value
    if (appStore.projectDir && !selectedProject.value.includes(':') && !selectedProject.value.startsWith('/')) {
      // 如果 selectedProject 不是完整路径，则拼接
      projectPath = `${appStore.projectDir}\\${selectedProject.value}`
    }

    // 使用知识图谱 API
    const res = await knowledgeGraphApi.getCallChainGraph(
      selectedUri.value,
      projectPath,
      true
    )

    if (res && res.nodes && res.nodes.length > 0) {
      chainData.value = convertGraphToTree(res.nodes, res.edges)

      if (!chainData.value) {
        ElMessage.warning('未找到调用链数据')
      }
    } else {
      ElMessage.warning('未找到调用链数据')
    }
  } catch (error) {
    ElMessage.error('加载调用链数据失败')
  } finally {
    loading.value = false
  }
}

const handleContextMenu = (payload: { event: MouseEvent; node: ChainNode } | ChainNode, maybeEvent?: MouseEvent) => {
  // 兼容两种调用方式
  let event: MouseEvent
  let node: ChainNode

  if (payload && 'event' in payload && 'node' in payload) {
    event = payload.event
    node = payload.node
  } else {
    event = maybeEvent!
    node = payload as ChainNode
  }

  event.preventDefault()
  contextMenuNode.value = node
  contextMenuX.value = event.clientX
  contextMenuY.value = event.clientY
  contextMenuVisible.value = true
}

const closeContextMenu = () => {
  contextMenuVisible.value = false
  contextMenuNode.value = null
}

// 处理跳转到方法引用分析页面
const handleNavigateMethodRef = (direction: 'up' | 'down', className: string, methodName: string) => {
  router.push({
    path: '/call-chain/method-reference',
    query: {
      className,
      methodName,
      direction
    }
  })
}

const handleMenuAction = (action: string, node: ChainNode) => {
  switch (action) {
    case 'upstream':
    case 'downstream':
      router.push({
        path: '/call-chain/method-reference',
        query: {
          className: node.className,
          methodName: node.name,
          direction: action === 'upstream' ? 'up' : 'down'
        }
      })
      break
    case 'copy':
      navigator.clipboard.writeText(node.methodSignature || node.name)
      ElMessage.success('已复制到剪贴板')
      break
    case 'openInMethodRef':
      router.push({
        path: '/call-chain/method-reference',
        query: {
          className: node.className,
          methodName: node.name
        }
      })
      break
  }
}

// Handle AI analysis for call chain
// 混合模式：后端从 Neo4j 组装完整调用链数据 → 前端创建 workspace session → PTY 终端
const handleAIAnalysis = async () => {
  if (!chainData.value || !selectedUri.value) return

  analysisLoading.value = true
  try {
    // 1. 调后端接口，从 Neo4j 拉取完整调用链数据组装为富提示词
    const result = await aiAnalysisApi.buildCallChainPrompt({
      entryKey: selectedUri.value,
      projectPath: effectiveProjectPath.value
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
      effectiveProjectPath.value || undefined
    )
    router.push({ name: 'ClaudeTerminal', query: { sessionId: session.id } })
    ElMessage.success('已创建调用链分析会话')
  } catch (error: any) {
    ElMessage.error(`创建分析会话失败: ${error.message || error}`)
  } finally {
    analysisLoading.value = false
  }
}

onMounted(() => {
  loadProjects()
  // 初始加载
  if (selectedUri.value && selectedProject.value) {
    loadCallChain()
  }
})
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.call-chain-graph {
  height: calc(100vh - 200px);
}
</style>