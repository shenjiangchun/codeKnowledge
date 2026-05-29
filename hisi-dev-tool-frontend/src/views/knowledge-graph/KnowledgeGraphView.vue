<template>
  <div class="knowledge-graph-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>知识图谱分析</span>
          <div class="header-actions">
            <el-select
              v-model="selectedProjectNames"
              placeholder="选择项目"
              filterable
              multiple
              collapse-tags
              collapse-tags-tooltip
              @change="handleProjectChange"
              style="width: 350px;"
            >
              <el-option
                v-for="proj in storeSelectedProjects"
                :key="proj.name"
                :label="proj.name"
                :value="proj.name"
              />
            </el-select>
            <el-tag v-if="graphStatus" :type="getStatusTagType(graphStatus.status)">
              {{ getStatusText(graphStatus.status) }}
            </el-tag>
            <!-- 向量状态显示 -->
            <div v-if="vectorStatus" class="vector-status-display">
              <el-tag :type="getVectorStatusTagType(vectorStatus.status)" size="small">
                向量: {{ getVectorStatusText(vectorStatus.status) }}
              </el-tag>
              <span v-if="vectorStatus.status === 'RUNNING' || vectorStatus.status === 'COMPLETED'" class="vector-progress">
                {{ vectorStatus.processedMethods }}/{{ vectorStatus.totalMethods }}
              </span>
            </div>
            <!-- 向量生成按钮 -->
            <el-button
              type="primary"
              :loading="isGeneratingVector"
              @click="handleGenerateVector"
              :disabled="!projectPath || isGeneratingVector || isInCooldown"
            >
              生成向量
            </el-button>
            <!-- 术语配置按钮 -->
            <el-button
              @click="showGlossaryDialog = true"
              :disabled="!projectPath"
            >
              术语配置
            </el-button>
            <!-- 补齐缺失向量按钮 -->
            <el-button
              v-if="missingInfo && missingInfo.missingCount > 0"
              type="warning"
              :loading="isRefreshingMissing"
              @click="handleRefreshMissing"
              :disabled="!projectPath || isRefreshingMissing || isInCooldown"
            >
              补齐缺失 ({{ missingInfo.missingCount }})
            </el-button>
            <!-- 查看缺失详情 -->
            <el-button
              v-if="missingInfo && missingInfo.missingCount > 0"
              text
              type="info"
              @click="showMissingDrawer = true"
            >
              查看缺失
            </el-button>
            <!-- 全量生成按钮 -->
            <el-button
              type="primary"
              :loading="isGenerating"
              @click="handleFullGenerate"
              :disabled="!projectPath || isGenerating || isInCooldown"
            >
              全量生成
            </el-button>
            <!-- 增量生成按钮（仅在有历史记录时显示） -->
            <el-button
              v-if="hasGeneratedRecord"
              type="success"
              :loading="isGenerating"
              @click="handleIncrementalGenerate"
              :disabled="!projectPath || isGenerating || isInCooldown"
            >
              增量生成{{ lastGeneratedCommit ? ` (基于 ${lastGeneratedCommit})` : '' }}
            </el-button>
          </div>
        </div>
      </template>

      <!-- 统计概览 -->
      <div class="stats-overview" v-if="graphStatus">
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.methodNodeCount }}</span>
          <span class="stat-label">方法节点</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.callRelationCount }}</span>
          <span class="stat-label">调用关系</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.entryPointCount }}</span>
          <span class="stat-label">入口点</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.interfaceImplCount }}</span>
          <span class="stat-label">接口实现</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.callChainCount }}</span>
          <span class="stat-label">调用链</span>
        </div>
        <div v-if="missingInfo" class="stat-item stat-item-vector">
          <span class="stat-value" :class="missingInfo.missingCount > 0 ? 'stat-value-warning' : 'stat-value-success'">
            {{ missingInfo.generatedCount }}/{{ missingInfo.totalMethods }}
          </span>
          <span class="stat-label">
            已向量化
            <el-tag v-if="missingInfo.missingCount > 0" size="small" type="warning" effect="plain" style="margin-left: 4px">
              缺 {{ missingInfo.missingCount }}
            </el-tag>
          </span>
        </div>
      </div>

      <!-- Tab 切换 -->
      <el-tabs v-model="activeTab" class="main-tabs">
        <el-tab-pane label="代码理解" name="understand">
          <CodeUnderstandingTab
            v-if="projectPath"
            :project-path="projectPath"
            :project-paths="projectPaths"
          />
          <el-empty v-else description="请先选择项目" />
        </el-tab-pane>
        <el-tab-pane label="语义搜索" name="semanticSearch">
          <SemanticSearchPanel
            v-if="projectPath"
            :project-path="projectPath"
            :project-paths="projectPaths"
            @view-detail="handleViewDetail"
            @view-call-chain="handleViewCallChain"
          />
          <el-empty v-else description="请先选择项目" />
        </el-tab-pane>
        <el-tab-pane label="引用分析" name="methodRef">
          <MethodReferenceGraph ref="methodRefGraphRef" :project-paths="projectPaths" />
        </el-tab-pane>
        <el-tab-pane label="跨服务调用" name="crossService">
          <CrossServiceBridgeTab
            v-if="projectPaths.length > 0"
            :project-path="projectPath"
            :project-paths="projectPaths"
          />
          <el-empty v-else description="请先选择项目" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 缺失向量方法详情抽屉 -->
    <el-drawer
      v-model="showMissingDrawer"
      title="缺失向量的方法"
      direction="rtl"
      size="480px"
    >
      <div v-if="missingInfo" class="missing-drawer-content">
        <div class="missing-summary">
          <el-statistic title="总方法" :value="missingInfo.totalMethods" />
          <el-statistic title="已生成" :value="missingInfo.generatedCount" />
          <el-statistic title="缺失" :value="missingInfo.missingCount" />
        </div>
        <el-divider />
        <p class="missing-hint">以下为缺失向量的方法预览（最多 50 条）。点击"补齐缺失"将自动跑增量向量生成。</p>
        <el-table :data="missingInfo.preview" size="small" stripe max-height="500">
          <el-table-column prop="className" label="类名" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <code style="font-size: 11px">{{ row.className?.split('.').pop() }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="methodName" label="方法名" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <code style="font-size: 11px">{{ row.methodName }}</code>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="missingInfo.missingCount > missingInfo.preview.length" class="missing-more">
          还有 {{ missingInfo.missingCount - missingInfo.preview.length }} 个方法未显示
        </div>
        <el-button
          type="warning"
          :loading="isRefreshingMissing"
          @click="handleRefreshMissing"
          style="margin-top: 16px; width: 100%"
        >
          补齐缺失向量 ({{ missingInfo.missingCount }})
        </el-button>
      </div>
    </el-drawer>

    <!-- 术语管理对话框 -->
    <el-dialog
      v-model="showGlossaryDialog"
      title="术语配置"
      width="700px"
      destroy-on-close
    >
      <p style="color: #909399; margin: 0 0 16px 0; font-size: 13px;">
        配置术语对照表后，LLM 生成语义描述时将自动遵守术语规范（重新生成向量后生效）
      </p>

      <div style="display: flex; justify-content: flex-end; margin-bottom: 12px;">
        <el-button type="primary" size="small" @click="glossaryShowForm = true">
          <el-icon><Plus /></el-icon>
          新增术语
        </el-button>
      </div>

      <el-table :data="glossaryTerms" v-loading="glossaryLoading" empty-text="暂无术语" stripe size="small">
        <el-table-column prop="wrongTerm" label="错误术语" width="140">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain" size="small">{{ row.wrongTerm }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="" width="40" align="center">
          <template #default>→</template>
        </el-table-column>
        <el-table-column prop="correctTerm" label="正确术语" width="140">
          <template #default="{ row }">
            <el-tag type="success" effect="plain" size="small">{{ row.correctTerm }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="context" label="说明" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="glossaryEditRow(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="glossaryDeleteRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 术语新增/编辑子对话框 -->
    <el-dialog
      v-model="glossaryShowForm"
      :title="glossaryEditingId ? '编辑术语' : '新增术语'"
      width="420px"
      append-to-body
      destroy-on-close
    >
      <el-form :model="glossaryForm" label-width="80px">
        <el-form-item label="错误术语" required>
          <el-input v-model="glossaryForm.wrongTerm" placeholder="LLM 可能错误使用的术语" />
        </el-form-item>
        <el-form-item label="正确术语" required>
          <el-input v-model="glossaryForm.correctTerm" placeholder="应该使用的正确术语" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="glossaryForm.context" placeholder="可选，如适用场景" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="glossaryShowForm = false">取消</el-button>
        <el-button type="primary" @click="glossarySubmit" :loading="glossarySubmitting">
          {{ glossaryEditingId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { projectApi } from '@/api/project'
import { knowledgeGraphApi, type KnowledgeGraphStatus, type GitStatus } from '@/api/knowledgeGraph'
import { getVectorGenerationStatus, startVectorGeneration, getMissingEmbeddings, refreshMissing, type VectorGenerationTask, type MissingEmbeddingInfo } from '@/api/vectorGeneration'
import { glossaryApi } from '@/api/glossary'
import type { GlossaryTerm } from '@/types/glossary'
import { useAppStore } from '@/stores/app'
import CodeUnderstandingTab from './components/CodeUnderstandingTab.vue'
import SemanticSearchPanel from './components/SemanticSearchPanel.vue'
import MethodReferenceGraph from '@/views/call-chain/MethodReferenceGraph.vue'
import CrossServiceBridgeTab from './components/CrossServiceBridgeTab.vue'

const route = useRoute()
const appStore = useAppStore()

interface ProjectInfo {
  name: string
  path: string
}

const projects = ref<ProjectInfo[]>([])
const selectedProjectNames = ref<string[]>(
  appStore.selectedProjectNames.length > 0
    ? [...appStore.selectedProjectNames]
    : (route.query.project ? [route.query.project as string] : (appStore.selectedProject ? [appStore.selectedProject] : []))
)
const activeTab = ref(route.query.tab as string || 'understand')
const graphStatus = ref<KnowledgeGraphStatus | null>(null)
const vectorStatus = ref<VectorGenerationTask | null>(null)
const gitStatus = ref<GitStatus | null>(null)
const isGenerating = ref(false)
const isGeneratingVector = ref(false)
const missingInfo = ref<MissingEmbeddingInfo | null>(null)
const isRefreshingMissing = ref(false)
const showMissingDrawer = ref(false)
let pollingTimer: number | null = null
let vectorPollingTimer: number | null = null

// 前端防呆：记录每个项目上次点击生成的时间（内存中，刷新可重置）
const lastGenerateTimes = reactive<Record<string, number>>({})
const ONE_MINUTE_MS = 60 * 1000

// 标准化路径格式（将反斜杠转换为正斜杠，与后端保持一致）
const normalizePath = (path: string): string => {
  if (!path) return ''
  return path.trim().replace(/\\/g, '/').replace(/\/+$/, '')
}

// 检查当前选中项目是否在冷却时间内
const isInCooldown = computed(() => {
  const currentPath = projectPath.value
  if (!currentPath) return false
  const normalizedPath = normalizePath(currentPath)
  const lastTime = lastGenerateTimes[normalizedPath]
  if (!lastTime) return false
  return Date.now() - lastTime < ONE_MINUTE_MS
})

// 记录当前选中项目的点击时间
const recordGenerateTime = () => {
  const currentPath = projectPath.value
  if (!currentPath) return
  const normalizedPath = normalizePath(currentPath)
  lastGenerateTimes[normalizedPath] = Date.now()
}

// 从 store 的已选项目列表中过滤出可用项目（确保 projects 加载后才过滤）
const storeSelectedProjects = computed(() => {
  const storeNames = appStore.selectedProjectNames
  if (storeNames.length === 0 || projects.value.length === 0) return projects.value
  const filtered = projects.value.filter(p => storeNames.includes(p.name))
  return filtered.length > 0 ? filtered : projects.value
})

// 检查是否有历史生成记录
const hasGeneratedRecord = computed(() => {
  return graphStatus.value &&
    (graphStatus.value.status === 'generated' ||
     graphStatus.value.status === 'completed' ||
     graphStatus.value.methodNodeCount > 0)
})

// 获取上次生成的 commit hash（用于显示在按钮上）
const lastGeneratedCommit = computed(() => {
  return gitStatus.value?.commitHash?.substring(0, 7) || ''
})

// 多项目路径列表
const projectPaths = computed(() => {
  return selectedProjectNames.value
    .map(name => {
      const proj = projects.value.find(p => p.name === name)
      return proj ? proj.path.replace(/\\/g, '/') : ''
    })
    .filter(Boolean)
})

// 向后兼容：第一个选中项目的路径
const projectPath = computed(() => projectPaths.value[0] || '')

// 获取状态标签类型
const getStatusTagType = (status: string): '' | 'success' | 'warning' | 'danger' | 'info' => {
  const typeMap: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    completed: 'success',
    generated: 'success',
    running: 'warning',
    pending: 'warning',
    failed: 'danger',
    not_generated: 'info'
  }
  return typeMap[status] || 'info'
}

// 获取状态文本
const getStatusText = (status: string): string => {
  const textMap: Record<string, string> = {
    completed: '已生成',
    generated: '已生成',
    running: '生成中',
    pending: '等待中',
    failed: '生成失败',
    not_generated: '未生成'
  }
  return textMap[status] || status
}

// 向量状态相关
const getVectorStatusTagType = (status: string): '' | 'success' | 'warning' | 'danger' | 'info' => {
  const typeMap: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    COMPLETED: 'success',
    RUNNING: 'warning',
    PENDING: 'warning',
    FAILED: 'danger'
  }
  return typeMap[status] || 'info'
}

const getVectorStatusText = (status: string): string => {
  const textMap: Record<string, string> = {
    COMPLETED: '已完成',
    RUNNING: '生成中',
    PENDING: '等待中',
    FAILED: '失败'
  }
  return textMap[status] || '未生成'
}

// 加载向量生成状态
const loadVectorStatus = async () => {
  if (!projectPath.value) {
    vectorStatus.value = null
    return
  }
  try {
    console.log('[KnowledgeGraph] Loading vector status for path:', projectPath.value)
    const status = await getVectorGenerationStatus(projectPath.value)
    console.log('[KnowledgeGraph] Vector status loaded:', status)
    vectorStatus.value = status
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load vector status:', error)
    vectorStatus.value = null
  }
}

// 开始向量状态轮询
const startVectorPolling = () => {
  console.log('[KnowledgeGraph] Starting vector polling')
  if (vectorPollingTimer) {
    clearInterval(vectorPollingTimer)
  }
  vectorPollingTimer = window.setInterval(async () => {
    console.log('[KnowledgeGraph] Vector poll - current status:', vectorStatus.value?.status)
    if (vectorStatus.value?.status === 'PENDING' || vectorStatus.value?.status === 'RUNNING') {
      await loadVectorStatus()
      // 检查任务是否完成
      if (vectorStatus.value?.status === 'COMPLETED' || vectorStatus.value?.status === 'FAILED') {
        console.log('[KnowledgeGraph] Vector task completed, stopping polling')
        stopVectorPolling()
        loadMissingInfo()
      }
    }
  }, 5000)
}

// 停止向量状态轮询
const stopVectorPolling = () => {
  if (vectorPollingTimer) {
    clearInterval(vectorPollingTimer)
    vectorPollingTimer = null
  }
}

// 加载项目列表
const loadProjects = async () => {
  try {
    // 使用与项目管理页面相同的 API 获取项目列表（包含完整路径）
    const res = await projectApi.scanGitRepos()
    const scannedRepos = Array.isArray(res) ? res : []

    // 直接使用后端返回的项目信息（包含正确的 path）
    projects.value = scannedRepos.map((repo: any) => ({
      name: repo.name,
      path: repo.path
    }))

    // 如果当前没有选中，自动选择 store 中已选项目
    if (selectedProjectNames.value.length === 0 && appStore.selectedProjectNames.length > 0) {
      selectedProjectNames.value = [...appStore.selectedProjectNames]
    } else if (projects.value.length === 1 && selectedProjectNames.value.length === 0) {
      selectedProjectNames.value = [projects.value[0].name]
    }
  } catch (error) {
    console.error('Failed to load projects:', error)
  }
}

// 加载知识图谱状态：使用多项目合并统计
const loadGraphStatus = async () => {
  if (projectPaths.value.length === 0) {
    graphStatus.value = null
    return
  }

  try {
    // 使用多项目合并查询，传入所有 projectPaths
    const result = await knowledgeGraphApi.getStatus(projectPaths.value[0], projectPaths.value)
    graphStatus.value = result as unknown as KnowledgeGraphStatus
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load graph status:', error)
    graphStatus.value = null
  }

  // 同时加载向量状态
  await loadVectorStatus()
}

// 开始状态轮询
const startPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
  }
  pollingTimer = window.setInterval(async () => {
    if (graphStatus.value?.status === 'pending' || graphStatus.value?.status === 'running' || isGenerating.value) {
      await loadGraphStatus()
      // 检查任务是否完成
      if (graphStatus.value?.status === 'completed' || graphStatus.value?.status === 'failed' || graphStatus.value?.status === 'generated') {
        stopPolling()
      }
    }
  }, 2000)
}

