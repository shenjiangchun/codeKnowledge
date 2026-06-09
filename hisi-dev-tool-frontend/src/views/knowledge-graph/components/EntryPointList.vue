<template>
  <div class="entry-point-list">
    <!-- 搜索栏 + 分组切换 -->
    <div class="toolbar">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索入口描述..."
        clearable
        style="max-width: 200px"
        @input="handleSearch"
      />
      <el-switch
        v-model="showGrouped"
        active-text="按服务分组"
        inactive-text="平铺列表"
        style="margin-left: 12px"
      />
    </div>

    <!-- 树形分组展示 -->
    <el-tree
      v-if="showGrouped"
      :data="treeData"
      :props="treeProps"
      highlight-current
      default-expand-all
      @node-click="handleTreeClick"
      class="entry-tree"
    >
      <template #default="{ data }">
        <span v-if="data.isGroup" class="group-node">
          <el-tag type="info" size="small">{{ data.serviceName }}</el-tag>
          <span class="group-count">{{ data.totalCount }} 个入口</span>
        </span>
        <span v-else class="entry-node">
          <el-tooltip :content="getEntryLabel(data.entryType)" placement="top">
            <el-tag :type="getEntryTagType(data.entryType)" size="small">
              {{ getEntryIcon(data.entryType) }}
            </el-tag>
          </el-tooltip>
          <span class="entry-key">{{ formatEntryKey(data.entryKey, data.entryType) }}</span>
          <span v-if="data.briefDescription" class="entry-desc">{{ data.briefDescription }}</span>
        </span>
      </template>
    </el-tree>

    <!-- 平铺列表展示 -->
    <el-table
      v-else
      :data="filteredEntryPoints"
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
      <el-table-column prop="briefDescription" label="简要描述" min-width="200">
        <template #default="{ row }">
          <span class="desc-text">{{ row.briefDescription || '待生成' }}</span>
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

    <div v-if="!loading && (showGrouped ? treeData.length === 0 : filteredEntryPoints.length === 0)" class="empty-state">
      <el-empty description="暂无入口点数据" :image-size="80" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { getEntryIcon, getEntryTagType, getEntryLabel, formatEntryKey } from '../utils/entryTypeUtils'
import type { EntryPoint, ServiceEntryGroup, EntrySummary } from '@/api/knowledgeGraph'

const props = defineProps<{
  entryPoints: EntryPoint[]
  entryGroups?: ServiceEntryGroup[]
  loading?: boolean
}>()

const emit = defineEmits<{
  select: [entry: EntryPoint | EntrySummary]
}>()

const searchKeyword = ref('')
const showGrouped = ref(false)

// 平铺模式：搜索过滤
const filteredEntryPoints = computed(() => {
  if (!searchKeyword.value) return props.entryPoints
  const kw = searchKeyword.value.toLowerCase()
  return props.entryPoints.filter(ep =>
    (ep.briefDescription && ep.briefDescription.toLowerCase().includes(kw)) ||
    ep.entryKey.toLowerCase().includes(kw)
  )
})

// 树形模式：构建树数据
const treeData = computed(() => {
  if (!props.entryGroups) return []
  return props.entryGroups.map(group => ({
    isGroup: true,
    serviceName: group.serviceName,
    totalCount: group.totalCount,
    children: group.entries.map(entry => ({
      ...entry,
      isGroup: false
    }))
  })).filter(group => {
    // 搜索过滤：分组或子项匹配
    if (!searchKeyword.value) return true
    const kw = searchKeyword.value.toLowerCase()
    return group.serviceName.toLowerCase().includes(kw) ||
      group.children.some(e =>
        (e.briefDescription && e.briefDescription.toLowerCase().includes(kw)) ||
        e.entryKey.toLowerCase().includes(kw)
      )
  })
})

const treeProps = {
  children: 'children',
  label: (data: any) => data.isGroup ? data.serviceName : data.entryKey
}

const handleSearch = () => {
  // 搜索触发重新渲染
}

const handleCurrentChange = (row: EntryPoint | null) => {
  if (row) {
    emit('select', row)
  }
}

const handleSelect = (row: EntryPoint) => {
  emit('select', row)
}

const handleTreeClick = (data: any) => {
  if (!data.isGroup) {
    emit('select', data as EntrySummary)
  }
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
  display: flex;
  flex-direction: column;
}

.toolbar {
  display: flex;
  align-items: center;
  padding-bottom: 12px;
}

.entry-tree {
  flex: 1;
  overflow: auto;
}

.group-node {
  display: flex;
  align-items: center;
  gap: 8px;
}

.group-count {
  color: #999;
  font-size: 12px;
}

.entry-node {
  display: flex;
  align-items: center;
  gap: 8px;
}

.entry-key {
  font-family: monospace;
  font-size: 13px;
}

.entry-desc {
  color: #666;
  font-size: 12px;
  margin-left: 8px;
}

.entry-key-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.method-tag {
  font-size: 11px;
}

.desc-text {
  color: #666;
  font-size: 12px;
}

.empty-state {
  padding: 20px;
}
</style>