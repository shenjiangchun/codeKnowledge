<template>
  <div class="semantic-search-view">
    <!-- 已选项目提示 -->
    <el-alert
      :title="`搜索范围: ${appStore.selectedProjectNames.join(', ')}`"
      type="info"
      show-icon
      :closable="false"
      class="project-scope-alert"
    />

    <!-- 搜索输入区 -->
    <div class="search-input-section">
      <div class="search-box">
        <el-input
          v-model="searchQuery"
          placeholder="输入自然语言描述搜索代码，如：处理用户登录的方法"
          size="large"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
          <template #append>
            <el-button type="primary" @click="handleSearch" :loading="loading">
              搜索
            </el-button>
          </template>
        </el-input>
      </div>

      <!-- 搜索过滤 -->
      <div class="search-filters">
        <el-select v-model="filters.language" placeholder="编程语言" size="small" clearable>
          <el-option label="全部" value="" />
          <el-option label="Java" value="java" />
          <el-option label="Python" value="python" />
          <el-option label="JavaScript" value="javascript" />
          <el-option label="TypeScript" value="typescript" />
        </el-select>

        <el-input
          v-model="filters.filePattern"
          placeholder="文件名过滤"
          size="small"
          clearable
          style="width: 150px"
        />

        <el-slider
          v-model="thresholdPercent"
          :min="0"
          :max="100"
          :format-tooltip="(val: number) => `相关度 >= ${val}%`"
          style="width: 120px"
        />
      </div>

      <!-- 搜索建议 -->
      <div v-if="suggestions.length > 0" class="search-suggestions">
        <span class="suggestion-label">建议查询：</span>
        <el-tag
          v-for="sug in suggestions"
          :key="sug"
          size="small"
          class="suggestion-tag"
          @click="applySuggestion(sug)"
        >
          {{ sug }}
        </el-tag>
      </div>

      <!-- 搜索历史 -->
      <div v-if="history.length > 0 && !searchQuery" class="search-history">
        <span class="history-label">最近搜索：</span>
        <el-tag
          v-for="item in history.slice(0, 5)"
          :key="item"
          size="small"
          type="info"
          class="history-tag"
          @click="applyHistory(item)"
        >
          {{ item }}
        </el-tag>
      </div>
    </div>

    <!-- 搜索结果区域 -->
    <div class="search-results-area">
      <div class="results-column">
        <SearchResultsPanel
          :results="results"
          :loading="loading"
          :has-more="hasMore"
          :selected-id="selectedResult?.id"
          @select="handleSelectResult"
          @load-more="loadMoreResults"
        />
      </div>

      <div class="preview-column">
        <CodePreviewPanel :selected-result="selectedResult" />
      </div>
    </div>

    <!-- 搜索统计 -->
    <div v-if="totalResults > 0" class="search-stats">
      <span>查询耗时: {{ queryTime }}ms</span>
      <span>总结果数: {{ totalResults }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import SearchResultsPanel from './components/SearchResultsPanel.vue'
import CodePreviewPanel from './components/CodePreviewPanel.vue'
import { semanticSearchV2, getSearchHistory } from '@/api/search'
import { useAppStore } from '@/stores/app'
import type { SemanticSearchResult, SearchFilters } from '@/types/search'

const appStore = useAppStore()

// 状态
const searchQuery = ref('')
const filters = ref<SearchFilters>({
  language: '',
  filePattern: ''
})
const thresholdPercent = ref(50)
const results = ref<SemanticSearchResult[]>([])
const selectedResult = ref<SemanticSearchResult | undefined>()
const loading = ref(false)
const hasMore = ref(false)
const totalResults = ref(0)
const queryTime = ref(0)
const suggestions = ref<string[]>([])
const history = ref<string[]>([])
const currentPage = ref(0)
const pageSize = 20

// 计算属性
const threshold = computed(() => thresholdPercent.value / 100)

// 方法
async function handleSearch() {
  if (!searchQuery.value.trim()) {
    ElMessage.warning('请输入搜索内容')
    return
  }

  loading.value = true
  currentPage.value = 0
  selectedResult.value = undefined

  try {
    const projectPaths = appStore.getSelectedProjectPaths()
    const response = await semanticSearchV2({
      query: searchQuery.value,
      projectPath: projectPaths[0] || '',
      projectPaths,
      limit: pageSize,
      threshold: threshold.value,
      filters: filters.value
    })

    results.value = response.results
    totalResults.value = response.total
    queryTime.value = response.queryTime
    hasMore.value = response.results.length < response.total

    // 获取建议
    if (response.suggestedQueries) {
      suggestions.value = response.suggestedQueries
    }

    // 自动选择第一个结果
    if (results.value.length > 0) {
      selectedResult.value = results.value[0]
    }
  } catch (e: any) {
    ElMessage.error(`搜索失败: ${e.message || '未知错误'}`)
    results.value = []
  } finally {
    loading.value = false
  }
}

async function loadMoreResults() {
  if (loading.value || !hasMore.value) return

  loading.value = true
  currentPage.value++

  try {
    const projectPaths = appStore.getSelectedProjectPaths()
    const response = await semanticSearchV2({
      query: searchQuery.value,
      projectPath: projectPaths[0] || '',
      projectPaths,
      limit: pageSize,
      threshold: threshold.value,
      filters: filters.value
    })

    results.value.push(...response.results)
    hasMore.value = results.value.length < response.total
  } catch (e: any) {
    ElMessage.error(`加载更多失败: ${e.message}`)
  } finally {
    loading.value = false
  }
}

function handleSelectResult(result: SemanticSearchResult) {
  selectedResult.value = result
}

function applySuggestion(suggestion: string) {
  searchQuery.value = suggestion
  handleSearch()
}

function applyHistory(item: string) {
  searchQuery.value = item
  handleSearch()
}

// 加载历史
async function loadHistory() {
  try {
    history.value = await getSearchHistory(10)
  } catch {
    history.value = []
  }
}

onMounted(() => {
  loadHistory()
})
</script>

<style scoped>
.semantic-search-view {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
  padding: 20px 24px;
  background: linear-gradient(135deg, #f0f4ff 0%, #f5f7fa 50%, #f0faf5 100%);
  gap: 16px;
}

.project-scope-alert {
  border-radius: 10px;
  border: none;
  background: linear-gradient(90deg, #e8f0fe 0%, #f0f4ff 100%);
}

.search-input-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px;
  border: 1px solid #ebeef5;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.06);
}

.search-box {
  margin-bottom: 16px;
}

.search-box :deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 0 0 1px #dcdfe6 inset;
  transition: box-shadow 0.3s;
}

.search-box :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #409eff inset;
}

.search-box :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #409eff inset, 0 0 0 3px rgba(64, 158, 255, 0.15);
}

.search-box :deep(.el-input-group__append) {
  border-radius: 0 10px 10px 0;
}

.search-filters {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
  padding: 10px 14px;
  background: #f8fafc;
  border-radius: 8px;
}

.search-suggestions,
.search-history {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 4px;
}

.suggestion-label,
.history-label {
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  white-space: nowrap;
}

.suggestion-tag,
.history-tag {
  cursor: pointer;
  border-radius: 12px;
  transition: all 0.2s;
}

.suggestion-tag:hover {
  background: var(--el-color-primary-light-9, #ecf5ff);
  transform: translateY(-1px);
}

.history-tag:hover {
  color: var(--el-color-primary, #409eff);
  transform: translateY(-1px);
}

.search-results-area {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.results-column {
  flex: 2;
  min-width: 320px;
}

.preview-column {
  flex: 3;
  min-width: 400px;
}

.search-stats {
  display: flex;
  gap: 24px;
  align-items: center;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  padding: 8px 16px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(6px);
  border-radius: 8px;
  border: 1px solid #ebeef5;
}
</style>