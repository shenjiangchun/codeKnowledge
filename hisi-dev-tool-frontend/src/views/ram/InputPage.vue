<script setup lang="ts">
/**
 * RAM InputPage — capture the requirement raw text + target project path,
 * then start a session via {@code useRamSession} and navigate to the draft
 * page using the returned {@code sessionId}.
 *
 * Project selection is aligned with the Knowledge Graph page:
 * - Auto-scan local Git repositories via /projects/scan-git-repos
 * - el-select with filterable search + status tag (branch / clean / source)
 * - Manual-path fallback toggle for paths outside the scanned roots
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useRamSession } from '@/composables/useRamSession'
import { projectApi } from '@/api/project'
import type { GitRepositoryInfo } from '@/types/callchain'

const router = useRouter()
const appStore = useAppStore()
const { start } = useRamSession()

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

async function loadProjects(): Promise<void> {
  loadingProjects.value = true
  try {
    const list = (await projectApi.scanGitRepos()) as unknown as GitRepositoryInfo[]
    if (Array.isArray(list)) {
      projects.value = list
    }
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
    const sid = await start(rawInput.value, projectPath.value)
    await router.push({ name: 'RamDraft', params: { sid } })
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
                      size="small"
                      :type="opt.clean ? 'success' : 'warning'"
                    >
                      {{ opt.clean ? 'clean' : 'dirty' }}
                    </el-tag>
                    <el-tag size="small">{{ opt.source }}</el-tag>
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
</style>
