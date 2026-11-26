<script setup lang="ts">
/**
 * 工作台：跨项目聚合的技术债待办 + SDD 自动修复。
 * 布局采用选定的原型 C「清零指挥台」：总览 → 项目×来源 → 待办表格（批量）→ 修复流水线；
 * 单条待办的决策上下文用原型 A 的右侧抽屉（为什么重要 → 证据 → 建议 → 自动修复）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Monitor, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { workbenchApi } from '@/api/workbench'
import { useWorkbenchStore } from '@/stores/workbench'
import type { WorkbenchItem, WorkbenchFixRun } from '@/types/workbench'
import SparkLine from './components/SparkLine.vue'
import ItemDetail from './components/ItemDetail.vue'
import RunMonitor from './components/RunMonitor.vue'

const store = useWorkbenchStore()
const loading = ref(false)
const syncing = ref(false)

const projectFilter = ref('')
const onlyP0 = ref(false)
const query = ref('')
const selectedIds = ref<number[]>([])

const drawer = ref(false)
const detailItem = ref<WorkbenchItem | null>(null)
const monitor = ref<{ runId: number; item: WorkbenchItem } | null>(null)

const summary = computed(() => store.summary)
const items = computed(() => store.items)
const runs = computed(() => store.runs)

const today = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
const projectLabel = (path: string) => path.replace(/\\/g, '/').split('/').filter(Boolean).pop() ?? path

const projectStats = computed(() => {
  const map = new Map<string, { open: number; done: number; total: number }>()
  for (const item of items.value) {
    const stat = map.get(item.projectPath) ?? { open: 0, done: 0, total: 0 }
    stat.total++
    if (item.status === 'DONE') stat.done++
    if (item.status === 'NEW' || item.status === 'RUNNING' || item.status === 'WAITING') stat.open++
    map.set(item.projectPath, stat)
  }
  return [...map.entries()].sort((a, b) => b[1].open - a[1].open)
})

const rows = computed(() => {
  const q = query.value.trim().toLowerCase()
  return items.value
    .filter(i => i.status !== 'IGNORED')
    .filter(i => !projectFilter.value || i.projectPath === projectFilter.value)
    .filter(i => !onlyP0.value || i.severity === 'P0')
    .filter(i => !q || i.title.toLowerCase().includes(q) || (i.location ?? '').toLowerCase().includes(q))
    .sort((a, b) => {
      const sev = { P0: 0, P1: 1, P2: 2 } as const
      return sev[a.severity] - sev[b.severity]
        || (a.status === 'WAITING' ? -1 : 0) - (b.status === 'WAITING' ? -1 : 0)
        || b.lastSeenAt - a.lastSeenAt
    })
})
const selectable = computed(() => rows.value.filter(r => r.status === 'NEW'))
const allChecked = computed(() => selectable.value.length > 0 && selectable.value.every(r => selectedIds.value.includes(r.id)))

const activeRuns = computed(() => runs.value.filter(r => r.status === 'RUNNING' || r.status === 'WAITING_CONFIRM'))
const runOf = (item: WorkbenchItem) => runs.value.find(r => r.itemKey === item.itemKey)
const runPercent = (item?: WorkbenchItem) => {
  if (!item) return 0
  const run = runOf(item)
  if (!run) return 0
  if (run.status === 'DONE') return 100
  return Math.min(100, Math.round((Math.max(0, run.stageIndex) / 8) * 100))
}
const donePercent = computed(() => {
  const total = (summary.value?.total ?? 0)
  return total ? Math.round(((summary.value?.done ?? 0) / total) * 100) : 0
})
/** 8 个阶段的名字（与后端 WorkbenchStages 一致）。待办列表里的运行不带阶段清单，进度列用这份静态名字。 */
const STAGE_NAMES = ['代码实证', '方案确认', 'Spec 生成', '任务拆分', '迭代开发', '用例回归', '全链路 E2E', '提交推送']
const stageLabelOf = (stageIndex: number, status: string, stages?: { label: string }[]) => {
  if (status === 'WAITING_CONFIRM') return '等你确认方案'
  if (status === 'DONE') return '完成'
  if (status === 'FAILED') return '失败'
  if (status === 'ABORTED') return '已中止'
  if (status === 'INTERRUPTED') return '已中断（可继续）'
  return stages?.[stageIndex]?.label ?? STAGE_NAMES[stageIndex] ?? '…'
}
const chainText = (chain: string | null) => ({
  verified: '因果链已验证', stack_verified: '调用链与堆栈一致', at_throw_site: '根因落在抛出点',
  unverified: '尚未证实', confirmed: '智能体判定属实', detected: '规则检出',
}[chain ?? ''] ?? (chain ?? ''))
const confText = (c: string | null) => ({ high: '高', medium: '中', low: '低' }[c ?? ''] ?? (c ?? ''))
const hasTrend = (trend?: number[]) => !!trend && trend.some(v => v > 0)
const STATUS_LABEL: Record<string, string> = {
  NEW: '待处理', RUNNING: '修复中', WAITING: '待你确认', DONE: '已清零', FAILED: '失败', IGNORED: '已忽略',
}

