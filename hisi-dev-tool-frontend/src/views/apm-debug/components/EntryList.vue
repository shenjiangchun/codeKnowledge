<script setup lang="ts">
import { computed, ref } from 'vue'
import { useApmStore } from '@/stores/apmStore'
import type { KgEntryPoint } from '@/types/apm'

const store = useApmStore()

const searchQuery = ref('')

interface EntryGroup {
  className: string
  entries: KgEntryPoint[]
}

/** Filter entries by search query (matches method, path, or class name) */
const filteredEntries = computed<KgEntryPoint[]>(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return store.entryPoints
  return store.entryPoints.filter(entry => {
    const path = (entry.httpPath || entry.entryKey || '').toLowerCase()
    const method = (entry.httpMethod || '').toLowerCase()
    const info = (entry.entryInfo || entry.nodeId || '').toLowerCase()
    return path.includes(q) || method.includes(q) || info.includes(q)
  })
})

const groupedEntries = computed<EntryGroup[]>(() => {
  const groups = new Map<string, KgEntryPoint[]>()

  for (const entry of filteredEntries.value) {
    // Extract class name from entryInfo or nodeId
    // entryInfo: "com.example.UserController.getUsers()" or null
    // nodeId: "projectPath:HTTP_com.example.UserController.method"
    const className = extractClassName(entry.entryInfo || extractInfoFromNodeId(entry.nodeId))
    if (!groups.has(className)) {
      groups.set(className, [])
    }
    groups.get(className)!.push(entry)
  }

  return Array.from(groups.entries())
    .map(([className, entries]) => ({ className, entries }))
    .sort((a, b) => a.className.localeCompare(b.className))
})

function extractClassName(entryInfo: string | null | undefined): string {
  if (!entryInfo) return 'Unknown'
  // "com.example.UserController.getUsers()" -> "UserController"
  const dotParts = entryInfo.split('.')
  // Find the class name (capitalized part before the method)
  for (let i = dotParts.length - 1; i >= 0; i--) {
    const part = dotParts[i]
    if (part.includes('(')) continue // method
    if (part.length > 0 && part[0] === part[0].toUpperCase() && part[0] !== part[0].toLowerCase()) {
      return part
    }
  }
  return dotParts.length > 1 ? dotParts[dotParts.length - 2] : entryInfo
}

/**
 * Extract class info from nodeId when entryInfo is null.
 * nodeId format: "projectPath:HTTP_com.example.Controller.method"
 */
function extractInfoFromNodeId(nodeId: string): string {
  const colonIdx = nodeId.indexOf(':')
  if (colonIdx < 0) return nodeId
  let methodPart = nodeId.substring(colonIdx + 1)
  // Remove prefix like "HTTP_" or "FEIGN_"
  const underscoreIdx = methodPart.indexOf('_')
  if (underscoreIdx >= 0 && underscoreIdx < 10) {
    methodPart = methodPart.substring(underscoreIdx + 1)
  }
  return methodPart
}

const methodTagType: Record<string, string> = {
  GET: 'success',
  POST: 'warning',
  PUT: '',
  DELETE: 'danger',
  PATCH: 'info',
}

function handleEntryClick(entry: KgEntryPoint): void {
  store.selectEntry(entry)
}
</script>

<template>
  <div class="entry-list">
    <div class="list-header">
      <span class="label">API 入口</span>
      <el-tag size="small" type="info" round>
        {{ filteredEntries.length }}/{{ store.entryPoints.length }}
      </el-tag>
    </div>

    <!-- Search filter -->
    <div v-if="store.entryPoints.length > 0" class="search-bar">
      <el-input
        v-model="searchQuery"
        placeholder="搜索 API (路径/方法/类名)"
        size="small"
        clearable
        prefix-icon="Search"
      />
    </div>

    <div v-if="store.entryPointsLoading" class="loading-state">
      <el-skeleton :rows="5" animated />
    </div>

    <div v-else-if="store.entryPoints.length === 0" class="empty-state">
      <el-empty
        :image-size="60"
        description="选择项目后加载入口"
      />
    </div>

    <div v-else-if="filteredEntries.length === 0" class="empty-state">
      <el-empty
        :image-size="40"
        description="无匹配结果"
      />
    </div>

    <el-scrollbar v-else class="entry-scrollbar">
      <div class="entry-groups">
        <div
          v-for="group in groupedEntries"
          :key="group.className"
          class="entry-group"
        >
          <div class="group-title">
            <el-icon><FolderOpened /></el-icon>
            <span>{{ group.className }}</span>
            <el-tag size="small" type="info" round>
              {{ group.entries.length }}
            </el-tag>
          </div>
          <div
            v-for="entry in group.entries"
            :key="entry.nodeId"
            class="entry-item"
            :class="{ active: store.selectedEntry?.nodeId === entry.nodeId }"
            @click="handleEntryClick(entry)"
          >
            <el-tag
              :type="(methodTagType[entry.httpMethod || ''] as any) || 'info'"
              size="small"
              class="method-tag"
              effect="dark"
            >
              {{ entry.httpMethod || entry.entryType }}
            </el-tag>
            <span class="entry-path" :title="entry.entryKey">
              {{ entry.httpPath || entry.entryKey }}
            </span>
          </div>
        </div>
      </div>
    </el-scrollbar>
  </div>
</template>

<style scoped>
.entry-list {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 12px 8px;
}

.list-header .label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.search-bar {
  padding: 0 12px 8px;
}

.loading-state,
.empty-state {
  padding: 16px 12px;
}

.entry-scrollbar {
  flex: 1;
}

.entry-groups {
  padding: 0 8px 12px;
}

.entry-group {
  margin-bottom: 4px;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}

.entry-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 6px 20px;
  cursor: pointer;
  border-radius: 4px;
  transition: background-color 0.15s;
}

.entry-item:hover {
  background-color: var(--el-fill-color-light);
}

.entry-item.active {
  background-color: var(--el-color-primary-light-9);
  border-left: 2px solid var(--el-color-primary);
  padding-left: 18px;
}

.method-tag {
  flex-shrink: 0;
  min-width: 48px;
  text-align: center;
  font-size: 10px;
}

.entry-path {
  font-size: 12px;
  font-family: 'Cascadia Code', 'JetBrains Mono', monospace;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-item.active .entry-path {
  color: var(--el-color-primary);
  font-weight: 500;
}
</style>