// 停止状态轮询
const stopPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

// 处理项目变化
const handleProjectChange = () => {
  loadGraphStatus()
  loadGitStatus()
  loadVectorStatus()
}

// 加载 Git 状态
const loadGitStatus = async () => {
  if (!projectPath.value) return
  try {
    const status = await knowledgeGraphApi.getGitStatus(projectPath.value)
    gitStatus.value = status as unknown as GitStatus
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load git status:', error)
    gitStatus.value = null
  }
}

// 全量生成知识图谱
const handleFullGenerate = async () => {
  if (!projectPath.value) return

  // 检查 Git 状态
  try {
    const status = await knowledgeGraphApi.getGitStatus(projectPath.value)
    const gitStatusData = status as unknown as GitStatus

    if (gitStatusData.hasUncommittedChanges) {
      ElMessage.warning('请先提交代码后再生成知识图谱')
      return
    }
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to check git status:', error)
    ElMessage.error('检查 Git 状态失败，请稍后重试')
    return
  }

  try {
    recordGenerateTime()
    isGenerating.value = true
    ElMessage.info('已启动知识图谱生成任务，请稍候...')
    await knowledgeGraphApi.startGenerateTask(projectPath.value)
    ElMessage.success('知识图谱生成任务已启动')
    // 立即刷新状态并开始轮询
    startPolling()
    startVectorPolling()
    // 提示用户：全量生成会清除跨服务依赖关系
    if (selectedProjectNames.value.length > 1) {
      setTimeout(() => {
        ElMessage.warning('全量生成完成后，请到项目管理页重新执行「跨服务依赖构建」以恢复跨项目调用关系')
      }, 1500)
    }
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to start generate task:', error)
    ElMessage.error('启动生成任务失败')
  } finally {
    isGenerating.value = false
  }
}

