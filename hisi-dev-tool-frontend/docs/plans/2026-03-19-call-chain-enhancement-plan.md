# 调用链分析页面增强功能实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 增强调用链分析功能，新增方法引用关系分析页面，支持 URI 下拉选择和节点右键菜单交互。

**Architecture:** 后端新增类名和方法列表查询API；前端改造 CallChainGraph 页面支持 URI 下拉选择、Tree/Graph 视图切换、右键菜单；新增 MethodReferenceGraph 页面支持类方法选择和调用关系查询。

**Tech Stack:** Spring Boot 3.2.0, Vue 3.5+, TypeScript, Element Plus, ECharts

---

## Task 1: 后端 - 新增类名列表查询接口

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/CallChainController.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/CallChainService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/impl/CallChainServiceImpl.java`

**Step 1: 在 CallChainService 接口添加方法**

```java
/**
 * 获取项目中的类名列表
 * @param project 项目名称
 * @param projectDir 项目目录（可选）
 * @return 类名列表
 */
List<Map<String, Object>> listClasses(String project, String projectDir);
```

**Step 2: 在 CallChainServiceImpl 实现方法**

查询 method_call_graph5 表中的去重类名：

```java
@Override
public List<Map<String, Object>> listClasses(String project, String projectDir) {
    String sql = "SELECT DISTINCT caller_class as className, " +
                 "COUNT(DISTINCT caller_method) as methodCount " +
                 "FROM method_call_graph5 " +
                 "WHERE caller_class IS NOT NULL AND caller_class != '' " +
                 "GROUP BY caller_class ORDER BY caller_class";
    return jdbcTemplate.queryForList(sql);
}
```

**Step 3: 在 CallChainController 添加端点**

```java
/**
 * 获取类名列表
 * GET /api/callchain/classes?project={project}
 */
@GetMapping("/classes")
public ApiResponse<List<Map<String, Object>>> listClasses(
        @RequestParam(required = false) String project,
        @RequestParam(required = false) String projectDir) {
    List<Map<String, Object>> classes = callChainService.listClasses(project, projectDir);
    return ApiResponse.success(classes);
}
```

**Step 4: 构建并测试**

```bash
cd hisi-dev-tool
mvn clean package -Dmaven.test.skip=true
# 启动后测试
curl -s "http://localhost:8080/api/callchain/classes"
```

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/CallChainController.java
git add src/main/java/com/huawei/hisi/service/CallChainService.java
git add src/main/java/com/huawei/hisi/service/impl/CallChainServiceImpl.java
git commit -m "feat: add classes list API for call chain analysis"
```

---

## Task 2: 后端 - 新增方法列表查询接口

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/CallChainController.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/CallChainService.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/impl/CallChainServiceImpl.java`

**Step 1: 在 CallChainService 接口添加方法**

```java
/**
 * 获取类中的方法列表
 * @param className 类名
 * @param project 项目名称（可选）
 * @param projectDir 项目目录（可选）
 * @return 方法列表
 */
List<Map<String, Object>> listMethods(String className, String project, String projectDir);
```

**Step 2: 在 CallChainServiceImpl 实现方法**

```java
@Override
public List<Map<String, Object>> listMethods(String className, String project, String projectDir) {
    String sql = "SELECT DISTINCT caller_method as methodName, " +
                 "caller_signature as signature " +
                 "FROM method_call_graph5 " +
                 "WHERE caller_class = ? " +
                 "ORDER BY caller_method";
    return jdbcTemplate.queryForList(sql, className);
}
```

**Step 3: 在 CallChainController 添加端点**

```java
/**
 * 获取类中的方法列表
 * GET /api/callchain/methods?className={className}
 */
