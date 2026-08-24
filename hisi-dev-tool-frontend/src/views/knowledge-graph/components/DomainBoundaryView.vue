<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import { knowledgeGraphApi, type DomainItem, type DomainEdge, type DomainClass, type MethodNode } from '@/api/knowledgeGraph'
import { ElSkeleton, ElEmpty, ElTag, ElCard, ElAlert } from 'element-plus'
import * as echarts from 'echarts'

const props = defineProps<{ projectPaths: string[]; language?: string }>()
const loading = ref(false)
const domains = ref<DomainItem[]>([])
const interactions = ref<DomainEdge[]>([])
const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null

// 领域下钻状态
const expandedDomainId = ref<string | null>(null)
const domainClasses = ref<DomainClass[]>([])
const expandedClassName = ref<string | null>(null)
const classMethods = ref<MethodNode[]>([])

async function load() {
  if (!props.projectPaths.length) return
  loading.value = true
  try {
    const res = await knowledgeGraphApi.getDomains(props.projectPaths, props.language)
    domains.value = res.domains
    interactions.value = res.interactions ?? []
  } catch { } finally { loading.value = false }
}

/** 点击领域 → 展开/收起该领域的类列表（虚拟类节点） */
async function toggleDomain(d: DomainItem) {
  if (expandedDomainId.value === d.id) {
    expandedDomainId.value = null
    domainClasses.value = []
    expandedClassName.value = null
    classMethods.value = []
    return
  }
  expandedDomainId.value = d.id
  expandedClassName.value = null
  classMethods.value = []
  try {
    const res = await knowledgeGraphApi.getDomainClasses(d.id, props.projectPaths)
    domainClasses.value = res.classes
  } catch { domainClasses.value = [] }
}

/** 点击类 → 展开/收起该类的方法列表 */
async function toggleClass(cls: DomainClass) {
  if (expandedClassName.value === cls.id) {
    expandedClassName.value = null
    classMethods.value = []
    return
  }
  expandedClassName.value = cls.id
  try {
    const methods = await knowledgeGraphApi.getMethodsByClass(cls.className, props.projectPaths)
    classMethods.value = methods
  } catch { classMethods.value = [] }
}

function shortName(fqn: string): string {
  const segs = fqn.split('.')
  return segs[segs.length - 1] || fqn
}

function confidenceColor(c: number): string {
  if (c >= 0.8) return '#4caf50'
  if (c >= 0.5) return '#ff9800'
  return '#9e9e9e'
}

// 手动网格布局：像素坐标（x/y 单位一致 → 节点正圆），横向铺满画布
function gridPositions(count: number, W: number, H: number): { x: number; y: number }[] {
  const cols = Math.max(1, Math.round(Math.sqrt(count * (W / H))))
  const rows = Math.ceil(count / cols)
  const xGap = W / (cols + 1)
  const yGap = H / (rows + 1)
  return Array.from({ length: count }, (_, i) => ({
    x: ((i % cols) + 1) * xGap,
    y: (Math.floor(i / cols) + 1) * yGap
  }))
}

function ensureObserver() {
  if (resizeObserver || typeof ResizeObserver === 'undefined' || !chartRef.value) return
  resizeObserver = new ResizeObserver(() => renderGraph())
  resizeObserver.observe(chartRef.value)
}

function renderGraph() {
  const el = chartRef.value
  if (!el || !domains.value.length) return
  ensureObserver()  // 容器首次出现即挂上（onMounted 时容器还没渲染）
  const rect = el.getBoundingClientRect()
  const W = rect.width
  const H = rect.height
  if (W < 50 || H < 50) return  // 容器还没展开（tab 未激活），等 ResizeObserver 触发

  const buildOption = (w: number, h: number) => {
    const validIds = new Set(domains.value.map(d => d.id))
    const links = interactions.value
      .filter(e => validIds.has(e.source) && validIds.has(e.target))
      .map(e => ({ source: e.source, target: e.target, value: e.weight }))
    const pos = gridPositions(domains.value.length, w, h)
    return {
      tooltip: {
        formatter: (p: any) => {
          if (p.dataType === 'edge') return `${p.data.sourceName} → ${p.data.targetName}<br/>调用 ${p.data.value ?? 0} 次`
          return `${p.data.name}<br/>方法数：${p.data.value}<br/>置信度：${(p.data.confidence * 100).toFixed(0)}%`
        }
      },
      legend: {
        data: ['高置信(≥80%)', '中置信(50-80%)', '低置信(<50%)'],
        top: 0
      },
      series: [{
        type: 'graph',
        layout: 'none',
        roam: true,
        draggable: true,
        data: domains.value.map((d, i) => ({
          id: d.id,
          name: d.name,
          value: d.methodCount,
          x: pos[i].x,
          y: pos[i].y,
          confidence: d.confidence,
          symbol: 'circle',
          symbolSize: Math.max(28, Math.min(90, d.methodCount / 2.5)),
          itemStyle: { color: confidenceColor(d.confidence) },
          label: { show: true, position: 'bottom', fontSize: 12, fontWeight: 'bold', color: '#333' }
        })),
        links,
        lineStyle: { color: '#bbb', width: 1.5, curveness: 0.15 },
        emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
        categories: [
          { name: '高置信(≥80%)' }, { name: '中置信(50-80%)' }, { name: '低置信(<50%)' }
        ]
      }]
    }
  }

  // 每次从当前容器尺寸干净重建，避免残留 100px 的坏实例
  if (chart) { chart.dispose(); chart = null }
  chart = echarts.init(el)
  chart.setOption(buildOption(W, H))
}