// 增量生成知识图谱
const handleIncrementalGenerate = async () => {
  if (!projectPath.value) return

  // 检查 Git 状态
  try {
    const status = await knowledgeGraphApi.getGitStatus(projectPath.value)
    const gitStatusData = status as unknown as GitStatus

    if (gitStatusData.hasUncommittedChanges) {
      ElMessage.warning('请先提交代码后再生成知识图谱')
      return
    }
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to check git status:', error)
    ElMessage.error('检查 Git 状态失败，请稍后重试')
    return
  }

  try {
    recordGenerateTime()
    isGenerating.value = true
    ElMessage.info('已启动增量生成任务，请稍候...')
    await knowledgeGraphApi.incrementalGenerate(projectPath.value)
    ElMessage.success('增量生成任务已启动')
    // 立即刷新状态并开始轮询
    startPolling()
    startVectorPolling()
  } catch (error: any) {
    console.error('[KnowledgeGraph] Failed to start incremental generate:', error)
    // 检查是否是"无变更"的错误
    if (error?.response?.data?.message?.includes('无变更') ||
        error?.message?.includes('无变更')) {
      ElMessage.info('无变更，无需更新')
    } else {
      ElMessage.error('启动增量生成任务失败')
    }
  } finally {
    isGenerating.value = false
  }
}