@GetMapping("/methods")
public ApiResponse<List<Map<String, Object>>> listMethods(
        @RequestParam String className,
        @RequestParam(required = false) String project,
        @RequestParam(required = false) String projectDir) {
    List<Map<String, Object>> methods = callChainService.listMethods(className, project, projectDir);
    return ApiResponse.success(methods);
}
```

**Step 4: 构建并测试**

```bash
curl -s "http://localhost:8080/api/callchain/methods?className=com.huawei.hisi.controller.CallChainController"
```

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/CallChainController.java
git add src/main/java/com/huawei/hisi/service/CallChainService.java
git add src/main/java/com/huawei/hisi/service/impl/CallChainServiceImpl.java
git commit -m "feat: add methods list API for call chain analysis"
```

---

## Task 3: 前端 - 新增 API 调用方法

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/callChain.ts`

**Step 1: 添加新的 API 方法**

```typescript
import request from '@/utils/request'

export const callChainApi = {
  // 获取项目列表
  getProjects() {
    return request.get('/callchain/projects')
  },

  // 获取URI列表
  getUris(params: { project: string }) {
    return request.get('/callchain/uris', { params })
  },

  // 获取调用链数据
  getCalls(params: { project: string; uri: string }) {
    return request.get('/callchain/calls', { params })
  },

  // 搜索方法或类
  search(params: { project: string; keyword: string }) {
    return request.get('/callchain/search', { params })
  },

  // 获取类名列表（新增）
  getClasses(params?: { project?: string }) {
    return request.get('/callchain/classes', { params })
  },

  // 获取类中的方法列表（新增）
  getMethods(params: { className: string; project?: string }) {
    return request.get('/callchain/methods', { params })
  },

  // 获取上游调用链
  getCallers(params: { method: string; maxDepth?: number; project?: string }) {
    return request.get('/callchain/callers', { params })
  },

  // 获取下游调用链
  getCallees(params: { method: string; maxDepth?: number; project?: string }) {
    return request.get('/callchain/callees', { params })
  }
}
```

**Step 2: Commit**

```bash
git add src/api/callChain.ts
git commit -m "feat: add getClasses and getMethods API methods"
```

---

## Task 4: 前端 - 创建 URI 选择器组件

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/components/UriSelector.vue`

**Step 1: 创建 UriSelector 组件**

```vue
<template>
  <div class="uri-selector">
    <el-select
      v-model="selectedUri"
      filterable
      remote
      clearable
      placeholder="选择或搜索URI"
      :remote-method="handleSearch"
      :loading="loading"
      @change="handleChange"
      style="width: 100%"
    >
      <el-option
        v-for="uri in filteredUris"
        :key="uri"
        :label="uri"
        :value="uri"
      />
    </el-select>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { callChainApi } from '@/api/callChain'

const props = defineProps<{
  project: string
  modelValue?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'change', value: string): void
}>()

const loading = ref(false)
const uris = ref<string[]>([])
const searchText = ref('')
const selectedUri = ref(props.modelValue || '')

const filteredUris = computed(() => {
  if (!searchText.value) return uris.value
  return uris.value.filter(uri =>
    uri.toLowerCase().includes(searchText.value.toLowerCase())
  )
})

const handleSearch = (query: string) => {
  searchText.value = query
}

const handleChange = (value: string) => {
  emit('update:modelValue', value)
  emit('change', value)
}

const loadUris = async () => {
  if (!props.project) return
  loading.value = true
  try {
    const res = await callChainApi.getUris({ project: props.project })
    uris.value = res.data || []
  } catch (error) {
    ElMessage.error('加载URI列表失败')
  } finally {
    loading.value = false
  }
}

watch(() => props.project, loadUris, { immediate: true })
watch(() => props.modelValue, (val) => {
  selectedUri.value = val || ''
})
</script>

<style scoped>
.uri-selector {
  width: 100%;
}
</style>
```

**Step 2: Commit**

```bash
git add src/views/call-chain/components/UriSelector.vue
git commit -m "feat: create UriSelector component with fuzzy search"
```

---

