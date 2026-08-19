<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch, nextTick, computed } from 'vue'
import { ElCard, ElStatistic, ElTag, ElSkeleton, ElEmpty, ElAlert, ElDrawer } from 'element-plus'
import { Warning } from '@element-plus/icons-vue'
import {
  knowledgeGraphApi,
  type DashboardData,
  type ClassifiedCycle,
  type ClassLayerViolation,
  type PackageDependencyGraph,
  type ClassEgoNet,
} from '@/api/knowledgeGraph'
import LayerDomainDiff from './LayerDomainDiff.vue'
import DsmMatrix from './DsmMatrix.vue'
import * as echarts from 'echarts'

const props = defineProps<{ projectPaths: string[]; language?: string }>()
const loading = ref(false)
const viewMode = ref<'graph' | 'dsm'>('graph')
const dashboard = ref<DashboardData | null>(null)
const packageCycles = ref<ClassifiedCycle[]>([])
const classViolations = ref<ClassLayerViolation[]>([])
const packageGraph = ref<PackageDependencyGraph | null>(null)
const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

// 已知分层的相对偏序（仅用于纵向排序，不是枚举清单——后端/LLM 可能返回其他层名）
const LAYER_BIAS = ['CONTROLLER', 'SERVICE', 'REPOSITORY', 'MODEL', 'UTILITY']
const UNKNOWN_COLOR = '#bdbdbd'
const PALETTE = ['#1976d2', '#388e3c', '#f57c00', '#7b1fa2', '#607d8b', '#00838f', '#6d4c41', '#ad1457', '#558b2f', '#4527a0']

// 从后端返回的角色集合动态派生：有序层列表 + 颜色（未知层自动追加，UNKNOWN 恒在末尾）
function buildLayerMeta(roles: Iterable<string>): { order: string[]; color: (r: string) => string } {
  const seen = new Set<string>()
  for (const r of roles) seen.add(r ?? 'UNKNOWN')
  const known = LAYER_BIAS.filter(r => seen.has(r))
  const others = [...seen].filter(r => !LAYER_BIAS.includes(r) && r !== 'UNKNOWN').sort()
  const order = [...known, ...others, 'UNKNOWN']
  const colorMap = new Map<string, string>()
  order.forEach((r, i) => colorMap.set(r, r === 'UNKNOWN' ? UNKNOWN_COLOR : PALETTE[i % PALETTE.length]))
  return { order, color: r => colorMap.get(r) ?? UNKNOWN_COLOR }
}

async function load() {
  if (!props.projectPaths.length) return
  loading.value = true
  try {
    dashboard.value = await knowledgeGraphApi.getDashboard(props.projectPaths, props.language)
    packageCycles.value = (await knowledgeGraphApi.getPackageCycles(props.projectPaths)).cycles ?? []
    classViolations.value = (await knowledgeGraphApi.getClassLayerViolations(props.projectPaths)).violations ?? []
    packageGraph.value = await knowledgeGraphApi.getPackageDependencies(props.projectPaths)
  } catch (e) {
    console.error('[DashboardPanel] load 失败:', e)
  } finally { loading.value = false }
}

// 包级环的节点集合（标红）
function cycleNodeKeys(): Set<string> {
  const s = new Set<string>()
  for (const cyc of packageCycles.value) for (const n of cyc.nodes) s.add(n)
  return s
}

// 违规依赖边集合（分层违规 + 跨层循环依赖环内边），用于标红连线。
// 同层循环依赖（SAME_LAYER，技术债）不标红——只反向依赖和跨层环才是坏味道。
function violationEdges(): Set<string> {
  const s = new Set<string>()
  for (const r of dashboard.value?.risks ?? []) {
    if (r.type === 'layered') s.add(r.source + '→' + r.target)
  }
  for (const cyc of packageCycles.value) {
    if (cyc.level !== 'CROSS_LAYER') continue
    const cs = new Set(cyc.nodes)
    for (const e of packageGraph.value?.edges ?? []) {
      if (cs.has(e.source) && cs.has(e.target)) s.add(e.source + '→' + e.target)
    }
  }
  return s
}

