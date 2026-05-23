<script setup lang="ts">
/**
 * RAM InputPage — capture the requirement raw text + target project path,
 * then start a session via {@code useRamSession} and navigate to the draft
 * page using the returned {@code sessionId}.
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useRamSession } from '@/composables/useRamSession'
import { projectApi } from '@/api/project'

interface ProjectOption {
  name: string
  path: string
}

const router = useRouter()
const appStore = useAppStore()
const { start } = useRamSession()

const rawInput = ref<string>('')
const projectPath = ref<string>('')
const projectOptions = ref<ProjectOption[]>([])
const submitting = ref<boolean>(false)

async function loadProjects(): Promise<void> {
  try {
    const list = (await projectApi.getProjects()) as unknown as ProjectOption[]
    if (Array.isArray(list)) {
      projectOptions.value = list
    }
  } catch {
    // Fall back silently; user can free-text type a path.
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
    ElMessage.warning('请选择或填写项目路径')
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
          <span class="hint">输入需求描述与目标项目，启动多 Agent 协同分析</span>
        </div>
      </template>
      <el-form label-position="top">
        <el-form-item label="项目路径" required>
          <el-autocomplete
            v-model="projectPath"
            :fetch-suggestions="
              (q: string, cb: (items: { value: string }[]) => void) =>
                cb(
                  projectOptions
                    .filter((p) => !q || p.name.includes(q) || p.path.includes(q))
                    .map((p) => ({ value: p.path }))
                )
            "
            placeholder="项目绝对路径，例如 C:/projects/foo"
            style="width: 100%"
            clearable
          />
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
</style>
