<script setup lang="ts">
import type { ApmSpan } from '@/types/apm'

const props = defineProps<{
  span: ApmSpan | null
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

function formatNs(ns: number): string {
  const ms = ns / 1_000_000
  return `${ms.toFixed(2)} ms`
}

const kgMatchLabels: Record<number, string> = {
  0: '精确匹配',
  1: '唯一匹配',
  2: '重载匹配',
  3: '未匹配',
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="Span 详情"
    direction="rtl"
    size="450px"
    @close="emit('close')"
  >
    <template v-if="span">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="Span ID">
          <code>{{ span.spanId }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="Trace ID">
          <code>{{ span.traceId }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="Parent Span ID">
          <code>{{ span.parentSpanId ?? '(root)' }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="操作名称">
          {{ span.operationName }}
        </el-descriptions-item>
        <el-descriptions-item label="服务名称">
          {{ span.serviceName }}
        </el-descriptions-item>
        <el-descriptions-item label="Span 类型">
          <el-tag size="small">{{ span.spanKind }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="耗时">
          {{ span.durationMs }} ms
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag
            :type="span.statusCode === 'OK' ? 'success' : span.statusCode === 'ERROR' ? 'danger' : 'info'"
            size="small"
          >
            {{ span.statusCode }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="span.statusMessage" label="状态信息">
          {{ span.statusMessage }}
        </el-descriptions-item>
        <el-descriptions-item v-if="span.className" label="类名">
          <code>{{ span.className }}</code>
        </el-descriptions-item>
        <el-descriptions-item v-if="span.methodName" label="方法名">
          <code>{{ span.methodName }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">
          {{ formatNs(span.startTimeNs) }}
        </el-descriptions-item>
        <el-descriptions-item label="结束时间">
          {{ formatNs(span.endTimeNs) }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- KG Mapping section -->
      <el-divider>知识图谱映射</el-divider>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="KG 节点 ID">
          <code v-if="span.kgNodeId">{{ span.kgNodeId }}</code>
          <span v-else style="color: #909399">未映射</span>
        </el-descriptions-item>
        <el-descriptions-item label="匹配级别">
          <el-tag
            :type="span.kgMatchLevel <= 1 ? 'success' : span.kgMatchLevel === 2 ? 'warning' : 'info'"
            size="small"
          >
            {{ kgMatchLabels[span.kgMatchLevel] ?? `级别 ${span.kgMatchLevel}` }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </template>

    <el-empty v-else description="未选择 Span" />
  </el-drawer>
</template>
