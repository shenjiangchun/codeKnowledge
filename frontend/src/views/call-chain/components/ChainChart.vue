<template>
  <div class="chain-container" @contextmenu.prevent>
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-radio-group v-model="viewMode" size="default">
          <el-radio-button value="tree">树形结构</el-radio-button>
          <el-radio-button value="flow">流程图</el-radio-button>
          <el-radio-button value="list">列表视图</el-radio-button>
          <el-radio-button value="dag">DAG 图</el-radio-button>
        </el-radio-group>
      </div>
      <div class="toolbar-right">
        <el-input
          v-model="searchText"
          placeholder="搜索方法..."
          clearable
          style="width: 200px;"
        />
        <el-switch
          v-model="showExternal"
          active-text="显示外部"
          inactive-text="隐藏外部"
          style="margin-left: 16px;"
        />
        <!-- DAG视图的环高亮控制 -->
        <div v-if="viewMode === 'dag' && cycleCount > 0" style="margin-left: 16px; display: flex; align-items: center; gap: 8px;">
          <el-switch
            v-model="highlightCycles"
            active-text="高亮环"
            inactive-text="正常显示"
          />
          <el-tag type="warning" size="small">{{ cycleCount }} 个环</el-tag>
        </div>
      </div>
    </div>

    <!-- 统计栏 -->
    <div class="stats-bar" v-if="filteredNodes.length">
      <div class="stat-item">
        <span class="stat-value">{{ filteredNodes.length }}</span>
        <span class="stat-label">节点</span>
      </div>
      <div class="stat-item project">
        <span class="stat-value">{{ projectCount }}</span>
        <span class="stat-label">项目方法</span>
      </div>
      <div class="stat-item external" v-if="showExternal">
        <span class="stat-value">{{ externalCount }}</span>
        <span class="stat-label">外部依赖</span>
      </div>
      <div class="stat-item depth">
        <span class="stat-value">{{ maxDepth }}</span>
        <span class="stat-label">最大深度</span>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="content-area" v-loading="loadingRecursive">
      <!-- 空状态 -->
      <div v-if="!data" class="empty-state">
        <div class="empty-icon">请选择项目和接口查询调用链</div>
      </div>

      <!-- 树形视图 -->
      <div v-else-if="viewMode === 'tree'" class="tree-view">
        <template v-for="node in rootNodes" :key="node.id">
          <TreeNode
            :node="node"
            :level="0"
            :search="searchText"
            :hide-external="!showExternal"
            @select="handleSelect"
            @contextmenu="handleContextMenu"
          />
        </template>
      </div>

      <!-- 流程图视图 - 按层级展示 -->
      <div v-else-if="viewMode === 'flow'" class="flow-view">
        <div class="flow-container">
          <div
            v-for="depth in flowDepthRange"
            :key="depth"
            class="flow-level"
          >
            <div class="level-header">
              <span class="level-badge">层级 {{ depth }}</span>
            </div>
            <div class="level-nodes">
              <div
                v-for="node in getNodesByDepth(depth)"
                :key="node.id"
                class="flow-node"
                :class="{
                  'is-external': node.isNoMatch,
                  'is-match': isMatch(node),
                  'is-root': depth === 0,
                  'is-mapper': node.bridgeType === 'MAPPER',
                  'is-feign': node.bridgeType === 'FEIGN',
                  'is-mq': node.bridgeType === 'MQ',
                  'is-http': node.bridgeType === 'HTTP',
                  'is-jpa': node.bridgeType === 'JPA',
                  'is-aspect': node.bridgeType === 'ASPECT'
                }"
                :style="node.bridgeType ? { borderColor: getBridgeNodeColor(node) } : {}"
                @click="handleSelect(node)"
                @contextmenu.prevent="handleContextMenu($event, node)"
              >
                <div class="node-icon" v-if="depth === 0">START</div>
                <div class="node-bridge-badge" v-if="node.bridgeType" :style="{ background: getBridgeNodeColor(node) }">
                  {{ getBridgeIcon(node.bridgeType) }}
                </div>
                <div class="node-name">{{ node.name }}</div>
                <div class="node-class" v-if="node.className">{{ shortClass(node.className) }}</div>
                <div class="node-description" v-if="node.description">{{ node.description }}</div>
                <div class="node-tag" v-if="node.isNoMatch">外部</div>
                <div class="node-bridge-info" v-if="node.bridgeType">
                  {{ getBridgeDescription(node) }}
                </div>
              </div>
            </div>
            <div class="level-arrow" v-if="depth < maxDepth">
              <svg viewBox="0 0 24 24" width="24" height="24">
                <path fill="#409eff" d="M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z" transform="rotate(90 12 12)"/>
              </svg>
            </div>
          </div>
        </div>
      </div>

      <!-- 列表视图 - 表格样式 -->
      <div v-else-if="viewMode === 'list'" class="list-view">
        <div class="list-table">
          <div class="list-header">
            <div class="col-depth">层级</div>
            <div class="col-method">方法名</div>
            <div class="col-class">所属类</div>
            <div class="col-type">类型</div>
            <div class="col-action">操作</div>
          </div>
          <div class="list-body">
            <div
              v-for="node in filteredNodes"
              :key="node.id"
              class="list-row"
              :class="{
                'is-external': node.isNoMatch,
                'is-match': isMatch(node),
                'depth-0': node.depth === 0,
                'depth-1': node.depth === 1,
                'depth-2': node.depth === 2,
                'depth-3': node.depth === 3
              }"
            >
              <div class="col-depth">
                <span class="depth-badge" :data-depth="node.depth">{{ node.depth }}</span>
              </div>
              <div class="col-method">
                <div class="method-indent" :style="{ width: node.depth * 20 + 'px' }"></div>
                <span class="method-name" v-html="highlightText(node.name)" @click="handleSelect(node)"></span>
              </div>
              <div class="col-class">{{ node.className ? shortClass(node.className) : '-' }}</div>
              <div class="col-type">
                <el-tag :type="node.isNoMatch ? 'warning' : 'success'" size="small" v-if="!node.bridgeType">
                  {{ node.isNoMatch ? '外部' : '项目' }}
                </el-tag>
                <el-tag
                  v-else
                  :type="getBridgeTagType(node.bridgeType)"
                  size="small"
                  class="bridge-tag"
                >
                  {{ getBridgeIcon(node.bridgeType) }}
                </el-tag>
              </div>
              <div class="col-action">
                <el-button-group size="small">
                  <el-button type="primary" link @click="queryUpstreamForNode(node)" title="向上查询">
                    <span>↑</span>
                  </el-button>
                  <el-button type="success" link @click="queryDownstreamForNode(node)" title="向下查询">
                    <span>↓</span>
                  </el-button>
                  <el-button link @click="handleSelect(node)" title="查看详情">
                    详情
                  </el-button>
                </el-button-group>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- DAG 图视图 - 使用 ECharts -->
      <div v-else-if="viewMode === 'dag'" class="dag-view">
        <div ref="dagChartRef" class="dag-chart"></div>
        <div v-if="!dagData" class="dag-empty">
          <el-empty description="请先加载调用链数据" />
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <div
      v-if="contextMenu.visible"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
    >
      <div class="menu-item" @click="goToUpstream">
        <span class="menu-icon">↑</span>
        <span>向上依赖分析</span>
      </div>
      <div class="menu-item" @click="goToDownstream">
        <span class="menu-icon">↓</span>
        <span>向下依赖分析</span>
      </div>
      <div class="menu-divider"></div>
      <div class="menu-item" @click="queryUpstream">
        <span class="menu-icon">🔍</span>
        <span>向上查询调用链（弹窗）</span>
      </div>
      <div class="menu-item" @click="queryDownstream">
        <span class="menu-icon">🔍</span>
        <span>向下查询调用链（弹窗）</span>
      </div>
      <div class="menu-divider"></div>
      <div class="menu-item" @click="viewDetails">
        <span class="menu-icon">📋</span>
        <span>查看详情</span>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <el-dialog
      v-model="showDetail"
      title="方法详情"
      width="600px"
      :close-on-click-modal="false"
    >
      <div class="detail-content" v-if="selectedNode">
        <div class="detail-row">
          <span class="detail-label">方法名称</span>
          <span class="detail-value name">{{ selectedNode.name }}</span>
        </div>
        <div class="detail-row" v-if="selectedNode.description">
          <span class="detail-label">功能描述</span>
          <span class="detail-value description">{{ selectedNode.description }}</span>
        </div>
        <div class="detail-row" v-if="selectedNode.className">
          <span class="detail-label">所属类</span>
          <span class="detail-value">{{ selectedNode.className }}</span>
        </div>
        <div class="detail-row" v-if="selectedNode.methodSignature">
          <span class="detail-label">方法签名</span>
          <pre class="detail-code">{{ selectedNode.methodSignature }}</pre>
        </div>
        <div class="detail-row" v-if="selectedNode.methodBody">
          <span class="detail-label">方法实现</span>
          <pre class="detail-code body">{{ selectedNode.methodBody }}</pre>
        </div>

        <!-- 桥接信息 -->
        <div class="detail-row bridge-info-row" v-if="selectedNode.bridgeType">
          <span class="detail-label">桥接类型</span>
          <div class="bridge-info-content">
            <el-tag :type="getBridgeTagType(selectedNode.bridgeType)" size="default">
              {{ getBridgeIcon(selectedNode.bridgeType) }}
            </el-tag>
            <span class="bridge-description">{{ getBridgeDescription(selectedNode) }}</span>
          </div>
        </div>

        <!-- Mapper SQL 信息 -->
        <div class="detail-row" v-if="selectedNode.bridgeType === 'MAPPER' && selectedNode.bridgeInfo?.sqlId">
          <span class="detail-label">SQL ID</span>
          <span class="detail-value sql-id">{{ selectedNode.bridgeInfo.sqlId }}</span>
        </div>

        <!-- Feign 调用信息 -->
        <div class="detail-row" v-if="selectedNode.bridgeType === 'FEIGN'">
          <span class="detail-label">目标服务</span>
          <span class="detail-value">{{ selectedNode.bridgeInfo?.targetService || '-' }}</span>
        </div>
        <div class="detail-row" v-if="selectedNode.bridgeType === 'FEIGN' && selectedNode.bridgeInfo?.targetEndpoint">
          <span class="detail-label">目标端点</span>
          <span class="detail-value">{{ selectedNode.bridgeInfo.targetEndpoint }}</span>
        </div>

        <!-- MQ 信息 -->
        <div class="detail-row" v-if="selectedNode.bridgeType === 'MQ'">
          <span class="detail-label">Topic</span>
          <span class="detail-value">{{ selectedNode.bridgeInfo?.topic || '-' }}</span>
        </div>

        <!-- HTTP 信息 -->
        <div class="detail-row" v-if="selectedNode.bridgeType === 'HTTP'">
          <span class="detail-label">HTTP 方法</span>
          <span class="detail-value">{{ selectedNode.bridgeInfo?.httpMethod || '-' }}</span>
        </div>
        <div class="detail-row" v-if="selectedNode.bridgeType === 'HTTP' && selectedNode.bridgeInfo?.httpUri">
          <span class="detail-label">URI</span>
          <span class="detail-value">{{ selectedNode.bridgeInfo.httpUri }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="queryUpstreamFromDialog" type="primary" plain>
          向上查询调用链
        </el-button>
        <el-button @click="queryDownstreamFromDialog" type="success" plain>
          向下查询调用链
        </el-button>
        <el-button @click="showDetail = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 递归查询结果弹窗 -->
    <el-dialog
      v-model="showRecursiveResult"
      :title="recursiveTitle"
      width="800px"
      :close-on-click-modal="false"
    >
      <div class="recursive-content" v-loading="loadingRecursive">
        <div v-if="recursiveData.length === 0 && !loadingRecursive" class="no-result">
          未找到相关数据
        </div>
        <!-- 向上查询 - 入口 + 直接调用方列表 -->
        <div v-else-if="isUpstreamQuery" class="uri-list">
          <div class="uri-count">
            共找到 {{ recursiveData.length }} 项（其中根入口 {{ entryCount }} 个，直接调用方 {{ recursiveData.length - entryCount }} 个）
          </div>
          <div
            v-for="(item, index) in recursiveData"
            :key="index"
            class="uri-item"
            :class="{ 'is-entry': item.isEntry }"
          >
            <span class="uri-index">{{ index + 1 }}</span>
            <span class="uri-text">{{ item.uri || item.rootUri || item.method || item.name }}</span>
            <span class="uri-meta" v-if="item.callLine">L{{ item.callLine }}</span>
            <span class="uri-meta" v-if="item.callType && !item.isEntry">{{ item.callType }}</span>
            <span class="uri-tag entry-tag" v-if="item.isEntry">入口</span>
          </div>
        </div>
        <!-- 向下查询 - 调用链（树形展示） -->
        <div v-else class="recursive-list">
          <div class="uri-count">
            共 {{ recursiveData.length }} 个节点，最大深度 {{ downstreamMaxDepth }} 层
          </div>
          <div
            v-for="(node, index) in recursiveData"
            :key="index"
            class="tree-row"
            :class="{ 'tree-cycle': node.isNoMatch }"
            :style="{ paddingLeft: ((node.depth || 0) * 24) + 'px' }"
          >
            <span class="tree-connector" v-if="(node.depth || 0) > 0">
              {{ '│  '.repeat((node.depth || 0) - 1) }}├─
            </span>
            <span class="tree-depth-badge" :title="`深度 ${node.depth || 0}`">{{ node.depth || 0 }}</span>
            <span class="tree-method">{{ node.method || `${node.className || ''}.${node.name}` }}</span>
            <span class="uri-meta" v-if="node.callType">{{ node.callType }}</span>
            <span class="uri-tag cycle-tag" v-if="node.isNoMatch">环</span>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import TreeNode from './TreeNode.vue'
import { ElMessage } from 'element-plus'
import { knowledgeGraphApi, type CallerInfo, type RootEntryInfo, type CallChainGraphData } from '@/api/knowledgeGraph'
import * as echarts from 'echarts'

// 桥接类型定义
type BridgeType = 'MAPPER' | 'JPA' | 'MQ' | 'FEIGN' | 'HTTP' | 'ASPECT' | 'DIRECT'

interface BridgeInfo {
  bridgeType: BridgeType
  sqlId?: string
  targetService?: string
  targetEndpoint?: string
  topic?: string
  httpMethod?: string
  httpUri?: string
}

interface ChainNode {
  name: string
  className?: string
  methodSignature?: string
  methodBody?: string
  description?: string
  isNoMatch?: boolean
  children?: ChainNode[]
  id?: string
  depth?: number
  method?: string
  package?: string
  uri?: string
  rootUri?: string
  nodeId?: string
  callType?: string
  callLine?: number
  isEntry?: boolean
  // 桥接信息
  bridgeType?: BridgeType
  bridgeInfo?: BridgeInfo
}

// 入口类型 → 中文/图标 显示
const formatEntryKey = (entryType: string, entryKey: string): string => {
  // entryKey 已经包含可读信息，按类型加前缀图标方便辨识
  switch (entryType) {
    case 'HTTP': return `🌐 ${entryKey}`              // e.g. "POST /api/xxx"
    case 'MQ': return `📨 ${entryKey}`                // e.g. "MQ:topic/group"
    case 'EVENT': return `⚡ ${entryKey}`             // e.g. "EVENT:ApplicationReadyEvent.class"
    case 'SCHEDULED': return `⏰ ${entryKey}`         // e.g. "SCHEDULED:com.x.Y.method"
    case 'LIFECYCLE': return `🔄 ${entryKey}`         // e.g. "LIFECYCLE:com.x.Y.shutdown"
    case 'FEIGN': return `🔌 ${entryKey}`             // e.g. "FEIGN:service.method"
    case 'FEIGN_CLIENT': return `🔌 ${entryKey}`
    case 'MQ_LISTENER': return `📨 ${entryKey}`
    case 'CONTROLLER': return `🌐 ${entryKey}`
    default: return `[${entryType}] ${entryKey}`
  }
}

// 把 CallChainGraphData 拍平为 DFS 顺序的 ChainNode[]，每行携带正确 depth
const flattenDownstreamGraph = (graph: CallChainGraphData): ChainNode[] => {
  // 邻接表（保留 edges 的顺序，方便稳定输出）
  const adjacency = new Map<string, string[]>()
  for (const edge of graph.edges) {
    if (!adjacency.has(edge.source)) adjacency.set(edge.source, [])
    adjacency.get(edge.source)!.push(edge.target)
  }

  const nodeMap = new Map<string, typeof graph.nodes[number]>()
  for (const n of graph.nodes) nodeMap.set(n.id, n)

  // 找入口（depth=0），通常只有一个
  const root = graph.nodes.find(n => n.depth === 0) ?? graph.nodes[0]
  if (!root) return []

  const out: ChainNode[] = []
  const visited = new Set<string>()

  const dfs = (id: string, depth: number) => {
    if (visited.has(id)) {
      // DAG 中重复访问的节点：仍显示一行，标记为环/重复
      const dup = nodeMap.get(id)
      if (dup) {
        out.push({
          name: dup.name,
          method: `${dup.className}.${dup.name}`,
          className: dup.className,
          nodeId: id,
          depth,
          callType: dup.callType,
          isNoMatch: dup.inCycle
        })
      }
      return
    }
    visited.add(id)

    const node = nodeMap.get(id)
    if (!node) return

    out.push({
      name: node.name,
      method: `${node.className}.${node.name}`,
      className: node.className,
      nodeId: id,
      depth,
      callType: node.callType,
      isNoMatch: node.inCycle
    })

    const children = adjacency.get(id) ?? []
    for (const child of children) {
      dfs(child, depth + 1)
    }
  }

  dfs(root.id, 0)
  return out
}

const props = defineProps<{
  data: ChainNode | null
  loading?: boolean
  projectPath?: string
  projectPaths?: string[]
}>()

const emit = defineEmits<{
  (e: 'node-click', node: ChainNode, event: MouseEvent): void
  (e: 'recursive-query', type: 'upstream' | 'downstream', method: string, className: string): void
  (e: 'navigate-method-ref', direction: 'up' | 'down', className: string, methodName: string): void
}>()

const viewMode = ref<'tree' | 'flow' | 'list' | 'dag'>('tree')
const searchText = ref('')
const showExternal = ref(false)
const showDetail = ref(false)
const selectedNode = ref<ChainNode | null>(null)

// DAG视图相关
const dagChartRef = ref<HTMLElement | null>(null)
const highlightCycles = ref(true)
const cycleCount = ref(0)
const dagData = ref<any>(null)
let dagChart: echarts.ECharts | null = null

// 右键菜单状态
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  node: null as ChainNode | null
})

