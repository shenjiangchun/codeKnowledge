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
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { startRamSession } from '@/api/ram'
import { projectApi } from '@/api/project'
import { listRemoteProjects } from '@/api/remote-project'
import type { GitRepositoryInfo } from '@/types/callchain'

const router = useRouter()
const appStore = useAppStore()

const rawInput = ref<string>('')
const projectPath = ref<string>('')
const projects = ref<GitRepositoryInfo[]>([])
const loadingProjects = ref<boolean>(false)
const manualMode = ref<boolean>(false)
const submitting = ref<boolean>(false)

const projectOptions = computed(() =>
  projects.value.map((p) => ({
    label: p.name,
    value: p.path,
    branch: p.branch,
    clean: p.clean,
    source: p.source
  }))
)

const selectedProjectLabel = computed(() => {
  if (!projectPath.value) return ''
  const match = projects.value.find((p) => p.path === projectPath.value)
  return match ? match.name : projectPath.value
})

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
            source: 'remote'
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
  const fromStore = appStore.selectedProjects?.[0]?.path
  if (fromStore && !projectPath.value) {
    projectPath.value = fromStore
  }
}

onMounted(loadProjects)

async function onSubmit(): Promise<void> {
  if (!rawInput.value.trim()) {
    ElMessage.warning('请输入需求描述')
    return
  }
  if (!projectPath.value.trim()) {
    ElMessage.warning('请选择项目')
    return
  }
  submitting.value = true
  try {
    // Only POST to create the session — do NOT open an SSE stream here.
    // DraftPage.vue will open the single SSE stream via rejoin().
    const resp = await startRamSession({ rawInput: rawInput.value, projectPath: projectPath.value })
    await router.push({ name: 'RamDraft', params: { sid: resp.sessionId } })
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
          <span>需求分析大师</span>
          <span class="hint">选择目标项目、贴入需求原文，启动多 Agent 协同分析</span>
        </div>
      </template>
      <el-form label-position="top">
        <el-form-item label="目标项目" required>
          <div class="project-row">
            <el-select
              v-if="!manualMode"
              v-model="projectPath"
              filterable
              clearable
              :loading="loadingProjects"
              placeholder="选择本机已扫描到的 Git 仓库"
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
                      v-if="opt.source !== 'remote'"
                      size="small"
                      :type="opt.clean ? 'success' : 'warning'"
                    >
                      {{ opt.clean ? 'clean' : 'dirty' }}
                    </el-tag>
                    <el-tag size="small" :type="opt.source === 'remote' ? 'warning' : 'primary'">
                      {{ opt.source === 'remote' ? '远端' : opt.source === 'scanned' ? '扫描' : opt.source }}
                    </el-tag>
                  </span>
                </div>
              </el-option>
            </el-select>
            <el-input
              v-else
              v-model="projectPath"
              placeholder="项目绝对路径，例如 C:/projects/foo"
              clearable
              style="flex: 1"
              data-test="ram-project-manual"
            />
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
          <div v-if="projectPath && !manualMode" class="selected-project-hint">
            <el-icon :size="14"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="14" height="14"><path fill="currentColor" d="M512 64a448 448 0 1 1 0 896 448 448 0 0 1 0-896m-55.808 536.384-99.52-99.584a38.4 38.4 0 1 0-54.336 54.336l126.72 126.72a38.272 38.272 0 0 0 54.336 0l262.4-262.464a38.4 38.4 0 1 0-54.336-54.336z"/></svg></el-icon>
            <span class="selected-name">{{ selectedProjectLabel }}</span>
            <span class="selected-path">{{ projectPath }}</span>
          </div>
          <div v-if="!manualMode && projects.length === 0 && !loadingProjects" class="empty-hint">
            未扫描到 Git 仓库，可点击「手动输入路径」直接填写绝对路径，或在「项目管理」中克隆/添加项目。
          </div>
        </el-form-item>
        <el-form-item label="需求原文" required>
          <el-input
            v-model="rawInput"
            type="textarea"
            :rows="10"
            placeholder="贴入需求文档片段、用户故事或问题描述..."
            data-test="ram-raw-input"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="submitting"
            data-test="ram-submit"
            @click="onSubmit"
          >
            开始分析
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
</style>
