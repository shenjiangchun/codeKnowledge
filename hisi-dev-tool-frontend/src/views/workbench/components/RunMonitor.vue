<script setup lang="ts">
/**
 * 运行监视器：全屏浮层，纵向时间线 + 阶段产物 + 实时日志。
 * 关闭监视器不会中断后台运行；方案确认在这里完成（选择方案、勾选推送、确认或驳回）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import StageDots from './StageDots.vue'
import StagePanel from './StagePanel.vue'
import { workbenchApi } from '@/api/workbench'
import { useFixRunStream } from '@/composables/useFixRunStream'
import type { WorkbenchItem } from '@/types/workbench'

const props = defineProps<{ runId: number; item: WorkbenchItem }>()
const emit = defineEmits<{ (e: 'close'): void }>()

// 监视器展示的运行详情与事件流共用同一份：事件到达时由流刷新。
// （以前这里自己攥一份、只在打开时加载一次，之后再也不更新——运行推进了，监视器还停在打开时的那个阶段。）
const stream = useFixRunStream()
const run = stream.detail

const selectedStage = ref(0)
const userPickedStage = ref(false)
const chosenOption = ref('')
const pushChoice = ref(false)
const feedback = ref('')
const busy = ref(false)

const checkpoints = computed<Record<string, any>>(() => run.value?.checkpoints ?? {})
const logTail = computed(() => run.value?.logTail ?? [])
const currentStageIndex = computed(() => run.value?.stageIndex ?? 0)
const waiting = computed(() => run.value?.status === 'WAITING_CONFIRM')
const finished = computed(() => !!run.value && ['DONE', 'FAILED', 'ABORTED'].includes(run.value.status))

/** 选中的阶段跟着运行走到前沿；用户点过某一阶段后尊重用户的选择，但到了方案确认门一定回到方案确认。 */
watch([() => run.value?.stageIndex ?? 0, waiting], ([index, isWaiting]) => {
  if (isWaiting) {
    selectedStage.value = 1
  } else if (!userPickedStage.value) {
    selectedStage.value = index
  }
}, { immediate: true })

async function load() {
  stream.stop()
  run.value = await workbenchApi.runDetail(props.runId)
  // 只对没结束的运行建立事件流
  if (run.value && run.value.sessionUuid && !finished.value) {
    stream.start(run.value.sessionUuid)
  }
}

let pollTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  load()
  // 阶段切换不产生事件（只有阶段完成才有 CHECKPOINT）：定时拉一次详情兜底，进度条和日志才是活的
  pollTimer = setInterval(() => {
    if (run.value && !finished.value) void stream.refreshDetail()
  }, 3000)
})
onBeforeUnmount(() => {
  clearInterval(pollTimer)
})
watch(() => props.runId, load)

const stageState = (index: number) => {
  const status = run.value?.status
  if (status === 'DONE') return 'done'
  if (status === 'FAILED' || status === 'ABORTED') {
    if (index === currentStageIndex.value) return 'failed'
    // 回到开发后又失败的运行，后面的阶段在上一轮已经跑过；其余没跑到的阶段不能显示成「完成」
    if (index < currentStageIndex.value || (run.value?.iteration ?? 1) > 1) return 'done'
    return 'pending'
  }
  if (index < currentStageIndex.value) return 'done'
  if (index === currentStageIndex.value) return waiting.value ? 'waiting' : 'active'
  return 'pending'
}
const stateText: Record<string, string> = { pending: '未开始', active: '进行中', waiting: '等你确认', done: '完成', failed: '失败' }

async function confirm() {
  if (!chosenOption.value) {
    ElMessage.warning('请选择一个方案')
    return
  }
  busy.value = true
  try {
    await workbenchApi.confirm(props.runId, chosenOption.value, pushChoice.value)
    ElMessage.success('方案已确认，智能体继续执行')
    await load()
  } finally {
    busy.value = false
  }
}

async function reject() {
  if (!feedback.value.trim()) {
    ElMessage.warning('请说明驳回原因')
    return
  }
  busy.value = true
  try {
    await workbenchApi.reject(props.runId, feedback.value)
    ElMessage.success('已驳回，智能体将按你的反馈重新出方案')
    feedback.value = ''
    await load()
  } finally {
    busy.value = false
  }
}

async function abortRun() {
  busy.value = true
  try {
    await workbenchApi.abort(props.runId)
    ElMessage.success('已请求中止')
    await load()
  } finally {
    busy.value = false
  }
}