// 递归查询状态
const loadingRecursive = ref(false)
const showRecursiveResult = ref(false)
const recursiveTitle = ref('')
const recursiveData = ref<ChainNode[]>([])
const isUpstreamQuery = ref(false)
const entryCount = computed(() => recursiveData.value.filter(item => item.isEntry).length)
const downstreamMaxDepth = computed(() =>
  recursiveData.value.reduce((max, item) => Math.max(max, item.depth ?? 0), 0)
)

// 扁平化节点
// 由于调用链可能有多个路径到达同一方法，树结构中会有重复的方法（不同实例）
// 每个实例有唯一 ID，所以 visited 检查只防止无限循环
const flatNodes = computed(() => {
  const nodes: (ChainNode & { id: string; depth: number })[] = []
  let idCounter = 0

  const flatten = (node: ChainNode, pathDepth: number) => {
    // 节点 ID 应该是唯一的（convertGraphToTree 已确保）
    const nodeId = node.id || `node_${idCounter++}_${node.name}`

    // 使用节点自身的 depth 属性（来自后端 BFS 计算）
    const nodeDepth = node.depth !== undefined ? node.depth : pathDepth
    nodes.push({ ...node, id: nodeId, depth: nodeDepth })

    // 递归处理子节点
    if (node.children && node.children.length > 0) {
      node.children.forEach(child => flatten(child, nodeDepth + 1))
    }
  }

  if (props.data) flatten(props.data, 0)
  return nodes
})

