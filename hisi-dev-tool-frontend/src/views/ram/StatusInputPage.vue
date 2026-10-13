<!-- DEPRECATED: 由 RamChatView.vue 内嵌 ProjectPickerDialog.vue 替代 -->
<script setup lang="ts">
/**
 * StatusInputPage — 项目现状分析输入页面.
 *
 * DEPRECATED: 由 RamChatView.vue (路径: /ram/chat) 内嵌的 ProjectPickerDialog 替代。
 *
 * 用户选择目标项目，可选输入分析问题，生成项目现状分析报告.
 *
 * Note: This page only calls the REST API to create the session. The SSE
 * event stream is opened exclusively by StatusPage to avoid duplicate streams.
 *
 * Project selection is aligned with the Knowledge Graph page:
 * - Auto-scan local Git repositories via /projects/scan-git-repos
 * - Also load cloned remote projects via /remote-projects
 * - el-select with filterable search + status tag (branch / clean / source)
 * - Manual-path fallback toggle for paths outside the scanned roots
 * - appId options merged with project options in a single dropdown
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { startStatusAnalysis } from '@/api/ram'
import { projectApi } from '@/api/project'
import { listRemoteProjects } from '@/api/remote-project'
import { projectGroupApi, type ProjectGroup } from '@/api/projectGroup'
import type { GitRepositoryInfo } from '@/types/callchain'
import type { RemoteProject } from '@/types/remote-project'

const router = useRouter()
const appStore = useAppStore()

const statusQuestion = ref<string>('')
const projectPaths = ref<string[]>([])
const manualInput = ref<string>('')
const projects = ref<GitRepositoryInfo[]>([])
const loadingProjects = ref<boolean>(false)
const submitting = ref<boolean>(false)
const showAdvanced = ref<boolean>(false)

// Task 72: appId selector (merged with project selector)
const groups = ref<ProjectGroup[]>([])
const loadingGroups = ref<boolean>(false)

// 合并的选项列表：appId 和项目
interface ProjectOption {
  value: string        // 实际值：appId 或 project path
  label: string        // 显示名
  type: 'appId' | 'project'
  paths: string[]      // appId 对应的所有 project paths，project 则是 [path]
  disabled?: boolean
}

// 构建合并的选项列表
const allOptions = computed<ProjectOption[]>(() => {
  const opts: ProjectOption[] = []

  // appId 选项
  for (const g of groups.value) {
    opts.push({
      value: `appId:${g.appId}`,
      label: g.appName || g.appId,
      type: 'appId',
      paths: g.projectPaths
    })
  }

  // 独立项目选项
  for (const p of projects.value) {
    opts.push({
      value: `path:${p.path}`,
      label: p.name,
      type: 'project',
      paths: [p.path]
    })
  }

  return opts
})

// 选择值（appId:xxx 或 path:xxx 格式）
const selectedValues = ref<string[]>([])

// 从 selectedValues 解析出实际的 projectPaths
watch(selectedValues, (vals) => {
  const paths = new Set<string>()
  for (const v of vals) {
    const opt = allOptions.value.find(o => o.value === v)
    if (opt) {
      opt.paths.forEach(p => paths.add(p))
    }
  }
  projectPaths.value = [...paths]
}, { immediate: true })

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

function addManualPath(): void {
  const path = manualInput.value.trim()
  if (!path) return
  if (projectPaths.value.includes(path)) {
    ElMessage.warning('该路径已添加')
    return
  }
  projectPaths.value = [...projectPaths.value, path]
  // 同步添加到 selectedValues
  selectedValues.value = [...selectedValues.value, `path:${path}`]
  manualInput.value = ''
}

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
      ? (remoteList.value as RemoteProject[])
          .filter(r => r.cloneStatus === 'CLONED')
          .map(r => ({
            name: r.name,
            // Use fullPath if available, otherwise fallback to localPath for backward compatibility
            path: r.fullPath || r.localPath,
            branch: r.branch || 'main',
            clean: true,
            source: 'cloned' as const
          }))
      : []

    // Dedup by project name (not path), prioritizing remote projects (correct KG path)
    const clonedNames = new Set(cloned.map(p => p.name))
    const dedupedLocal = local.filter(p => !clonedNames.has(p.name))
    projects.value = [...cloned, ...dedupedLocal]
  } catch (error) {
    const msg = error instanceof Error ? error.message : '扫描项目失败'
    ElMessage.warning(`未能加载项目列表：${msg}`)
  } finally {
    loadingProjects.value = false
  }

  // Pre-populate from app store if available.
  const fromStore = appStore.selectedProjects?.map(p => p.path).filter(Boolean) as string[]
  if (fromStore && fromStore.length > 0 && projectPaths.value.length === 0) {
    projectPaths.value = fromStore
    // 同步到 selectedValues
    selectedValues.value = fromStore.map(p => `path:${p}`)
  }
}

onMounted(() => {
  loadProjects()
  loadGroups()
})

async function onSubmit(): Promise<void> {
  if (projectPaths.value.length === 0) {
    ElMessage.warning('请选择至少一个项目')
    return
  }

  submitting.value = true
  try {
    const resp = await startStatusAnalysis({
      projectPath: projectPaths.value[0],
      mode: 'quick',
      question: statusQuestion.value.trim() || undefined
    })
    await router.push({ name: 'RamStatus', params: { sid: resp.sessionId } })
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
          <span>项目现状分析</span>
          <span class="hint">
            输入问题，分析项目代码，生成定制化技术报告
          </span>
        </div>
      </template>

      <el-form label-position="top">
        <el-form-item label="目标项目" required>
          <div class="project-row">
            <el-select
              v-model="selectedValues"
              multiple
              filterable
              clearable
              collapse-tags
              collapse-tags-tooltip
              class="project-select"
              :loading="loadingProjects || loadingGroups"
              placeholder="选择 appId 或项目"
              data-test="ram-project-select"
            >
              <el-option
                v-for="opt in allOptions"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
                :disabled="opt.disabled"
              >
                <div class="option-row">
                  <el-tag :type="opt.type === 'appId' ? 'warning' : 'primary'" size="small">
                    {{ opt.type === 'appId' ? '分组' : '项目' }}
                  </el-tag>
                  <span>{{ opt.label }}</span>
                  <span v-if="opt.type === 'appId'" class="option-count">({{ opt.paths.length }} 个项目)</span>
                </div>
              </el-option>
            </el-select>
            <el-button
              :loading="loadingProjects"
              @click="loadProjects"
            >
              刷新
            </el-button>
          </div>
          <div v-if="projectPaths.length > 0" class="selected-project-hint">
            <el-icon :size="14"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="14" height="14"><path fill="currentColor" d="M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896m-55.808 536.384-99.52-99.584a38.4 38.4 0 1 0-54.336 54.336l126.72 126.72a38.272 38.272 0 0 0 54.336 0l262.4-262.464a38.4 38.4 0 1 0-54.336-54.336z"/></svg></el-icon>
            <span>已选 {{ projectPaths.length }} 个项目</span>
          </div>
          <div v-if="projects.length === 0 && groups.length === 0 && !loadingProjects" class="empty-hint">
            未扫描到 Git 仓库，可点击「更多选择方式」手动输入路径，或在「项目管理」中克隆/添加项目。
          </div>

          <!-- R-14: Advanced options collapsed by default -->
          <el-collapse class="advanced-collapse">
            <el-collapse-item>
              <template #title>
                <span class="advanced-toggle" @click.stop="showAdvanced = !showAdvanced">
                  {{ showAdvanced ? '收起高级选项' : '更多选择方式' }}
                </span>
              </template>
              <div v-if="showAdvanced" class="advanced-content">
                <div class="manual-mode-row">
                  <el-input
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
                </div>
                <div class="manual-hint">手动输入的路径将添加到上方下拉列表的已选项目中</div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </el-form-item>

        <!-- 问题输入 -->
        <el-form-item label="分析问题">
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
            生成分析报告
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
.project-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.project-select {
  flex: 1;
  min-width: 200px;
}
.project-select :deep(.el-select__wrapper) {
  min-height: 32px;
}
.empty-hint {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
.option-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.option-count {
  color: #909399;
  font-size: 12px;
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
.question-hint {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}

/* R-14: Advanced options collapse styling */
.advanced-collapse {
  margin-top: 12px;
  border: none;
}
.advanced-collapse :deep(.el-collapse-item__header) {
  border: none;
  background: transparent;
  height: 32px;
  line-height: 32px;
}
.advanced-collapse :deep(.el-collapse-item__wrap) {
  border: none;
  background: transparent;
}
.advanced-toggle {
  font-size: 13px;
  color: #409eff;
  cursor: pointer;
}
.advanced-toggle:hover {
  color: #66b1ff;
}
.advanced-content {
  padding: 12px 0;
}
.manual-mode-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.manual-hint {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}
</style>