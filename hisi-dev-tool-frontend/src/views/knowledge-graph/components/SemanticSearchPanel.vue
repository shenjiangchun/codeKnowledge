<template>
  <div class="semantic-search-panel">
    <!-- 搜索输入 -->
    <div class="search-input-section">
      <el-input
        v-model="searchQuery"
        placeholder="输入自然语言描述搜索方法，如：处理用户登录的方法、支付回调处理"
        size="large"
        clearable
        @keyup.enter="handleSearch"
      >
        <template #append>
          <el-button :icon="Search" @click="handleSearch" :loading="loading">
            搜索
          </el-button>
        </template>
      </el-input>
    </div>

    <!-- 分词子查询展示 + 筛选 -->
    <div class="sub-queries-section" v-if="subQueries.length > 1">
      <div class="sub-queries-header">
        <el-icon><Connection /></el-icon>
        <span class="sub-queries-label">AI 分词多路召回</span>
        <el-tag size="small" type="info">{{ subQueries.length }} 路</el-tag>
        <span class="filter-hint" v-if="activeFilter">（筛选中）</span>
      </div>
      <div class="sub-queries-tags">
        <el-tag
          v-for="(sq, idx) in subQueries"
          :key="idx"
          :size="activeFilter === sq ? 'default' : 'small'"
          :effect="activeFilter === sq ? 'dark' : 'plain'"
          :type="activeFilter === sq ? 'primary' : ''"
          class="sub-query-tag"
          @click="toggleFilter(sq)"
        >
          {{ sq }}
        </el-tag>
        <el-tag
          v-if="activeFilter"
          size="small"
          type="warning"
          effect="plain"
          class="sub-query-tag clear-filter-tag"
          @click="clearFilter"
        >
          清除筛选
        </el-tag>
      </div>
    </div>

    <!-- 搜索结果 -->
    <div class="search-results" v-if="filteredResults.length > 0">
      <el-card v-for="result in filteredResults" :key="result.nodeId" class="result-card">
        <template #header>
          <div class="result-header">
            <span class="method-name">{{ result.methodName }}</span>
            <el-tag size="small">{{ result.className.split('.').pop() }}</el-tag>
            <!-- RRF 分数标签（多路召回时显示） -->
            <el-tag
              v-if="getRrfScore(result.nodeId) != null"
              type="primary"
              size="small"
              effect="dark"
              class="rrf-tag"
            >
              RRF {{ formatRrfScore(getRrfScore(result.nodeId)!) }}
            </el-tag>
            <!-- 相似度分数标签（单路或作为补充） -->
            <el-tag
              v-else-if="result.similarityScore != null"
              :type="getSimilarityTagType(result.similarityScore)"
              size="small"
              class="similarity-tag"
            >
              置信度 {{ (result.similarityScore * 100).toFixed(1) }}%
            </el-tag>
            <!-- 多路命中标记 -->
            <el-tag
              v-if="getMatchedCount(result.nodeId) > 1"
              type="success"
              size="small"
              effect="dark"
              class="multi-hit-tag"
            >
              {{ getMatchedCount(result.nodeId) }}路命中
            </el-tag>
          </div>
        </template>

        <div class="result-content">
          <!-- 功能描述 -->
          <div class="description-section">
            <span class="label">功能描述：</span>
            <span class="description">{{ result.description }}</span>
          </div>

          <!-- 签名 -->
          <div class="signature-section">
            <span class="label">签名：</span>
            <code>{{ result.signature }}</code>
          </div>

          <!-- 命中分词（多路召回时显示） -->
          <div class="matched-queries-section" v-if="getMatchedQueries(result.nodeId).length > 0">
            <span class="label">命中分词：</span>
            <div class="matched-queries-tags">
              <el-tag
                v-for="(mq, mqi) in getMatchedQueries(result.nodeId)"
                :key="mqi"
                size="small"
                effect="light"
                type="info"
                class="matched-query-tag"
              >
                {{ mq }}
              </el-tag>
            </div>
          </div>

          <!-- 操作按钮 -->
          <div class="actions">
            <el-button size="small" @click="viewDetail(result)">
              查看详情
            </el-button>
            <el-button size="small" @click="viewCallChain(result)">
              调用链
            </el-button>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 空状态 -->
    <el-empty v-if="!loading && searched && filteredResults.length === 0 && results.length > 0" description="当前筛选条件下无匹配结果" />
    <el-empty v-if="!loading && searched && results.length === 0" description="未找到相关方法" />

    <!-- 耗时提示 -->
    <div class="search-info" v-if="costTimeMs">
      <el-text type="info">
        搜索耗时 {{ costTimeMs }}ms，共找到 {{ totalCount }} 个结果
        <template v-if="activeFilter">
          （筛选出 {{ filteredResults.length }} 个）
        </template>
        <template v-if="subQueries.length > 1">
          （{{ subQueries.length }} 路召回 + RRF 融合）
        </template>
      </el-text>
    </div>

    <!-- 方法详情弹窗 -->
    <el-dialog
      v-model="detailDialogVisible"
      title="方法详情"
      width="700px"
      :close-on-click-modal="false"
    >
      <el-descriptions :column="1" border v-if="selectedMethod">
        <el-descriptions-item label="方法名称">
          <el-text type="primary" size="large">{{ selectedMethod.methodName }}</el-text>
        </el-descriptions-item>
        <el-descriptions-item label="所属类">
          {{ selectedMethod.className }}
        </el-descriptions-item>
        <el-descriptions-item label="功能描述">
          <el-text type="primary">{{ selectedMethod.description || '暂无描述' }}</el-text>
        </el-descriptions-item>
        <el-descriptions-item label="方法签名">
          <code class="signature-code">{{ selectedMethod.signature }}</code>
        </el-descriptions-item>
        <el-descriptions-item label="文件路径">
          {{ selectedMethod.filePath }}
        </el-descriptions-item>
        <el-descriptions-item label="代码行号">
          第 {{ selectedMethod.startLine }} - {{ selectedMethod.endLine }} 行
        </el-descriptions-item>
        <el-descriptions-item label="复杂度">
          <el-tag :type="getComplexityTagType(selectedMethod.complexity)">
            {{ selectedMethod.complexity }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="方法体" v-if="selectedMethod.methodBody">
          <pre class="method-body-code">{{ selectedMethod.methodBody }}</pre>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="viewCallChainFromDetail">查看调用链</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { Search, Connection } from '@element-plus/icons-vue'
import { vectorSearchApi, type VectorSearchResult } from '@/api/vectorSearch'

const props = defineProps<{
  projectPath: string
  projectPaths?: string[]
}>()

const emit = defineEmits<{
  (e: 'viewDetail', result: VectorSearchResult): void
  (e: 'viewCallChain', result: VectorSearchResult): void
}>()

const searchQuery = ref('')
const results = ref<VectorSearchResult[]>([])
const loading = ref(false)
const searched = ref(false)
const totalCount = ref(0)
const costTimeMs = ref(0)
const subQueries = ref<string[]>([])
const rrfScores = ref<Record<string, number>>({})
const matchedSubQueriesMap = ref<Record<string, string[]>>({})
const activeFilter = ref<string | null>(null)

/** 按选中的分词筛选结果 */
const filteredResults = computed(() => {
  if (!activeFilter.value) return results.value
  return results.value.filter(r => {
    const matched = matchedSubQueriesMap.value[r.nodeId] ?? []
    return matched.includes(activeFilter.value!)
  })
})

// 详情弹窗状态
const detailDialogVisible = ref(false)
const selectedMethod = ref<VectorSearchResult | null>(null)

async function handleSearch() {
  if (!searchQuery.value.trim()) return

  loading.value = true
  searched.value = true
  subQueries.value = []
  rrfScores.value = {}
  matchedSubQueriesMap.value = {}
  activeFilter.value = null
  try {
    const response = await vectorSearchApi.searchV2({
      query: searchQuery.value,
      projectPath: props.projectPath,
      projectPaths: props.projectPaths && props.projectPaths.length > 0 ? props.projectPaths : undefined,
      limit: 10
    })

    // 保存分词和 RRF 分数
    subQueries.value = response.subQueries ?? []
    rrfScores.value = response.rrfScores ?? {}

    // 合并 items 中的 similarityScore + matchedSubQueries 到 results（按 nodeId 匹配）
    const scoreMap = new Map<string, number>()
    const matchedMap = new Map<string, string[]>()
    if (response.items && Array.isArray(response.items)) {
      response.items.forEach(item => {
        if (item && item.nodeId) {
          if (item.similarityScore != null) {
            scoreMap.set(item.nodeId, item.similarityScore)
          }
          if (item.matchedSubQueries && item.matchedSubQueries.length > 0) {
            matchedMap.set(item.nodeId, item.matchedSubQueries)
          }
        }
      })
    }
    matchedSubQueriesMap.value = Object.fromEntries(matchedMap)
    results.value = (response.results || []).map(r => ({
      ...r,
      similarityScore: scoreMap.get(r.nodeId) ?? r.similarityScore
    }))
    totalCount.value = response.totalCount
    costTimeMs.value = response.costTimeMs
  } catch (error) {
    console.error('Semantic search failed:', error)
  } finally {
    loading.value = false
  }
}

/** 获取节点的 RRF 分数，仅多路召回时存在 */
function getRrfScore(nodeId: string): number | null {
  const score = rrfScores.value[nodeId]
  return score != null ? score : null
}

/** 格式化 RRF 分数为可读字符串 */
function formatRrfScore(score: number): string {
  // RRF 分数一般在 0.01-0.1 范围，乘以 1000 显示更直观
  return (score * 1000).toFixed(1)
}

/** 获取节点命中的子查询列表 */
function getMatchedQueries(nodeId: string): string[] {
  return matchedSubQueriesMap.value[nodeId] ?? []
}

/** 获取节点被多少路子查询命中 */
function getMatchedCount(nodeId: string): number {
  return getMatchedQueries(nodeId).length
}

/** 切换分词筛选（再次点击同一分词取消筛选） */
function toggleFilter(sq: string) {
  activeFilter.value = activeFilter.value === sq ? null : sq
}

/** 清除分词筛选 */
function clearFilter() {
  activeFilter.value = null
}

function viewDetail(result: VectorSearchResult) {
  selectedMethod.value = result
  detailDialogVisible.value = true
  emit('viewDetail', result)
}

function viewCallChain(result: VectorSearchResult) {
  emit('viewCallChain', result)
}

// 从详情弹窗查看调用链
function viewCallChainFromDetail() {
  if (selectedMethod.value) {
    emit('viewCallChain', selectedMethod.value)
    detailDialogVisible.value = false
  }
}

// 获取复杂度标签类型
function getComplexityTagType(complexity: number): '' | 'success' | 'warning' | 'danger' | 'info' {
  if (complexity <= 5) return 'success'
  if (complexity <= 10) return 'info'
  if (complexity <= 20) return 'warning'
  return 'danger'
}

// 根据相似度分数返回标签颜色
function getSimilarityTagType(score: number): '' | 'success' | 'warning' | 'danger' | 'info' {
  if (score >= 0.8) return 'success'
  if (score >= 0.6) return ''
  if (score >= 0.4) return 'warning'
  return 'info'
}
</script>

<style scoped>
.semantic-search-panel {
  padding: 20px;
}

.search-input-section {
  margin-bottom: 20px;
}

/* 分词子查询展示 */
.sub-queries-section {
  margin-bottom: 16px;
  padding: 12px 16px;
  background: linear-gradient(135deg, #ecf5ff 0%, #f0f9eb 100%);
  border-radius: 8px;
  border: 1px solid #d9ecff;
}

.sub-queries-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 500;
}

.sub-queries-label {
  font-weight: 600;
}

.sub-queries-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sub-query-tag {
  border-radius: 12px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.sub-query-tag:hover {
  box-shadow: 0 0 4px rgba(64, 158, 255, 0.4);
}

.clear-filter-tag {
  cursor: pointer;
}

.filter-hint {
  font-size: 12px;
  color: var(--el-color-warning);
  margin-left: 4px;
}

.multi-hit-tag {
  font-size: 11px;
}

/* 命中分词展示 */
.matched-queries-section {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.matched-queries-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.matched-query-tag {
  border-radius: 10px;
  font-size: 11px;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-card {
  margin-bottom: 16px;
}

.result-header {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.similarity-tag,
.rrf-tag {
  margin-left: auto;
}

.method-name {
  font-weight: bold;
  font-size: 16px;
}

.result-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.description-section .description {
  color: var(--el-text-color-primary);
}

.signature-section code {
  background-color: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}

.actions {
  display: flex;
  gap: 8px;
}

.search-info {
  margin-top: 16px;
  text-align: center;
}

/* 详情弹窗样式 */
.signature-code {
  background-color: var(--el-fill-color-light);
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-family: monospace;
  word-break: break-all;
}

.method-body-code {
  background-color: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  font-family: 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  overflow-x: auto;
  max-height: 300px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