// 过滤节点
const filteredNodes = computed(() => {
  let nodes = flatNodes.value

  if (!showExternal.value) {
    nodes = nodes.filter(n => !n.isNoMatch)
  }

  if (searchText.value) {
    const keyword = searchText.value.toLowerCase()
    nodes = nodes.filter(n =>
      n.name.toLowerCase().includes(keyword) ||
      (n.className && n.className.toLowerCase().includes(keyword))
    )
  }

  return nodes
})

// 根节点
const rootNodes = computed(() => {
  if (!props.data) return []
  // 保留原始 id，只确保 depth 为 0
  return [{ ...props.data, depth: 0 }]
})

// 统计
const projectCount = computed(() => flatNodes.value.filter(n => !n.isNoMatch).length)
const externalCount = computed(() => flatNodes.value.filter(n => n.isNoMatch).length)
const maxDepth = computed(() => Math.max(...flatNodes.value.map(n => n.depth), 0))

// 流程图 - 深度范围
const flowDepthRange = computed(() => {
  const range: number[] = []
  for (let i = 0; i <= maxDepth.value; i++) {
    range.push(i)
  }
  return range
})

// 获取指定深度的节点
const getNodesByDepth = (depth: number) => {
  return filteredNodes.value.filter(n => n.depth === depth)
}

