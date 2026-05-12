<template>
  <div class="search-results-panel">
    <div class="panel-header">
      <span class="title">搜索结果</span>
      <span class="result-count" v-if="results.length > 0">
        共 {{ results.length }} 条结果
      </span>
    </div>

    <div v-if="loading" class="loading-state">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>正在搜索...</span>
    </div>

    <div v-else-if="results.length === 0" class="empty-state">
      <el-icon :size="48"><Search /></el-icon>
      <span>暂无搜索结果</span>
    </div>

    <div v-else class="results-list">
      <div
        v-for="result in results"
        :key="result.id"
        class="result-item"
        :class="{ selected: selectedId === result.id }"
        @click="handleSelect(result)"
      >
        <div class="result-header">
          <el-tag :type="typeTagType(result.type)" size="small">
            {{ typeText(result.type) }}
          </el-tag>
          <span class="relevance-score">{{ (result.relevanceScore * 100).toFixed(0) }}%</span>
        </div>

        <div class="result-fqn">
          {{ getFullQualifiedName(result) }}
        </div>

        <div v-if="result.metadata?.documentation" class="result-description">
          {{ result.metadata.documentation }}
        </div>
      </div>
    </div>

    <div v-if="hasMore" class="load-more">
      <el-button size="small" @click="loadMore">加载更多</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Loading, Search } from '@element-plus/icons-vue'
import type { SemanticSearchResult } from '@/types/search'

const props = defineProps<{
  results: SemanticSearchResult[]
  loading: boolean
  hasMore: boolean
  selectedId?: string
}>()

const emit = defineEmits<{
  'select': [result: SemanticSearchResult]
  'load-more': []
}>()

function typeTagType(type: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (type) {
    case 'class': return 'success'
    case 'method': return 'warning'
    case 'function': return 'info'
    case 'variable': return 'info'
    default: return 'info'
  }
}

function typeText(type: string): string {
  switch (type) {
    case 'class': return '类'
    case 'method': return '方法'
    case 'function': return '函数'
    case 'variable': return '变量'
    case 'comment': return '注释'
    case 'code_block': return '代码块'
    default: return type
  }
}

function getFullQualifiedName(result: SemanticSearchResult): string {
  const className = result.metadata?.className || ''
  const methodName = result.name || result.metadata?.methodName || ''
  if (className && methodName) {
    return `${className}.${methodName}`
  }
  return className || methodName || result.id || '未知'
}

function handleSelect(result: SemanticSearchResult) {
  emit('select', result)
}

function loadMore() {
  emit('load-more')
}
</script>

<style scoped>
.search-results-panel {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.06);
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f2f5;
}

.title {
  color: var(--el-text-color-primary, #303133);
  font-weight: 600;
  font-size: 15px;
}

.result-count {
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  background: #f0f2f5;
  padding: 2px 10px;
  border-radius: 10px;
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: #c0c4cc;
  gap: 12px;
}

.results-list {
  flex: 1;
  overflow-y: auto;
  padding-right: 4px;
}

.results-list::-webkit-scrollbar {
  width: 4px;
}

.results-list::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 4px;
}

.result-item {
  background: #fafbfc;
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 10px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.25s ease;
}

.result-item:hover {
  border-color: #c6d9f7;
  background: #f5f8ff;
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.1);
  transform: translateX(2px);
}

.result-item.selected {
  border-color: var(--el-color-primary, #409eff);
  background: linear-gradient(135deg, #ecf5ff 0%, #f5f0ff 100%);
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.15);
}

.result-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.result-name {
  color: var(--el-text-color-primary, #303133);
  font-size: 14px;
  font-weight: 500;
  flex: 1;
}

.relevance-score {
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  margin-left: auto;
  background: linear-gradient(135deg, #67c23a, #85ce61);
  padding: 2px 8px;
  border-radius: 10px;
}

.result-fqn {
  color: #5b8ff9;
  font-size: 13px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  margin-bottom: 6px;
  word-break: break-all;
  line-height: 1.5;
}

.result-description {
  color: #8c8c8c;
  font-size: 12px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.result-location {
  display: flex;
  gap: 4px;
  margin-bottom: 8px;
}

.file-path {
  color: var(--el-color-primary, #409eff);
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
}

.line-range {
  color: var(--el-color-warning, #e6a23c);
  font-size: 12px;
}

.result-snippet {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 8px;
  margin-bottom: 8px;
}

.result-snippet pre {
  margin: 0;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--el-text-color-regular, #606266);
  white-space: pre-wrap;
}

.result-metadata {
  display: flex;
  gap: 8px;
  font-size: 12px;
}

.meta-class {
  color: var(--el-color-success, #67c23a);
}

.meta-signature {
  color: var(--el-text-color-secondary, #909399);
}

.load-more {
  text-align: center;
  padding: 12px;
}

.load-more .el-button {
  border-radius: 18px;
  padding: 8px 28px;
}
</style>