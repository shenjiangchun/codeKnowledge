<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Close } from '@element-plus/icons-vue'
import { projectApi } from '@/api/project'
import { listRemoteProjects } from '@/api/remote-project'
import { listBranches, type ImageContent } from '@/api/merge-analysis'
import type { UploadFile } from 'element-plus'

const router = useRouter()

interface ProjectItem {
  name: string
  path: string
  source?: string
}

const form = ref({
  projectPath: '',
  sourceBranch: '',
  targetBranch: ''
})

const projects = ref<ProjectItem[]>([])
const branches = ref<string[]>([])
const loadingProjects = ref(false)
const loadingBranches = ref(false)

// 多模态图片上传
const uploadedImages = ref<ImageContent[]>([])
const imagePreviewUrls = ref<string[]>([])

const canProceed = computed(() =>
  form.value.projectPath &&
  form.value.sourceBranch &&
  form.value.targetBranch &&
  form.value.sourceBranch !== form.value.targetBranch
)

async function fetchProjects() {
  loadingProjects.value = true
  try {
    const [localResult, remoteResult] = await Promise.allSettled([
      projectApi.scanGitRepos() as Promise<unknown>,
      listRemoteProjects() as Promise<unknown>
    ])

    const local = localResult.status === 'fulfilled' && Array.isArray(localResult.value)
      ? (localResult.value as any[]).map((p: any) => ({
          name: p.name || '',
          path: p.path || '',
          source: p.source || 'scanned'
        }))
      : []
    const cloned = remoteResult.status === 'fulfilled' && Array.isArray(remoteResult.value)
      ? (remoteResult.value as any[])
          .filter((r: any) => r.cloneStatus === 'CLONED')
          .map((r: any) => ({
            name: r.name,
            // Use fullPath if available, otherwise fallback to localPath for backward compatibility
            path: r.fullPath || r.localPath,
            source: 'remote'
          }))
      : []

    // Dedup by project name, prioritizing remote projects (correct KG path)
    const clonedNames = new Set(cloned.map(p => p.name))
    const dedupedLocal = local.filter(p => !clonedNames.has(p.name))
    projects.value = [...cloned, ...dedupedLocal]
  } catch {
    ElMessage.error('获取项目列表失败')
  } finally {
    loadingProjects.value = false
  }
}

watch(() => form.value.projectPath, async (newPath) => {
  form.value.sourceBranch = ''
  form.value.targetBranch = ''
  branches.value = []
  if (!newPath) return

  loadingBranches.value = true
  try {
    branches.value = await listBranches(newPath)
  } catch {
    ElMessage.error('获取分支列表失败')
  } finally {
    loadingBranches.value = false
  }
})

// 图片上传处理：转为 Base64 格式
function handleImageUpload(file: UploadFile): boolean {
  const rawFile = file.raw
  if (!rawFile) return false

  if (rawFile.size > 500 * 1024) {
    ElMessage.warning('图片大小不能超过 500KB')
    return false
  }

  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(rawFile.type)) {
    ElMessage.warning('仅支持 JPEG、PNG、GIF、WebP 格式')
    return false
  }

  if (uploadedImages.value.length >= 5) {
    ElMessage.warning('最多上传 5 张图片')
    return false
  }

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

  return false
}

function removeImage(index: number): void {
  uploadedImages.value.splice(index, 1)
  imagePreviewUrls.value.splice(index, 1)
}

function handleNext() {
  if (!canProceed.value) return

  // 存储图片数据到 sessionStorage，避免 URL 长度限制
  if (uploadedImages.value.length > 0) {
    sessionStorage.setItem('mergeAnalysisImages', JSON.stringify(uploadedImages.value))
  } else {
    sessionStorage.removeItem('mergeAnalysisImages')
  }

  router.push({
    name: 'MergeAnalysisDiff',
    query: {
      projectPath: form.value.projectPath,
      sourceBranch: form.value.sourceBranch,
      targetBranch: form.value.targetBranch,
      hasImages: uploadedImages.value.length > 0 ? 'true' : undefined
    }
  })
}

fetchProjects()
</script>

<template>
  <div class="merge-analysis-input">
    <el-card>
      <template #header>
        <h3 style="margin: 0">合入分析 — 选择项目与分支</h3>
      </template>
      <el-form :model="form" label-width="140px">
        <el-form-item label="选择项目">
          <el-select
            v-model="form.projectPath"
            filterable
            placeholder="选择已有项目"
            :loading="loadingProjects"
            style="width: 100%"
          >
            <el-option
              v-for="p in projects"
              :key="p.path"
              :label="p.name"
              :value="p.path"
            >
              <div style="display: flex; align-items: center; justify-content: space-between;">
                <span>{{ p.name }}</span>
                <el-tag v-if="p.source === 'remote'" size="small" type="warning">远端</el-tag>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="源分支 (feature)">
          <el-select
            v-model="form.sourceBranch"
            filterable
            placeholder="选择源分支"
            :loading="loadingBranches"
            :disabled="!form.projectPath"
            style="width: 100%"
          >
            <el-option v-for="b in branches" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标分支 (main)">
          <el-select
            v-model="form.targetBranch"
            filterable
            placeholder="选择目标分支"
            :loading="loadingBranches"
            :disabled="!form.projectPath"
            style="width: 100%"
          >
            <el-option v-for="b in branches" :key="b" :label="b" :value="b" />
          </el-select>
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
          <el-button type="primary" :disabled="!canProceed" @click="handleNext">
            下一步 — 查看 Diff
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.merge-analysis-input {
  max-width: 700px;
  margin: 40px auto;
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