// 工具函数
const isMatch = (node: ChainNode) => {
  if (!searchText.value) return false
  return node.name.toLowerCase().includes(searchText.value.toLowerCase())
}

const highlightText = (text: string) => {
  if (!searchText.value) return text
  return text.replace(new RegExp(`(${searchText.value})`, 'gi'), '<mark>$1</mark>')
}

const shortClass = (className: string) => {
  const parts = className.split('.')
  return parts.length > 2 ? '...' + parts.slice(-2).join('.') : className
}

// 桥接类型相关工具函数
const getBridgeNodeColor = (node: ChainNode): string => {
  if (!node.bridgeType) return '#409eff' // 默认蓝色

  const colorMap: Record<BridgeType, string> = {
    'MAPPER': '#67c23a',    // 绿色
    'JPA': '#409eff',        // 蓝色
    'MQ': '#e6a23c',         // 紫色
    'FEIGN': '#f56c6c',      // 橙色
    'HTTP': '#f5d44d',       // 黄色
    'ASPECT': '#b37feb',     // 紫色
    'DIRECT': '#409eff'      // 蓝色
  }
  return colorMap[node.bridgeType] || '#409eff'
}

const getBridgeTagType = (type: BridgeType): '' | 'success' | 'warning' | 'danger' | 'info' => {
  const typeMap: Record<BridgeType, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    'MAPPER': 'success',
    'JPA': 'primary',
    'MQ': 'warning',
    'FEIGN': 'danger',
    'HTTP': 'info',
    'ASPECT': '',
    'DIRECT': 'info'
  }
  return typeMap[type] || 'info'
}

