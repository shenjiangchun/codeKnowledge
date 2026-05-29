<template>
  <div class="semantic-search-panel">
    <!-- 搜索输入 -->
    <div class="search-input-section">
      <el-input
        v-model="searchQuery"
        placeholder="输入自然语言描述搜索方法，如：错误诊断、数据库查询"
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

    <!-- 搜索结果 -->
    <div class="search-results" v-if="results.length > 0">
      <el-card v-for="result in results" :key="result.nodeId" class="result-card">
        <template #header>
          <div class="result-header">
            <span class="method-name">{{ result.methodName }}</span>
            <el-tag size="small">{{ result.className.split('.').pop() }}</el-tag>
            <el-tag
              v-if="result.similarityScore != null"
              :type="getSimilarityTagType(result.similarityScore)"
              size="small"
              class="similarity-tag"
            >
              置信度 {{ (result.similarityScore * 100).toFixed(1) }}%
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
    <el-empty v-if="!loading && searched && results.length === 0" description="未找到相关方法" />

    <!-- 耗时提示 -->
    <div class="search-info" v-if="costTimeMs">
      <el-text type="info">
        搜索耗时 {{ costTimeMs }}ms，共找到 {{ totalCount }} 个结果
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
import { ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
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

// 详情弹窗状态
const detailDialogVisible = ref(false)
const selectedMethod = ref<VectorSearchResult | null>(null)

async function handleSearch() {
  if (!searchQuery.value.trim()) return

  loading.value = true
  searched.value = true
  try {
    const response = await vectorSearchApi.search({
      query: searchQuery.value,
      projectPath: props.projectPath,
      projectPaths: props.projectPaths && props.projectPaths.length > 0 ? props.projectPaths : undefined,
      limit: 10
    })
    // 合并 items 中的 similarityScore 到 results（按 nodeId 匹配）
    const scoreMap = new Map<string, number>()
    if (response.items && Array.isArray(response.items)) {
      response.items.forEach(item => {
        if (item && item.nodeId && item.similarityScore != null) {
          scoreMap.set(item.nodeId, item.similarityScore)
        }
      })
    }
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

.similarity-tag {
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