## Task 5: 前端 - 创建类方法选择器组件

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/components/ClassMethodSelector.vue`

**Step 1: 创建 ClassMethodSelector 组件**

```vue
<template>
  <div class="class-method-selector">
    <el-select
      v-model="selectedClass"
      filterable
      remote
      clearable
      placeholder="选择或搜索类名"
      :remote-method="handleClassSearch"
      :loading="classLoading"
      @change="handleClassChange"
      style="width: 100%"
    >
      <el-option
        v-for="item in filteredClasses"
        :key="item.className"
        :label="item.className"
        :value="item.className"
      >
        <span>{{ item.className }}</span>
        <span style="color: #999; font-size: 12px; margin-left: 8px;">
          ({{ item.methodCount }} methods)
        </span>
      </el-option>
    </el-select>

    <el-select
      v-model="selectedMethod"
      filterable
      clearable
      placeholder="选择方法"
      :loading="methodLoading"
      :disabled="!selectedClass"
      @change="handleMethodChange"
      style="width: 100%; margin-top: 8px;"
    >
      <el-option
        v-for="item in methods"
        :key="item.methodName"
        :label="item.methodName"
        :value="item.methodName"
      >
        <div>
          <div>{{ item.methodName }}</div>
          <div style="color: #999; font-size: 12px;">{{ item.signature }}</div>
        </div>
      </el-option>
    </el-select>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { callChainApi } from '@/api/callChain'

interface ClassInfo {
  className: string
  methodCount: number
}

interface MethodInfo {
  methodName: string
  signature: string
}

const props = defineProps<{
  project?: string
}>()

const emit = defineEmits<{
  (e: 'change', value: { className: string; methodName: string } | null): void
}>()

const classLoading = ref(false)
const methodLoading = ref(false)
const classes = ref<ClassInfo[]>([])
const methods = ref<MethodInfo[]>([])
const classSearchText = ref('')
const selectedClass = ref('')
const selectedMethod = ref('')

const filteredClasses = computed(() => {
  if (!classSearchText.value) return classes.value
  return classes.value.filter(c =>
    c.className.toLowerCase().includes(classSearchText.value.toLowerCase())
  )
})

const handleClassSearch = (query: string) => {
  classSearchText.value = query
}

const handleClassChange = async (value: string) => {
  selectedMethod.value = ''
  methods.value = []
  emit('change', null)

  if (!value) return

  methodLoading.value = true
  try {
    const res = await callChainApi.getMethods({
      className: value,
      project: props.project
    })
    methods.value = res.data || []
  } catch (error) {
    ElMessage.error('加载方法列表失败')
  } finally {
    methodLoading.value = false
  }
}

const handleMethodChange = (value: string) => {
  if (value && selectedClass.value) {
    emit('change', {
      className: selectedClass.value,
      methodName: value
    })
  } else {
    emit('change', null)
  }
}

const loadClasses = async () => {
  classLoading.value = true
  try {
    const res = await callChainApi.getClasses({ project: props.project })
    classes.value = res.data || []
  } catch (error) {
    ElMessage.error('加载类列表失败')
  } finally {
    classLoading.value = false
  }
}

loadClasses()
</script>

