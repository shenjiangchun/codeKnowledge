<template>
  <div class="project-list">
    <!-- Project Directory Configuration -->
    <ProjectDirConfig />

    <!-- Guidance Prompts -->
    <el-alert
      v-if="!appStore.projectDirConfigured"
      title="请先配置项目目录"
      type="warning"
      show-icon
      :closable="false"
      class="guidance-alert"
    />
    <el-alert
      v-else-if="!appStore.projectSelected"
      title="请在表格中勾选一个或多个项目以开始分析"
      type="info"
      show-icon
      :closable="false"
      class="guidance-alert"
    />
    <el-alert
      v-else
      :title="`已选择 ${appStore.selectedProjects.length} 个项目: ${appStore.selectedProjectNames.join(', ')}`"
      type="success"
      show-icon
      :closable="false"
      class="guidance-alert"
    />

    <el-card>
      <template #header>
        <div class="card-header">
          <span>项目管理</span>
          <div class="header-buttons">
            <el-button
              type="warning"
              @click="handleUpdateAll"
              :loading="updatingAll"
              :disabled="!appStore.projectDirConfigured"
            >
              <el-icon><Refresh /></el-icon>
              一键更新所有仓库
            </el-button>
            <el-button
              type="success"
              @click="handleScan"
              :loading="scanning"
              :disabled="!appStore.projectDirConfigured"
            >
              <el-icon><FolderOpened /></el-icon>
              扫描仓库
            </el-button>
            <el-button @click="openKgExcludeDialog">
              <el-icon><Setting /></el-icon>
              图谱屏蔽目录
            </el-button>
            <el-button type="primary" @click="showCloneDialog = true">
              <el-icon><Plus /></el-icon>
              克隆项目
            </el-button>
            <el-button
              type="warning"
              @click="handleCrossServiceBuild"
              :disabled="selectedProjectsWithKg.length < 2"
              :loading="crossServiceBuilding"
            >
              跨服务依赖构建 ({{ selectedProjectsWithKg.length }})
            </el-button>
            <el-button
              type="primary"
              @click="handleConfirmMultiSelect"
              :disabled="selectedProjects.length === 0"
            >
              <el-icon><Select /></el-icon>
              确认选择 ({{ selectedProjects.length }})
            </el-button>
          </div>
        </div>
      </template>
      <el-table :data="projects" v-loading="loading" stripe @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" />        <el-table-column prop="name" label="项目名称">
          <template #default="{ row }">
            <div class="project-name-cell">
              <span>{{ row.name }}</span>
              <el-tag v-if="appStore.selectedProjectNames.includes(row.name)" type="success" size="small">
                已选择
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="branch" label="分支" width="100" />
        <el-table-column prop="remoteUrl" label="远程地址" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.remoteUrl || row.url || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row)">{{ getStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.source === 'scanned' ? 'primary' : 'info'" size="small">
              {{ row.source === 'scanned' ? '扫描' : '克隆' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="图谱状态" width="120" align="center">
          <template #default="{ row }">
            <div class="status-indicator">
              <span
                class="status-dot"
                :class="getKnowledgeGraphStatusClass(getProjectKnowledgeGraphStatus(row.path))"
                :title="getKnowledgeGraphStatusTooltip(row.path)"
              ></span>
              <span class="status-text">{{ getKnowledgeGraphStatusText(getProjectKnowledgeGraphStatus(row.path)) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="向量状态" width="120" align="center">
          <template #default="{ row }">
            <div class="vector-status">
              <div class="status-indicator">
                <span
                  class="status-dot"
                  :class="getVectorStatusClass(getProjectVectorStatus(row.path))"
                  :title="getVectorStatusTooltip(row.path)"
                ></span>
                <span class="status-text">{{ getVectorStatusText(getProjectVectorStatus(row.path)) }}</span>
              </div>
              <span v-if="getProjectVectorProgress(row.path)" class="progress-text">
                {{ getProjectVectorProgress(row.path)!.processed }}/{{ getProjectVectorProgress(row.path)!.total }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="lastCommitMessage" label="最近提交" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.lastCommitMessage">{{ row.lastCommitMessage }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="450">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              @click="handleSelect(row)"
              :disabled="!appStore.projectDirConfigured"
            >
              <el-icon><Select /></el-icon>
              选择
            </el-button>
            <el-button
              type="info"
              link
              @click="showCommitDialog(row)"
              :disabled="!appStore.projectDirConfigured"
            >
              <el-icon><Document /></el-icon>
              提交分析
            </el-button>
            <el-button
              type="success"
              link
              @click="handleGenerateKnowledgeGraph(row)"
              :loading="generatingKnowledgeGraph.has(row.path)"
              :disabled="isKnowledgeGraphButtonDisabled(row.path)"
            >
              <el-icon><DataAnalysis /></el-icon>
              生成图谱
            </el-button>
            <el-button
              type="primary"
              link
              @click="handleGenerateVector(row)"
              :loading="isVectorGenerating(row.path)"
              :disabled="isVectorButtonDisabled(row.path)"
            >
              <el-icon><Collection /></el-icon>
              描述&amp;向量
            </el-button>
            <GitOperations
              v-if="hasGit(row) && appStore.projectDirConfigured"
              :project-path="getProjectPath(row.name)"
            />
            <el-button type="primary" link @click="handlePull(row)">拉取</el-button>
            <el-button type="info" link @click="handleRefreshProject(row)" :disabled="!appStore.projectDirConfigured">
              <el-icon><Refresh /></el-icon>
              图谱刷新
            </el-button>
            <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Clone Dialog -->
    <el-dialog v-model="showCloneDialog" title="克隆项目" width="500px">
      <el-form :model="cloneForm" label-width="100px">
        <el-form-item label="仓库地址">
          <el-input v-model="cloneForm.url" placeholder="Git仓库URL" />
        </el-form-item>
        <el-form-item label="分支">
          <el-input v-model="cloneForm.branch" placeholder="默认: master" />
        </el-form-item>
        <el-form-item label="目录名">
          <el-input v-model="cloneForm.directory" placeholder="可选，默认使用仓库名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCloneDialog = false">取消</el-button>
        <el-button type="primary" @click="handleClone" :loading="cloning">克隆</el-button>
      </template>
    </el-dialog>

    <!-- Commit Analysis Dialog -->
    <el-dialog v-model="commitDialogVisible" title="提交代码分析" width="800px">
      <div v-if="commitsLoading" class="loading-container">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载提交列表...</span>
      </div>
      <div v-else>
        <div class="commit-header">
          <span>项目: {{ selectedProjectForCommit }}</span>
          <el-button type="primary" size="small" @click="loadCommits">刷新</el-button>
        </div>
        <el-table
          :data="commits"
          @selection-change="handleCommitSelection"
          max-height="400"
          v-loading="analysisLoading"
        >
          <el-table-column type="selection" width="55" />
          <el-table-column prop="commitId" label="Commit" width="100" />
          <el-table-column prop="shortMessage" label="提交信息" show-overflow-tooltip />
          <el-table-column prop="author" label="作者" width="120" />
          <el-table-column prop="date" label="时间" width="160">
            <template #default="{ row }">
              {{ formatDate(row.date) }}
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="commitDialogVisible = false">取消</el-button>
        <el-button
          type="warning"
          @click="handleImpactAnalysis"
          :disabled="selectedCommits.length === 0"
          :loading="analysisLoading"
        >
          影响分析
        </el-button>
        <el-button
          type="primary"
          @click="handleCodeAnalysis"
          :disabled="selectedCommits.length === 0"
          :loading="analysisLoading"
        >
          提交代码分析
        </el-button>
      </template>
    </el-dialog>

    <!-- Update All Result Dialog -->
    <el-dialog v-model="updateResultVisible" title="一键更新结果" width="600px">
      <div class="update-result">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="总仓库数">{{ updateResult?.totalRepos || 0 }}</el-descriptions-item>
          <el-descriptions-item label="成功">{{ updateResult?.successCount || 0 }}</el-descriptions-item>
          <el-descriptions-item label="失败">{{ updateResult?.failCount || 0 }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="updateResult?.results || []" max-height="300" class="mt-4">
          <el-table-column prop="path" label="仓库路径" show-overflow-tooltip />
          <el-table-column prop="branch" label="分支" width="100" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'">
                {{ row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="消息" show-overflow-tooltip />
        </el-table>
      </div>
      <template #footer>
        <el-button type="primary" @click="updateResultVisible = false">确定</el-button>
      </template>
    </el-dialog>

    <!-- Knowledge Graph Exclude Paths Dialog -->
    <el-dialog v-model="showKgExcludeDialog" title="知识图谱生成屏蔽目录配置" width="520px">
      <div class="kg-exclude-hint">
        生成知识图谱时将跳过匹配以下路径片段的目录（相对于项目根目录，支持片段匹配，例如 <code>src/test/</code> 或 <code>target</code>）。
      </div>
      <el-input
        v-model="kgExcludeNewItem"
        placeholder="输入目录片段，按回车添加，例如 src/test/"
        @keyup.enter="addKgExcludeItem"
        class="mt-4"
      >
        <template #append>
          <el-button @click="addKgExcludeItem">添加</el-button>
        </template>
      </el-input>
      <div class="kg-exclude-tags mt-4">
        <el-tag
          v-for="(item, idx) in kgExcludeDraft"
          :key="`${item}-${idx}`"
          closable
          @close="removeKgExcludeItem(idx)"
          class="kg-exclude-tag"
        >
          {{ item }}
        </el-tag>
        <span v-if="kgExcludeDraft.length === 0" class="text-muted">（当前无屏蔽目录）</span>
      </div>
      <template #footer>
        <el-button @click="resetKgExcludeDefaults">恢复默认</el-button>
        <el-button @click="showKgExcludeDialog = false">取消</el-button>
        <el-button type="primary" @click="saveKgExcludePaths">保存</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Select, FolderOpened, Document, Refresh, Loading, DataAnalysis, Collection, Setting } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi } from '@/api/project'
import { gitApi, type GitCommit, type UpdateAllResponse } from '@/api/git'
import { knowledgeGraphApi, type KnowledgeGraphTask } from '@/api/knowledgeGraph'
import { getVectorGenerationStatusBatch, startVectorGeneration, type VectorGenerationTask } from '@/api/vectorGeneration'
import { useAppStore } from '@/stores/app'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import ProjectDirConfig from '@/components/ProjectDirConfig.vue'
import GitOperations from '@/components/GitOperations.vue'
import type { GitRepositoryInfo } from '@/types/callchain'

const router = useRouter()
const appStore = useAppStore()
const workspaceStore = useWorkspaceStore()

// 标准化路径格式（将反斜杠转换为正斜杠，与后端保持一致）
const normalizePath = (path: string): string => {
  if (!path) return ''
  return path.trim().replace(/\\/g, '/').replace(/\/+$/, '')
}

// 前端防呆：记录每个项目上次点击生成的时间（内存中，刷新可重置）
const lastGenerateTimes = reactive<Record<string, number>>({})
const ONE_MINUTE_MS = 60 * 1000

// 检查项目是否在冷却时间内
const isInCooldown = (projectPath: string) => {
  const normalizedPath = normalizePath(projectPath)
  const lastTime = lastGenerateTimes[normalizedPath]
  if (!lastTime) return false
  return Date.now() - lastTime < ONE_MINUTE_MS
}

// 记录项目当前点击时间
const recordGenerateTime = (projectPath: string) => {
  const normalizedPath = normalizePath(projectPath)
  lastGenerateTimes[normalizedPath] = Date.now()
}

const loading = ref(false)
const cloning = ref(false)
const scanning = ref(false)
const updatingAll = ref(false)
const showCloneDialog = ref(false)
const projects = ref<GitRepositoryInfo[]>([])

// Commit analysis state
const commitDialogVisible = ref(false)
const commitsLoading = ref(false)
const analysisLoading = ref(false)
const selectedProjectForCommit = ref('')
const commits = ref<GitCommit[]>([])
const selectedCommits = ref<GitCommit[]>([])

// Update all result
const updateResultVisible = ref(false)
const updateResult = ref<UpdateAllResponse | null>(null)

const cloneForm = reactive({
  url: '',
  branch: 'master',
  directory: ''
})

// Track knowledge graph generation
const generatingKnowledgeGraph = ref<Set<string>>(new Set())
// Track vector generation
const generatingVector = ref<Set<string>>(new Set())

// Knowledge graph exclude paths config (defaults: common build outputs + test directory)
const DEFAULT_KG_EXCLUDE_PATHS = ['target', 'build', 'node_modules', '.git', '.idea', 'out', 'bin', 'dist', 'src/test/']
const KG_EXCLUDE_STORAGE_KEY = 'hisi.kg.excludePaths'
const kgExcludePaths = ref<string[]>(
  (() => {
    try {
      const saved = localStorage.getItem(KG_EXCLUDE_STORAGE_KEY)
      if (saved) {
        const parsed = JSON.parse(saved)
        if (Array.isArray(parsed)) return parsed
      }
    } catch {
      // Ignore parse errors
    }
    return [...DEFAULT_KG_EXCLUDE_PATHS]
  })()
)
const showKgExcludeDialog = ref(false)
const kgExcludeDraft = ref<string[]>([])
const kgExcludeNewItem = ref('')

const openKgExcludeDialog = () => {
  kgExcludeDraft.value = [...kgExcludePaths.value]
  kgExcludeNewItem.value = ''
  showKgExcludeDialog.value = true
}

const addKgExcludeItem = () => {
  const item = kgExcludeNewItem.value.trim()
  if (!item) return
  if (!kgExcludeDraft.value.includes(item)) {
    kgExcludeDraft.value.push(item)
  }
  kgExcludeNewItem.value = ''
}

const removeKgExcludeItem = (index: number) => {
  kgExcludeDraft.value.splice(index, 1)
}

const resetKgExcludeDefaults = () => {
  kgExcludeDraft.value = [...DEFAULT_KG_EXCLUDE_PATHS]
}

const saveKgExcludePaths = () => {
  kgExcludePaths.value = [...kgExcludeDraft.value]
  try {
    localStorage.setItem(KG_EXCLUDE_STORAGE_KEY, JSON.stringify(kgExcludePaths.value))
  } catch (e) {
    console.error('Failed to persist KG exclude paths:', e)
  }
  showKgExcludeDialog.value = false
  ElMessage.success('已保存屏蔽目录配置')
}

// Knowledge graph task status map
const knowledgeGraphTaskStatusMap = ref<Record<string, KnowledgeGraphTask>>({})
// Knowledge graph status map (for data stats)
const knowledgeGraphStatusMap = ref<Record<string, { status: string; methodNodeCount: number; callRelationCount: number; entryPointCount: number }>>({})
// Vector generation status map
const vectorGenerationStatusMap = ref<Record<string, VectorGenerationTask>>({})
let kgPollingTimer: ReturnType<typeof setInterval> | null = null
let vectorPollingTimer: ReturnType<typeof setInterval> | null = null

// ============================================================
// Knowledge Graph Task Status Management
// ============================================================

// Knowledge graph status class
const getKnowledgeGraphStatusClass = (status?: string) => {
  const classes: Record<string, string> = {
    PENDING: 'status-pending',
    RUNNING: 'status-running',
    COMPLETED: 'status-completed',
    FAILED: 'status-failed',
    generated: 'status-completed',
    not_generated: 'status-none'
  }
  return classes[status || ''] || 'status-none'
}

// Knowledge graph status tooltip
const getKnowledgeGraphStatusTooltip = (projectPath: string) => {
  const normalizedPath = normalizePath(projectPath)
  const task = knowledgeGraphTaskStatusMap.value[normalizedPath]
  const status = knowledgeGraphStatusMap.value[normalizedPath]

  if (task) {
    const tooltips: Record<string, string> = {
      PENDING: '等待生成知识图谱',
      RUNNING: '正在生成知识图谱...',
      COMPLETED: `知识图谱已生成\n方法节点: ${task.methodNodeCount || 0}\n调用关系: ${task.callRelationCount || 0}\n入口点: ${task.entryPointCount || 0}`,
      FAILED: `生成失败: ${task.errorMessage || '未知错误'}`
    }
    return tooltips[task.status || ''] || '未生成知识图谱'
  }

  if (status && status.methodNodeCount > 0) {
    return `知识图谱已生成\n方法节点: ${status.methodNodeCount}\n调用关系: ${status.callRelationCount}\n入口点: ${status.entryPointCount}`
  }

  return '未生成知识图谱'
}

// Knowledge graph status text
const getKnowledgeGraphStatusText = (status?: string) => {
  const texts: Record<string, string> = {
    PENDING: '等待中',
    RUNNING: '生成中',
    COMPLETED: '已完成',
    FAILED: '失败',
    generated: '已生成',
    not_generated: '未生成'
  }
  return texts[status || ''] || '未生成'
}

// Get knowledge graph task status for a project
const getProjectKnowledgeGraphStatus = (projectPath: string) => {
  const normalizedPath = normalizePath(projectPath)
  const task = knowledgeGraphTaskStatusMap.value[normalizedPath]
  if (task) {
    // 前端额外检查：如果任务处于RUNNING状态超过1天，视为FAILED
    if ((task.status === 'PENDING' || task.status === 'RUNNING') && task.startTime) {
      const startTime = new Date(task.startTime).getTime()
      const now = Date.now()
      const hours = (now - startTime) / (1000 * 60 * 60)
      if (hours >= 24) {
        return 'FAILED'
      }
    }
    return task.status
  }
  const status = knowledgeGraphStatusMap.value[normalizedPath]
  if (status && status.methodNodeCount > 0) {
    return 'generated'
  }
  return 'not_generated'
}

// Check if knowledge graph task is running
const isKnowledgeGraphTaskRunning = (projectPath: string) => {
  const status = getProjectKnowledgeGraphStatus(projectPath)
  return status === 'PENDING' || status === 'RUNNING'
}

// Check if knowledge graph button should be disabled
const isKnowledgeGraphButtonDisabled = (projectPath: string) => {
  if (!appStore.projectDirConfigured) return true
  if (generatingKnowledgeGraph.value.has(normalizePath(projectPath))) return true
  // 只有冷却时间内才禁用，刷新页面或超过60秒后可以重新触发
  return isInCooldown(projectPath)
}

// ============================================================
// Vector Generation Task Status Management
// ============================================================

// Vector generation status class
const getVectorStatusClass = (status?: string) => {
  const classes: Record<string, string> = {
    PENDING: 'status-pending',
    RUNNING: 'status-running',
    COMPLETED: 'status-completed',
    FAILED: 'status-failed'
  }
  return classes[status || ''] || 'status-none'
}

// Vector generation status text
const getVectorStatusText = (status?: string) => {
  const texts: Record<string, string> = {
    PENDING: '等待中',
    RUNNING: '生成中',
    COMPLETED: '已完成',
    FAILED: '失败'
  }
  return texts[status || ''] || '未生成'
}

// Vector generation status tooltip
const getVectorStatusTooltip = (projectPath: string) => {
  const normalizedPath = normalizePath(projectPath)
  const task = vectorGenerationStatusMap.value[normalizedPath]
  if (task) {
    const tooltips: Record<string, string> = {
      PENDING: '等待生成向量',
      RUNNING: `正在生成向量...\n进度: ${task.processedMethods}/${task.totalMethods}`,
      COMPLETED: `向量已生成\n处理方法数: ${task.totalMethods}\n耗时: ${task.costTimeMs ? (task.costTimeMs / 1000).toFixed(1) + 's' : '-'}`,
      FAILED: `生成失败: ${task.errorMessage || '未知错误'}`
    }
    return tooltips[task.status || ''] || '未生成向量'
  }
  return '未生成向量'
}

// Get vector generation task status for a project
const getProjectVectorStatus = (projectPath: string) => {
  const normalizedPath = normalizePath(projectPath)
  const task = vectorGenerationStatusMap.value[normalizedPath]
  if (!task) return undefined

  // 前端额外检查：如果任务处于RUNNING状态超过1天，视为FAILED
  if ((task.status === 'PENDING' || task.status === 'RUNNING') && task.startTime) {
    const startTime = new Date(task.startTime).getTime()
    const now = Date.now()
    const hours = (now - startTime) / (1000 * 60 * 60)
    if (hours >= 24) {
      return 'FAILED'
    }
  }
  return task.status
}

// Get vector generation progress for a project
const getProjectVectorProgress = (projectPath: string) => {
  const normalizedPath = normalizePath(projectPath)
  const task = vectorGenerationStatusMap.value[normalizedPath]
  if (task && (task.status === 'RUNNING' || task.status === 'COMPLETED')) {
    return {
      processed: task.processedMethods,
      total: task.totalMethods
    }
  }
  return null
}

// Check if vector generation task is running
const isVectorTaskRunning = (projectPath: string) => {
  const status = getProjectVectorStatus(projectPath)
  return status === 'PENDING' || status === 'RUNNING'
}

// Check if vector generation button should be disabled
const isVectorButtonDisabled = (projectPath: string) => {
  if (!appStore.projectDirConfigured) return true
  if (generatingVector.value.has(normalizePath(projectPath))) return true
  // 只有冷却时间内才禁用，刷新页面或超过60秒后可以重新触发
  return isInCooldown(projectPath)
}

// Check if vector generation button is loading
const isVectorGenerating = (projectPath: string) => {
  return generatingVector.value.has(normalizePath(projectPath))
}

// Start polling for vector generation tasks
const startVectorPolling = () => {
  if (vectorPollingTimer) {
    return
  }
  vectorPollingTimer = setInterval(async () => {
    const runningTasks = Object.values(vectorGenerationStatusMap.value)
      .filter(t => t.status === 'PENDING' || t.status === 'RUNNING')

    if (runningTasks.length === 0) {
      stopVectorPolling()
      return
    }

    const runningProjectPaths = runningTasks.map(t => t.projectPath)

    try {
      const tasks = await getVectorGenerationStatusBatch(runningProjectPaths)
      if (tasks && Array.isArray(tasks)) {
        const newMap = { ...vectorGenerationStatusMap.value }
        tasks.forEach(task => {
          if (task) {
            newMap[normalizePath(task.projectPath)] = task
          }
        })
        vectorGenerationStatusMap.value = newMap

        const stillRunning = tasks.some(t => t && (t.status === 'PENDING' || t.status === 'RUNNING'))
        if (!stillRunning) {
          stopVectorPolling()
        }
      }
    } catch (e) {
      console.error('Failed to poll vector generation task status:', e)
    }
  }, 5000) // Poll every 5 seconds for faster updates
}

// Stop vector generation polling
const stopVectorPolling = () => {
  if (vectorPollingTimer) {
    clearInterval(vectorPollingTimer)
    vectorPollingTimer = null
  }
}

// Load all vector generation statuses
const loadAllVectorGenerationStatuses = async () => {
  if (projects.value.length === 0) return

  const projectPaths = projects.value.map(p => normalizePath(p.path))

  try {
    const tasks = await getVectorGenerationStatusBatch(projectPaths)
    if (tasks && Array.isArray(tasks)) {
      const newTaskMap: Record<string, VectorGenerationTask> = {}
      let hasRunning = false
      tasks.forEach(task => {
        if (task) {
          newTaskMap[normalizePath(task.projectPath)] = task
          if (task.status === 'PENDING' || task.status === 'RUNNING') {
            hasRunning = true
          }
        }
      })
      vectorGenerationStatusMap.value = newTaskMap
      if (hasRunning) {
        startVectorPolling()
      }
    }
  } catch (e) {
    console.error('Failed to load vector generation statuses:', e)
  }
}

// Start polling for knowledge graph tasks
const startKgPolling = () => {
  if (kgPollingTimer) {
    return
  }
  kgPollingTimer = setInterval(async () => {
    const runningTasks = Object.values(knowledgeGraphTaskStatusMap.value)
      .filter(t => t.status === 'PENDING' || t.status === 'RUNNING')

    if (runningTasks.length === 0) {
      stopKgPolling()
      return
    }

    const runningProjectPaths = runningTasks.map(t => t.projectPath)

    try {
      const tasks = await knowledgeGraphApi.getTaskStatus(runningProjectPaths)
      if (tasks && Array.isArray(tasks)) {
        const newMap = { ...knowledgeGraphTaskStatusMap.value }
        tasks.forEach(task => {
          newMap[normalizePath(task.projectPath)] = task
        })
        knowledgeGraphTaskStatusMap.value = newMap

        const stillRunning = tasks.some(t => t.status === 'PENDING' || t.status === 'RUNNING')
        if (!stillRunning) {
          stopKgPolling()
          // Reload knowledge graph statuses for completed tasks
          loadAllKnowledgeGraphStatuses()
        }
      }
    } catch (e) {
      console.error('Failed to poll knowledge graph task status:', e)
    }
  }, 20000)
}

// Stop knowledge graph polling
const stopKgPolling = () => {
  if (kgPollingTimer) {
    clearInterval(kgPollingTimer)
    kgPollingTimer = null
  }
}

// Load all knowledge graph statuses
const loadAllKnowledgeGraphStatuses = async () => {
  if (projects.value.length === 0) return

  const projectPaths = projects.value.map(p => normalizePath(p.path))

  try {
    // Load task statuses
    const tasks = await knowledgeGraphApi.getTaskStatus(projectPaths)
    if (tasks && Array.isArray(tasks)) {
      const newTaskMap: Record<string, KnowledgeGraphTask> = {}
      let hasRunning = false
      tasks.forEach(task => {
        const key = normalizePath(task.projectPath)
        newTaskMap[key] = task
        if (task.status === 'PENDING' || task.status === 'RUNNING') {
          hasRunning = true
        }
      })
      knowledgeGraphTaskStatusMap.value = newTaskMap
      if (hasRunning) {
        startKgPolling()
      }
    }

    // 单次批量查询所有项目的图谱状态（covers legacy graphs with no task record）
    const newStatusMap: Record<string, { status: string; methodNodeCount: number; callRelationCount: number; entryPointCount: number }> = {}
    try {
      const batchResult = await knowledgeGraphApi.getBatchStatus(projectPaths) as unknown as Array<{
        projectPath: string
        status: string
        methodNodeCount: number
        callRelationCount: number
        entryPointCount: number
      }>
      if (Array.isArray(batchResult)) {
        for (const status of batchResult) {
          if (status && status.projectPath) {
            newStatusMap[status.projectPath] = {
              status: status.status,
              methodNodeCount: status.methodNodeCount,
              callRelationCount: status.callRelationCount,
              entryPointCount: status.entryPointCount
            }
          }
        }
      }
    } catch (batchErr) {
      // 批量接口失败时不再回退到 N 次单查，直接告警避免雪崩
      console.warn('Failed to load batch knowledge graph statuses:', batchErr)
    }
    knowledgeGraphStatusMap.value = newStatusMap
  } catch (e) {
    console.error('Failed to load knowledge graph statuses:', e)
  }
}

// Generate call chain
// Generate call chain functionality removed (deprecated).

const getStatusType = (row: GitRepositoryInfo) => {
  if (row.source === 'scanned') return row.clean ? 'success' : 'warning'
  const types: Record<string, string> = {
    READY: 'success',
    CLONING: 'warning',
    ERROR: 'danger'
  }
  return types[row.status || ''] || 'info'
}

const getStatusText = (row: GitRepositoryInfo) => {
  if (row.source === 'scanned') return row.clean ? 'Clean' : 'Modified'
  return row.status || 'Unknown'
}

// All repos have git since they were scanned or cloned
const hasGit = (_row: GitRepositoryInfo) => {
  return true
}

// Construct full project path (normalized to forward slashes)
const getProjectPath = (projectName: string) => {
  const dir = appStore.projectDir.replace(/\\/g, '/')
  return `${dir}/${projectName}`
}

// Handle project selection (single row button - selects only this project)
const handleSelect = (row: GitRepositoryInfo) => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }
  appStore.selectProject(row.name, row.path)
  ElMessage.success(`已选择项目: ${row.name}`)
}

const loadProjects = async () => {
  loading.value = true
  try {
    // Merge scanned repos with existing project list
    const [scannedRes, listRes] = await Promise.all([
      projectApi.scanGitRepos(),
      projectApi.getProjects().catch(() => [])
    ])

    // axios 拦截器已提取 data，scannedRes 直接就是仓库数组
    const scannedRepos = Array.isArray(scannedRes) ? scannedRes : []
    const existingNames = new Set(scannedRepos.map(r => r.name))

    // Convert legacy projects to GitRepositoryInfo format
    // listRes 也已被拦截器提取，直接就是字符串数组
    const legacyProjects = (Array.isArray(listRes) ? listRes : [])
      .filter((name: string) => !existingNames.has(name))
      .map((name: string): GitRepositoryInfo => ({
        name,
        path: getProjectPath(name),
        branch: 'unknown',
        clean: true,
        source: 'cloned',
        status: 'READY'
      }))

    projects.value = [...scannedRepos, ...legacyProjects]

    // Load task statuses after projects are loaded
    await loadAllKnowledgeGraphStatuses()
    await loadAllVectorGenerationStatuses()
  } catch (error) {
    ElMessage.error('加载项目列表失败')
    console.error('Failed to load projects:', error)
  } finally {
    loading.value = false
  }
}

const handleScan = async () => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }
  scanning.value = true
  try {
    const res = await projectApi.scanGitRepos()
    // axios 拦截器已提取 data，res 直接就是仓库数组
    projects.value = Array.isArray(res) ? res : (res as any)?.data || []
    ElMessage.success(`扫描完成，发现 ${projects.value.length} 个仓库`)
    // Load task statuses after scan
    await loadAllKnowledgeGraphStatuses()
    await loadAllVectorGenerationStatuses()
  } catch (error) {
    ElMessage.error('扫描失败')
  } finally {
    scanning.value = false
  }
}

const handleClone = async () => {
  if (!cloneForm.url) {
    ElMessage.warning('请输入仓库地址')
    return
  }
  cloning.value = true
  try {
    await projectApi.clone(cloneForm)
    ElMessage.success('克隆成功')
    showCloneDialog.value = false
    handleScan() // Refresh list after clone
  } catch (error) {
    ElMessage.error('克隆失败')
  } finally {
    cloning.value = false
  }
}

const handlePull = async (row: GitRepositoryInfo) => {
  try {
    await projectApi.pull(row.name)
    ElMessage.success('拉取成功')
    loadProjects()
  } catch (error) {
    ElMessage.error('拉取失败')
  }
}

const handleDelete = (row: GitRepositoryInfo) => {
  ElMessageBox.confirm(`确定要删除项目 ${row.name} 吗？`, '确认删除', {
    type: 'warning'
  }).then(async () => {
    try {
      await projectApi.delete(row.name)
      ElMessage.success('删除成功')
      // Clear selection if deleted project was selected
      if (appStore.selectedProjectNames.includes(row.name)) {
        appStore.selectProjects(appStore.selectedProjects.filter(p => p.name !== row.name))
      }
      loadProjects()
    } catch (error) {
      ElMessage.error('删除失败')
    }
  }).catch(() => {})
}

// Show commit dialog
const showCommitDialog = (row: GitRepositoryInfo) => {
  selectedProjectForCommit.value = row.name
  commitDialogVisible.value = true
  loadCommits()
}

// Load commits
const loadCommits = async () => {
  commitsLoading.value = true
  try {
    const path = getProjectPath(selectedProjectForCommit.value)
    const res = await gitApi.getCommits(path, 50)
    commits.value = res.data || []
  } catch (error) {
    ElMessage.error('加载提交列表失败')
  } finally {
    commitsLoading.value = false
  }
}

// Handle commit selection
const handleCommitSelection = (selection: GitCommit[]) => {
  selectedCommits.value = selection
}

// Format date
const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

// Handle code analysis
const handleCodeAnalysis = async () => {
  if (selectedCommits.value.length === 0) return

  analysisLoading.value = true
  try {
    // Build prompt with commit info
    const commitInfos = selectedCommits.value.map(c =>
      `${c.commitId}: ${c.shortMessage} (${c.author})`
    ).join('\n')

    const prompt = `分析以下 Git 提交:\n\n${commitInfos}`
    const workingDirectory = getProjectPath(selectedProjectForCommit.value)

    // Create session via workspaceStore
    const newSession = await workspaceStore.createSession('code-analysis', prompt, workingDirectory)

    // Ensure the session is in the store before navigating
    if (!workspaceStore.sessions.find(s => s.id === newSession.id)) {
      workspaceStore.sessions.unshift(newSession)
    }
    workspaceStore.selectSession(newSession.id)

    commitDialogVisible.value = false
    // Navigate with sessionId as query parameter
    await router.push({ name: 'ClaudeTerminal', query: { sessionId: newSession.id } })
  } catch (error) {
    ElMessage.error('创建分析会话失败')
  } finally {
    analysisLoading.value = false
  }
}

// Handle impact analysis
const handleImpactAnalysis = async () => {
  if (selectedCommits.value.length === 0) return

  analysisLoading.value = true
  try {
    // Build prompt with commit info
    const commitInfos = selectedCommits.value.map(c =>
      `${c.commitId}: ${c.shortMessage}`
    ).join('\n')

    const prompt = `分析以下 Git 提交的影响范围:\n\n${commitInfos}`
    const workingDirectory = getProjectPath(selectedProjectForCommit.value)

    // Create session via workspaceStore
    const newSession = await workspaceStore.createSession('impact-analysis', prompt, workingDirectory)

    // Ensure the session is in the store before navigating
    if (!workspaceStore.sessions.find(s => s.id === newSession.id)) {
      workspaceStore.sessions.unshift(newSession)
    }
    workspaceStore.selectSession(newSession.id)

    commitDialogVisible.value = false
    await router.push({ name: 'ClaudeTerminal', query: { sessionId: newSession.id } })
  } catch (error) {
    ElMessage.error('创建分析会话失败')
  } finally {
    analysisLoading.value = false
  }
}

// Handle update all
const handleUpdateAll = async () => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }

  updatingAll.value = true
  try {
    const res = await gitApi.updateAll(appStore.projectDir)
    // axios 拦截器已返回 response.data
    updateResult.value = res as unknown as UpdateAllResponse
    updateResultVisible.value = true

    // Refresh project list
    await handleScan()
  } catch (error: any) {
    ElMessage.error(`一键更新失败: ${error.message || error}`)
  } finally {
    updatingAll.value = false
  }
}