function ensureObserver() {
  if (resizeObserver || typeof ResizeObserver === 'undefined' || !chartRef.value) return
  resizeObserver = new ResizeObserver(() => renderPackageGraph())
  resizeObserver.observe(chartRef.value)
}

function renderPackageGraph() {
  const el = chartRef.value
  if (!el || !packageGraph.value) return
  ensureObserver()
  const rect = el.getBoundingClientRect()
  if (rect.width < 50 || rect.height < 50) return

  const d = packageGraph.value
  const inCycle = cycleNodeKeys()
  const badEdges = violationEdges()

  // 分层排布（包级，几十个节点）——层列表与颜色从后端返回的 layerRole 动态派生
  const { order: layerOrder, color: roleColor } = buildLayerMeta(d.nodes.map(n => n.layerRole))
  const W = rect.width
  const H = rect.height
  const yGap = H / (layerOrder.length + 1)
  const LEFT_PAD = 96   // 左侧留白放层级名称
  const layerCounts = new Map<string, number>()
  for (const n of d.nodes) {
    const role = n.layerRole ?? 'UNKNOWN'
    layerCounts.set(role, (layerCounts.get(role) ?? 0) + 1)
  }
  const layerIndex = new Map<string, number>()

  const data = d.nodes.map(n => {
    const role = n.layerRole ?? 'UNKNOWN'
    const li = layerOrder.indexOf(role) >= 0 ? layerOrder.indexOf(role) : layerOrder.indexOf('UNKNOWN')
    const idx = layerIndex.get(role) ?? 0
    layerIndex.set(role, idx + 1)
    const count = layerCounts.get(role) ?? 1
    const xGap = (W - LEFT_PAD) / (count + 1)
    return {
      id: n.moduleName,
      name: n.moduleName.split('.').pop(),
      role,
      symbolSize: Math.max(24, Math.min(60, n.methodCount / 15)),
      x: LEFT_PAD + (idx + 1) * xGap,
      y: (li + 1) * yGap,
      itemStyle: {
        color: inCycle.has(n.moduleName) ? '#ef5350' : roleColor(role),
      },
      label: { show: true, fontSize: 11, color: '#333' }
    }
  })

  // 每层画一个淡色条带框 + 左侧层级名称
  const graphics = layerOrder.map((role, li) => {
    const bandTop = (li + 0.5) * yGap
    const bandH = yGap * 0.8
    const color = roleColor(role)
    return [
      {
        type: 'rect' as const,
        left: 4, top: bandTop,
        shape: { width: W - 8, height: bandH, r: 6 },
        style: { fill: color, opacity: 0.05, stroke: color, lineWidth: 1, lineDash: [4, 3] },
        z: -1,
      },
      {
        type: 'text' as const,
        left: 10, top: bandTop + bandH / 2 - 8,
        style: { text: role, fill: color, fontSize: 13, fontWeight: 'bold' },
        z: 10,
      },
    ]
  }).flat()

  if (chart) { chart.dispose(); chart = null }
  chart = echarts.init(el)
  chart.setOption({
    tooltip: {
      formatter: (p: any) => {
        if (p.dataType === 'edge') {
          const bad = badEdges.has(p.data.source + '→' + p.data.target)
          return `${p.data.source}<br/>↓<br/>${p.data.target}${bad ? '<br/><span style="color:#ef5350">⚠ 违规依赖（点击下钻类级）</span>' : ''}`
        }
        return `${p.data.id}<br/>职责：${p.data.role}`
      }
    },
    graphic: graphics,
    series: [{
      type: 'graph',
      layout: 'none',
      roam: false,
      draggable: true,
      data,
      links: d.edges.map(e => {
        const bad = badEdges.has(e.source + '→' + e.target)
        return {
          source: e.source,
          target: e.target,
          lineStyle: bad
            ? { color: '#ef5350', width: 2, opacity: 0.9, curveness: 0.15 }
            : { color: '#c5cae9', width: 1, opacity: 0.5, curveness: 0.15 },
        }
      }),
      emphasis: { focus: 'adjacency', lineStyle: { width: 2, opacity: 1 } }
    }]
  })
  chart.on('click', (p: any) => {
    if (p.dataType === 'node') openClassDrill([p.data.id])
    else if (p.dataType === 'edge') openClassDrill([p.data.source, p.data.target])
  })
}

