<script setup lang="ts">
import { computed } from 'vue'
import { SuccessFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import type { DebugReport, Hotspot } from '@/types/apm'

const props = defineProps<{
  report: DebugReport | null
}>()

const matchRate = computed(() => {
  if (!props.report || props.report.totalSpanCount === 0) return 0
  return Math.round((props.report.matchedSpanCount / props.report.totalSpanCount) * 100)
})

const hotspotColumns = [
  { prop: 'operationName', label: '操作', minWidth: 200 },
  { prop: 'className', label: '类名', minWidth: 150 },
  { prop: 'methodName', label: '方法名', minWidth: 120 },
  { prop: 'durationMs', label: '耗时(ms)', width: 100 },
  { prop: 'percentOfTotal', label: '占比(%)', width: 100 },
]

const errorColumns = [
  { prop: 'operationName', label: '操作', minWidth: 200 },
  { prop: 'exceptionType', label: '异常类型', minWidth: 150 },
  { prop: 'exceptionMessage', label: '异常信息', minWidth: 250 },
]
</script>

<template>
  <div v-if="report" class="execution-report">
    <!-- Summary card -->
    <el-card shadow="never" class="summary-card">
      <template #header>
        <span>执行摘要</span>
      </template>
      <el-row :gutter="24">
        <el-col :span="6">
          <div class="stat-item">
            <span class="stat-label">状态</span>
            <el-tag
              :type="report.success ? 'success' : 'danger'"
              effect="dark"
              size="large"
            >
              <el-icon v-if="report.success"><SuccessFilled /></el-icon>
              <el-icon v-else><CircleCloseFilled /></el-icon>
              {{ report.success ? '成功' : '失败' }}
            </el-tag>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-item">
            <span class="stat-label">总耗时</span>
            <span class="stat-value">{{ report.totalDurationMs }} ms</span>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-item">
            <span class="stat-label">Span 数量</span>
            <span class="stat-value">{{ report.totalSpanCount }}</span>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stat-item">
            <span class="stat-label">KG 匹配率</span>
            <el-progress
              :percentage="matchRate"
              :stroke-width="18"
              :text-inside="true"
            />
          </div>
        </el-col>
      </el-row>

      <div v-if="report.traceId" class="trace-info">
        <span class="stat-label">Trace ID: </span>
        <code>{{ report.traceId }}</code>
      </div>
      <div v-if="report.entryPoint" class="trace-info">
        <span class="stat-label">入口: </span>
        <code>{{ report.entryPoint }}</code>
      </div>
    </el-card>

    <!-- Hotspots table -->
    <el-card v-if="report.hotspots.length > 0" shadow="never" class="hotspot-card">
      <template #header>
        <span>热点分析 (Top {{ report.hotspots.length }})</span>
      </template>
      <el-table :data="report.hotspots" stripe size="small">
        <el-table-column
          v-for="col in hotspotColumns"
          :key="col.prop"
          :prop="col.prop"
          :label="col.label"
          :min-width="col.minWidth"
          :width="col.width"
        >
          <template #default="{ row }">
            <template v-if="col.prop === 'percentOfTotal'">
              {{ (row as Hotspot).percentOfTotal.toFixed(1) }}%
            </template>
            <template v-else>
              {{ (row as Record<string, unknown>)[col.prop] ?? '-' }}
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Errors table -->
    <el-card v-if="report.errors.length > 0" shadow="never" class="error-card">
      <template #header>
        <span style="color: #f56c6c">错误列表 ({{ report.errors.length }})</span>
      </template>
      <el-table :data="report.errors" stripe size="small">
        <el-table-column
          v-for="col in errorColumns"
          :key="col.prop"
          :prop="col.prop"
          :label="col.label"
          :min-width="col.minWidth"
        >
          <template #default="{ row }">
            {{ (row as Record<string, unknown>)[col.prop] ?? '-' }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.execution-report {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-card .stat-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-value {
  font-size: 20px;
  font-weight: bold;
  color: #303133;
}

.trace-info {
  margin-top: 12px;
}

.trace-info code {
  font-family: monospace;
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 12px;
}

.hotspot-card,
.error-card {
  margin-top: 0;
}
</style>