onMounted(() => {
  if (appStore.projectDirConfigured) {
    handleScan()
  }
})

onUnmounted(() => {
  stopKgPolling()
  stopVectorPolling()
})

// Generate knowledge graph (async task)
const handleGenerateKnowledgeGraph = async (row: GitRepositoryInfo) => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }

  const normalizedPath = normalizePath(row.path)
  generatingKnowledgeGraph.value.add(normalizedPath)

  try {
    // 记录防呆时间
    recordGenerateTime(row.path)
    // Start async task
    const task = await knowledgeGraphApi.startGenerateTask(row.path, kgExcludePaths.value)
    if (task) {
      knowledgeGraphTaskStatusMap.value = {
        ...knowledgeGraphTaskStatusMap.value,
        [normalizedPath]: task
      }
      ElMessage.success('已开始生成知识图谱')
      startKgPolling()
    }
  } catch (error: any) {
    // Handle different error types
    if (error.response?.status === 409) {
      const errorData = error.response.data
      ElMessage.warning(errorData?.message || '该项目已有知识图谱生成任务在执行中')
      if (errorData?.runningTask) {
        knowledgeGraphTaskStatusMap.value = {
          ...knowledgeGraphTaskStatusMap.value,
          [normalizedPath]: errorData.runningTask
        }
        startKgPolling()
      }
    } else if (error.response?.status === 400) {
      const errorData = error.response.data
      ElMessage.error(errorData?.message || '项目路径不存在')
    } else {
      ElMessage.error(`启动知识图谱生成失败: ${error.message || error}`)
    }
  } finally {
    generatingKnowledgeGraph.value.delete(normalizedPath)
  }
}

