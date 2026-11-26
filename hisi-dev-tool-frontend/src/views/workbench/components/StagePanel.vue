<script setup lang="ts">
/** 一个阶段的产物面板：按阶段键渲染 checkpoint 里的内容。 */
import { computed } from 'vue'
import type { ProposalOption, WorkbenchItem } from '@/types/workbench'

const props = defineProps<{ stageKey: string; stageLabel: string; output: Record<string, any> | null; item: WorkbenchItem | null }>()
const emit = defineEmits<{ (e: 'confirm', optionId: string, push: boolean): void }>()

const options = computed<ProposalOption[]>(() => {
  const raw = props.output?.options
  return Array.isArray(raw) ? (raw as ProposalOption[]) : []
})
const testCases = computed<any[]>(() => {
  const raw = props.output?.testCases
  return Array.isArray(raw) ? raw : []
})
const typeLabel: Record<string, string> = { repro: '复现用例', unit: '单元用例', api: 'API 链路用例', e2e: '前端 E2E 用例' }
const riskLabel: Record<string, string> = { low: '低', medium: '中', high: '高' }
const anchors = computed<any[]>(() => {
  const raw = props.output?.anchors
  return Array.isArray(raw) ? raw : []
})
</script>

<template>
  <div class="sp">
    <p v-if="!output" class="sp-empty">该阶段还没有产物</p>

    <template v-else-if="stageKey === 'evidence'">
      <ul class="anchors">
        <li v-for="a in anchors" :key="a.id"><code>[{{ a.id }}]</code> {{ a.text }}</li>
      </ul>
      <p class="sp-line" v-if="output">
        图谱：{{ output.graphStatus === 'found' ? '已命中' : '未命中（保留待办自带证据）' }}
        · 置信度 {{ output.confidence }}
      </p>
    </template>

    <div v-else-if="stageKey === 'proposal'" class="options">
      <div v-for="p in options" :key="p.id" class="option">
        <div class="option-body">
          <div class="option-title">
            {{ p.id }} · {{ p.title }}<span v-if="p.id === output?.recommendation" class="rec">推荐</span>
          </div>
          <div class="option-sum">{{ p.summary }}</div>
          <div class="option-meta">风险 {{ riskLabel[p.risk] ?? p.risk }} · 预计 {{ p.effort }} · 触及 {{ p.touchedFiles }} 个文件</div>
        </div>
      </div>
    </div>

    <div v-else-if="stageKey === 'spec'" class="doc">
      <h5>目标</h5>
      <p>{{ output.goal }}</p>
      <h5>验收标准</h5>
      <ul>
        <li v-for="(a, i) in output.acceptanceCriteria || []" :key="i">{{ a }}</li>
      </ul>
      <h5>测试用例（Spec 阶段确定，回归与 E2E 只执行这份清单）</h5>
      <table class="tc">
        <tbody>
          <tr v-for="(tc, i) in testCases" :key="i">
            <td class="tc-type">{{ typeLabel[tc.type] ?? tc.type }}</td>
            <td>
              <b>{{ tc.name }}</b>
              <div class="tc-target"><code>{{ tc.target }}</code></div>
              <div class="tc-verify">{{ tc.verify }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <h5 v-if="output.nonGoals?.length">非目标</h5>
      <ul v-if="output.nonGoals?.length">
        <li v-for="(n, i) in output.nonGoals" :key="i">{{ n }}</li>
      </ul>
    </div>

    <ul v-else-if="stageKey === 'tasks'" class="tasks">
      <li v-for="(t, i) in output.tasks || []" :key="i">
        <span class="task-idx">{{ Number(i) + 1 }}</span>
        <div>
          <div>{{ t.title }}</div>
          <div class="tc-target" v-if="t.file"><code>{{ t.file }}</code><span v-if="t.verify"> · {{ t.verify }}</span></div>
        </div>
      </li>
    </ul>

    <div v-else-if="stageKey === 'develop'" class="doc">
      <p class="sp-line">分支 <code>{{ output.branch }}</code> · worktree <code>{{ output.worktree }}</code></p>
      <ul class="files">
        <li v-for="f in output.changedFiles || []" :key="f"><code>{{ f }}</code></li>
      </ul>
    </div>

    <div v-else-if="stageKey === 'tests'" class="doc">
      <p v-if="output?.reason" class="sp-muted">未通过：{{ output.reason }}</p>
      <div class="metrics">
        <div><b>{{ output?.surefire?.tests ?? '-' }}</b><span>用例</span></div>
        <div><b>{{ output?.surefire?.failures ?? '-' }}</b><span>失败</span></div>
        <div><b>{{ output?.surefire?.errors ?? '-' }}</b><span>错误</span></div>
      </div>
      <ul v-if="output?.surefire?.failed?.length" class="failed-list">
        <li v-for="f in output.surefire.failed" :key="f"><code>{{ f }}</code></li>
      </ul>
    </div>

    <div v-else-if="stageKey === 'e2e'" class="doc">
      <ul class="steps">
        <li v-for="(s, i) in output.steps || []" :key="i"
          :class="{ bad: s.includes('失败') || s.includes('没有') }">{{ s }}</li>
      </ul>
      <p v-if="output.reason" class="sp-muted">未通过：{{ output.reason }}</p>
      <div v-if="output.apiResults?.length" class="sub">API 用例</div>
      <table v-if="output.apiResults?.length" class="tc">
        <tbody>
          <tr v-for="r in output.apiResults" :key="r.name">
            <td class="tc-type" :class="r.passed ? 'ok' : 'bad'">{{ r.passed ? '✓' : '✗' }}</td>
            <td>
              {{ r.name }} <code>{{ r.request }}</code>
              <template v-if="r.status">→ {{ r.status }}（期望 {{ r.expected }}）</template>
              <span v-else class="bad"> · {{ r.error }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="output.frontendE2e" class="sp-line">前端 E2E：{{ output.frontendE2e.summary }}</p>
    </div>

    <div v-else-if="stageKey === 'ship'" class="doc">
      <p class="sp-line">分支 <code>{{ output.branch }}</code></p>
      <p class="sp-line">提交 <code>{{ output.commit }}</code></p>
      <p class="sp-line">{{ output.pushed ? '已推送到 origin' : '仅本地提交' }} · 不自动合并主干</p>
    </div>

    <p v-else class="sp-empty">产物：{{ JSON.stringify(output).slice(0, 300) }}</p>
  </div>
</template>

<style scoped>
.sp { font-size: 13px; line-height: 1.65; min-width: 0; }
.sp-empty, .sp-muted { color: #9ca3af; }
.sp-line { margin-top: 6px; }
code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; background: #f3f4f6; padding: 1px 5px; border-radius: 4px; word-break: break-all; }
.anchors, .tasks, .failed-list { list-style: none; display: grid; gap: 5px; }
.options { display: grid; gap: 8px; }
.option { display: flex; gap: 10px; padding: 8px 10px; border: 1px solid #e5e7eb; border-radius: 6px; }
.option-title { font-weight: 600; }
.rec { margin-left: 6px; font-size: 11px; font-weight: 500; color: #1d4ed8; background: #dbeafe; border-radius: 3px; padding: 0 5px; }
.option-sum { color: #4b5563; }
.option-meta { color: #6b7280; font-size: 12px; }
.doc h5 { font-size: 12px; color: #6b7280; margin: 8px 0 2px; font-weight: 500; }
.doc ul { padding-left: 18px; }
.metrics { display: flex; gap: 24px; margin-top: 6px; }
.metrics div { display: flex; flex-direction: column; }
.metrics b { font-size: 16px; }
.metrics span { color: #6b7280; font-size: 12px; }
.tc { width: 100%; border-collapse: collapse; margin-top: 4px; }
.tc td { padding: 5px 8px; border-bottom: 1px solid #f3f4f6; vertical-align: top; }
.tc-type { white-space: nowrap; color: #6b7280; font-size: 12px; width: 90px; }
.tc-type.ok { color: #15803d; }
.tc-type.bad { color: #b91c1c; }
.tc-target { font-size: 12px; color: #6b7280; }
.tc-verify { font-size: 12px; color: #4b5563; }
.tasks li { display: flex; gap: 8px; align-items: baseline; }
.task-idx { color: #2563eb; font-size: 12px; width: 14px; }
.files { list-style: none; display: grid; gap: 3px; }
.steps { list-style: none; }
.steps li.bad, .tc .bad { color: #b91c1c; }
.sub { font-size: 12px; color: #6b7280; margin-top: 8px; }
</style>
