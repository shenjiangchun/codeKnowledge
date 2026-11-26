<script setup lang="ts">
/** 待办详情抽屉（原型 A 的决策上下文）：为什么重要 → 证据 → 影响面 → 建议 → 自动修复。 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Monitor, VideoPlay } from '@element-plus/icons-vue'
import { workbenchApi } from '@/api/workbench'
import type { WorkbenchItem } from '@/types/workbench'

const props = defineProps<{ item: WorkbenchItem }>()
const emit = defineEmits<{ (e: 'autofix'): void; (e: 'monitor'): void }>()
const busy = ref(false)

const evidence = computed<string[]>(() => {
  try {
    const parsed = JSON.parse(props.item.evidenceJson ?? '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
})
const sourceRef = computed<Record<string, string>>(() => {
  try {
    return JSON.parse(props.item.sourceRefJson ?? '{}')
  } catch {
    return {}
  }
})

const chainText = (chain: string | null) => ({
  verified: '因果链已验证', stack_verified: '调用链与堆栈一致', at_throw_site: '根因落在抛出点',
  unverified: '尚未证实', confirmed: '智能体判定属实', detected: '规则检出',
}[chain ?? ''] ?? (chain ?? ''))
const confText = (c: string | null) => ({ high: '高', medium: '中', low: '低' }[c ?? ''] ?? (c ?? ''))
const STATUS_LABEL: Record<string, string> = {
  NEW: '待处理', RUNNING: '修复中', WAITING: '待你确认', DONE: '已清零', FAILED: '失败', IGNORED: '已忽略',
}
const sourceLabel = (source: string) => ({ log: '日志分析', arch_rule: '架构规则', arch_ai: '架构深度分析' }[source] ?? source)

function goSource() {
  const refs = sourceRef.value
  if (refs.reportId) {
    window.open(`/log-analysis/report/${refs.reportId}`, '_blank')
  } else {
    ElMessage.info('该待办来自架构分析，可在架构诊断页查看原始报告')
  }
}

async function ignore() {
  busy.value = true
  try {
    await workbenchApi.ignore(props.item.id)
    ElMessage.success('已忽略')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="wb-item-detail">
    <header class="d-head">
      <div class="d-tags">
        <span class="wb-tag sev" :class="'sev-' + item.severity">{{ item.severity }}</span>
        <span class="wb-tag src" :class="'src-' + item.source">{{ sourceLabel(item.source) }}</span>
        <span class="wb-tag" :class="'st-' + item.status">{{ STATUS_LABEL[item.status] }}</span>
        <span class="wb-muted wb-mono d-id">{{ item.itemKey }}</span>
      </div>
      <h3>{{ item.title }}</h3>
      <div class="wb-mono wb-muted">{{ item.location }}</div>
      <div class="d-meta">
        <span :title="item.projectPath">{{ item.projectPath.split(/[\\/]/).filter(Boolean).pop() }}</span>
        <span>出现 {{ item.occurrenceCount }} 次</span>
        <span v-if="item.lastSeenAt">最近 {{ Math.round((Date.now() / 1000 - item.lastSeenAt) / 3600) }} 小时前</span>
      </div>
    </header>
    <div class="d-body">
      <section>
        <h4>为什么重要</h4>
        <p>{{ item.why || '（无说明）' }}</p>
      </section>
      <section v-if="evidence.length">
        <h4>证据 <span class="wb-muted">{{ chainText(item.chainStatus) }} · 置信度 {{ confText(item.confidence) }}</span></h4>
        <ul class="d-ev">
          <li v-for="e in evidence" :key="e"><code>{{ e }}</code></li>
        </ul>
      </section>
      <section v-if="item.suggestion">
        <h4>建议</h4>
        <p>{{ item.suggestion }}</p>
      </section>
    </div>
    <footer class="d-foot">
      <template v-if="item.status === 'NEW'">
        <el-button type="primary" :icon="VideoPlay" :loading="busy" @click="emit('autofix')">自动修复</el-button>
        <el-button :loading="busy" @click="ignore">忽略</el-button>
        <p class="hint">启动后按 SDD 流程在后台执行：代码实证 → 方案确认（需要你点头）→ Spec → 任务 → 开发 → 回归 → 全链路 E2E → 提交。</p>
      </template>
      <template v-else-if="item.status === 'WAITING'">
        <el-button type="warning" :icon="Monitor" @click="emit('monitor')">查看方案并确认</el-button>
      </template>
      <template v-else-if="item.status === 'RUNNING'">
        <el-button :icon="Monitor" @click="emit('monitor')">查看进展</el-button>
        <p class="hint">正在后台运行，关掉页面不影响执行。</p>
      </template>
      <template v-else-if="item.status === 'DONE'">
        <span class="done-text">✓ 已清零</span>
        <el-button link type="primary" @click="emit('monitor')">查看修复记录</el-button>
      </template>
      <template v-else>
        <el-button :icon="Monitor" @click="emit('monitor')">查看详情</el-button>
      </template>
      <el-button v-if="Object.keys(sourceRef).length" link @click="goSource">打开原始报告</el-button>
    </footer>
  </div>
</template>

<style scoped>
.wb-item-detail { display: flex; flex-direction: column; height: 100%; padding: 18px 22px; }
.d-head { border-bottom: 1px solid #f3f4f6; padding-bottom: 10px; }
.d-tags { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.d-id { margin-left: auto; font-size: 11px; }
.d-head h3 { font-size: 16px; line-height: 1.45; margin: 10px 0 4px; }
.d-meta { display: flex; gap: 12px; font-size: 12px; color: #6b7280; margin-top: 6px; }
.d-body { flex: 1; overflow-y: auto; padding: 4px 0 8px; }
.d-body section { padding: 10px 0; border-bottom: 1px dashed #f3f4f6; }
.d-body h4 { font-size: 12px; color: #6b7280; font-weight: 500; margin-bottom: 4px; display: flex; justify-content: space-between; }
.d-body p { font-size: 13px; line-height: 1.7; }
.d-ev { list-style: none; display: grid; gap: 4px; }
.d-ev code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; background: #f9fafb; border: 1px solid #f3f4f6; border-radius: 4px; padding: 2px 6px; display: block; word-break: break-all; }
.d-foot { padding-top: 12px; border-top: 1px solid #e5e7eb; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.hint { flex-basis: 100%; font-size: 12px; color: #6b7280; }
.done-text { color: #15803d; font-weight: 600; }
</style>