<style scoped>
.class-method-selector {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
```

**Step 2: Commit**

```bash
git add src/views/call-chain/components/ClassMethodSelector.vue
git commit -m "feat: create ClassMethodSelector component"
```

---

## Task 6: 前端 - 创建右键菜单组件

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/components/ContextMenu.vue`

**Step 1: 创建 ContextMenu 组件**

```vue
<template>
  <teleport to="body">
    <div
      v-if="visible"
      class="context-menu"
      :style="{ left: x + 'px', top: y + 'px' }"
      @click.stop
    >
      <div class="menu-item" @click="handleAction('upstream')">
        <el-icon><Top /></el-icon>
        查看上游调用链
      </div>
      <div class="menu-item" @click="handleAction('downstream')">
        <el-icon><Bottom /></el-icon>
        查看下游调用链
      </div>
      <div class="menu-divider"></div>
      <div class="menu-item" @click="handleAction('copy')">
        <el-icon><DocumentCopy /></el-icon>
        复制方法签名
      </div>
      <div class="menu-divider"></div>
      <div class="menu-item" @click="handleAction('openInMethodRef')">
        <el-icon><Link /></el-icon>
        在方法引用分析中打开
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { Top, Bottom, DocumentCopy, Link } from '@element-plus/icons-vue'

interface MenuNode {
  name: string
  className?: string
  methodSignature?: string
}

const props = defineProps<{
  visible: boolean
  x: number
  y: number
  node: MenuNode | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'action', action: string, node: MenuNode): void
}>()

const handleAction = (action: string) => {
  if (props.node) {
    emit('action', action, props.node)
  }
  emit('close')
}

const handleClickOutside = () => {
  emit('close')
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
.context-menu {
  position: fixed;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  z-index: 3000;
  min-width: 180px;
  padding: 4px 0;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  cursor: pointer;
  font-size: 14px;
}

.menu-item:hover {
  background: #f5f7fa;
}

.menu-divider {
  height: 1px;
  background: #e4e7ed;
  margin: 4px 0;
}
</style>
```

**Step 2: Commit**

```bash
git add src/views/call-chain/components/ContextMenu.vue
git commit -m "feat: create ContextMenu component for chain nodes"
```

---

## Task 7: 前端 - 创建调用链图表组件

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/components/ChainChart.vue`

**Step 1: 创建 ChainChart 组件（支持 Tree/Graph 视图切换）**

```vue
<template>
  <div class="chain-chart">
    <div class="chart-toolbar">
      <el-radio-group v-model="viewMode" size="small">
        <el-radio-button label="tree">Tree视图</el-radio-button>
        <el-radio-button label="graph">Graph视图</el-radio-button>
      </el-radio-group>
    </div>
    <div ref="chartRef" class="chart-container" v-loading="loading"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'

interface ChainNode {
  name: string
  className?: string
  methodSignature?: string
  children?: ChainNode[]
}

const props = defineProps<{
  data: ChainNode | null
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'node-click', node: ChainNode, event: MouseEvent): void
  (e: 'node-contextmenu', node: ChainNode, event: MouseEvent): void
}>()

const chartRef = ref<HTMLElement>()
const viewMode = ref<'tree' | 'graph'>('tree')
let chart: echarts.ECharts | null = null

const handleResize = () => chart?.resize()

const initChart = () => {
  if (!chartRef.value || !props.data) return

  if (chart) {
    chart.dispose()
  }

  chart = echarts.init(chartRef.value)

  const option = viewMode.value === 'tree'
    ? getTreeOption()
    : getGraphOption()

  chart.setOption(option)

  // 右键菜单事件
  chart.on('contextmenu', (params: any) => {
    if (params.data) {
      emit('node-contextmenu', params.data, params.event.event)
    }
  })
}

const getTreeOption = () => ({
  tooltip: {
    trigger: 'item',
    formatter: (params: any) => {
      const data = params.data
      return `<div>
        <div><strong>${data.name}</strong></div>
        ${data.className ? `<div style="color:#999">${data.className}</div>` : ''}
      </div>`
    }
  },
  series: [{
    type: 'tree',
    data: [props.data],
    left: '2%',
    right: '2%',
    top: '8%',
    bottom: '20%',
    symbol: 'rect',
    symbolSize: [120, 30],
    orient: 'LR',
    label: {
      position: 'left',
      verticalAlign: 'middle',
      align: 'right',
      fontSize: 12
    },
    leaves: {
      label: {
        position: 'right',
        verticalAlign: 'middle',
        align: 'left'
      }
    },
    expandAndCollapse: true,
    animationDuration: 550,
    animationDurationUpdate: 750
  }]
})