// 下钻：点击包 → 看该包类级 ego-net（中心类 + 一跳邻居，按包分组框包裹）
const drillVisible = ref(false)
const drillTitle = ref('')
const drillRef = ref<HTMLDivElement>()
let drillChart: echarts.ECharts | null = null
const drillLoading = ref(false)
let drillData: ClassEgoNet | null = null

async function openClassDrill(packages: string[]) {
  drillVisible.value = true
  drillTitle.value = packages.length === 1
    ? '类级依赖：' + packages[0]
    : '类级依赖：' + packages.join('  ⇄  ')
  drillLoading.value = true
  drillData = null
  try {
    drillData = await knowledgeGraphApi.getClassEgoNet(props.projectPaths, packages)
    // drawer 滑入动画未完成时容器宽度为 0，用重试等尺寸就绪
    await nextTick()
    renderClassDrillWithRetry()
  } catch (e) {
    console.error('[DashboardPanel] 类级下钻失败:', e)
  } finally {
    drillLoading.value = false
  }
}

function renderClassDrillWithRetry(attempt = 0) {
  if (!drillData) return
  const el = drillRef.value
  if (!el) { if (attempt < 20) setTimeout(() => renderClassDrillWithRetry(attempt + 1), 50); return }
  const rect = el.getBoundingClientRect()
  if (rect.width < 50 || rect.height < 50) {
    if (attempt < 20) setTimeout(() => renderClassDrillWithRetry(attempt + 1), 50)
    return
  }
  renderClassDrill(drillData)
}