// 生成向量
const handleGenerateVector = async () => {
  if (!projectPath.value) return

  try {
    recordGenerateTime()
    isGeneratingVector.value = true
    ElMessage.info('已启动向量生成任务，请稍候...')
    console.log('[KnowledgeGraph] Starting vector generation for path:', projectPath.value)
    await startVectorGeneration(projectPath.value)
    ElMessage.success('向量生成任务已启动')
    // 立即刷新状态并开始轮询
    await loadVectorStatus()
    startVectorPolling()
  } catch (error: any) {
    console.error('[KnowledgeGraph] Failed to start vector generation:', error)
    ElMessage.error('启动向量生成任务失败')
  } finally {
    isGeneratingVector.value = false
  }
}

// 加载缺失向量信息
const loadMissingInfo = async () => {
  if (!projectPath.value) {
    missingInfo.value = null
    return
  }
  try {
    missingInfo.value = await getMissingEmbeddings(projectPath.value)
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load missing info:', error)
    missingInfo.value = null
  }
}

// 补齐缺失向量
const handleRefreshMissing = async () => {
  if (!projectPath.value) return
  try {
    isRefreshingMissing.value = true
    const msg = await refreshMissing(projectPath.value)
    ElMessage.success(msg || '补齐任务已启动')
    await loadVectorStatus()
    startVectorPolling()
  } catch (error: any) {
    console.error('[KnowledgeGraph] Failed to refresh missing:', error)
    ElMessage.error('补齐任务启动失败')
  } finally {
    isRefreshingMissing.value = false
  }
}