const getBridgeIcon = (type: BridgeType): string => {
  const iconMap: Record<BridgeType, string> = {
    'MAPPER': 'SQL',
    'JPA': 'JPA',
    'MQ': 'MQ',
    'FEIGN': 'RPC',
    'HTTP': 'HTTP',
    'ASPECT': 'AOP',
    'DIRECT': 'CALL'
  }
  return iconMap[type] || 'BRIDGE'
}

const getBridgeDescription = (node: ChainNode): string => {
  if (!node.bridgeType) return ''

  switch (node.bridgeType) {
    case 'MAPPER':
      return node.bridgeInfo?.sqlId ? `SQL: ${node.bridgeInfo.sqlId}` : 'Mapper SQL'
    case 'FEIGN':
      return node.bridgeInfo?.targetService
        ? `Feign: ${node.bridgeInfo.targetService}${node.bridgeInfo.targetEndpoint || ''}`
        : 'Feign 调用'
    case 'MQ':
      return node.bridgeInfo?.topic ? `MQ Topic: ${node.bridgeInfo.topic}` : 'MQ 消息'
    case 'HTTP':
      return node.bridgeInfo?.httpUri ? `HTTP: ${node.bridgeInfo.httpMethod || 'GET'} ${node.bridgeInfo.httpUri}` : 'HTTP 调用'
    case 'JPA':
      return 'JPA 操作'
    case 'ASPECT':
      return 'AOP 切面'
    default:
      return ''
  }
}

const handleSelect = (node: ChainNode) => {
  selectedNode.value = node
  showDetail.value = true
  emit('node-click', node, new MouseEvent('click'))
}

// 右键菜单处理
const handleContextMenu = (payload: { event: MouseEvent; node: ChainNode } | MouseEvent, node?: ChainNode) => {
  // 兼容两种调用方式
  let event: MouseEvent
  let targetNode: ChainNode

  if ('event' in payload && 'node' in payload) {
    event = payload.event
    targetNode = payload.node
  } else {
    event = payload as MouseEvent
    targetNode = node!
  }

  event.preventDefault()
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    node: targetNode
  }
}

const hideContextMenu = () => {
  contextMenu.value.visible = false
}

// 构建方法签名
const buildMethodSignature = (node: ChainNode): string => {
  if (!node) return ''
  const className = node.className || ''
  const methodName = node.name || ''
  return className ? `${className}.${methodName}` : methodName
}

// 向上查询
const queryUpstream = async () => {
  hideContextMenu()
  const node = contextMenu.value.node
  if (!node) return

  const method = buildMethodSignature(node)
  await doRecursiveQuery('upstream', method)
}

// 向下查询
const queryDownstream = async () => {
  hideContextMenu()
  const node = contextMenu.value.node
  if (!node) return

  const method = buildMethodSignature(node)
  await doRecursiveQuery('downstream', method)
}

// 跳转到方法引用分析页面 - 向上
const goToUpstream = () => {
  hideContextMenu()
  const node = contextMenu.value.node
  if (!node) return
  emit('navigate-method-ref', 'up', node.className || '', node.name || '')
}

// 跳转到方法引用分析页面 - 向下
const goToDownstream = () => {
  hideContextMenu()
  const node = contextMenu.value.node
  if (!node) return
  emit('navigate-method-ref', 'down', node.className || '', node.name || '')
}

// 查看详情
const viewDetails = () => {
  hideContextMenu()
  if (contextMenu.value.node) {
    handleSelect(contextMenu.value.node)
  }
}

// 列表视图中的快速查询
const queryUpstreamForNode = (node: ChainNode) => {
  const method = buildMethodSignature(node)
  doRecursiveQuery('upstream', method)
}

const queryDownstreamForNode = (node: ChainNode) => {
  const method = buildMethodSignature(node)
  doRecursiveQuery('downstream', method)
}

// 从详情弹窗向上查询
const queryUpstreamFromDialog = async () => {
  if (!selectedNode.value) return
  const method = buildMethodSignature(selectedNode.value)
  showDetail.value = false
  await doRecursiveQuery('upstream', method)
}

// 从详情弹窗向下查询
const queryDownstreamFromDialog = async () => {
  if (!selectedNode.value) return
  const method = buildMethodSignature(selectedNode.value)
  showDetail.value = false
  await doRecursiveQuery('downstream', method)
}