function renderClassDrill(g: ClassEgoNet) {
  const el = drillRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  if (rect.width < 50 || rect.height < 50) return

  const violationNodes = new Set<string>()
  for (const v of classViolations.value) { violationNodes.add(v.source); violationNodes.add(v.target) }

  // 按包分组：中心包优先，邻居包按类数降序
  const pkgOrder: string[] = []
  const pkgToNodes = new Map<string, ClassEgoNet['nodes']>()
  for (const n of g.nodes) {
    const p = n.packageName || '(无包)'
    if (!pkgToNodes.has(p)) { pkgToNodes.set(p, []); pkgOrder.push(p) }
    pkgToNodes.get(p)!.push(n)
  }
  // 排序：含 center 的包排最前（中心包），其余按类数降序
  pkgOrder.sort((a, b) => {
    const aCenter = pkgToNodes.get(a)!.some(n => n.center) ? 0 : 1
    const bCenter = pkgToNodes.get(b)!.some(n => n.center) ? 0 : 1
    if (aCenter !== bCenter) return aCenter - bCenter
    return pkgToNodes.get(b)!.length - pkgToNodes.get(a)!.length
  })

  const { color: roleColor } = buildLayerMeta(g.nodes.map(n => n.classRole))
  const W = rect.width
  const PACKAGE_H = 130          // 每个包框的高度
  const PACKAGE_GAP = 14         // 包框间距
  const TOP_PAD = 20
  const LEFT_PAD = 20
  const H = TOP_PAD + pkgOrder.length * (PACKAGE_H + PACKAGE_GAP)

  // 每个包：标题行 + 类节点行
  const data: any[] = []
  const graphics: any[] = []
  pkgOrder.forEach((pkg, pi) => {
    const nodes = pkgToNodes.get(pkg)!
    const isCenter = nodes.some(n => n.center)
    const boxTop = TOP_PAD + pi * (PACKAGE_H + PACKAGE_GAP)
    const boxW = W - LEFT_PAD - 10
    graphics.push({
      type: 'rect' as const,
      left: LEFT_PAD, top: boxTop,
      shape: { width: boxW, height: PACKAGE_H, r: 6 },
      style: {
        fill: isCenter ? 'rgba(64,158,255,0.06)' : 'rgba(144,147,153,0.04)',
        stroke: isCenter ? '#409eff' : '#c0c4cc',
        lineWidth: isCenter ? 2 : 1,
      },
      z: -1,
    })
    graphics.push({
      type: 'text' as const,
      left: LEFT_PAD + 10, top: boxTop + 8,
      style: {
        text: `${pkg}（${nodes.length} 类${isCenter ? ' · 中心' : ''}）`,
        fill: isCenter ? '#409eff' : '#606266', fontSize: 12, fontWeight: isCenter ? 'bold' : 'normal',
      },
      z: 10,
    })
    const count = nodes.length
    const xGap = boxW / (count + 1)
    nodes.forEach((n, ni) => {
      data.push({
        id: n.className,
        name: n.className.split('.').pop(),
        role: n.classRole ?? 'UNKNOWN',
        roleSource: n.classRoleSource,
        center: n.center,
        pkg: n.packageName,
        symbolSize: Math.max(18, Math.min(30, 26)),
        x: LEFT_PAD + (ni + 1) * xGap,
        y: boxTop + PACKAGE_H / 2 + 6,
        itemStyle: {
          color: violationNodes.has(n.className) ? '#ef5350' : roleColor(n.classRole ?? 'UNKNOWN'),
          borderColor: n.center ? '#409eff' : (n.classRoleSource === 'LLM' ? '#ff9800' : 'transparent'),
          borderWidth: n.center ? 2 : (n.classRoleSource === 'LLM' ? 2 : 0),
          opacity: n.classRoleSource === 'LLM' ? 0.7 : 1,
        },
        label: { show: true, fontSize: 9, color: '#333' }
      })
    })
  })

  if (drillChart) { drillChart.dispose(); drillChart = null }
  // 撑高容器以容纳所有包框，再初始化（避免 roam 缩放时 graphic 与节点错位，关闭 roam）
  el.style.height = `${H}px`
  drillChart = echarts.init(el)
  drillChart.setOption({
    tooltip: {
      formatter: (p: any) => {
        if (p.dataType === 'edge') return `${p.data.source.split('.').pop()} → ${p.data.target.split('.').pop()}`
        const src = p.data.roleSource === 'LLM' ? '（LLM 推测）' : ''
        return `${p.data.id}<br/>包：${p.data.pkg ?? ''}<br/>职责：${p.data.role}${src}`
      }
    },
    graphic: graphics,
    series: [{
      type: 'graph',
      layout: 'none',
      roam: false,
      draggable: true,
      data,
      links: g.edges.map(e => ({
        source: e.source,
        target: e.target,
        lineStyle: { color: '#c5cae9', width: 0.8, opacity: 0.5, curveness: 0.15 }
      })),
      emphasis: { focus: 'adjacency', lineStyle: { width: 2, opacity: 1 } }
    }]
  })
}


const domainRefCount = computed(() => dashboard.value?.domains.length ?? 0)

onMounted(load)
watch(() => props.projectPaths, load)
watch(packageGraph, () => { nextTick(() => renderPackageGraph()) }, { flush: 'post' })
onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
  drillChart?.dispose()
  drillChart = null
})
</script>

