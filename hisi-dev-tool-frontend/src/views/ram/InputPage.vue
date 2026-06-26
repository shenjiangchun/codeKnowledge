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
 * - appId options merged with project options in a single dropdown
 *
 * Multimodal support:
 * - Upload images and convert to Base64 for vision model input
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Close } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { startRamSession, type ImageContent } from '@/api/ram'
import { projectApi } from '@/api/project'
import { listRemoteProjects } from '@/api/remote-project'
import { projectGroupApi, type ProjectGroup } from '@/api/projectGroup'
import type { GitRepositoryInfo } from '@/types/callchain'
import type { UploadFile } from 'element-plus'

const router = useRouter()
const appStore = useAppStore()

const rawInput = ref<string>('')
const projectPaths = ref<string[]>([])
const manualInput = ref<string>('')
const projects = ref<GitRepositoryInfo[]>([])
const loadingProjects = ref<boolean>(false)
const submitting = ref<boolean>(false)
const showAdvanced = ref<boolean>(false)  // R-14: Toggle for advanced project selection options

// 多模态图片上传
const uploadedImages = ref<ImageContent[]>([])
const imagePreviewUrls = ref<string[]>([]) // 用于预览显示

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
      ? (remoteList.value as any[])
          .filter((r: any) => r.cloneStatus === 'CLONED' && r.fullPath)
          .map((r: any) => ({
            name: r.name,
            path: r.fullPath,  // Use fullPath (complete physical path) instead of localPath (just name slug)
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
  const fromStore = appStore.selectedProjects?.map((p: any) => p.path).filter(Boolean) as string[]
  if (fromStore && fromStore.length > 0 && projectPaths.value.length === 0) {
    projectPaths.value = fromStore
    // 同步到 selectedValues
    selectedValues.value = fromStore.map(p => `path:${p}`)
  }
}

onMounted(() => {
  loadProjects()
  loadGroups()  // Task 72: Load project groups for appId selector
})

// 图片上传处理：转为 Base64 格式
function handleImageUpload(file: UploadFile): boolean {
  const rawFile = file.raw
  if (!rawFile) return false

  // 限制文件大小（500KB）
  if (rawFile.size > 500 * 1024) {
    ElMessage.warning('图片大小不能超过 500KB')
    return false
  }

  // 限制文件类型
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(rawFile.type)) {
    ElMessage.warning('仅支持 JPEG、PNG、GIF、WebP 格式')
    return false
  }

  // 限制数量（最多 5 张）
  if (uploadedImages.value.length >= 5) {
    ElMessage.warning('最多上传 5 张图片')
    return false
  }

  // 转为 Base64
  const reader = new FileReader()
  reader.onload = (e) => {
    const base64 = e.target?.result as string
    uploadedImages.value.push({
      type: 'image_url',
      image_url: { url: base64 }
    })
    imagePreviewUrls.value.push(base64)
  }
  reader.readAsDataURL(rawFile)

  return false // 阻止 el-upload 默认上传行为
}

// 移除图片
function removeImage(index: number): void {
  uploadedImages.value.splice(index, 1)
  imagePreviewUrls.value.splice(index, 1)
}

async function onSubmit(): Promise<void> {
  // R-14: projectPaths now combines both dropdown selection and manual input
  if (projectPaths.value.length === 0) {
    ElMessage.warning('请选择至少一个项目')
    return
  }

  if (!rawInput.value.trim()) {
    ElMessage.warning('请输入需求描述')
    return
  }

  submitting.value = true
  try {
    const resp = await startRamSession({
      rawInput: rawInput.value,
      projectPaths: projectPaths.value,
      images: uploadedImages.value.length > 0 ? uploadedImages.value : undefined
    })
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
          <span class="hint">
            选择目标项目、贴入需求原文，启动多 Agent 协同分析
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

        <!-- 需求原文 -->
        <el-form-item label="需求原文" required>
          <el-input
            v-model="rawInput"
            type="textarea"
            :rows="10"
            placeholder="贴入需求文档片段、用户故事或问题描述..."
            data-test="ram-raw-input"
          />
        </el-form-item>

        <!-- 多模态图片上传 -->
        <el-form-item label="附加图片">
          <div class="image-upload-section">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept="image/jpeg,image/png,image/gif,image/webp"
              :on-change="handleImageUpload"
              :multiple="true"
            >
              <el-button type="default" :icon="Plus">
                上传图片
              </el-button>
            </el-upload>
            <span class="image-hint">（最多 5 张，每张不超过 500KB）</span>
          </div>
          <!-- 图片预览 -->
          <div v-if="imagePreviewUrls.length > 0" class="image-preview-list">
            <div v-for="(url, idx) in imagePreviewUrls" :key="idx" class="image-preview-item">
              <img :src="url" class="preview-img" />
              <el-button
                type="danger"
                :icon="Close"
                circle
                size="small"
                class="remove-btn"
                @click="removeImage(idx)"
              />
            </div>
          </div>
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

/* 图片上传样式 */
.image-upload-section {
  display: flex;
  align-items: center;
  gap: 8px;
}
.image-hint {
  color: #909399;
  font-size: 12px;
}
.image-preview-list {
  display: flex;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}
.image-preview-item {
  position: relative;
  width: 100px;
  height: 100px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  overflow: hidden;
}
.preview-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.remove-btn {
  position: absolute;
  top: 4px;
  right: 4px;
  opacity: 0.8;
}
.remove-btn:hover {
  opacity: 1;
}
</style>