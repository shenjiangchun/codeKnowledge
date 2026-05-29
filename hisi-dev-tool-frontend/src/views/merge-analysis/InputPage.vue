<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { projectApi } from '@/api/project'
import { listBranches } from '@/api/merge-analysis'

const router = useRouter()
const appStore = useAppStore()

interface ProjectItem {
  name: string
  path: string
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

const canProceed = computed(() =>
  form.value.projectPath &&
  form.value.sourceBranch &&
  form.value.targetBranch &&
  form.value.sourceBranch !== form.value.targetBranch
)

async function fetchProjects() {
  loadingProjects.value = true
  try {
    const list = await projectApi.getProjects() as any[]
    projects.value = list.map((p: any) => ({
      name: p.name || p.projectName || '',
      path: p.path || p.projectPath || ''
    }))
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

function handleNext() {
  if (!canProceed.value) return
  router.push({
    name: 'MergeAnalysisDiff',
    query: {
      projectPath: form.value.projectPath,
      sourceBranch: form.value.sourceBranch,
      targetBranch: form.value.targetBranch
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
            />
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
</style>