async function resumeRun() {
  busy.value = true
  try {
    await workbenchApi.resume(props.runId)
    ElMessage.success('已继续')
    await load()
  } finally {
    busy.value = false
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
watch(() => props.runId, () => {
  window.addEventListener('keydown', onKey)
  return () => window.removeEventListener('keydown', onKey)
}, { immediate: true })
</script>

<template>
  <div v-if="run" class="monitor">
    <header class="mo-head">
      <b>运行监视</b>
      <StageDots :run="run" :checkpoints="checkpoints" />
      <span class="mo-status" :class="run.status">{{ waiting ? '等你确认方案' : run.status }}</span>
      <span class="spacer" />
      <span class="wb-muted mo-hint">流程在后台运行，关闭监视器不会中断 · Esc 关闭</span>
      <el-button v-if="run.status === 'RUNNING'" size="small" type="danger" plain :loading="busy" @click="abortRun">中止</el-button>
      <el-button v-if="run.status === 'INTERRUPTED' || run.status === 'FAILED'" size="small" type="primary" :loading="busy" @click="resumeRun">继续</el-button>
      <el-button circle :icon="Close" @click="emit('close')" />
    </header>

    <div class="mo-body">
      <aside class="wb-card mo-left">
        <div class="mo-tags">
          <span class="wb-tag sev" :class="'sev-' + item.severity">{{ item.severity }}</span>
          <span class="wb-tag src" :class="'src-' + item.source">{{ item.source === 'log' ? '日志分析' : item.source === 'arch_ai' ? '架构深度分析' : '架构规则' }}</span>
          <span v-if="run.iteration > 1" class="wb-tag iter">第 {{ run.iteration }} 轮</span>
        </div>
        <h3>{{ item.title }}</h3>
        <div class="wb-mono wb-muted mo-loc">{{ item.location }}</div>
        <ol class="mo-tl">
          <li
            v-for="(stage, i) in run.stages"
            :key="stage.key"
            :class="[stageState(i), { sel: selectedStage === i }]"
            @click="selectedStage = i; userPickedStage = true"
          >
            <i />
            <span>{{ stage.label }}</span>
            <em>{{ stateText[stageState(i)] }}</em>
          </li>
        </ol>
        <dl class="mo-meta" v-if="run.worktreePath || run.branchName">
          <div v-if="run.branchName"><dt>分支</dt><dd class="wb-mono">{{ run.branchName }}</dd></div>
          <div v-if="run.commitHash"><dt>提交</dt><dd class="wb-mono">{{ run.commitHash.slice(0, 10) }}</dd></div>
          <div v-if="run.errorMessage"><dt>错误</dt><dd>{{ run.errorMessage }}</dd></div>
        </dl>
      </aside>

      <section class="wb-card mo-center">
        <div class="mo-stage-head">
          <b>{{ run.stages[selectedStage]?.label }}</b>
          <span class="wb-muted">{{ run.stages[selectedStage]?.hint }}</span>
        </div>
        <StagePanel
          :stage-key="run.stages[selectedStage]?.key ?? ''"
          :stage-label="run.stages[selectedStage]?.label ?? ''"
          :output="checkpoints[run.stages[selectedStage]?.key ?? ''] ?? null"
          :item="item"
        />

        <div v-if="waiting && selectedStage === 1" class="confirm-box">
          <div class="confirm-title">确认方案</div>
          <div class="confirm-options">
            <label v-for="p in ((checkpoints.proposal ?? {}) as any).options ?? []" :key="p.id" class="confirm-option" :class="{ picked: chosenOption === p.id }">
              <input v-model="chosenOption" type="radio" :value="p.id" />
              <span>{{ p.id }} · {{ p.title }}</span>
            </label>
          </div>
          <label class="push-choice"><input v-model="pushChoice" type="checkbox" />验证通过后推送到 origin 同名分支（不合并主干）</label>
          <div class="confirm-actions">
            <el-button type="primary" size="small" :loading="busy" @click="confirm">确认并继续</el-button>
            <el-input v-model="feedback" size="small" placeholder="驳回原因（重新生成方案）" style="width: 240px" />
            <el-button size="small" :loading="busy" @click="reject">驳回重出</el-button>
          </div>
        </div>
      </section>

      <section class="mo-console">
        <div class="log-head">实时日志<span v-if="run.worktreePath">{{ run.worktreePath }}</span></div>
        <pre class="log-body"><code v-for="(line, i) in logTail" :key="i">{{ line }}
</code></pre>
      </section>
    </div>
  </div>
</template>

<style scoped>
.monitor { position: fixed; top: 48px; left: 220px; right: 0; bottom: 0; z-index: 900; background: #f8fafb; display: flex; flex-direction: column; gap: 12px; padding: 12px 28px 24px; }
.mo-head { display: flex; align-items: center; gap: 14px; }
.mo-head b { font-size: 16px; white-space: nowrap; }
.mo-head .wb-dots { flex: 1; max-width: 480px; }
.mo-status { font-size: 12px; padding: 2px 10px; border-radius: 12px; background: #dbeafe; color: #1d4ed8; }
.mo-status.WAITING_CONFIRM { background: #fef3c7; color: #b45309; }
.mo-status.FAILED, .mo-status.ABORTED { background: #fee2e2; color: #b91c1c; }
.mo-status.DONE { background: #dcfce7; color: #15803d; }
.spacer { flex: 1; }
.mo-hint { font-size: 12px; }
.mo-body { flex: 1; min-height: 0; display: grid; grid-template-columns: 260px minmax(0, 1fr) minmax(300px, 32%); gap: 12px; }
/* 窄窗口或手机：三栏放不下，改成上下排列整块滚动；同时盖住侧边栏，把宽度让给内容 */
@media (max-width: 1100px) {
  .monitor { left: 0; padding: 12px 14px 24px; }
  .mo-body { display: block; overflow-y: auto; }
  .mo-left, .mo-center { overflow-y: visible; }
}
.mo-left { padding: 14px; overflow-y: auto; }
.mo-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.mo-left h3 { font-size: 14px; line-height: 1.5; margin: 8px 0 4px; }
.mo-loc { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mo-tl { list-style: none; margin-top: 14px; }
.mo-tl li { display: flex; align-items: center; gap: 8px; padding: 5px 8px; border-radius: 6px; font-size: 13px; color: #6b7280; cursor: pointer; }
.mo-tl li:hover, .mo-tl li.sel { background: #eff6ff; color: #1d4ed8; }
.mo-tl i { width: 8px; height: 8px; border-radius: 50%; background: #d1d5db; flex-shrink: 0; }
.mo-tl li.done i { background: #2563eb; }
.mo-tl li.active i { background: #2563eb; box-shadow: 0 0 0 3px #dbeafe; }
.mo-tl li.waiting i { background: #f59e0b; box-shadow: 0 0 0 3px #fef3c7; }
.mo-tl li.failed i { background: #dc2626; }
.mo-tl li.waiting { color: #b45309; font-weight: 600; }
.mo-tl li.failed { color: #b91c1c; }
.mo-tl em { margin-left: auto; font-style: normal; font-size: 12px; }
.mo-meta { margin-top: 14px; padding-top: 10px; border-top: 1px solid #f3f4f6; display: grid; gap: 6px; }
.mo-meta div { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
.mo-meta dt { color: #6b7280; }
.mo-meta dd { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mo-center { padding: 14px 18px; overflow-y: auto; }
.mo-stage-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
.mo-stage-head b { font-size: 14px; }
.mo-stage-head .wb-muted { font-size: 12px; }
.confirm-box { margin-top: 16px; padding: 12px; border: 1px solid #fde68a; background: #fffbeb; border-radius: 8px; }
.confirm-title { font-weight: 600; margin-bottom: 8px; }
.confirm-options { display: grid; gap: 6px; }
.confirm-option { display: flex; gap: 8px; align-items: flex-start; font-size: 13px; }
.confirm-option input { margin-top: 3px; accent-color: #2563eb; }
.push-choice { display: flex; gap: 6px; align-items: center; margin-top: 10px; font-size: 12px; color: #4b5563; }
.confirm-actions { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.mo-console { display: flex; flex-direction: column; min-height: 0; background: #0f172a; border-radius: 8px; overflow: hidden; }
.log-head { display: flex; justify-content: space-between; gap: 12px; padding: 8px 12px; font-size: 11px; color: #94a3b8; border-bottom: 1px solid #1e293b; }
.log-body { flex: 1; margin: 0; padding: 8px 12px; overflow-y: auto; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; line-height: 1.7; color: #cbd5e1; white-space: pre-wrap; word-break: break-all; }
</style>