async function load(force = false) {
  loading.value = true
  try {
    await store.load(force)
  } finally {
    loading.value = false
  }
}

async function sync() {
  syncing.value = true
  try {
    const result = await workbenchApi.sync()
    ElMessage.success(`聚合完成：日志 ${result.logItems} 条，架构 ${result.archItems} 条，新增 ${result.created} 项`)
    await load(true)
  } finally {
    syncing.value = false
  }
}

function toggle(id: number) {
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter(k => k !== id)
    : [...selectedIds.value, id]
}
function toggleAll() {
  selectedIds.value = allChecked.value ? [] : selectable.value.map(r => r.id)
}

function openDetail(item: WorkbenchItem) {
  detailItem.value = item
  drawer.value = true
}

async function autofix(item: WorkbenchItem, quiet = false) {
  try {
    const run = await workbenchApi.autofix(item.id, false)
    if (!quiet) ElMessage.success('已在后台启动自动修复')
    store.applyRun(run)
    // 立刻把这行标成进行中：不等列表刷新，用户也不会再对它点一次「自动修复」
    const row = items.value.find(i => i.itemKey === run.itemKey)
    if (row) {
      row.currentRunId = run.id
      row.status = run.status === 'WAITING_CONFIRM' ? 'WAITING' : 'RUNNING'
    }
    await load(true)
  } catch (e: any) {
    ElMessage.error(e.message || '启动自动修复失败')
  }
}

async function bulkFix() {
  try {
    const result = await workbenchApi.autofixBatch([...selectedIds.value], false)
    ElMessage.success(`已启动 ${result.started.length} 项${result.failed.length ? `，${result.failed.length} 项失败` : ''}`)
    selectedIds.value = []
    await load(true)
  } catch (e: any) {
    ElMessage.error(e.message || '批量启动失败')
  }
}

/** 一条运行对应的待办。刚点完「自动修复」时列表还没刷新、待办上还没有 currentRunId，所以退一步按 itemKey 找。 */
function itemOf(run: WorkbenchFixRun) {
  return items.value.find(i => i.currentRunId === run.id) ?? items.value.find(i => i.itemKey === run.itemKey)
}

function openMonitor(item: WorkbenchItem | undefined, run?: WorkbenchFixRun) {
  const target = run ?? (item?.currentRunId ? runs.value.find(r => r.id === item.currentRunId) : undefined)
  // 找不到待办就不打开：监视器要用待办的标题和位置，传 undefined 会让整个页面渲染失败
  const owner = item ?? (target ? itemOf(target) : undefined)
  if (!target || !owner) return
  monitor.value = { runId: target.id, item: owner }
}