<template>
  <div class="dashboard">
    <el-alert type="info" :closable="false" class="desc-bar">
      <template #title>
        <b>架构仪表盘</b>：基于<strong>真实代码架构</strong>分析坏味道。默认展示<strong>包级依赖图</strong>（按分层排布），点击某个包可下钻查看该包的类级依赖。LLM 推断领域仅作参考。
      </template>
    </el-alert>

    <!-- 视图切换：架构图 / DSM 矩阵（同数据源 DEPENDS_ON，两种视角） -->
    <el-radio-group v-model="viewMode" size="small" style="margin-bottom: 12px">
      <el-radio-button value="graph">架构图</el-radio-button>
      <el-radio-button value="dsm">DSM 矩阵</el-radio-button>
    </el-radio-group>

    <!-- DSM 矩阵视图（与架构图同数据源，合并展示） -->
    <DsmMatrix v-if="viewMode === 'dsm'" :project-paths="projectPaths" />

    <el-skeleton v-if="viewMode === 'graph'" :loading="loading" animated :count="5">
      <template v-if="packageGraph">
        <div class="kpi-row">
          <el-card shadow="hover"><el-statistic title="包数量" :value="packageGraph.nodes.length" /></el-card>
          <el-card shadow="hover"><el-statistic title="包级循环依赖" :value="packageCycles.length">
            <template #suffix><el-tag v-if="packageCycles.length > 0" type="danger" size="small">需关注</el-tag></template>
          </el-statistic></el-card>
          <el-card shadow="hover"><el-statistic title="类级分层违规" :value="classViolations.length">
            <template #suffix><el-tag v-if="classViolations.length > 0" type="warning" size="small">疑似</el-tag></template>
          </el-statistic></el-card>
          <el-card shadow="hover"><el-statistic title="LLM 领域（参考）" :value="domainRefCount" /></el-card>
        </div>

        <!-- 洞察卡片 -->
        <div class="insight-row">
          <el-card class="insight-card" shadow="hover">
            <template #header>
              <span class="insight-title"><el-icon><Warning /></el-icon> 包级循环依赖（{{ packageCycles.length }}）</span>
            </template>
            <template v-if="packageCycles.length">
              <el-alert
                v-for="(cyc, i) in packageCycles"
                :key="'c' + i"
                :type="cyc.level === 'CROSS_LAYER' ? 'error' : 'warning'"
                :closable="false"
                class="insight-item"
                :title="cyc.message"
              />
            </template>
            <el-empty v-else description="未检测到包级循环依赖" :image-size="60" />
          </el-card>

          <el-card class="insight-card" shadow="hover">
            <template #header>
              <span class="insight-title">📐 类级分层违规（{{ classViolations.length }}，疑似）</span>
            </template>
            <template v-if="classViolations.length">
              <div v-for="(v, i) in classViolations" :key="'v' + i" class="vio-item">
                <el-tag type="danger" size="small">{{ v.sourceRole }} → {{ v.targetRole }}</el-tag>
                <span class="vio-msg">{{ v.message }}</span>
              </div>
            </template>
            <el-empty v-else description="未检测到类级分层违规" :image-size="60" />
          </el-card>
        </div>

        <el-card>
          <template #header>
            <div class="graph-header">
              <span>包级依赖图（分层排布）</span>
              <span class="graph-hint">颜色=职责分层 · 红=循环依赖 · 点击包查看类级依赖 · 拖拽/滚轮缩放</span>
            </div>
          </template>
          <div ref="chartRef" class="graph-container" />
        </el-card>

        <!-- 分层 × 领域 差异图（Sankey / 热力图） -->
        <LayerDomainDiff :project-paths="projectPaths" />
      </template>
      <el-empty v-else-if="!loading" description="请先构建图谱">
        <template #description>
          <div style="line-height: 1.8">
            <p>架构分析基于包级依赖（DEPENDS_ON）+ 类级依赖（CALLS 间接聚合）。</p>
            <p style="color:#909399;font-size:12px">需先执行「生成图谱」。</p>
          </div>
        </template>
      </el-empty>
    </el-skeleton>

    <el-drawer v-model="drillVisible" :title="drillTitle" size="70%">
      <el-skeleton v-if="drillLoading" :rows="8" animated />
      <div v-else ref="drillRef" class="drill-container" />
    </el-drawer>
  </div>
</template>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: 16px; }
.desc-bar { margin-bottom: 4px; }
.kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.insight-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
.insight-card { min-height: 120px; }
.insight-title { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.insight-item { margin-bottom: 8px; }
.vio-item { display: flex; align-items: flex-start; gap: 8px; padding: 6px 0; border-bottom: 1px solid #f5f5f5; }
.vio-msg { color: #303133; font-size: 13px; line-height: 1.5; word-break: break-all; }
.graph-header { display: flex; justify-content: space-between; align-items: center; }
.graph-hint { color: #909399; font-size: 12px; }
.graph-container { width: 100%; height: 500px; }
.drill-container { width: 100%; height: calc(100vh - 120px); }
</style>
