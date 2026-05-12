<template>
  <div class="code-preview-panel">
    <div class="panel-header">
      <span class="title">代码预览</span>
      <div class="header-actions">
        <el-button-group size="small">
          <el-button @click="expandContext">
            <el-icon><FullScreen /></el-icon>
          </el-button>
          <el-button @click="copyCode">
            <el-icon><DocumentCopy /></el-icon>
          </el-button>
        </el-button-group>
      </div>
    </div>

    <div v-if="!selectedResult" class="empty-state">
      <el-icon :size="48"><Document /></el-icon>
      <span>请选择搜索结果查看代码</span>
    </div>

    <div v-else class="preview-content">
      <!-- 文件路径 -->
      <div class="file-info">
        <span class="file-path">{{ selectedResult.filePath }}</span>
        <span class="line-info">行 {{ selectedResult.lineNumber }}-{{ selectedResult.endLineNumber }}</span>
      </div>

      <!-- 代码信息 -->
      <div class="code-info">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="类型">
            <el-tag :type="typeTagType(selectedResult.type)" size="small">
              {{ typeText(selectedResult.type) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="名称">
            {{ selectedResult.name }}
          </el-descriptions-item>
          <el-descriptions-item v-if="metadata?.className" label="所属类">
            {{ metadata.className }}
          </el-descriptions-item>
          <el-descriptions-item v-if="metadata?.returnType" label="返回类型">
            {{ metadata.returnType }}
          </el-descriptions-item>
          <el-descriptions-item label="相关度">
            {{ (selectedResult.relevanceScore * 100).toFixed(0) }}%
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 代码片段 -->
      <div class="code-container" ref="codeContainerRef">
        <div class="code-lines">
          <div
            v-for="(line, index) in codeLines"
            :key="index"
            class="code-line"
            :class="{ highlight: isHighlightLine(index) }"
          >
            <span class="line-number">{{ startLine + index }}</span>
            <span class="line-content">{{ line }}</span>
          </div>
        </div>
      </div>

      <!-- 文档注释 -->
      <div v-if="metadata?.documentation" class="documentation">
        <h4>文档说明</h4>
        <pre>{{ metadata.documentation }}</pre>
      </div>

      <!-- 相关关系 -->
      <div v-if="relations.length > 0" class="relations">
        <h4>相关关系</h4>
        <div class="relation-list">
          <div v-for="rel in relations" :key="rel.id" class="relation-item">
            <el-tag size="small">{{ relationTypeText(rel.type) }}</el-tag>
            <span class="relation-target">{{ rel.targetId }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { FullScreen, DocumentCopy, Document } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { SemanticSearchResult, CodeRelation, SearchResultMetadata } from '@/types/search'
import { getCodeContext, getNodeRelations } from '@/api/search'

const props = defineProps<{
  selectedResult?: SemanticSearchResult
}>()

const codeLines = ref<string[]>([])
const startLine = ref(0)
const relations = ref<CodeRelation[]>([])
const loadingContext = ref(false)

const metadata = computed<SearchResultMetadata | undefined>(() => {
  return props.selectedResult?.metadata
})

function typeTagType(type: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (type) {
    case 'class': return 'success'
    case 'method': return 'warning'
    case 'function': return 'info'
    default: return 'info'
  }
}

function typeText(type: string): string {
  switch (type) {
    case 'class': return '类'
    case 'method': return '方法'
    case 'function': return '函数'
    case 'variable': return '变量'
    default: return type
  }
}

function relationTypeText(type: string): string {
  switch (type) {
    case 'calls': return '调用'
    case 'implements': return '实现'
    case 'extends': return '继承'
    case 'references': return '引用'
    case 'depends_on': return '依赖'
    default: return type
  }
}

function isHighlightLine(index: number): boolean {
  if (!props.selectedResult) return false
  const lineNum = startLine.value + index
  return lineNum >= props.selectedResult.lineNumber && lineNum <= props.selectedResult.endLineNumber
}

// 加载代码上下文
async function loadCodeContext() {
  if (!props.selectedResult) return

  loadingContext.value = true
  try {
    const result = props.selectedResult
    const context = await getCodeContext(result.filePath, result.lineNumber, 15)

    // 合并上下文代码
    const beforeLines = context.before.split('\n').filter(l => l.trim())
    const targetLines = context.target.split('\n')
    const afterLines = context.after.split('\n').filter(l => l.trim())

    startLine.value = result.lineNumber - beforeLines.length
    codeLines.value = [...beforeLines, ...targetLines, ...afterLines]

    // 加载关系
    if (result.nodeId) {
      const relatedNodes = await getNodeRelations(result.nodeId)
      relations.value = relatedNodes.flatMap(node => node.relations)
    }
  } catch (e) {
    // 使用代码片段作为备选
    if (props.selectedResult) {
      codeLines.value = props.selectedResult.codeSnippet.split('\n')
      startLine.value = props.selectedResult.lineNumber
    }
  } finally {
    loadingContext.value = false
  }
}

function expandContext() {
  if (!props.selectedResult) return
  loadCodeContext()
  ElMessage.success('已展开代码上下文')
}

function copyCode() {
  if (!props.selectedResult) return
  navigator.clipboard.writeText(props.selectedResult.codeSnippet)
  ElMessage.success('代码已复制')
}

// 监听选择变化
watch(() => props.selectedResult, (newResult) => {
  if (newResult) {
    // 初始化代码片段
    codeLines.value = newResult.codeSnippet.split('\n')
    startLine.value = newResult.lineNumber
    relations.value = []
  }
}, { immediate: true })
</script>

<style scoped>
.code-preview-panel {
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

.header-actions :deep(.el-button) {
  border-radius: 8px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: #c0c4cc;
  gap: 16px;
}

.preview-content {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 4px;
}

.preview-content::-webkit-scrollbar {
  width: 4px;
}

.preview-content::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 4px;
}

.file-info {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 13px;
  padding: 8px 12px;
  background: #f8fafc;
  border-radius: 8px;
}

.file-path {
  color: #5b8ff9;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-weight: 500;
}

.line-info {
  color: #faad14;
  font-size: 12px;
  background: #fffbe6;
  padding: 2px 8px;
  border-radius: 6px;
}

.code-info {
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid #ebeef5;
}

.code-info :deep(.el-descriptions__border) {
  border-radius: 10px;
}

.code-container {
  flex: 1;
  background: #fafbfc;
  border-radius: 10px;
  padding: 14px;
  overflow-y: auto;
  min-height: 200px;
  border: 1px solid #ebeef5;
}

.code-container::-webkit-scrollbar {
  width: 4px;
}

.code-container::-webkit-scrollbar-thumb {
  background: #dcdfe6;
  border-radius: 4px;
}

.code-lines {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.6;
}

.code-line {
  display: flex;
  padding: 1px 0;
  border-radius: 3px;
}

.code-line.highlight {
  background: linear-gradient(90deg, rgba(64, 158, 255, 0.08), rgba(64, 158, 255, 0.04));
  border-left: 3px solid #409eff;
  padding-left: 4px;
}

.line-number {
  color: #c0c4cc;
  min-width: 50px;
  text-align: right;
  padding-right: 16px;
  user-select: none;
  font-size: 12px;
}

.line-content {
  color: #1f2937;
  flex: 1;
  white-space: pre;
}

.documentation,
.relations {
  background: #f8fafc;
  border-radius: 10px;
  padding: 14px;
  border: 1px solid #ebeef5;
}

.documentation h4,
.relations h4 {
  color: var(--el-text-color-primary, #303133);
  font-size: 13px;
  font-weight: 600;
  margin: 0 0 10px 0;
}

.documentation pre {
  color: #4b5563;
  font-size: 12px;
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.6;
}

.relation-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.relation-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #f0f2f5;
  transition: background 0.2s;
}

.relation-item:hover {
  background: #f5f8ff;
}

.relation-target {
  color: #5b8ff9;
  font-size: 12px;
  font-family: 'JetBrains Mono', monospace;
}
</style>