<template>
  <div class="entry-point-list">
    <el-table
      :data="entryPoints"
      v-loading="loading"
      stripe
      highlight-current-row
      @current-change="handleCurrentChange"
      style="width: 100%"
    >
      <el-table-column label="类型" width="70" align="center">
        <template #default="{ row }">
          <el-tooltip :content="getEntryLabel(row.entryType)" placement="top">
            <el-tag :type="getEntryTagType(row.entryType)" size="small">
              {{ getEntryIcon(row.entryType) }}
            </el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="entryKey" label="入口标识" min-width="200">
        <template #default="{ row }">
          <div class="entry-key-cell">
            <span class="entry-key">{{ formatEntryKey(row.entryKey, row.entryType) }}</span>
            <el-tag v-if="row.entryType === 'HTTP'" size="small" type="info" class="method-tag">
              {{ extractHttpMethod(row.entryInfo) }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click.stop="handleSelect(row)">
            详情
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="entryPoints.length === 0 && !loading" class="empty-state">
      <el-empty description="暂无入口点数据" :image-size="80" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { getEntryIcon, getEntryTagType, getEntryLabel, formatEntryKey } from '../utils/entryTypeUtils'
import type { EntryPoint } from '@/api/knowledgeGraph'

defineProps<{
  entryPoints: EntryPoint[]
  loading?: boolean
}>()

const emit = defineEmits<{
  select: [entry: EntryPoint]
}>()

const handleCurrentChange = (row: EntryPoint | null) => {
  if (row) {
    emit('select', row)
  }
}

const handleSelect = (row: EntryPoint) => {
  emit('select', row)
}

/**
 * 从 entryInfo 提取 HTTP 方法
 */
const extractHttpMethod = (entryInfo: string): string => {
  if (!entryInfo) return ''
  try {
    const info = JSON.parse(entryInfo)
    return info.method || ''
  } catch {
    return ''
  }
}
</script>

<style scoped>
.entry-point-list {
  height: 100%;
}

.entry-key-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.entry-key {
  font-family: monospace;
  font-size: 13px;
}

.method-tag {
  font-size: 11px;
}

.empty-state {
  padding: 20px;
}
</style>