// Generate vector (description + vector generation)
const handleGenerateVector = async (row: GitRepositoryInfo) => {
  if (!appStore.projectDirConfigured) {
    ElMessage.warning('请先配置项目目录')
    return
  }

  const normalizedPath = normalizePath(row.path)
  generatingVector.value.add(normalizedPath)

  try {
    // 记录防呆时间
    recordGenerateTime(row.path)
    // Start async task
    const result = await startVectorGeneration(row.path)
    if (result) {
      // Initialize task status in map (use normalized path as key)
      vectorGenerationStatusMap.value = {
        ...vectorGenerationStatusMap.value,
        [normalizedPath]: {
          id: 0,
          projectPath: normalizedPath,
          status: 'PENDING',
          totalMethods: 0,
          processedMethods: 0,
          startTime: null,
          endTime: null,
          costTimeMs: null,
          errorMessage: null
        }
      }
      ElMessage.success('已开始生成描述和向量')
      startVectorPolling()
    }
  } catch (error: any) {
    // Handle different error types
    if (error.response?.status === 409) {
      const errorData = error.response.data
      ElMessage.warning(errorData?.message || '该项目已有向量生成任务在执行中')
      if (errorData?.runningTask) {
        vectorGenerationStatusMap.value = {
          ...vectorGenerationStatusMap.value,
          [normalizedPath]: errorData.runningTask
        }
        startVectorPolling()
      }
    } else if (error.response?.status === 400) {
      const errorData = error.response.data
      ElMessage.error(errorData?.message || '项目路径不存在')
    } else {
      ElMessage.error(`启动向量生成失败: ${error.message || error}`)
    }
  } finally {
    generatingVector.value.delete(normalizedPath)
  }
}
// ============================================================
// Multi-select & Cross-service Build
// ============================================================
const selectedProjects = ref<any[]>([])
const crossServiceBuilding = ref(false)

