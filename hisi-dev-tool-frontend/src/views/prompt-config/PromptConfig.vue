<template>
  <div class="prompt-config">
    <div class="config-header">
      <h2>提示词配置</h2>
      <p>配置各场景的提示词模板，支持 <code>#{变量名}</code> 格式的变量插值</p>
    </div>

    <div class="config-content">
      <!-- 模板列表 -->
      <div class="template-list">
        <div
          v-for="template in promptStore.templates"
          :key="template.templateKey"
          class="template-item"
          :class="{ active: selectedKey === template.templateKey }"
          @click="selectTemplate(template.templateKey)"
        >
          <div class="template-name">{{ template.name }}</div>
          <div class="template-desc">{{ template.description }}</div>
        </div>
      </div>

      <!-- 模板编辑器 -->
      <div class="template-editor" v-if="currentTemplate">
        <div class="editor-header">
          <h3>{{ currentTemplate.name }}</h3>
          <el-button type="primary" @click="saveCurrentTemplate" :loading="saving">
            保存
          </el-button>
        </div>

        <div class="editor-body">
          <div class="editor-main">
            <div class="editor-label">提示词内容</div>
            <el-input
              v-model="editContent"
              type="textarea"
              :rows="20"
              placeholder="输入提示词模板..."
            />
          </div>

          <div class="editor-sidebar">
            <div class="variables-panel">
              <div class="panel-title">变量列表</div>
              <div class="variables-list">
                <el-tag
                  v-for="variable in extractedVariables"
                  :key="variable"
                  class="variable-tag"
                >
                  #{{ '{' + variable + '}' }}
                </el-tag>
                <div v-if="extractedVariables.length === 0" class="no-variables">
                  暂无变量
                </div>
              </div>
            </div>

            <div class="preview-panel">
              <div class="panel-title">预览</div>
              <div class="preview-content" v-html="previewContent"></div>
            </div>
          </div>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-else class="empty-state">
        <el-empty description="选择一个模板进行编辑" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { usePromptStore } from '@/stores/promptStore'

const promptStore = usePromptStore()

// 状态
const selectedKey = ref<string | null>(null)
const editContent = ref('')
const saving = ref(false)
const previewVariables = ref<Record<string, string>>({
  errorMessage: '示例错误信息',
  errorType: 'NullPointerException',
  stackTrace: 'at com.example.Service.method(Service.java:100)',
  projectPath: '/path/to/project',
  codeSnippet: 'public void method() { ... }',
  language: 'Java',
  projectContext: '示例项目上下文',
  traceId: 'trace-123',
  entryPoint: 'Controller.method',
  callChain: 'Controller -> Service -> Repository',
  changedFile: 'Service.java',
  changedMethod: 'updateData',
  changeType: 'MODIFY',
  projectName: '示例项目'
})

// 计算属性
const currentTemplate = computed(() =>
  selectedKey.value ? promptStore.getTemplate(selectedKey.value as any) : null
)

const extractedVariables = computed(() =>
  promptStore.extractVariables(editContent.value)
)

const previewContent = computed(() => {
  let content = editContent.value
  for (const [key, value] of Object.entries(previewVariables.value)) {
    content = content.replace(new RegExp(`#\\{${key}\\}`, 'g'), value)
  }
  return content
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code class="language-$1">$2</code></pre>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
})

// 方法
function selectTemplate(key: string) {
  selectedKey.value = key
}

async function saveCurrentTemplate() {
  if (!selectedKey.value) return

  saving.value = true
  try {
    const variables = JSON.stringify(extractedVariables.value)
    await promptStore.updateTemplate(selectedKey.value, editContent.value, variables)
    ElMessage.success('保存成功')
  } catch (error) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

// 监听模板切换
watch(currentTemplate, (template) => {
  if (template) {
    editContent.value = template.content
  }
})

// 初始化
onMounted(() => {
  promptStore.loadTemplates()
})
</script>

<style scoped>
.prompt-config {
  padding: 24px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.config-header {
  margin-bottom: 24px;
}

.config-header h2 {
  margin: 0 0 8px 0;
}

.config-header p {
  margin: 0;
  color: #606266;
}

.config-header code {
  background: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
  color: #409eff;
}

.config-content {
  flex: 1;
  display: flex;
  gap: 24px;
  min-height: 0;
}

.template-list {
  width: 260px;
  background: #fff;
  border-radius: 8px;
  padding: 16px;
}

.template-item {
  padding: 16px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 8px;
  border: 1px solid transparent;
}

.template-item:hover {
  background: #f5f7fa;
}

.template-item.active {
  background: #ecf5ff;
  border-color: #409eff;
}

.template-name {
  font-weight: 500;
  margin-bottom: 4px;
}

.template-desc {
  font-size: 12px;
  color: #909399;
}

.template-editor {
  flex: 1;
  background: #fff;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
}

.editor-header {
  padding: 16px 24px;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.editor-header h3 {
  margin: 0;
}

.editor-body {
  flex: 1;
  display: flex;
  padding: 24px;
  gap: 24px;
  min-height: 0;
}

.editor-main {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.editor-label {
  margin-bottom: 8px;
  font-weight: 500;
}

.editor-main :deep(.el-textarea) {
  flex: 1;
}

.editor-main :deep(.el-textarea__inner) {
  height: 100%;
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
}

.editor-sidebar {
  width: 300px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.variables-panel,
.preview-panel {
  background: #f5f7fa;
  border-radius: 8px;
  padding: 16px;
}

.panel-title {
  font-weight: 500;
  margin-bottom: 12px;
}

.variables-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.variable-tag {
  font-family: 'Monaco', 'Menlo', monospace;
}

.no-variables {
  color: #909399;
  font-size: 13px;
}

.preview-panel {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.preview-content {
  flex: 1;
  overflow-y: auto;
  font-size: 13px;
  line-height: 1.6;
  background: #fff;
  padding: 12px;
  border-radius: 6px;
}

.preview-content :deep(pre) {
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
}

.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border-radius: 8px;
}
</style>
