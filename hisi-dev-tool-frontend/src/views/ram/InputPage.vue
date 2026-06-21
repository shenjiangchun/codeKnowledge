<script setup lang="ts">
/**
 * RAM InputPage — capture the requirement raw text + target project path,
 * then POST to create a session and navigate to the draft page.
 *
 * Note: This page only calls the REST API to create the session. The SSE
 * event stream is opened exclusively by DraftPage to avoid duplicate streams.
 *
 * Project selection is aligned with the Knowledge Graph page:
 * - Auto-scan local Git repositories via /projects/scan-git-repos
 * - Also load cloned remote projects via /remote-projects
 * - el-select with filterable search + status tag (branch / clean / source)
 * - Manual-path fallback toggle for paths outside the scanned roots
 *
 * Supports two modes:
 * - 需求分析大师 (demand): traditional requirement analysis
 * - 项目现状分析 (status): project overview for new employees
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { startRamSession, startStatusAnalysis } from '@/api/ram'
import { projectApi } from '@/api/project'
import { listRemoteProjects } from '@/api/remote-project'
import { projectGroupApi, type ProjectGroup } from '@/api/projectGroup'
import type { GitRepositoryInfo } from '@/types/callchain'

const router = useRouter()
const route = useRoute()
const appStore = useAppStore()

// Analysis mode: 'demand' or 'status'
const analysisMode = ref<'demand' | 'status'>('demand')

const rawInput = ref<string>('')
const statusQuestion = ref<string>('')  // Question for status analysis mode
const projectPaths = ref<string[]>([])
const manualInput = ref<string>('')
const projects = ref<GitRepositoryInfo[]>([])
const loadingProjects = ref<boolean>(false)
const manualMode = ref<boolean>(false)
const submitting = ref<boolean>(false)

// Task 72: appId selector
const groups = ref<ProjectGroup[]>([])
const selectedAppId = ref<string>('')
const loadingGroups = ref<boolean>(false)

async function loadGroups(): Promise<void> {
  loadingGroups.value = true
  try {
    groups.value = await projectGroupApi.getGroups()
  } catch {
    // Silently fail if no groups available
    groups.value = []
  } finally {
    loadingGroups.value = false
  }
}

// When appId is selected, auto-fill projectPaths from the group
function onAppIdChange(): void {
  if (!selectedAppId.value) return
  const group = groups.value.find(g => g.appId === selectedAppId.value)
  if (group && group.projectPaths.length > 0) {
    projectPaths.value = [...group.projectPaths]
    ElMessage.success(`已加载分组 "${group.appName}" 下的 ${group.projectPaths.length} 个项目`)
  }
}

function addManualPath(): void {
  const path = manualInput.value.trim()
  if (!path) return
  if (projectPaths.value.includes(path)) {
    ElMessage.warning('该路径已添加')
    return
  }
  projectPaths.value = [...projectPaths.value, path]
  manualInput.value = ''
}

const projectOptions = computed(() =>
  projects.value.map((p) => ({
    label: p.name,
    value: p.path,
    branch: p.branch,
    clean: p.clean,
    source: p.source
  }))
)

async function loadProjects(): Promise<void> {
  loadingProjects.value = true
  try {
    // 并行加载本地项目 + 已克隆远端项目
    const [localList, remoteList] = await Promise.allSettled([
      projectApi.scanGitRepos() as Promise<unknown>,
      listRemoteProjects() as Promise<unknown>
    ])

    const local = localList.status === 'fulfilled' && Array.isArray(localList.value)
      ? localList.value as GitRepositoryInfo[]
      : []
    const cloned = remoteList.status === 'fulfilled' && Array.isArray(remoteList.value)
      ? (remoteList.value as any[])
          .filter((r: any) => r.cloneStatus === 'CLONED' && r.localPath)
          .map((r: any) => ({
            name: r.name,
            path: r.localPath,
            branch: r.branch || 'main',
            clean: true,
            source: 'cloned'
          }))
      : []

    // 去重：远端项目 localPath 可能和本地项目 path 重复
    const localPaths = new Set(local.map(p => p.path))
    const dedupedRemote = cloned.filter(p => !localPaths.has(p.path))
    projects.value = [...local, ...dedupedRemote]
  } catch (error) {
    const msg = error instanceof Error ? error.message : '扫描项目失败'
    ElMessage.warning(`未能加载项目列表：${msg}`)
  } finally {
    loadingProjects.value = false
  }

  // Pre-populate from app store if available.
  const fromStore = appStore.selectedProjects?.map((p: any) => p.path).filter(Boolean) as string[]
  if (fromStore && fromStore.length > 0 && projectPaths.value.length === 0) {
    projectPaths.value = fromStore
  }
}

onMounted(() => {
  // Check URL query parameter for mode
  const modeParam = route.query.mode as string
  if (modeParam === 'status' || modeParam === 'demand') {
    analysisMode.value = modeParam
  }
  loadProjects()
  loadGroups()  // Task 72: Load project groups for appId selector
})

async function onSubmit(): Promise<void> {
  const paths = manualMode.value
    ? projectPaths.value // manual mode: paths are typed directly
    : projectPaths.value
  if (paths.length === 0) {
    ElMessage.warning('请选择至少一个项目')
    return
  }

  submitting.value = true
  try {
    if (analysisMode.value === 'status') {
      // 项目现状分析
      const resp = await startStatusAnalysis({
        projectPath: paths[0],
        mode: 'quick',
        question: statusQuestion.value.trim() || undefined
      })
      await router.push({ name: 'RamStatus', params: { sid: resp.sessionId } })
    } else {
      // 需求分析大师
      if (!rawInput.value.trim()) {
        ElMessage.warning('请输入需求描述')
        return
      }
      const resp = await startRamSession({
        rawInput: rawInput.value,
        projectPaths: paths
      })
      await router.push({ name: 'RamDraft', params: { sid: resp.sessionId } })
    }
  } catch (error) {
    const msg = error instanceof Error ? error.message : '启动失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="ram-input-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ analysisMode === 'demand' ? '需求分析大师' : '项目现状分析' }}</span>
          <span class="hint">
            {{ analysisMode === 'demand'
              ? '选择目标项目、贴入需求原文，启动多 Agent 协同分析'
              : '输入问题，分析项目代码，生成定制化技术报告' }}
          </span>
        </div>
      </template>

      <!-- Mode switch -->
      <el-radio-group v-model="analysisMode" class="mode-switch">
        <el-radio-button value="demand">需求分析大师</el-radio-button>
        <el-radio-button value="status">项目现状分析</el-radio-button>
      </el-radio-group>

      <el-form label-position="top">
        <!-- Task 72: appId selector - quick selection of project group -->
        <el-form-item label="按 appId 选择分组" v-if="groups.length > 0">
          <el-select
            v-model="selectedAppId"
            clearable
            filterable
            placeholder="选择 appId 自动加载分组下的所有项目"
            style="width: 100%"
            @change="onAppIdChange"
          >
            <el-option
              v-for="group in groups"
              :key="group.appId"
              :label="`${group.appName} (${group.appId})`"
              :value="group.appId"
            >
              <div style="display: flex; justify-content: space-between; align-items: center">
                <span>{{ group.appName }}</span>
                <el-tag size="small" type="info">{{ group.projectPaths.length }}个项目</el-tag>
              </div>
            </el-option>
          </el-select>
          <div class="appId-hint" v-if="selectedAppId">
            <el-icon :size="12"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="12" height="12"><path fill="currentColor" d="M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896m-55.808 536.384-99.52-99.584a38.4 38.4 0 1 0-54.336 54.336l126.72 126.72a38.272 38.272 0 0 0 54.336 0l262.4-262.464a38.4 38.4 0 1 0-54.336-54.336z"/></svg></el-icon>
            <span>选择 appId 后将自动填充下方项目路径</span>
          </div>
        </el-form-item>

        <el-form-item label="目标项目" required>
          <div class="project-row">
            <el-select
              v-if="!manualMode"
              v-model="projectPaths"
              multiple
              filterable
              clearable
              collapse-tags
              collapse-tags-tooltip
              :loading="loadingProjects"
              placeholder="选择一个或多个 Git 仓库"
              style="flex: 1"
              data-test="ram-project-select"
            >
              <el-option
                v-for="opt in projectOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              >
                <div class="proj-option">
                  <span class="proj-name">{{ opt.label }}</span>
                  <span class="proj-meta">
                    <el-tag size="small" type="info">{{ opt.branch || '-' }}</el-tag>
                    <el-tag
                      v-if="opt.source !== 'cloned'"
                      size="small"
                      :type="opt.clean ? 'success' : 'warning'"
                    >
                      {{ opt.clean ? 'clean' : 'dirty' }}
                    </el-tag>
                    <el-tag size="small" :type="opt.source === 'cloned' ? 'warning' : 'primary'">
                      {{ opt.source === 'cloned' ? '远端' : opt.source === 'scanned' ? '扫描' : opt.source }}
                    </el-tag>
                  </span>
                </div>
              </el-option>
            </el-select>
            <el-input
              v-else
              v-model="manualInput"
              placeholder="输入项目绝对路径，回车添加（可添加多个）"
              clearable
              style="flex: 1"
              data-test="ram-project-manual"
              @keyup.enter="addManualPath"
            >
              <template #append>
                <el-button @click="addManualPath">添加</el-button>
              </template>
            </el-input>
            <el-button
              :icon="loadingProjects ? undefined : undefined"
              :loading="loadingProjects"
              :disabled="manualMode"
              @click="loadProjects"
            >
              刷新
            </el-button>
            <el-button @click="manualMode = !manualMode">
              {{ manualMode ? '从列表选择' : '手动输入路径' }}
            </el-button>
          </div>
          <div v-if="projectPaths.length > 0 && !manualMode" class="selected-project-hint">
            <el-icon :size="14"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="14" height="14"><path fill="currentColor" d="M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896m-55.808 536.384-99.52-99.584a38.4 38.4 0 1 0-54.336 54.336l126.72 126.72a38.272 38.272 0 0 0 54.336 0l262.4-262.464a38.4 38.4 0 1 0-54.336-54.336z"/></svg></el-icon>
            <span>已选 {{ projectPaths.length }} 个项目</span>
          </div>
          <div v-if="manualMode && projectPaths.length > 0" class="manual-paths-list">
            <el-tag
              v-for="(p, idx) in projectPaths"
              :key="idx"
              closable
              size="small"
              @close="projectPaths.splice(idx, 1)"
            >{{ p }}</el-tag>
          </div>
          <div v-if="!manualMode && projects.length === 0 && !loadingProjects" class="empty-hint">
            未扫描到 Git 仓库，可点击「手动输入路径」直接填写绝对路径，或在「项目管理」中克隆/添加项目。
          </div>
        </el-form-item>

        <!-- 需求原文 - only for demand mode -->
        <el-form-item v-if="analysisMode === 'demand'" label="需求原文" required>
          <el-input
            v-model="rawInput"
            type="textarea"
            :rows="10"
            placeholder="贴入需求文档片段、用户故事或问题描述..."
            data-test="ram-raw-input"
          />
        </el-form-item>

        <!-- 问题输入 - only for status mode -->
        <el-form-item v-if="analysisMode === 'status'" label="分析问题">
          <el-input
            v-model="statusQuestion"
            type="textarea"
            :rows="5"
            placeholder="输入你想了解的问题，如：'需求状态会受哪些接口影响？这些接口的逻辑是什么？'"
            data-test="status-question-input"
          />
          <div class="question-hint">
            <span>可选。如不输入，将生成项目概览报告（入口点、核心调用链、模块划分、技术栈）。</span>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            :loading="submitting"
            data-test="ram-submit"
            @click="onSubmit"
          >
            {{ analysisMode === 'demand' ? '开始分析' : '生成分析报告' }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.ram-input-view {
  padding: 16px;
}
.card-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.card-header .hint {
  color: #909399;
  font-size: 12px;
}
.mode-switch {
  margin-bottom: 20px;
}
.project-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.empty-hint {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
.proj-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.proj-name {
  font-weight: 500;
}
.proj-meta {
  display: inline-flex;
  gap: 4px;
}
.selected-project-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 6px 10px;
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 6px;
  font-size: 13px;
  color: #67c23a;
}
.selected-project-hint .selected-name {
  font-weight: 600;
  color: #529b2e;
}
.selected-project-hint .selected-path {
  color: #909399;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  margin-left: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.manual-paths-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.appId-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
.question-hint {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
</style>
