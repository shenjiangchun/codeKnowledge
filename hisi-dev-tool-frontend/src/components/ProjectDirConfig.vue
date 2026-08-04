<template>
  <el-card class="project-dir-config">
    <template #header>
      <div class="card-header">
        <span>项目目录配置</span>
        <el-tag v-if="configured" type="success">已配置</el-tag>
        <el-tag v-else type="warning">未配置</el-tag>
      </div>
    </template>

    <el-form :model="form" label-width="100px">
      <el-form-item label="项目目录">
        <el-input
          v-model="form.path"
          placeholder="请输入项目代码存放目录"
          clearable
        >
          <template #append>
            <el-button @click="selectDirectory">选择目录</el-button>
          </template>
        </el-input>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" @click="handleSave" :loading="saving">
          保存配置
        </el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      closable
      @close="error = ''"
    />
  </el-card>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

const form = ref({
  path: ''
})

const saving = ref(false)
const error = ref('')

const configured = computed(() => appStore.projectDirConfigured)

onMounted(() => {
  form.value.path = appStore.projectDir
})

function selectDirectory() {
  // In browser environment, we can only use input
  // For Electron app, could use dialog.showOpenDialog
  ElMessage.info('请手动输入目录路径')
}

async function handleSave() {
  if (!form.value.path.trim()) {
    ElMessage.warning('请输入项目目录')
    return
  }

  saving.value = true
  try {
    const success = await appStore.updateProjectDir(form.value.path.trim())
    if (success) {
      ElMessage.success('配置保存成功')
    } else {
      error.value = appStore.configError || '保存失败'
    }
  } finally {
    saving.value = false
  }
}

function handleReset() {
  form.value.path = appStore.projectDir
}
</script>

<style scoped>
.project-dir-config {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>