// 有运行在途时定时刷新列表与总览：流水线条和待办状态跟着变（确认、完成、失败），不用手动点刷新
let pollTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  load()
  pollTimer = setInterval(() => {
    if (activeRuns.value.length) store.load(true)
  }, 5000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<template>
  <div class="wb-page">
    <header class="wb-pagehead">
      <div>
        <span class="wb-title">工作台</span>
        <span class="wb-sub">技术债清零 · {{ today }}</span>
      </div>
      <div class="wb-actions">
        <el-button size="small" :loading="syncing" @click="sync">立即聚合</el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load(true)">刷新</el-button>
      </div>
    </header>

    <section class="wb-card mission">
      <div class="m-stat">
        <span class="m-label">技术债余额</span>
        <b>{{ summary?.open ?? 0 }}</b>
        <span class="m-note">P0 {{ summary?.p0 ?? 0 }} · P1 {{ summary?.p1 ?? 0 }} · P2 {{ summary?.p2 ?? 0 }}</span>
      </div>
      <div class="m-stat">
        <span class="m-label">清零进度</span>
        <b>{{ donePercent }}%</b>
        <span class="m-note">已清零 {{ summary?.done ?? 0 }} / 共 {{ summary?.total ?? 0 }}</span>
      </div>
      <div class="m-stat">
        <span class="m-label">近 7 天清零</span>
        <b>{{ summary?.cleared7d ?? 0 }}</b>
        <span class="m-note">{{ (summary?.cleared7d ?? 0) > 0 && (summary?.open ?? 0) > 0
          ? `按这个速度还需约 ${Math.ceil((summary?.open ?? 0) / Math.max(1, summary?.cleared7d ?? 0))} 周` : '还没有清零记录' }}</span>
      </div>
      <div class="m-stat">
        <span class="m-label">在途修复</span>
        <b>{{ summary?.running ?? 0 }}</b>
        <span class="m-note" :class="{ warn: summary?.waiting }">
          {{ summary?.waiting ? `${summary?.waiting} 个方案等你确认` : '全部自动推进中' }}
        </span>
      </div>
      <div class="m-ring" :style="{ background: `conic-gradient(#2563eb ${donePercent}%, #e5e7eb 0)` }">
        <i><b>{{ donePercent }}%</b><span>已清零</span></i>
      </div>
      <div class="m-projects">
        <div class="m-sub">按项目（已清零 / 总数）</div>
        <button
          v-for="[path, stat] in projectStats"
          :key="path"
          class="m-proj"
          :class="{ on: projectFilter === path }"
          @click="projectFilter = projectFilter === path ? '' : path"
        >
          <span class="pname" :title="path">{{ projectLabel(path) }}</span>
          <span class="pbar"><i :style="{ width: (stat.done / Math.max(1, stat.total)) * 100 + '%' }" /></span>
          <span class="pnum">{{ stat.done }}/{{ stat.total }}</span>
        </button>
      </div>
    </section>

    <div class="wb-body">
      <section class="wb-card tbl">
        <div class="t-head">
          <b>待办</b><span class="wb-muted">{{ rows.length }} 项</span>
          <el-input v-model="query" size="small" placeholder="搜索标题或位置" clearable style="width: 220px" />
          <label class="r-check"><input v-model="onlyP0" type="checkbox" />只看 P0</label>
          <span class="spacer" />
          <template v-if="selectedIds.length">
            <span class="sel">已选 {{ selectedIds.length }} 项</span>
            <el-button type="primary" size="small" :icon="VideoPlay" @click="bulkFix">批量自动修复</el-button>
          </template>
        </div>
        <div class="t-scroll">
          <table>
            <thead>
              <tr>
                <th class="w-check"><input type="checkbox" :checked="allChecked" :disabled="!selectable.length" @change="toggleAll" /></th>
                <th>严重度</th><th>待办</th><th>项目</th><th>来源</th><th>证据</th><th>近 7 日</th><th>状态 / 进度</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in rows" :key="item.itemKey" :class="{ waiting: item.status === 'WAITING' }">
                <td class="w-check"><input type="checkbox" :checked="selectedIds.includes(item.id)" :disabled="item.status !== 'NEW'" @change="toggle(item.id)" /></td>
                <td><span class="wb-tag sev" :class="'sev-' + item.severity">{{ item.severity }}</span></td>
                <td class="c-title" @click="openDetail(item)">
                  <div class="t-title">{{ item.title }}</div>
                  <div class="wb-mono wb-muted">{{ item.location }}</div>
                </td>
                <td class="c-proj" :title="item.projectPath">{{ projectLabel(item.projectPath) }}</td>
                <td><span class="wb-tag src" :class="'src-' + item.source">{{ item.source === 'log' ? '日志' : item.source === 'arch_ai' ? '架构·AI' : '架构·规则' }}</span></td>
                <td class="c-ev" :title="`${chainText(item.chainStatus)} · 置信度 ${confText(item.confidence)}`">
                  <span class="dots3" :class="item.confidence ?? ''"><i /><i /><i /></span>
                </td>
                <td><SparkLine v-if="hasTrend(item.trend)" :values="item.trend!" :width="48" :height="16" /><span v-else class="wb-muted">—</span></td>
                <td class="c-status">
                  <template v-if="item.status === 'RUNNING' || item.status === 'WAITING'">
                    <div class="prog"><i :style="{ width: runPercent(item) + '%' }" /></div>
                    <span>{{ stageLabelOf(runOf(item)?.stageIndex ?? 0, item.status) }}</span>
                  </template>
                  <span v-else class="wb-tag" :class="['st-' + item.status]">{{ STATUS_LABEL[item.status] }}</span>
                </td>
                <td class="c-actions">
                  <el-button v-if="item.status === 'NEW'" type="primary" link size="small" :icon="VideoPlay" @click="autofix(item)">自动修复</el-button>
                  <el-button v-else-if="item.status === 'FAILED'" type="primary" plain link size="small" :icon="VideoPlay" @click="autofix(item)">重试</el-button>
                  <el-button v-else-if="item.status === 'WAITING' || item.status === 'RUNNING'" type="warning" link size="small" @click="openMonitor(item)">查看进展</el-button>
                  <el-button v-else link size="small" @click="openMonitor(item)">记录</el-button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <footer class="wb-card pipe" v-if="activeRuns.length">
      <span class="pipe-title">修复流水线</span>
      <div class="tiles">
        <button
          v-for="run in activeRuns"
          :key="run.id"
          class="tile"
          :class="{ waiting: run.status === 'WAITING_CONFIRM' }"
          @click="openMonitor(undefined, run)"
        >
          <i class="tile-dot" />
          <span class="tile-title">{{ itemOf(run)?.title }}</span>
          <b>{{ runPercent(itemOf(run)) }}%</b>
          <span class="tile-stage">{{ stageLabelOf(run.stageIndex, run.status) }}</span>
        </button>
      </div>
      <el-button size="small" :icon="Monitor" @click="activeRuns[0] && openMonitor(undefined, activeRuns[0])">运行监视器</el-button>
    </footer>

    <el-drawer v-model="drawer" size="520px" :with-header="false">
      <ItemDetail v-if="detailItem" :item="detailItem" @autofix="autofix(detailItem!)" @monitor="openMonitor(detailItem!)" />
    </el-drawer>

    <RunMonitor v-if="monitor" :run-id="monitor.runId" :item="monitor.item" @close="monitor = null" />
  </div>
</template>

<style scoped>
.wb-page { display: flex; flex-direction: column; gap: 12px; min-height: calc(100vh - 96px); }
.wb-pagehead { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.wb-title { font-size: 18px; font-weight: 600; }
.wb-sub { color: #6b7280; font-size: 13px; margin-left: 10px; }
.wb-actions { display: flex; gap: 8px; }
.wb-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; min-width: 0; }
.wb-muted { color: #6b7280; font-weight: normal; }
.wb-mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.wb-tag { display: inline-flex; align-items: center; height: 20px; padding: 0 7px; border-radius: 4px; font-size: 12px; font-weight: 500; line-height: 1; white-space: nowrap; }
.sev-P0 { background: #fef2f2; color: #b91c1c; }
.sev-P1 { background: #fffbeb; color: #b45309; }
.sev-P2 { background: #f1f5f9; color: #475569; }
.src-log { background: #f5f3ff; color: #6d28d9; }
.src-arch_ai { background: #f0fdfa; color: #0f766e; }
.src-arch_rule { background: #ecfeff; color: #155e75; }
.st-NEW { background: #f3f4f6; color: #4b5563; }
.st-RUNNING { background: #dbeafe; color: #1d4ed8; }
.st-WAITING { background: #fef3c7; color: #b45309; }
.st-DONE { background: #dcfce7; color: #15803d; }
.st-FAILED, .st-ABORTED { background: #fee2e2; color: #b91c1c; }
.st-IGNORED { background: #f3f4f6; color: #9ca3af; }
.iter { background: #eef2ff; color: #4338ca; }
.pulse { animation: pulse 1.6s ease-in-out infinite; }
@keyframes pulse { 50% { box-shadow: 0 0 0 4px #fde68a; } }

/* 总览 */
.mission { display: grid; grid-template-columns: repeat(4, minmax(104px, 0.8fr)) 76px minmax(340px, 1.5fr); gap: 18px; align-items: center; padding: 14px 18px; }
.m-stat { display: flex; flex-direction: column; gap: 2px; }
.m-label { font-size: 12px; color: #6b7280; }
.m-stat b { font-size: 26px; line-height: 1.2; font-variant-numeric: tabular-nums; }
.m-note { font-size: 12px; color: #6b7280; }
.m-note.warn { color: #b45309; font-weight: 600; }
.m-ring { width: 76px; height: 76px; border-radius: 50%; display: grid; place-items: center; }
.m-ring i { width: 58px; height: 58px; border-radius: 50%; background: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; font-style: normal; }
.m-ring b { font-size: 14px; color: #1d4ed8; }
.m-ring span { font-size: 10px; color: #6b7280; }
.m-sub { font-size: 11px; color: #6b7280; margin-bottom: 6px; grid-column: 1 / -1; }
.m-projects { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 16px; }
.m-proj { display: grid; grid-template-columns: minmax(0, 1fr) 44px 32px; align-items: center; gap: 8px; border: 0; background: transparent; font-size: 12px; color: #374151; padding: 2px 6px; border-radius: 5px; cursor: pointer; text-align: left; }
.m-proj:hover, .m-proj.on { background: #eff6ff; color: #1d4ed8; }
.pname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pbar { height: 5px; border-radius: 3px; background: #e5e7eb; overflow: hidden; }
.pbar i { display: block; height: 100%; background: #2563eb; }
.pnum { font-variant-numeric: tabular-nums; color: #6b7280; }

/* 表格 */
.wb-body { display: flex; flex: 1; min-height: 320px; }
.tbl { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.t-head { display: flex; align-items: center; gap: 12px; padding: 9px 14px; border-bottom: 1px solid #f3f4f6; font-size: 13px; }
.r-check { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #374151; }
.r-check input { accent-color: #2563eb; }
.spacer { flex: 1; }
.sel { color: #1d4ed8; font-weight: 600; }
.t-scroll { flex: 1; overflow: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { position: sticky; top: 0; background: #fcfcfd; text-align: left; font-weight: 500; font-size: 12px; color: #6b7280; padding: 7px 10px; border-bottom: 1px solid #e5e7eb; white-space: nowrap; z-index: 1; }
td { padding: 8px 10px; border-bottom: 1px solid #f3f4f6; vertical-align: middle; }
tbody tr:hover { background: #f9fafb; }
tr.waiting { background: #fffbeb; }
.w-check { width: 34px; text-align: center; }
input[type='checkbox'] { accent-color: #2563eb; }
.t-title { max-width: 430px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.c-title { cursor: pointer; }
.c-title .wb-mono { font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 430px; }
.c-proj { font-size: 12px; color: #4b5563; white-space: nowrap; max-width: 130px; overflow: hidden; text-overflow: ellipsis; }
.dots3 { display: inline-flex; gap: 3px; }
.dots3 i { width: 7px; height: 7px; border-radius: 50%; background: #d1d5db; }
.dots3.high i { background: #15803d; }
.dots3.medium i:nth-child(-n + 2) { background: #d97706; }
.dots3.low i:nth-child(1) { background: #b91c1c; }
.c-status { min-width: 160px; }
.prog { height: 4px; border-radius: 2px; background: #e5e7eb; margin-bottom: 4px; overflow: hidden; }
.prog i { display: block; height: 100%; background: #2563eb; }
.c-status span:not(.wb-tag) { font-size: 12px; color: #1d4ed8; }
.c-actions { white-space: nowrap; width: 96px; }

/* 流水线 */
/* 有运行时吸在视口底部：窄屏下内容再长，流水线与监视器入口也始终可见 */
.pipe { display: flex; align-items: center; gap: 12px; padding: 8px 14px; position: sticky; bottom: 10px; z-index: 5; box-shadow: 0 -4px 16px rgba(17, 24, 39, 0.08); }
.pipe-title { font-weight: 600; font-size: 13px; flex-shrink: 0; }
.tiles { display: flex; gap: 8px; overflow-x: auto; flex: 1; min-width: 0; }
/* 窄屏（侧边栏占了大部分宽度）：标题、运行条目、监视器入口上下排开，条目占满一行，不被挤成看不见 */
@media (max-width: 720px) {
  .pipe { flex-direction: column; align-items: stretch; }
  .tiles { flex: none; }
}
.tile { display: grid; grid-template-columns: auto minmax(0, 170px) auto; align-items: center; gap: 3px 8px; padding: 5px 10px; border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; cursor: pointer; text-align: left; flex-shrink: 0; max-width: 100%; }
.tile.waiting { border-color: #f59e0b; background: #fffbeb; }
.tile-dot { width: 7px; height: 7px; border-radius: 50%; background: #2563eb; }
.tile.waiting .tile-dot { background: #f59e0b; animation: blink 1.4s infinite; }
.tile-title { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #111827; }
.tile-stage { grid-column: 2; font-size: 12px; color: #6b7280; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tile b { font-size: 12px; color: #1d4ed8; font-variant-numeric: tabular-nums; }
@keyframes blink { 50% { opacity: 0.25; } }
</style>
