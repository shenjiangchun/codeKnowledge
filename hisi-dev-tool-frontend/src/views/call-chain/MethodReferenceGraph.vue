<template>
  <div class="method-reference-graph">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>方法引用关系分析</span>
          <el-tag type="info" v-if="projectName">{{ projectName }}</el-tag>
        </div>
      </template>

      <div class="toolbar">
        <div class="method-input-area">
          <el-tag
            v-for="(method, index) in entryMethods"
            :key="index"
            closable
            @close="removeMethod(index)"
            class="method-tag"
          >
            {{ method }}
          </el-tag>
          <el-input
            v-model="methodInput"
            placeholder="输入完整方法签名，例如：com.hisilicon.dm.client.fallback.ApmMoveFallback.syncApmDeliverGroup"
            @keyup.enter="addMethod"
            style="width: 400px;"
            size="small"
          >
            <template #append>
              <el-button @click="addMethod" :disabled="!methodInput">添加</el-button>
            </template>
          </el-input>
        </div>
        <div class="toolbar-controls">
          <el-select v-model="maxDepth" size="small" style="width: 120px;">
            <el-option :value="3" label="3 层" />
            <el-option :value="5" label="5 层" />
            <el-option :value="10" label="10 层" />
            <el-option :value="15" label="15 层" />
            <el-option :value="20" label="20 层" />
          </el-select>
          <el-radio-group v-model="analysisDirection" size="small">
            <el-radio-button label="upstream">向上（调用方）</el-radio-button>
            <el-radio-button label="downstream">向下（被调用方）</el-radio-button>
          </el-radio-group>
          <el-button
            type="primary"
            @click="loadDependencyGraph"
            :loading="loading"
            :disabled="entryMethods.length === 0"
          >
            查询
          </el-button>
          <el-button
            v-if="analysisDirection === 'downstream' && chainData"
            type="success"
            @click="handleAIAnalysis"
            :loading="analysisLoading"
          >
            <el-icon><ChatDotRound /></el-icon>
            AI 影响分析
          </el-button>
        </div>
      </div>

      <!-- 向上查询：展示根入口列表 -->
      <div v-if="analysisDirection === 'upstream' && upstreamEntries.length > 0" class="upstream-section">
        <div class="section-header">
          <el-icon><Link /></el-icon>
          <span>根入口 ({{ upstreamEntries.length }})</span>
        </div>

        <el-table :data="upstreamEntries" stripe style="width: 100%">
          <el-table-column prop="entryType" label="入口类型" width="180">
            <template #default="{ row }">
              <el-tag size="small">{{ row.entryType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="entryKey" label="入口标识" min-width="400" show-overflow-tooltip />
        </el-table>
      </div>

      <!-- 向上查询：无结果提示 -->
      <el-empty v-if="analysisDirection === 'upstream' && upstreamEntries.length === 0 && !loading && hasQueried"
        description="未找到根入口" />

      <!-- 多入口合并流程图 -->
      <div v-if="analysisDirection === 'downstream' && mergedGraph" class="merged-graph-section">
        <div class="section-header">
          <span>多入口合并流程图 ({{ mergedGraph.nodes.length }} 节点)</span>
        </div>
        <div class="merged-flow-container">
          <FlowDag
            :nodes="mergedGraph.nodes"
            :edges="mergedGraph.edges"
            direction="TB"
            :entry-sources="mergedGraph.entrySources"
            :entry-colors="mergedGraph.entryColors"
            :entry-labels="mergedGraph.entryLabels"
            @node-click="handleMergedNodeClick"
          />
        </div>
      </div>

      <!-- 向下查询：展示依赖图 -->
      <ChainChart
        v-if="analysisDirection === 'downstream'"
        :data="chainData"
        :loading="loading"
        :project-paths="effectiveProjectPaths"
        @node-contextmenu="handleContextMenu"
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
import { ChatDotRound, Link } from '@element-plus/icons-vue'
import { knowledgeGraphApi, type CallChainGraphData } from '@/api/knowledgeGraph'
import { mergeCallChainGraphs, type MergedGraph } from './components/mergeGraphs'
import { claudeApi } from '@/api/claude'
import { useAppStore } from '@/stores/app'
import { usePromptStore } from '@/stores/promptStore'
import ChainChart from './components/ChainChart.vue'
import FlowDag from './components/FlowDag.vue'
import type { FlowNode } from './components/flowDagLayout'
import ContextMenu from './components/ContextMenu.vue'

interface ChainNode {
  name: string
  className?: string
  methodSignature?: string
  description?: string
  children?: ChainNode[]
}

interface RootEntry {
  entryId: string
  entryType: string
  entryKey: string
}

interface Selection {
  className: string
  methodName: string
}

const props = defineProps<{
  projectPaths?: string[]
}>()

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const promptStore = usePromptStore()

// 优先使用传入的 projectPaths，否则从 store 获取
const effectiveProjectPaths = computed(() => {
  if (props.projectPaths && props.projectPaths.length > 0) {
    return props.projectPaths
  }
  return appStore.getSelectedProjectPaths()
})

const projectName = computed(() => appStore.selectedProject || '')
const selection = ref<Selection | null>(null)
const loading = ref(false)
const analysisLoading = ref(false)
const chainData = ref<ChainNode | null>(null)
const upstreamEntries = ref<RootEntry[]>([])
const hasQueried = ref(false)
const maxDepth = ref<number>(5)
const mergedGraph = ref<MergedGraph | null>(null)

// Multi-method input
const methodInput = ref('')
const entryMethods = ref<string[]>([])
// FQN -> nodeId（由外部调用 setAndSearch 传入时暂存）
const entryNodeIdMap = ref<Map<string, string>>(new Map())
const analysisDirection = ref<'upstream' | 'downstream'>('downstream')

// 右键菜单状态
const contextMenuVisible = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const contextMenuNode = ref<ChainNode | null>(null)

// Add method to entry list
const addMethod = () => {
  const method = methodInput.value.trim()
  if (method && !entryMethods.value.includes(method)) {
    if (!isValidMethodSignature(method)) {
      ElMessage.warning('请输入完整的方法签名，格式为：包名.类名.方法名')
      return
    }
    entryMethods.value.push(method)
    methodInput.value = ''
  }
}

// Remove method from entry list
const removeMethod = (index: number) => {
  entryMethods.value.splice(index, 1)
}

// Load dependency graph for multiple methods
const loadDependencyGraph = async () => {
  if (entryMethods.value.length === 0) return

  // 验证所有方法签名
  for (const method of entryMethods.value) {
    if (!isValidMethodSignature(method)) {
      ElMessage.error(`方法签名无效：${method}，请使用格式：包名.类名.方法名`)
      return
    }
  }

  loading.value = true
  hasQueried.value = true
  upstreamEntries.value = []
  chainData.value = null
  mergedGraph.value = null

  try {
    const projectPaths = effectiveProjectPaths.value

    if (analysisDirection.value === 'upstream') {
      // 向上：并行查询所有方法的根入口点
      const queries = entryMethods.value.map(method => {
        const { className, methodName } = splitFqn(method)
        return knowledgeGraphApi.getRootEntries(className, methodName, projectPaths)
          .then(resp => (resp as any)?.rootEntries || [])
          .catch(() => [] as any[])
      })
      const results = await Promise.all(queries)
      const allEntries: RootEntry[] = []
      const seen = new Set<string>()
      for (const entries of results) {
        for (const r of entries) {
          if (r.entryId && seen.add(r.entryId)) {
            allEntries.push({ entryId: r.entryId, entryType: r.entryType || '', entryKey: r.entryKey || '' })
          }
        }
      }
      upstreamEntries.value = allEntries

      if (allEntries.length > 0) {
        ElMessage.success(`找到 ${allEntries.length} 个根入口`)
      } else {
        ElMessage.info('未找到根入口')
      }
    } else {
      // 向下：并行查询所有方法的下游调用树
      const queries = entryMethods.value.map(method => {
        const { className, methodName } = splitFqn(method)
        return knowledgeGraphApi.getCalleesTree(className, methodName, projectPaths, maxDepth.value)
          .then(graph => ({ entryFqn: method, data: graph as unknown as CallChainGraphData }))
          .catch(() => null)
      })
      const results = await Promise.all(queries)

      const graphResults: { entryFqn: string; data: CallChainGraphData }[] = []
      const rootChildren: ChainNode[] = []
      for (let i = 0; i < results.length; i++) {
        const result = results[i]
        const method = entryMethods.value[i]
        const { className, methodName } = splitFqn(method)
        if (result && Array.isArray(result.data?.nodes) && result.data.nodes.length > 0) {
          graphResults.push(result)
          rootChildren.push(buildSubtreeFromCalleesTree(result.data, method))
        } else {
          rootChildren.push({ name: methodName, className, methodSignature: method, children: [] })
        }
      }
      chainData.value = { name: '入口方法', className: '', children: rootChildren }

      if (graphResults.length > 1) {
        mergedGraph.value = mergeCallChainGraphs(graphResults)
      } else {
        mergedGraph.value = null
      }

      ElMessage.success('依赖图生成成功')
    }
  } catch (error) {
    console.error('[MethodRef] query failed:', error)
    ElMessage.error('查询失败')
  } finally {
    loading.value = false
  }
}

// 解析完整方法签名: className.methodName
const splitFqn = (method: string): { className: string; methodName: string } => {
  const lastDot = method.lastIndexOf('.')
  if (lastDot <= 0) {
    // 无效的方法签名
    return {
      className: '',
      methodName: method
    }
  }
  return {
    className: method.substring(0, lastDot),
    methodName: method.substring(lastDot + 1)
  }
}

// 验证是否是有效的完整方法签名
const isValidMethodSignature = (method: string): boolean => {
  const { className, methodName } = splitFqn(method)
  return className.length > 0 && methodName.length > 0
}

// 用 callees-tree 返回的图结构（nodes+edges）构建子树
const buildSubtreeFromCalleesTree = (graph: any, rootFqn: string): ChainNode => {
  const root: ChainNode = {
    name: splitFqn(rootFqn).methodName,
    className: splitFqn(rootFqn).className,
    methodSignature: rootFqn,
    children: []
  }
  if (!graph || !Array.isArray(graph.nodes)) return root

  const nodeMap = new Map<string, ChainNode>()
  for (const n of graph.nodes) {
    const cn: ChainNode = {
      name: n.name || n.methodName || n.label || '',
      className: n.className || '',
      methodSignature: `${n.className || ''}.${n.name || n.methodName || n.label || ''}`,
      description: n.description,
      children: []
    }
    nodeMap.set(n.nodeId || n.id, cn)
    if ((n.depth ?? 0) === 0) {
      // root node — reuse our root object
      nodeMap.set(n.nodeId || n.id, root)
      root.name = cn.name || root.name
      root.className = cn.className || root.className
      root.description = cn.description || root.description
    }
  }

  if (Array.isArray(graph.edges)) {
    for (const e of graph.edges) {
      const parent = nodeMap.get(e.source || e.from)
      const child = nodeMap.get(e.target || e.to)
      if (parent && child && parent !== child) {
        parent.children = parent.children || []
        parent.children.push(child)
      }
    }
  }

  return root
}

// 用 callees-tree 返回的图结构（nodes+edges）构建子树
const handleMergedNodeClick = (node: FlowNode) => {
  if (node.className && node.name) {
    ElMessage.info(`${node.className}.${node.name}`)
  }
}

// 父组件可调用：把方法加入入口列表并立即查询
const setAndSearch = (fqn: string, nodeId: string | undefined, direction: 'upstream' | 'downstream' = 'downstream') => {
  if (!fqn) return
  entryMethods.value = [fqn]
  entryNodeIdMap.value.clear()
  if (nodeId) entryNodeIdMap.value.set(fqn, nodeId)
  analysisDirection.value = direction
  loadDependencyGraph()
}

defineExpose({ setAndSearch })

// Handle AI analysis
const handleAIAnalysis = async () => {
  if (!chainData.value) return

  analysisLoading.value = true
  try {
    const methodList = entryMethods.value.join(', ')
    const direction = '向下（查找被调用方）'

    await promptStore.loadTemplates()
    const prompt = promptStore.render('impact-analysis', {
      changedFile: '方法依赖分析',
      changedMethod: methodList,
      changeType: 'DEPENDENCY_ANALYSIS',
      projectName: projectName.value
    }) + `\n\n分析方向: ${direction}\n入口方法数量: ${entryMethods.value.length}`

    const sessionId = await claudeApi.universalChat(
      {
        prompt,
        scene: 'impact-analysis',
        metadata: {
          projectName: projectName.value,
          methods: entryMethods.value,
          direction: analysisDirection.value
        }
      },
      {
        onOutput: () => {},
        onDone: () => {},
        onError: (error) => {
          ElMessage.error(`分析失败: ${error}`)
        }
      }
    )

    router.push({ name: 'ClaudeSession', query: { sessionId } })
    ElMessage.success('已创建影响分析会话')
  } catch (error) {
    ElMessage.error('创建分析会话失败')
  } finally {
    analysisLoading.value = false
  }
}

const handleContextMenu = (node: ChainNode, event: MouseEvent) => {
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

const handleMenuAction = (action: string, node: ChainNode) => {
  switch (action) {
    case 'upstream':
    case 'downstream':
      if (node.methodSignature) {
        analysisDirection.value = action === 'upstream' ? 'upstream' : 'downstream'
        entryMethods.value = [node.methodSignature]
        entryNodeIdMap.value.clear()
        loadDependencyGraph()
      }
      break
    case 'copy':
      navigator.clipboard.writeText(node.methodSignature || node.name)
      ElMessage.success('已复制到剪贴板')
      break
    case 'addToEntry':
      if (node.methodSignature && !entryMethods.value.includes(node.methodSignature)) {
        entryMethods.value.push(node.methodSignature)
        ElMessage.success('已添加到入口方法列表')
      }
      break
  }
}

// 从 URL 参数初始化
onMounted(() => {
  const { className, methodName, direction, methods } = route.query

  if (methods) {
    const methodList = (methods as string).split(',').filter(m => m.trim())
    entryMethods.value = methodList
    if (direction === 'up') {
      analysisDirection.value = 'upstream'
    }
    if (methodList.length > 0) {
      loadDependencyGraph()
    }
  } else if (className && methodName) {
    selection.value = {
      className: className as string,
      methodName: methodName as string
    }
    entryMethods.value = [`${className}.${methodName}`]
    analysisDirection.value = direction === 'up' ? 'upstream' : 'downstream'
    loadDependencyGraph()
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
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}

.method-input-area {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.method-tag {
  margin-right: 4px;
}

.uri-list-section {
  margin-top: 16px;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.method-reference-graph {
  height: calc(100vh - 200px);
}

.upstream-section {
  margin-top: 16px;
}

.merged-graph-section {
  margin-top: 16px;
}

.merged-flow-container {
  height: 600px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  margin-top: 12px;
}
</style>
