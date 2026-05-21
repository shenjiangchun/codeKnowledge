<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useApmStore } from '@/stores/apmStore'
import { testCaseApi } from '@/api/testCase'
import type { ApmTestCase } from '@/api/testCase'

const store = useApmStore()

const testCases = ref<ApmTestCase[]>([])
const loading = ref(false)
const saveDialogVisible = ref(false)
const saveName = ref('')
const editingId = ref<number | null>(null)

const hasProject = computed(() => !!store.selectedProject)

// Load test cases when project changes
watch(
  () => store.selectedProject,
  (project) => {
    testCases.value = []
    if (project) {
      loadTestCases(project.projectPath)
    }
  },
)

async function loadTestCases(projectPath: string): Promise<void> {
  loading.value = true
  try {
    testCases.value = await testCaseApi.list(projectPath)
  } catch {
    // Interceptor shows error
  } finally {
    loading.value = false
  }
}

function openSaveDialog(): void {
  editingId.value = null
  saveName.value = buildDefaultName()
  saveDialogVisible.value = true
}

function openUpdateDialog(tc: ApmTestCase): void {
  editingId.value = tc.id ?? null
  saveName.value = tc.name
  saveDialogVisible.value = true
}

function buildDefaultName(): string {
  const method = store.requestConfig.method
  const url = store.requestConfig.url
  const short = url.length > 30 ? url.substring(0, 30) + '...' : url
  return `${method} ${short}`
}

async function handleSave(): Promise<void> {
  if (!saveName.value.trim()) {
    ElMessage.warning('请输入测试用例名称')
    return
  }
  if (!store.selectedProject) return

  const payload: ApmTestCase = {
    name: saveName.value.trim(),
    projectPath: store.selectedProject.projectPath,
    entryNodeId: store.selectedEntry?.nodeId ?? null,
    method: store.requestConfig.method,
    url: store.requestConfig.url,
    headers: JSON.stringify(store.requestConfig.headers),
    params: JSON.stringify(store.requestConfig.queryParams),
    body: store.requestConfig.body || null,
  }

  try {
    if (editingId.value) {
      await testCaseApi.update(editingId.value, payload)
      ElMessage.success('已更新')
    } else {
      await testCaseApi.create(payload)
      ElMessage.success('已保存')
    }
    saveDialogVisible.value = false
    if (store.selectedProject) {
      await loadTestCases(store.selectedProject.projectPath)
    }
  } catch {
    // Interceptor shows error
  }
}

async function handleLoad(tc: ApmTestCase): Promise<void> {
  // Restore request config from saved test case
  if (tc.method) store.setMethod(tc.method)
  if (tc.url) store.setUrl(tc.url)

  // Restore headers
  if (tc.headers) {
    try {
      const headers = JSON.parse(tc.headers)
      if (typeof headers === 'object' && headers !== null) {
        store.setHeaders(headers as Record<string, string>)
      }
    } catch {
      // invalid JSON, skip
    }
  }

  // Restore query params
  if (tc.params) {
    try {
      const params = JSON.parse(tc.params) as Array<{ key: string; value: string; enabled: boolean }>
      // Clear existing params
      while (store.requestConfig.queryParams.length > 0) {
        store.removeQueryParam(0)
      }
      // Add restored params
      for (const p of params) {
        store.addQueryParam()
        const idx = store.requestConfig.queryParams.length - 1
        store.updateQueryParam(idx, 'key', p.key)
        store.updateQueryParam(idx, 'value', p.value)
        store.updateQueryParam(idx, 'enabled', p.enabled)
      }
    } catch {
      // invalid JSON, skip
    }
  }

  // Restore body
  if (tc.body) {
    store.setBody(tc.body)
  }

  ElMessage.success(`已加载: ${tc.name}`)
}