const getGraphOption = () => {
  const nodes: any[] = []
  const links: any[] = []

  const traverse = (node: ChainNode, parentId?: string) => {
    const nodeId = nodes.length.toString()
    nodes.push({
      id: nodeId,
      name: node.name,
      className: node.className,
      methodSignature: node.methodSignature,
      symbolSize: 50,
      category: 0
    })

    if (parentId !== undefined) {
      links.push({
        source: parentId,
        target: nodeId
      })
    }

    if (node.children) {
      node.children.forEach(child => traverse(child, nodeId))
    }
  }

  if (props.data) {
    traverse(props.data)
  }

  return {
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        if (params.dataType === 'node') {
          return `<div>
            <div><strong>${params.data.name}</strong></div>
            ${params.data.className ? `<div style="color:#999">${params.data.className}</div>` : ''}
          </div>`
        }
        return ''
      }
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodes,
      links: links,
      roam: true,
      label: {
        show: true,
        position: 'right',
        fontSize: 12
      },
      force: {
        repulsion: 100,
        edgeLength: 80
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: {
          width: 10
        }
      }
    }]
  }
}

watch(() => props.data, initChart, { deep: true })
watch(viewMode, initChart)

onMounted(() => {
  initChart()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  chart?.dispose()
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.chain-chart {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chart-toolbar {
  padding: 8px 0;
}

.chart-container {
  flex: 1;
  min-height: 400px;
}
</style>
```

**Step 2: Commit**

```bash
git add src/views/call-chain/components/ChainChart.vue
git commit -m "feat: create ChainChart component with Tree/Graph view toggle"
```

---

## Task 8: 前端 - 改造 CallChainGraph 页面

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/call-chain/CallChainGraph.vue`

**Step 1: 重构 CallChainGraph 页面**

```vue
<template>
  <div class="call-chain-graph">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>接口调用链查询</span>
          <el-tag type="info">{{ projectName }}</el-tag>
        </div>
      </template>

      <div class="toolbar">
        <UriSelector
          :project="projectName"
          v-model="selectedUri"
          @change="handleUriChange"
          style="width: 400px;"
        />
        <el-button type="primary" @click="loadCallChain" :loading="loading" :disabled="!selectedUri">
          查询
        </el-button>
      </div>

      <ChainChart
        :data="chainData"
        :loading="loading"
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
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { callChainApi } from '@/api/callChain'
import { useAppStore } from '@/stores/app'
import UriSelector from './components/UriSelector.vue'
import ChainChart from './components/ChainChart.vue'
import ContextMenu from './components/ContextMenu.vue'

interface ChainNode {
  name: string
  className?: string
  methodSignature?: string
  children?: ChainNode[]
}

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()

const projectName = computed(() => appStore.selectedProject || route.query.project as string || '')
const selectedUri = ref(route.query.uri as string || '')
const loading = ref(false)
const chainData = ref<ChainNode | null>(null)

// 右键菜单状态
const contextMenuVisible = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const contextMenuNode = ref<ChainNode | null>(null)

const handleUriChange = (uri: string) => {
  selectedUri.value = uri
}

const loadCallChain = async () => {
  if (!selectedUri.value) return

  loading.value = true
  try {
    const res = await callChainApi.getCalls({
      project: projectName.value,
      uri: selectedUri.value
    })
    chainData.value = res.data
  } catch (error) {
    ElMessage.error('加载调用链数据失败')
  } finally {
    loading.value = false
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

// 初始加载
if (selectedUri.value) {
  loadCallChain()
}
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
```

**Step 2: Commit**

```bash
git add src/views/call-chain/CallChainGraph.vue
git commit -m "feat: refactor CallChainGraph with URI selector and context menu"
```

---

## Task 9: 前端 - 新增方法引用关系分析页面

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/call-chain/MethodReferenceGraph.vue`

**Step 1: 创建 MethodReferenceGraph 页面**

```vue
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
        <ClassMethodSelector
          :project="projectName"
          @change="handleSelectionChange"
          style="width: 400px;"
        />
        <el-button
          type="primary"
          @click="loadUpstream"
          :loading="loading"
          :disabled="!selection"
        >
          向上查询
        </el-button>
        <el-button
          type="success"
          @click="loadDownstream"
          :loading="loading"
          :disabled="!selection"
        >
          向下查询
        </el-button>
      </div>

      <ChainChart
        :data="chainData"
        :loading="loading"
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
import { callChainApi } from '@/api/callChain'
import { useAppStore } from '@/stores/app'
import ClassMethodSelector from './components/ClassMethodSelector.vue'
import ChainChart from './components/ChainChart.vue'
import ContextMenu from './components/ContextMenu.vue'

interface ChainNode {
  name: string
  className?: string
  methodSignature?: string
  children?: ChainNode[]
}

interface Selection {
  className: string
  methodName: string
}

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()

const projectName = computed(() => appStore.selectedProject || '')
const selection = ref<Selection | null>(null)
const loading = ref(false)
const chainData = ref<ChainNode | null>(null)

// 右键菜单状态
const contextMenuVisible = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)
const contextMenuNode = ref<ChainNode | null>(null)

const handleSelectionChange = (value: Selection | null) => {
  selection.value = value
  chainData.value = null
}

const loadUpstream = async () => {
  if (!selection.value) return

  loading.value = true
  try {
    const res = await callChainApi.getCallers({
      method: `${selection.value.className}.${selection.value.methodName}`,
      maxDepth: 5,
      project: projectName.value
    })
    chainData.value = buildTreeData(res.data, 'upstream')
  } catch (error) {
    ElMessage.error('加载上游调用链失败')
  } finally {
    loading.value = false
  }
}

const loadDownstream = async () => {
  if (!selection.value) return

  loading.value = true
  try {
    const res = await callChainApi.getCallees({
      method: `${selection.value.className}.${selection.value.methodName}`,
      maxDepth: 5,
      project: projectName.value
    })
    chainData.value = buildTreeData(res.data, 'downstream')
  } catch (error) {
    ElMessage.error('加载下游调用链失败')
  } finally {
    loading.value = false
  }
}

const buildTreeData = (data: any[], direction: 'upstream' | 'downstream'): ChainNode => {
  // 构建树形数据结构
  const root: ChainNode = {
    name: selection.value!.methodName,
    className: selection.value!.className,
    children: []
  }

  if (data && data.length > 0) {
    root.children = data.map(item => ({
      name: item.methodName || item.callee_method || item.caller_method || '',
      className: item.className || item.callee_class || item.caller_class || '',
      methodSignature: item.signature || ''
    }))
  }

  return root
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
      selection.value = {
        className: node.className || '',
        methodName: node.name
      }
      if (action === 'upstream') {
        loadUpstream()
      } else {
        loadDownstream()
      }
      break
    case 'copy':
      navigator.clipboard.writeText(node.methodSignature || node.name)
      ElMessage.success('已复制到剪贴板')
      break
  }
}

// 从 URL 参数初始化
onMounted(() => {
  const { className, methodName, direction } = route.query
  if (className && methodName) {
    selection.value = {
      className: className as string,
      methodName: methodName as string
    }
    if (direction === 'up') {
      loadUpstream()
    } else if (direction === 'down') {
      loadDownstream()
    }
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
  align-items: flex-start;
  margin-bottom: 16px;
}

.method-reference-graph {
  height: calc(100vh - 200px);
}
</style>
```

**Step 2: Commit**

```bash
git add src/views/call-chain/MethodReferenceGraph.vue
git commit -m "feat: create MethodReferenceGraph page for method call analysis"
```

---

## Task 10: 前端 - 更新路由和侧边栏

**Files:**
- Modify: `hisi-dev-tool-frontend/src/router/index.ts`
- Modify: `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`

**Step 1: 更新路由配置**

```typescript
// 在 routes 数组中替换 call-chain 相关路由
{
  path: '/call-chain',
  redirect: '/call-chain/uri-chain'
},
{
  path: '/call-chain/uri-chain',
  name: 'UriCallChain',
  component: () => import('@/views/call-chain/CallChainGraph.vue'),
  meta: { title: '接口调用链查询' }
},
{
  path: '/call-chain/method-reference',
  name: 'MethodReference',
  component: () => import('@/views/call-chain/MethodReferenceGraph.vue'),
  meta: { title: '方法引用关系分析' }
}
```

**Step 2: 更新侧边栏菜单**

在 AppSidebar.vue 中找到调用链分析菜单，改为带子菜单的形式：

```vue
<el-sub-menu index="call-chain" v-if="availableMenus['call-chain']">
  <template #title>
    <el-icon><Share /></el-icon>
    <span>调用链分析</span>
  </template>
  <el-menu-item index="/call-chain/uri-chain">
    接口调用链查询
  </el-menu-item>
  <el-menu-item index="/call-chain/method-reference">
    方法引用关系分析
  </el-menu-item>
</el-sub-menu>
```

**Step 3: Commit**

```bash
git add src/router/index.ts
git add src/components/layout/AppSidebar.vue
git commit -m "feat: update routes and sidebar for call chain enhancement"
```

---

## Task 11: 集成测试

**Step 1: 启动后端服务**

```bash
cd hisi-dev-tool
mvn clean package -Dmaven.test.skip=true
java -jar target/devTools-1.0.0.jar
```

**Step 2: 启动前端开发服务器**

```bash
cd hisi-dev-tool-frontend
npm run dev
```

**Step 3: 测试功能**

- [ ] 接口调用链页面：URI 下拉选择
- [ ] 接口调用链页面：URI 模糊搜索
- [ ] 接口调用链页面：Tree/Graph 视图切换
- [ ] 接口调用链页面：节点右键菜单
- [ ] 右键菜单：向上/向下查询跳转
- [ ] 右键菜单：复制方法签名
- [ ] 右键菜单：在方法引用分析中打开
- [ ] 方法引用关系页面：类名下拉选择
- [ ] 方法引用关系页面：方法名下拉选择
- [ ] 方法引用关系页面：向上/向下查询
- [ ] 方法引用关系页面：Tree/Graph 视图切换
- [ ] 侧边栏菜单：子菜单显示正确

**Step 4: 最终 Commit**

```bash
git add .
git commit -m "feat: complete call chain analysis enhancement"
```

---

## 文件变更清单

### 后端
- `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/CallChainController.java` - 新增接口
- `hisi-dev-tool/src/main/java/com/huawei/hisi/service/CallChainService.java` - 新增方法签名
- `hisi-dev-tool/src/main/java/com/huawei/hisi/service/impl/CallChainServiceImpl.java` - 实现新方法

### 前端
- `hisi-dev-tool-frontend/src/api/callChain.ts` - 新增 API 方法
- `hisi-dev-tool-frontend/src/views/call-chain/components/UriSelector.vue` - 新建
- `hisi-dev-tool-frontend/src/views/call-chain/components/ClassMethodSelector.vue` - 新建
- `hisi-dev-tool-frontend/src/views/call-chain/components/ContextMenu.vue` - 新建
- `hisi-dev-tool-frontend/src/views/call-chain/components/ChainChart.vue` - 新建
- `hisi-dev-tool-frontend/src/views/call-chain/CallChainGraph.vue` - 重构
- `hisi-dev-tool-frontend/src/views/call-chain/MethodReferenceGraph.vue` - 新建
- `hisi-dev-tool-frontend/src/router/index.ts` - 更新路由
- `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue` - 更新菜单