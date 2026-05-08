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
            placeholder="输入方法名（如：com.example.Service.method）"
            @keyup.enter="addMethod"
            style="width: 400px;"
            size="small"
          >
            <template #append>
              <el-button @click="addMethod" :disabled="!methodInput">添加</el-button>
            </template>
          </el-input>
        </div>
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

      <!-- 向上查询：展示调用者方法 -->
      <div v-if="analysisDirection === 'upstream' && upstreamCallers.length > 0" class="uri-list-section">
        <div class="section-header">
          <el-icon><Link /></el-icon>
          <span>调用者 ({{ upstreamCallers.length }})</span>
        </div>
        <el-table :data="upstreamCallers" stripe style="width: 100%">
          <el-table-column prop="display" label="调用者方法" min-width="400" />
          <el-table-column prop="callType" label="调用类型" width="120" />
          <el-table-column prop="callLine" label="行号" width="80" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button size="small" @click="drillUpFromCaller(row)">继续向上</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 向上查询：无结果提示 -->
      <el-empty v-if="analysisDirection === 'upstream' && upstreamCallers.length === 0 && !loading && hasQueried"
        description="未找到调用者" />

      <!-- 向下查询：展示依赖图 -->
      <ChainChart
        v-if="analysisDirection === 'downstream'"
        :data="chainData"
        :loading="loading"
        :project-paths="appStore.getSelectedProjectPaths()"
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
import { knowledgeGraphApi } from '@/api/knowledgeGraph'
import { claudeApi } from '@/api/claude'
import { useAppStore } from '@/stores/app'
import { usePromptStore } from '@/stores/promptStore'
import ChainChart from './components/ChainChart.vue'
import ContextMenu from './components/ContextMenu.vue'

interface ChainNode {
  name: string
  className?: string
  methodSignature?: string
  description?: string
  children?: ChainNode[]
}

interface UpstreamCaller {
  callerId: string
  callerClassName: string
  callerMethodName: string
  callType: string
  callLine: number
  display: string
}

interface Selection {
  className: string
  methodName: string
}

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const promptStore = usePromptStore()

const projectName = computed(() => appStore.selectedProject || '')
const selection = ref<Selection | null>(null)
const loading = ref(false)
const analysisLoading = ref(false)
const chainData = ref<ChainNode | null>(null)
const upstreamCallers = ref<UpstreamCaller[]>([])
const hasQueried = ref(false)

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

  loading.value = true
  hasQueried.value = true
  upstreamCallers.value = []
  chainData.value = null

  try {
    const projectPath = appStore.projectDir || ''

    if (analysisDirection.value === 'upstream') {
      // 向上：使用合并接口获取根入口 + 直接调用者
      const callers: UpstreamCaller[] = []
      for (const method of entryMethods.value) {
        const { className, methodName } = splitFqn(method)
        const resp = await knowledgeGraphApi.getRootEntries(className, methodName, projectPath) as unknown as { rootEntries: any[]; directCallers: any[] }
        for (const item of (resp?.directCallers || [])) {
          callers.push({
            callerId: item.callerId || '',
            callerClassName: item.callerClassName || '',
            callerMethodName: item.callerMethodName || '',
            callType: item.callType || '',
            callLine: item.callLine || 0,
            display: `${item.callerClassName || ''}.${item.callerMethodName || ''}`
          })
        }
      }
      upstreamCallers.value = callers
      if (callers.length > 0) {
        ElMessage.success(`找到 ${callers.length} 个调用者`)
      } else {
        ElMessage.info('未找到调用者')
      }
    } else {
      // 向下：使用 callees-tree 获取完整子树
      const rootChildren: ChainNode[] = []
      for (const method of entryMethods.value) {
        const { className, methodName } = splitFqn(method)
        try {
          const graph = await knowledgeGraphApi.getCalleesTree(className, methodName, projectPath, 5) as unknown as any
          if (graph && Array.isArray(graph.nodes) && graph.nodes.length > 0) {
            rootChildren.push(buildSubtreeFromCalleesTree(graph, method))
          } else {
            rootChildren.push({ name: methodName, className, methodSignature: method, children: [] })
          }
        } catch {
          rootChildren.push({ name: methodName, className, methodSignature: method, children: [] })
        }
      }
      chainData.value = { name: '入口方法', className: '', children: rootChildren }
      ElMessage.success('依赖图生成成功')
    }
  } catch (error) {
    console.error('[MethodRef] query failed:', error)
    ElMessage.error('查询失败')
  } finally {
    loading.value = false
  }
}

const splitFqn = (method: string): { className: string; methodName: string } => {
  const lastDot = method.lastIndexOf('.')
  return {
    className: lastDot > 0 ? method.substring(0, lastDot) : '',
    methodName: lastDot > 0 ? method.substring(lastDot + 1) : method
  }
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
      name: n.methodName || n.label || '',
      className: n.className || '',
      methodSignature: `${n.className || ''}.${n.methodName || n.label || ''}`,
      children: []
    }
    nodeMap.set(n.nodeId || n.id, cn)
    if ((n.depth ?? 0) === 0) {
      // root node — reuse our root object
      nodeMap.set(n.nodeId || n.id, root)
      root.name = cn.name || root.name
      root.className = cn.className || root.className
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

// 从调用者表中"继续向上"
const drillUpFromCaller = (caller: UpstreamCaller) => {
  const fqn = `${caller.callerClassName}.${caller.callerMethodName}`
  if (!entryMethods.value.includes(fqn)) {
    entryMethods.value.push(fqn)
  }
  loadDependencyGraph()
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
</style>