// 监听项目路径变化
watch(projectPath, (path) => {
  if (path) {
    loadGraphStatus()
    loadGitStatus()
    loadMissingInfo()
  }
})

// 处理查看方法详情
function handleViewDetail(_result: any) {
  // 跳转到方法详情或打开弹窗
}

// 处理查看调用链：切到引用分析 tab 并自动以该方法为入口查向下调用
const methodRefGraphRef = ref<InstanceType<typeof MethodReferenceGraph> | null>(null)
function handleViewCallChain(result: any) {
  if (!result || !result.className || !result.methodName) {
    ElMessage.warning('该结果缺少类名或方法名，无法跳转')
    return
  }
  const fqn = `${result.className}.${result.methodName}`
  activeTab.value = 'methodRef'
  // 等待 tab 渲染后再调用子组件
  nextTick(() => {
    const inst = methodRefGraphRef.value as any
    if (inst && typeof inst.setAndSearch === 'function') {
      inst.setAndSearch(fqn, result.nodeId, 'downstream')
    } else {
      ElMessage.warning('引用分析组件未就绪')
    }
  })
}

// ==================== 术语管理 ====================
const showGlossaryDialog = ref(false)
const glossaryTerms = ref<GlossaryTerm[]>([])
const glossaryLoading = ref(false)
const glossaryShowForm = ref(false)
const glossarySubmitting = ref(false)
const glossaryEditingId = ref<number | null>(null)
const glossaryForm = ref({ wrongTerm: '', correctTerm: '', context: '' })