async function handleDelete(tc: ApmTestCase): Promise<void> {
  if (!tc.id) return
  try {
    await ElMessageBox.confirm(
      `确定删除测试用例 "${tc.name}" 吗？`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
    await testCaseApi.delete(tc.id)
    ElMessage.success('已删除')
    if (store.selectedProject) {
      await loadTestCases(store.selectedProject.projectPath)
    }
  } catch {
    // User cancelled or interceptor shows error
  }
}

function formatTime(epoch: number | undefined): string {
  if (!epoch) return ''
  return new Date(epoch * 1000).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// ==========================================================
// JSON Import / Export
// ==========================================================

interface ExportPayload {
  exportedAt: string
  projectPath: string
  cases: Array<Omit<ApmTestCase, 'id' | 'createdAt' | 'updatedAt'>>
}

function handleExport(): void {
  if (!store.selectedProject || testCases.value.length === 0) {
    ElMessage.warning('当前项目没有可导出的测试用例')
    return
  }
  const payload: ExportPayload = {
    exportedAt: new Date().toISOString(),
    projectPath: store.selectedProject.projectPath,
    cases: testCases.value.map(tc => ({
      name: tc.name,
      projectPath: tc.projectPath,
      entryNodeId: tc.entryNodeId ?? null,
      method: tc.method,
      url: tc.url,
      headers: tc.headers,
      params: tc.params,
      body: tc.body,
    })),
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  const safeName = (store.selectedProject.label || 'project').replace(/[^\w.-]+/g, '_')
  a.href = url
  a.download = `apm-testcases-${safeName}-${Date.now()}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${payload.cases.length} 条测试用例`)
}

const fileInputRef = ref<HTMLInputElement | null>(null)

function triggerImport(): void {
  fileInputRef.value?.click()
}

async function handleImportFile(e: Event): Promise<void> {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = '' // allow re-importing the same file
  if (!file) return
  if (!store.selectedProject) {
    ElMessage.warning('请先选择项目')
    return
  }
  try {
    const text = await file.text()
    const parsed = JSON.parse(text) as Partial<ExportPayload>
    const cases = Array.isArray(parsed.cases) ? parsed.cases : null
    if (!cases || cases.length === 0) {
      ElMessage.warning('文件中没有可导入的测试用例')
      return
    }
    let okCount = 0
    let failCount = 0
    for (const c of cases) {
      try {
        await testCaseApi.create({
          name: c.name,
          // 强制绑定到当前选中项目,避免跨项目串扰
          projectPath: store.selectedProject.projectPath,
          entryNodeId: c.entryNodeId ?? null,
          method: c.method,
          url: c.url,
          headers: c.headers,
          params: c.params,
          body: c.body,
        } as ApmTestCase)
        okCount++
      } catch {
        failCount++
      }
    }
    ElMessage.success(`导入完成: 成功 ${okCount} 条${failCount ? `,失败 ${failCount} 条` : ''}`)
    await loadTestCases(store.selectedProject.projectPath)
  } catch (err) {
    ElMessage.error('导入失败: 文件格式不合法')
  }
}
</script>

<template>
  <div class="test-case-manager">
    <!-- Toolbar -->
    <div class="tc-toolbar">
      <el-text size="small" type="info" tag="b">测试用例</el-text>
      <div class="tc-actions">
        <el-button
          size="small"
          text
          :disabled="!hasProject"
          title="从 JSON 文件导入"
          @click="triggerImport"
        >
          <el-icon><Upload /></el-icon>
          导入
        </el-button>
        <el-button
          size="small"
          text
          :disabled="!hasProject || testCases.length === 0"
          title="导出当前项目的所有测试用例"
          @click="handleExport"
        >
          <el-icon><Download /></el-icon>
          导出
        </el-button>
        <el-button
          size="small"
          type="primary"
          text
          :disabled="!hasProject || !store.requestConfig.url"
          @click="openSaveDialog"
        >
          <el-icon><FolderAdd /></el-icon>
          保存
        </el-button>
      </div>
    </div>

    <!-- Hidden file input for import -->
    <input
      ref="fileInputRef"
      type="file"
      accept="application/json,.json"
      style="display: none"
      @change="handleImportFile"
    />

    <!-- Test case list -->
    <div v-if="loading" class="tc-loading">
      <el-icon class="loading-spin"><Loading /></el-icon>
    </div>

    <el-scrollbar v-else-if="testCases.length > 0" class="tc-list" max-height="200px">
      <div
        v-for="tc in testCases"
        :key="tc.id"
        class="tc-item"
      >
        <div class="tc-info" @click="handleLoad(tc)">
          <el-tag
            :type="
              tc.method === 'GET' ? 'success' :
              tc.method === 'POST' ? 'warning' :
              tc.method === 'DELETE' ? 'danger' : 'info'
            "
            size="small"
            effect="plain"
          >
            {{ tc.method || '?' }}
          </el-tag>
          <span class="tc-name" :title="tc.name">{{ tc.name }}</span>
          <span class="tc-time">{{ formatTime(tc.updatedAt) }}</span>
        </div>
        <div class="tc-item-actions">
          <el-button
            size="small"
            text
            type="primary"
            title="更新"
            @click.stop="openUpdateDialog(tc)"
          >
            <el-icon><Edit /></el-icon>
          </el-button>
          <el-button
            size="small"
            text
            type="danger"
            title="删除"
            @click.stop="handleDelete(tc)"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </div>
    </el-scrollbar>

    <div v-else-if="hasProject" class="tc-empty">
      <el-text type="info" size="small">暂无测试用例</el-text>
    </div>

    <!-- Save dialog -->
    <el-dialog
      v-model="saveDialogVisible"
      :title="editingId ? '更新测试用例' : '保存测试用例'"
      width="400px"
      append-to-body
    >
      <el-input
        v-model="saveName"
        placeholder="测试用例名称"
        maxlength="200"
        show-word-limit
        @keyup.enter="handleSave"
      />
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">
          {{ editingId ? '更新' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.test-case-manager {
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 8px 12px;
}

.tc-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.tc-actions {
  display: flex;
  gap: 4px;
}

.tc-loading {
  display: flex;
  justify-content: center;
  padding: 12px;
}

.loading-spin {
  animation: rotate 1.5s linear infinite;
}

@keyframes rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.tc-list {
  margin: 0 -4px;
}

.tc-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.tc-item:hover {
  background-color: var(--el-fill-color-light);
}

.tc-info {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.tc-name {
  font-size: 12px;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.tc-time {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
  flex-shrink: 0;
}

.tc-item-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s;
}

.tc-item:hover .tc-item-actions {
  opacity: 1;
}

.tc-empty {
  padding: 8px 0;
  text-align: center;
}
</style>
