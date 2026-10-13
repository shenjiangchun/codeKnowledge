<template>
  <div class="frontend-backend-tab">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="前后端跨层关系"
      description="一次铺开前端路由 → 组件 → API 调用点 → 后端接口 → 入口方法 的连接骨架；点击「方法」节点可按需展开其下游调用链。"
      style="margin-bottom: 12px"
    />

    <div v-if="!hasProjects" class="empty-hint">
      <el-empty description="请先在页面顶部选择项目（需已建立前后端绑定）" />
    </div>

    <div v-else-if="loading" v-loading="true" class="graph-container" />

    <div v-else-if="nodes.length === 0" class="empty-hint">
      <el-empty description="未找到跨层关系，请先「构建前后端完整图谱」并完成绑定" />
    </div>

    <div v-else class="graph-container">
      <FlowDag
        :nodes="nodes"
        :edges="edges"
        direction="LR"
        :node-colors="nodeColors"
        @node-click="onNodeClick"
      />

      <div class="legend-panel">
        <div v-for="item in legend" :key="item.type" class="legend-item">
          <span class="legend-dot" :style="{ backgroundColor: item.color }" />
          <span class="legend-label">{{ item.type }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import FlowDag from '@/views/call-chain/components/FlowDag.vue'
import type { FlowNode } from '@/views/call-chain/components/flowDagLayout'
import type { GraphNode, GraphEdge } from '@/api/knowledgeGraph'
import { knowledgeGraphApi } from '@/api/knowledgeGraph'
import { frontendBackendApi } from '@/api/frontendBackend'

const props = defineProps<{
  projectPaths: string[]
}>()

const NODE_TYPE_COLORS: Record<string, string> = {
  FrontendRoute: '#b37feb',
  Component: '#67c23a',
  ApiClient: '#f56c6c',
  EntryPoint: '#e6a23c',
  Method: '#409eff',
}

const legend = Object.entries(NODE_TYPE_COLORS).map(([type, color]) => ({ type, color }))

const nodes = ref<GraphNode[]>([])
const edges = ref<GraphEdge[]>([])
const loading = ref(false)
// 已展开下游调用链的方法节点 id
const expandedMethods = ref<Set<string>>(new Set())

const hasProjects = computed(() => props.projectPaths.length > 0)

const nodeColors = computed(() => {
  const m = new Map<string, string>()
  for (const n of nodes.value) {
    if (n.nodeType && NODE_TYPE_COLORS[n.nodeType]) {
      m.set(n.id, NODE_TYPE_COLORS[n.nodeType])
    }
  }
  return m
})

async function load() {
  if (props.projectPaths.length === 0) return
  loading.value = true
  try {
    const data = await frontendBackendApi.getFrontendBackendGraph(props.projectPaths)
    nodes.value = data.nodes ?? []
    edges.value = data.edges ?? []
    expandedMethods.value = new Set()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载失败'
    ElMessage.error('加载跨层图失败: ' + msg)
  } finally {
    loading.value = false
  }
}

async function onNodeClick(node: FlowNode) {
  // 仅「方法」节点支持展开下游调用链
  const meta = nodes.value.find((n) => n.id === node.id)
  if (!meta || meta.nodeType !== 'Method') return
  if (expandedMethods.value.has(node.id)) return
  try {
    const data = await knowledgeGraphApi.getDownstreamGraph(node.id, props.projectPaths, 3)
    mergeGraph(data.nodes ?? [], data.edges ?? [])
    expandedMethods.value = new Set(expandedMethods.value).add(node.id)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '展开失败'
    ElMessage.error('展开调用链失败: ' + msg)
  }
}

/** 将下游子图合并进当前图（按 id / source+target 去重）。 */
function mergeGraph(newNodes: GraphNode[], newEdges: GraphEdge[]) {
  const nodeIds = new Set(nodes.value.map((n) => n.id))
  const edgeKeys = new Set(edges.value.map((e) => `${e.source}->${e.target}`))

  const addedNodes = newNodes.filter((n) => !nodeIds.has(n.id))
  if (addedNodes.length > 0) {
    nodes.value = [...nodes.value, ...addedNodes]
  }

  const addedEdges = newEdges.filter((e) => !edgeKeys.has(`${e.source}->${e.target}`))
  if (addedEdges.length > 0) {
    edges.value = [...edges.value, ...addedEdges]
  }
}

watch(() => props.projectPaths, load, { immediate: true, deep: true })
</script>

<style scoped>
.frontend-backend-tab {
  padding: 4px 0;
}

.empty-hint {
  display: flex;
  justify-content: center;
}

.graph-container {
  position: relative;
  height: 560px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}

.legend-panel {
  position: absolute;
  top: 10px;
  right: 10px;
  background: rgba(255, 255, 255, 0.92);
  border-radius: 4px;
  padding: 6px 10px;
  font-size: 11px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-label {
  color: #606266;
  white-space: nowrap;
}
</style>