const loadGlossaryTerms = async () => {
  if (!projectPath.value) return
  glossaryLoading.value = true
  try {
    glossaryTerms.value = await glossaryApi.list(projectPath.value) as unknown as GlossaryTerm[]
  } catch {
    ElMessage.error('加载术语列表失败')
  } finally {
    glossaryLoading.value = false
  }
}

watch(showGlossaryDialog, (visible) => {
  if (visible) loadGlossaryTerms()
})

const glossaryEditRow = (row: GlossaryTerm) => {
  glossaryEditingId.value = row.id!
  glossaryForm.value = {
    wrongTerm: row.wrongTerm,
    correctTerm: row.correctTerm,
    context: row.context || ''
  }
  glossaryShowForm.value = true
}

const glossaryDeleteRow = async (row: GlossaryTerm) => {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.wrongTerm} → ${row.correctTerm}」？`,
      '删除确认',
      { type: 'warning' }
    )
    await glossaryApi.delete(row.id!)
    ElMessage.success('已删除')
    await loadGlossaryTerms()
  } catch {
    // cancelled
  }
}

const glossarySubmit = async () => {
  if (!glossaryForm.value.wrongTerm.trim() || !glossaryForm.value.correctTerm.trim()) {
    ElMessage.warning('错误术语和正确术语不能为空')
    return
  }
  glossarySubmitting.value = true
  try {
    const payload: GlossaryTerm = {
      projectPath: projectPath.value,
      wrongTerm: glossaryForm.value.wrongTerm.trim(),
      correctTerm: glossaryForm.value.correctTerm.trim(),
      context: glossaryForm.value.context.trim() || undefined
    }
    if (glossaryEditingId.value) {
      await glossaryApi.update(glossaryEditingId.value, payload)
      ElMessage.success('术语已更新')
    } else {
      await glossaryApi.create(payload)
      ElMessage.success('术语已创建')
    }
    glossaryShowForm.value = false
    glossaryEditingId.value = null
    glossaryForm.value = { wrongTerm: '', correctTerm: '', context: '' }
    await loadGlossaryTerms()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    glossarySubmitting.value = false
  }
}

onMounted(() => {
  loadProjects()
  if (projectPath.value) {
    loadGraphStatus()
    loadGitStatus()
    loadVectorStatus()
    loadMissingInfo()
  }
  startPolling()
  startVectorPolling()
})

onUnmounted(() => {
  stopPolling()
  stopVectorPolling()
})

// 当 store 选中项目变化时，同步到本地
watch(() => appStore.selectedProjectNames, (newNames) => {
  if (newNames.length > 0) {
    selectedProjectNames.value = [...newNames]
    handleProjectChange()
  }
})
</script>

<style scoped>
.knowledge-graph-view {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stats-overview {
  display: flex;
  gap: 24px;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 16px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 80px;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #409EFF;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.vector-status-display {
  display: flex;
  align-items: center;
  gap: 8px;
}

.vector-progress {
  font-size: 12px;
  color: #606266;
  background: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
}

.main-tabs {
  min-height: 500px;
}

:deep(.el-tabs__content) {
  height: calc(100vh - 350px);
  overflow: auto;
}

.stat-value-warning {
  color: #E6A23C !important;
}

.stat-value-success {
  color: #67C23A !important;
}

.stat-item-vector {
  border-left: 1px solid #dcdfe6;
  padding-left: 24px;
}

.missing-drawer-content {
  padding: 0 4px;
}

.missing-summary {
  display: flex;
  gap: 24px;
  justify-content: center;
}

.missing-hint {
  font-size: 13px;
  color: #909399;
  margin-bottom: 12px;
}

.missing-more {
  font-size: 12px;
  color: #909399;
  text-align: center;
  margin-top: 8px;
}
</style>