// 执行递归查询
const doRecursiveQuery = async (type: 'upstream' | 'downstream', method: string) => {
  loadingRecursive.value = true
  showRecursiveResult.value = true
  isUpstreamQuery.value = type === 'upstream'
  recursiveTitle.value = type === 'upstream' ? '向上查询 - 调用接口列表' : '向下调用链查询结果'
  recursiveData.value = []

  try {
    // 解析方法签名: className.methodName
    const lastDot = method.lastIndexOf('.')
    const className = method.substring(0, lastDot)
    const methodName = method.substring(lastDot + 1)

    // 优先使用 projectPaths（多项目跨范围查找），回退到单 projectPath
    const paths = (props.projectPaths && props.projectPaths.length > 0)
      ? props.projectPaths
      : (props.projectPath ? [props.projectPath] : [])
    if (paths.length === 0) {
      ElMessage.error('查询失败：未指定项目路径（请在项目管理中选择项目）')
      return
    }
    const primaryPath = paths[0]

    // 使用知识图谱 API（已通过 request 工具自动解包 ApiResponse）
    if (type === 'upstream') {
      // 单次调用：root-entries 返回根入口 + 直接调用方
      const resp = await knowledgeGraphApi.getRootEntries(className, methodName, primaryPath, paths) as unknown as { rootEntries: RootEntryInfo[]; directCallers: CallerInfo[] }
      const roots = resp?.rootEntries ?? []
      const callers = resp?.directCallers ?? []

      const rootRows = (Array.isArray(roots) ? roots : []).map((r) => ({
        method: formatEntryKey(r.entryType, r.entryKey),
        nodeId: r.entryId,
        callType: r.entryType,
        isEntry: true
      })) as unknown as ChainNode[]

      const callerRows = (Array.isArray(callers) ? callers : []).map((item) => ({
        method: `${item.callerClassName}.${item.callerMethodName}`,
        nodeId: item.callerId,
        callType: item.callType,
        callLine: item.callLine
      })) as unknown as ChainNode[]

      // 根入口在前，直接调用方在后
      recursiveData.value = [...rootRows, ...callerRows]

      if (recursiveData.value.length === 0) {
        ElMessage.info('未找到相关数据')
      }
    } else {
      // 向下：调用 callees-tree 拿完整树（带 depth）
      const graph = await knowledgeGraphApi.getCalleesTree(className, methodName, primaryPath, 10, paths) as unknown as CallChainGraphData
      if (!graph || !Array.isArray(graph.nodes) || graph.nodes.length === 0) {
        recursiveData.value = []
        ElMessage.info('未找到相关数据')
      } else {
        recursiveData.value = flattenDownstreamGraph(graph)
      }
    }
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : '未知错误'
    ElMessage.error(`查询失败: ${message}`)
  } finally {
    loadingRecursive.value = false
  }

  emit('recursive-query', type, method, selectedNode.value?.className || '')
}

// 点击其他地方隐藏右键菜单
const handleClickOutside = () => {
  hideContextMenu()
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  if (dagChart) {
    dagChart.dispose()
    dagChart = null
  }
})

// DAG 视图初始化
const initDagChart = () => {
  if (!dagChartRef.value) return

  if (dagChart) {
    dagChart.dispose()
  }

  dagChart = echarts.init(dagChartRef.value)

  // 从 flatNodes 构建 DAG 数据
  const nodes = filteredNodes.value.map(node => ({
    id: node.id,
    name: node.name,
    className: node.className,
    depth: node.depth,
    description: node.description,
    bridgeType: node.bridgeType,
    bridgeInfo: node.bridgeInfo,
    symbolSize: 30 + node.depth * 5,
    category: node.bridgeType ? `bridge_${node.bridgeType.toLowerCase()}` : (node.isNoMatch ? 'external' : 'normal'),
    itemStyle: {
      color: node.bridgeType ? getBridgeNodeColor(node) : (node.isNoMatch ? '#f0a020' : '#409eff'),
      borderColor: node.bridgeType ? getBridgeNodeColor(node) : (node.isNoMatch ? '#f0a020' : '#409eff'),
      borderWidth: node.bridgeType ? 2 : 1
    },
    label: {
      show: true,
      formatter: node.bridgeType ? `${getBridgeIcon(node.bridgeType)}: ${node.name}` : node.name,
      fontSize: 12
    }
  }))

  // 构建边（父子关系）
  const edges: any[] = []
  flatNodes.value.forEach(node => {
    if (node.children) {
      node.children.forEach(child => {
        const childNode = flatNodes.value.find(n => n.name === child.name && n.depth === node.depth + 1)
        if (childNode) {
          edges.push({
            source: node.id,
            target: childNode.id,
            lineStyle: {
              color: '#909399',
              width: 1,
              curveness: 0.1
            }
          })
        }
      })
    }
  })

  const option = {
    title: {
      text: '调用链 DAG 图',
      left: 'center',
      top: 10
    },
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        if (params.dataType === 'node') {
          const bridgeInfo = params.data.bridgeType
            ? `<br/><span style="color: #67c23a;">桥接: ${params.data.bridgeType}</span>`
            : ''
          const descriptionHtml = params.data.description
            ? `<div style="margin-top: 6px; padding-top: 6px; border-top: 1px solid #eee; color: #666; font-size: 12px;">${params.data.description}</div>`
            : ''
          return `<div class="node-tooltip">
            <div style="font-weight: bold; font-size: 14px;">${params.data.name}</div>
            <div style="color: #909399; font-size: 12px;">类: ${params.data.className || '-'}</div>
            <div style="color: #606266; font-size: 12px;">深度: ${params.data.depth}</div>${bridgeInfo}
            ${descriptionHtml}
          </div>`
        }
        return `${params.data.source} -> ${params.data.target}`
      }
    },
    legend: [{
      data: ['普通方法', '外部依赖', 'Mapper', 'JPA', 'MQ', 'Feign', 'HTTP', 'Aspect'],
      top: 40
    }],
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodes,
      edges: edges,
      roam: true,
      draggable: true,
      force: {
        repulsion: 200,
        edgeLength: 100,
        gravity: 0.1
      },
      categories: [{
        name: 'normal',
        itemStyle: { color: '#409eff' }
      }, {
        name: 'external',
        itemStyle: { color: '#f0a020' }
      }, {
        name: 'bridge_mapper',
        itemStyle: { color: '#67c23a' }
      }, {
        name: 'bridge_jpa',
        itemStyle: { color: '#409eff' }
      }, {
        name: 'bridge_mq',
        itemStyle: { color: '#e6a23c' }
      }, {
        name: 'bridge_feign',
        itemStyle: { color: '#f56c6c' }
      }, {
        name: 'bridge_http',
        itemStyle: { color: '#f5d44d' }
      }, {
        name: 'bridge_aspect',
        itemStyle: { color: '#b37feb' }
      }],
      label: {
        show: true,
        position: 'bottom'
      },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [4, 10],
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 5 }
      }
    }]
  }

  dagChart.setOption(option)

  // 设置 dagData 标记已加载数据
  dagData.value = { nodes: nodes.length, edges: edges.length }

  // 点击事件
  dagChart.on('click', (params: any) => {
    if (params.dataType === 'node') {
      const node = filteredNodes.value.find(n => n.id === params.data.id)
      if (node) {
        handleSelect(node)
      }
    }
  })
}

