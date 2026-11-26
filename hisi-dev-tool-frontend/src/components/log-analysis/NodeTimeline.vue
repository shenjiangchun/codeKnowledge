<script setup lang="ts">
/**
 * NodeTimeline — 实时 DAG 节点执行进度条
 *
 * 节点不写死：按 WebSocket 事件里出现的先后顺序显示，
 * v1 的 5 节点与 v2 的 6 节点都能展示。
 */
import { computed } from 'vue'
import type { LogNodeEvent } from '@/composables/useLogAnalysisWebSocket'

interface NodeState {
  name: string
  label: string
  status: 'pending' | 'running' | 'done' | 'error'
  durationMs?: number
  error?: string
}

const props = defineProps<{
  events: LogNodeEvent[]
}>()

/**
 * 节点显示名。键是后端事件里的 nodeName（即节点的 name()），
 * 同时覆盖 v1 与 v2 的节点名——v1 的 ParseNode / ReportNode 在 v2 里被原样复用。
 */
const NODE_LABELS: Record<string, string> = {
  ParseNode: '日志解析',
  KgSearchNode: '知识图谱检索',
  CodeContextNode: '代码上下文',
  ClaudeAnalyzeNode: 'AI 根因分析',
  KgEvidenceV2: '图谱取证',
  HypothesisV2: '根因假设',
  VerifyV2: '验证',
  SynthesisV2: '综合成文',
  ReportNode: '报告生成'
}

const nodeStates = computed<NodeState[]>(() => {
  const order: string[] = []
  const states = new Map<string, Partial<NodeState>>()

  for (const event of props.events) {
    // 只有节点级事件才对应时间线上的一项；DAG_COMPLETE 的 nodeName 是「_dag」，不是节点
    if (event.type === 'DAG_COMPLETE') continue
    if (!order.includes(event.nodeName)) order.push(event.nodeName)
    if (event.type === 'NODE_START') {
      states.set(event.nodeName, { status: 'running' })
    } else if (event.type === 'NODE_COMPLETE') {
      states.set(event.nodeName, {
        status: 'done',
        durationMs: event.payload?.durationMs
      })
    } else if (event.type === 'NODE_ERROR') {
      states.set(event.nodeName, {
        status: 'error',
        error: event.payload?.error
      })
    }
  }

  return order.map(name => ({
    name,
    label: NODE_LABELS[name] || name,
    status: (states.get(name)?.status || 'pending') as NodeState['status'],
    durationMs: states.get(name)?.durationMs,
    error: states.get(name)?.error
  }))
})

const isComplete = computed(() => props.events.some(e => e.type === 'DAG_COMPLETE'))
const totalDuration = computed(() => {
  const evt = props.events.find(e => e.type === 'DAG_COMPLETE')
  return evt?.payload?.totalDurationMs
})

function statusIcon(status: NodeState['status']): string {
  switch (status) {
    case 'running': return '⏳'
    case 'done': return '✅'
    case 'error': return '❌'
    default: return '○'
  }
}
</script>

<template>
  <div class="node-timeline">
    <div class="timeline-header">
      <span class="timeline-title">分析进度</span>
      <el-tag v-if="isComplete" type="success" size="small">完成</el-tag>
      <el-tag v-else-if="nodeStates.some(n => n.status === 'running')" type="warning" size="small">执行中</el-tag>
      <el-tag v-else-if="nodeStates.some(n => n.status === 'error')" type="danger" size="small">失败</el-tag>
      <el-tag v-else-if="nodeStates.length === 0" type="info" size="small">等待节点事件</el-tag>
    </div>

    <div v-if="nodeStates.length" class="timeline-nodes">
      <div
        v-for="(node, idx) in nodeStates"
        :key="node.name"
        class="timeline-node"
        :class="[`status-${node.status}`]"
      >
        <div class="node-connector" v-if="idx > 0">
          <span class="connector-line" :class="{ active: node.status !== 'pending' }"></span>
        </div>
        <div class="node-badge">
          <span class="node-icon">{{ statusIcon(node.status) }}</span>
        </div>
        <div class="node-info">
          <span class="node-label">{{ node.label }}</span>
          <span v-if="node.durationMs" class="node-duration">{{ node.durationMs }}ms</span>
          <span v-if="node.error" class="node-error">{{ node.error }}</span>
        </div>
      </div>
    </div>
    <div v-else class="timeline-empty">分析尚未开始，节点启动后会在这里逐个显示。</div>

    <div v-if="totalDuration" class="timeline-footer">
      总耗时: {{ totalDuration }}ms
    </div>
  </div>
</template>

<style scoped>
.node-timeline {
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}
.timeline-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}
.timeline-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}
.timeline-nodes {
  display: flex;
  align-items: flex-start;
  gap: 0;
}
.timeline-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
  position: relative;
}
.node-connector {
  position: absolute;
  top: 14px;
  right: 50%;
  width: 100%;
  height: 2px;
  z-index: 0;
}
.connector-line {
  display: block;
  width: 100%;
  height: 2px;
  background: #dcdfe6;
  transition: background 0.3s;
}
.connector-line.active {
  background: #67c23a;
}
.node-badge {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border: 2px solid #dcdfe6;
  z-index: 1;
  transition: border-color 0.3s;
}
.status-running .node-badge {
  border-color: #e6a23c;
  animation: pulse 1.5s infinite;
}
.status-done .node-badge {
  border-color: #67c23a;
}
.status-error .node-badge {
  border-color: #f56c6c;
}
.node-icon {
  font-size: 14px;
}
.node-info {
  margin-top: 8px;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.node-label {
  font-size: 12px;
  color: #606266;
  font-weight: 500;
}
.node-duration {
  font-size: 11px;
  color: #909399;
}
.node-error {
  font-size: 11px;
  color: #f56c6c;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.timeline-footer {
  margin-top: 12px;
  text-align: right;
  font-size: 12px;
  color: #909399;
}
.timeline-empty {
  font-size: 12px;
  color: #909399;
}
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(230, 162, 60, 0.4); }
  50% { box-shadow: 0 0 0 6px rgba(230, 162, 60, 0); }
}
</style>