function handleSelectionChange(selection: any[]) {
  selectedProjects.value = selection
}

/** 确认多选：将表格勾选的项目设置为全局选中 */
function handleConfirmMultiSelect() {
  if (selectedProjects.value.length === 0) {
    ElMessage.warning('请先在表格中勾选项目')
    return
  }
  const projects = selectedProjects.value.map((p: any) => ({ name: p.name, path: p.path }))
  appStore.selectProjects(projects)
  const names = projects.map(p => p.name)
  ElMessage.success(`已选择 ${names.length} 个项目: ${names.join(', ')}`)
}

const selectedProjectsWithKg = computed(() =>
  selectedProjects.value.filter(p => {
    const status = knowledgeGraphStatusMap.value[normalizePath(p.path)]
    return status && (status.status === 'generated' || status.status === 'completed')
  })
)

async function handleCrossServiceBuild() {
  crossServiceBuilding.value = true
  try {
    const paths = selectedProjectsWithKg.value.map(p => p.path)
    await knowledgeGraphApi.crossServiceBuild(paths)
    ElMessage.success('跨服务依赖构建完成')
  } catch (e: unknown) {
    ElMessage.error('跨服务依赖构建失败')
  } finally {
    crossServiceBuilding.value = false
  }
}

async function handleRefreshProject(project: { path: string }) {
  try {
    const res = await knowledgeGraphApi.refresh(project.path)
    ElMessage.success(`刷新任务已创建，taskId=${(res as any)?.taskId}`)
  } catch (e: unknown) {
    if ((e as { response?: { status?: number } })?.response?.status === 412) {
      ElMessage.warning('工作区不干净，请先提交所有改动')
    } else {
      ElMessage.error('图谱刷新失败')
    }
  }
}
</script>

