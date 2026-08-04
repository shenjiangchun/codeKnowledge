<template>
  <div class="user-management">
    <div class="page-header">
      <h2>用户管理</h2>
    </div>

    <el-table :data="users" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" width="200" />
      <el-table-column label="角色" width="200">
        <template #default="{ row }">
          <el-select
            v-model="row.role"
            size="small"
            style="width: 140px;"
            @change="handleChangeRole(row)"
            :disabled="row.username === 'root'"
          >
            <el-option label="管理员" value="ADMIN" />
            <el-option label="成员" value="MEMBER" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="创建时间">
        <template #default="{ row }">
          {{ formatTime(row.createdAt) }}
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api/user'
import type { UserInfo } from '@/types/api'

const users = ref<UserInfo[]>([])
const loading = ref(false)

async function loadUsers() {
  loading.value = true
  try {
    users.value = await userApi.list()
  } catch (err: unknown) {
    ElMessage.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

async function handleChangeRole(user: UserInfo) {
  try {
    await userApi.changeRole(user.id, user.role)
    ElMessage.success(`已将 ${user.username} 角色改为 ${user.role === 'ADMIN' ? '管理员' : '成员'}`)
  } catch {
    ElMessage.error('修改角色失败')
    await loadUsers()
  }
}

function formatTime(ts: number) {
  if (!ts) return '-'
  return new Date(ts * 1000).toLocaleString()
}

onMounted(loadUsers)
</script>

<style scoped>
.user-management {
  padding: 20px;
}

.page-header {
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 18px;
}
</style>
