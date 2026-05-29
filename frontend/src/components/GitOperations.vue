<template>
  <el-dropdown trigger="click" @command="handleCommand">
    <el-button type="primary" link>
      <el-icon><Operation /></el-icon>
      Git 操作
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="status">
          <el-icon><InfoFilled /></el-icon>
          查看状态
        </el-dropdown-item>
        <el-dropdown-item command="pull">
          <el-icon><Download /></el-icon>
          拉取更新
        </el-dropdown-item>
        <el-dropdown-item command="logs">
          <el-icon><Document /></el-icon>
          提交记录
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>

  <!-- Status Dialog -->
  <el-dialog v-model="statusDialogVisible" title="Git 状态" width="500px" append-to-body>
    <el-descriptions :column="1" border v-if="status">
      <el-descriptions-item label="当前分支">
        <el-tag>{{ status.branch }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="工作区状态">
        <el-tag :type="status.clean ? 'success' : 'warning'">
          {{ status.clean ? '干净' : '有改动' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="修改文件" v-if="status.modified?.length">
        {{ status.modified.join(', ') }}
      </el-descriptions-item>
      <el-descriptions-item label="未跟踪文件" v-if="status.untracked?.length">
        {{ status.untracked.join(', ') }}
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>

  <!-- Logs Dialog -->
  <el-dialog v-model="logsDialogVisible" title="提交记录" width="600px" append-to-body>
    <el-table :data="logs" v-loading="loading">
      <el-table-column prop="commitId" label="Commit" width="100" />
      <el-table-column prop="message" label="消息" show-overflow-tooltip />
      <el-table-column prop="author" label="作者" width="100" />
      <el-table-column prop="date" label="日期" width="150">
        <template #default="{ row }">
          {{ formatDate(row.date) }}
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Operation, InfoFilled, Download, Document } from '@element-plus/icons-vue'
import { gitApi, type GitStatus, type GitLog } from '@/api/git'

const props = defineProps<{
  projectPath: string
}>()

const loading = ref(false)
const statusDialogVisible = ref(false)
const logsDialogVisible = ref(false)
const status = ref<GitStatus | null>(null)
const logs = ref<GitLog[]>([])

async function handleCommand(command: string) {
  switch (command) {
    case 'status':
      await loadStatus()
      break
    case 'pull':
      await handlePull()
      break
    case 'logs':
      await loadLogs()
      break
  }
}

async function loadStatus() {
  loading.value = true
  try {
    const response = await gitApi.getStatus(props.projectPath)
    // axios 拦截器已解包，response 直接是数据对象
    status.value = response
    statusDialogVisible.value = true
  } catch (e: any) {
    ElMessage.error('获取状态失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function handlePull() {
  loading.value = true
  try {
    await gitApi.pull(props.projectPath)
    ElMessage.success('拉取成功')
  } catch (e: any) {
    ElMessage.error('拉取失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  loading.value = true
  try {
    const response = await gitApi.getLogs(props.projectPath, 20)
    // axios 拦截器已解包，response 直接是数据数组
    logs.value = response || []
    logsDialogVisible.value = true
  } catch (e: any) {
    ElMessage.error('获取记录失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

function formatDate(date: string | Date) {
  return new Date(date).toLocaleString()
}
</script>