<style scoped>
.guidance-alert {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-buttons {
  display: flex;
  gap: 8px;
}

.project-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.text-muted {
  color: #909399;
}

/* Status indicator styles */
.status-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  display: inline-block;
  position: relative;
}

.status-none {
  background-color: #c0c4cc;
  box-shadow: 0 0 4px rgba(192, 196, 204, 0.5);
}

.status-pending {
  background-color: #e6a23c;
  box-shadow: 0 0 8px rgba(230, 162, 60, 0.7);
  animation: pulse-pending 1.5s ease-in-out infinite;
}

.status-running {
  background-color: #409eff;
  box-shadow: 0 0 12px rgba(64, 158, 255, 0.8);
  animation: pulse-running 0.8s ease-in-out infinite;
}

.status-completed {
  background-color: #67c23a;
  box-shadow: 0 0 6px rgba(103, 194, 58, 0.6);
}

.status-completed::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  border-radius: 50%;
  background-color: rgba(103, 194, 58, 0.3);
  animation: completed-ring 0.5s ease-out forwards;
}

.status-failed {
  background-color: #f56c6c;
  box-shadow: 0 0 6px rgba(245, 108, 108, 0.6);
  animation: pulse-failed 2s ease-in-out infinite;
}

@keyframes pulse-pending {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
    box-shadow: 0 0 8px rgba(230, 162, 60, 0.7);
  }
  50% {
    opacity: 0.7;
    transform: scale(1.1);
    box-shadow: 0 0 16px rgba(230, 162, 60, 0.9);
  }
}