onMounted(load)
watch(() => props.projectPaths, load)
watch([domains, interactions], () => {
  nextTick(() => renderGraph())
}, { flush: 'post' })
onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" class="desc-bar">
      <template #title>
        <b>领域划分</b>：自动检测代码的业务领域。气泡 = 领域，大小 = 方法数，颜色 = 置信度（绿=高/橙=中/灰=低），连线 = 领域间调用。点击领域卡片可下钻查看领域内的类和方法。
      </template>
    </el-alert>

    <el-skeleton :loading="loading" animated :count="3">
      <el-empty v-if="!domains.length && !loading" description="尚未检测到领域">
        <template #description>
          <div style="line-height: 1.8">
            <p>领域检测依赖「架构现状分析」完成（LLM 全局归纳 + BELONGS_TO 边）。</p>
            <p style="color:#909399;font-size:12px">可能原因：① 尚未构建知识图谱；② 尚未执行架构现状分析；③ 归纳失败。</p>
          </div>
        </template>
      </el-empty>
      <div v-else>
        <div class="domain-cards">
          <div v-for="d in domains" :key="d.id" class="domain-card-wrap">
            <el-card shadow="hover" class="domain-card" :class="{ active: expandedDomainId === d.id }" @click="toggleDomain(d)">
              <span class="domain-name">{{ d.name }}</span>
              <el-tag size="small" style="margin-left:8px" :type="d.confidence > 0.8 ? 'success' : d.confidence > 0.5 ? 'warning' : 'info'">
                {{ (d.confidence * 100).toFixed(0) }}%
              </el-tag>
              <div class="domain-meta">{{ d.classCount }} 类 · {{ d.methodCount }} 方法</div>
            </el-card>

            <!-- 领域下钻：类列表 -->
            <div v-if="expandedDomainId === d.id" class="drill-panel">
              <div v-if="!domainClasses.length" class="drill-empty">该领域暂无类</div>
              <div v-for="cls in domainClasses" :key="cls.id" class="class-item">
                <div class="class-row" @click.stop="toggleClass(cls)">
                  <span class="class-name">{{ shortName(cls.className) }}</span>
                  <span class="class-meta">{{ cls.methodCount }} 方法</span>
                </div>
                <!-- 类下钻：方法列表 -->
                <div v-if="expandedClassName === cls.id" class="method-panel">
                  <div v-if="!classMethods.length" class="drill-empty">该类暂无方法</div>
                  <div v-for="m in classMethods" :key="m.nodeId" class="method-item">
                    <code>{{ m.methodName }}</code><span class="method-sig">{{ m.signature }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div ref="chartRef" class="graph-container" />
      </div>
    </el-skeleton>
  </div>
</template>

<style scoped>
.desc-bar { margin-bottom: 12px; }
.domain-cards { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.domain-card-wrap { min-width: 180px; max-width: 320px; }
.domain-card { cursor: pointer; }
.domain-card.active { border-color: #409eff; }
.domain-name { font-weight: 600; }
.domain-meta { margin-top: 4px; color: #909399; font-size: 12px; }
.drill-panel { margin-top: 8px; padding: 8px; background: #f8f9fb; border-radius: 4px; max-height: 300px; overflow-y: auto; }
.drill-empty { color: #c0c4cc; font-size: 12px; }
.class-item { margin-bottom: 4px; }
.class-row { cursor: pointer; padding: 4px 6px; border-radius: 3px; }
.class-row:hover { background: #ecf5ff; }
.class-name { font-size: 13px; color: #409eff; }
.class-meta { margin-left: 8px; font-size: 12px; color: #909399; }
.method-panel { margin: 4px 0 4px 12px; padding-left: 8px; border-left: 2px solid #e4e7ed; }
.method-item { font-size: 12px; color: #606266; padding: 2px 0; }
.method-sig { margin-left: 6px; color: #909399; }
.graph-container { width: 100%; height: 480px; border: 1px solid #ebeef5; border-radius: 4px; }
</style>