// 监听视图模式变化
watch(viewMode, async (mode) => {
  if (mode === 'dag') {
    await nextTick()
    initDagChart()
  }
})

// 监听数据变化，更新 DAG
watch([flatNodes, filteredNodes], () => {
  if (viewMode.value === 'dag' && dagChartRef.value) {
    initDagChart()
  }
}, { deep: true })
</script>

<style scoped>
.chain-container {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
  position: relative;
}

/* 工具栏 */
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-bottom: 1px solid #eee;
  flex-wrap: wrap;
  gap: 12px;
  background: #fafafa;
}

/* 统计栏 */
.stats-bar {
  display: flex;
  gap: 24px;
  padding: 12px 20px;
  background: #f0f5ff;
  border-bottom: 1px solid #d6e4ff;
}

.stat-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
  color: #333;
}

.stat-item.project .stat-value { color: #18a058; }
.stat-item.external .stat-value { color: #f0a020; }
.stat-item.depth .stat-value { color: #2080f0; }

.stat-label {
  font-size: 13px;
  color: #666;
}

/* 内容区域 */
.content-area {
  flex: 1;
  overflow: auto;
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
  font-size: 14px;
}

/* 树形视图 */
.tree-view {
  padding: 16px;
}

/* 流程图视图 */
.flow-view {
  padding: 20px;
  background: linear-gradient(180deg, #f8fbff 0%, #fff 100%);
}

.flow-container {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.flow-level {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 8px;
}

.level-header {
  margin-bottom: 12px;
}

.level-badge {
  display: inline-block;
  padding: 4px 16px;
  background: linear-gradient(135deg, #409eff 0%, #66b1ff 100%);
  color: #fff;
  border-radius: 16px;
  font-size: 12px;
  font-weight: 500;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.3);
}

.level-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: center;
  padding: 0 20px;
}

.flow-node {
  position: relative;
  padding: 14px 18px;
  min-width: 180px;
  max-width: 280px;
  background: #fff;
  border: 2px solid #e4e7ed;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.flow-node:hover {
  border-color: #409eff;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.2);
  transform: translateY(-2px);
}

.flow-node.is-external {
  background: linear-gradient(135deg, #fffbe6 0%, #fff9e0 100%);
  border-color: #f5d44d;
}

.flow-node.is-match {
  border-color: #18a058;
  background: linear-gradient(135deg, #f0fff4 0%, #e8ffec 100%);
}

.flow-node.is-root {
  border-color: #409eff;
  background: linear-gradient(135deg, #e8f4ff 0%, #d6e9ff 100%);
}

/* 桥接节点样式 */
.flow-node.is-mapper {
  background: linear-gradient(135deg, #f0fff4 0%, #e8ffec 100%);
}

.flow-node.is-feign {
  background: linear-gradient(135deg, #fef0f0 0%, #fde2e2 100%);
}

.flow-node.is-mq {
  background: linear-gradient(135deg, #fdf6ec 0%, #faecd8 100%);
}

.flow-node.is-http {
  background: linear-gradient(135deg, #fffbf0 0%, #fff7e6 100%);
}

.flow-node.is-jpa {
  background: linear-gradient(135deg, #ecf5ff 0%, #d9ecff 100%);
}

.flow-node.is-aspect {
  background: linear-gradient(135deg, #f9f0ff 0%, #efdbff 100%);
}

.node-icon {
  position: absolute;
  top: -10px;
  left: 50%;
  transform: translateX(-50%);
  padding: 2px 12px;
  background: #409eff;
  color: #fff;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.node-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-top: 4px;
  word-break: break-all;
}

.node-class {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.node-description {
  font-size: 11px;
  color: #606266;
  margin-top: 4px;
  padding: 2px 6px;
  background: #f0f7ff;
  border-left: 2px solid #409eff;
  border-radius: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

.node-tag {
  display: inline-block;
  margin-top: 8px;
  padding: 2px 8px;
  background: #f0a020;
  color: #fff;
  border-radius: 4px;
  font-size: 11px;
}

.node-bridge-badge {
  position: absolute;
  top: -8px;
  right: -8px;
  padding: 2px 6px;
  color: #fff;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
}

.node-bridge-info {
  font-size: 11px;
  color: #606266;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #dcdfe6;
  word-break: break-all;
}

.level-arrow {
  margin: 8px 0;
  animation: bounce 1s infinite;
}

@keyframes bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(4px); }
}

/* 列表视图 */
.list-view {
  padding: 0;
}

.list-table {
  width: 100%;
  border-collapse: collapse;
}

.list-header {
  display: flex;
  align-items: center;
  background: #f5f7fa;
  border-bottom: 2px solid #e4e7ed;
  font-weight: 600;
  color: #606266;
  position: sticky;
  top: 0;
  z-index: 10;
}

.list-header > div {
  padding: 12px 16px;
  text-align: left;
}

.list-body {
  max-height: calc(100vh - 280px);
  overflow-y: auto;
}

.list-row {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #ebeef5;
  transition: background 0.2s;
}

.list-row:hover {
  background: #f5f7fa;
}

.list-row.is-external {
  background: #fffbf0;
}

.list-row.is-external:hover {
  background: #fff7e6;
}

.list-row.is-match {
  background: #f0fff4;
}

.list-row.depth-0 {
  background: #e8f4ff;
}

.list-row.depth-0:hover {
  background: #d6e9ff;
}

.col-depth {
  width: 80px;
  padding: 10px 16px;
  text-align: center;
}

.depth-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: linear-gradient(135deg, #409eff 0%, #66b1ff 100%);
  color: #fff;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
}

.col-method {
  flex: 1;
  display: flex;
  align-items: center;
  padding: 10px 16px;
  min-width: 0;
}

.method-indent {
  flex-shrink: 0;
  border-left: 2px solid #d9ecff;
  height: 100%;
  margin-right: 8px;
}

.method-name {
  cursor: pointer;
  color: #303133;
  font-weight: 500;
}

.method-name:hover {
  color: #409eff;
}

.method-name :deep(mark) {
  background: #ffe066;
  padding: 0 2px;
  border-radius: 2px;
}

.col-class {
  width: 200px;
  padding: 10px 16px;
  color: #909399;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-type {
  width: 100px;
  padding: 10px 16px;
  text-align: center;
}

.col-action {
  width: 160px;
  padding: 10px 16px;
  text-align: right;
}

/* 右键菜单 */
.context-menu {
  position: fixed;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  padding: 8px 0;
  min-width: 180px;
  z-index: 9999;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  cursor: pointer;
  transition: background 0.2s;
  font-size: 14px;
  color: #303133;
}

.menu-item:hover {
  background: #f5f7fa;
}

.menu-icon {
  font-size: 14px;
  width: 20px;
  text-align: center;
}

.menu-divider {
  height: 1px;
  background: #eee;
  margin: 6px 0;
}

/* 详情弹窗 */
.detail-content {
  padding: 0 8px;
}

.detail-row {
  margin-bottom: 20px;
}

.detail-label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}

.detail-value {
  font-size: 14px;
  color: #303133;
}

.detail-value.name {
  font-size: 16px;
  font-weight: 600;
  color: #409eff;
}

.detail-value.description {
  color: #606266;
  font-size: 13px;
  line-height: 1.6;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  border-left: 3px solid #409eff;
}

.detail-code {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  font-family: monospace;
  font-size: 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 150px;
}

.detail-code.body {
  max-height: 300px;
}

/* 递归查询结果 */
.recursive-content {
  max-height: 500px;
  overflow-y: auto;
}

.no-result {
  text-align: center;
  padding: 40px;
  color: #909399;
}

.recursive-list {
  padding: 8px;
}

.recursive-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 8px;
  border-left: 3px solid #409eff;
}

.recursive-depth {
  min-width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #409eff;
  color: #fff;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.recursive-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.recursive-package {
  font-size: 12px;
  color: #909399;
  margin-left: auto;
}

/* 树形展示（向下调用链） */
.tree-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  font-family: monospace;
  font-size: 13px;
  border-bottom: 1px solid #f0f2f5;
  white-space: nowrap;
  overflow-x: auto;
}

.tree-row:hover {
  background: #f5f7fa;
}

.tree-row.tree-cycle {
  background: #fff7e6;
}

.tree-connector {
  color: #c0c4cc;
  white-space: pre;
  flex-shrink: 0;
}

.tree-depth-badge {
  min-width: 22px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.tree-method {
  color: #303133;
  flex: 1;
  word-break: break-all;
}

.uri-tag.cycle-tag {
  font-size: 12px;
  color: #fff;
  background: #e6a23c;
  padding: 2px 8px;
  border-radius: 10px;
  margin-left: 8px;
  flex-shrink: 0;
}

/* URI 列表样式 */
.uri-list {
  padding: 8px;
}

.uri-count {
  font-size: 14px;
  color: #606266;
  margin-bottom: 16px;
  padding-left: 8px;
}

.uri-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 8px;
  border-left: 3px solid #409eff;
  transition: all 0.2s;
}

.uri-item:hover {
  background: #e8f4ff;
  border-left-color: #66b1ff;
}

.uri-index {
  min-width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #409eff;
  color: #fff;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 500;
}

.uri-text {
  font-size: 14px;
  font-family: monospace;
  color: #303133;
  word-break: break-all;
  flex: 1;
}

.uri-meta {
  font-size: 12px;
  color: #909399;
  background: #f0f2f5;
  padding: 2px 6px;
  border-radius: 3px;
  margin-left: 8px;
  font-family: monospace;
  flex-shrink: 0;
}

.uri-item.is-entry {
  background: #f0f9ff;
  border-left: 3px solid #409eff;
}

.uri-tag.entry-tag {
  font-size: 12px;
  color: #fff;
  background: #409eff;
  padding: 2px 8px;
  border-radius: 10px;
  margin-left: 8px;
  flex-shrink: 0;
}

/* DAG 图视图 */
.dag-view {
  flex: 1;
  position: relative;
  min-height: 500px;
  background: #fafafa;
}

.dag-chart {
  width: 100%;
  height: 100%;
  min-height: 500px;
}

.dag-empty {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

/* 环节点高亮样式 */
:deep(.cycle-node) {
  border: 3px dashed #f56c6c !important;
  background: linear-gradient(135deg, #fff5f5 0%, #ffe8e8 100%) !important;
}

:deep(.cycle-edge) {
  stroke: #f56c6c !important;
  stroke-dasharray: 5, 5 !important;
}

/* 桥接标签样式 */
.bridge-tag {
  font-weight: 600;
}

/* 详情弹窗桥接信息样式 */
.bridge-info-row {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 16px;
}

.bridge-info-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.bridge-description {
  font-size: 13px;
  color: #606266;
}

.sql-id {
  font-family: monospace;
  font-size: 13px;
  color: #67c23a;
}
</style>