@keyframes pulse-running {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
    box-shadow: 0 0 12px rgba(64, 158, 255, 0.8);
  }
  50% {
    opacity: 0.6;
    transform: scale(1.2);
    box-shadow: 0 0 24px rgba(64, 158, 255, 1);
  }
}

@keyframes pulse-failed {
  0%, 100% {
    box-shadow: 0 0 6px rgba(245, 108, 108, 0.6);
  }
  50% {
    box-shadow: 0 0 12px rgba(245, 108, 108, 0.9);
  }
}

@keyframes completed-ring {
  0% {
    transform: scale(1);
    opacity: 1;
  }
  100% {
    transform: scale(2);
    opacity: 0;
  }
}

.status-text {
  font-size: 12px;
  color: #606266;
}

/* Vector status styles */
.vector-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.progress-text {
  font-size: 11px;
  color: #909399;
}

/* Commit dialog styles */
.commit-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  font-weight: 500;
}

.loading-container {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: #909399;
}

/* Update result styles */
.update-result {
  margin-top: 16px;
}

.mt-4 {
  margin-top: 16px;
}

.kg-status-header {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  justify-content: center;
}

.kg-exclude-hint {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

.kg-exclude-hint code {
  background: #f4f4f5;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
}

.kg-exclude-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 32px;
}

.kg-exclude-tag {
  margin: 0;
}
</style>