<template>
  <div class="user-dropdown">
    <!-- 已登录 -->
    <template v-if="authStore.isLoggedIn">
      <el-dropdown trigger="click">
        <span class="user-info">
          <span class="username">{{ authStore.username }}</span>
          <el-tag :type="authStore.isAdmin ? 'danger' : 'info'" size="small" class="role-tag">
            {{ authStore.isAdmin ? '管理员' : '成员' }}
          </el-tag>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="authStore.isAdmin" @click="goUserManagement">
              <el-icon><User /></el-icon>
              用户管理
            </el-dropdown-item>
            <el-dropdown-item divided @click="handleLogout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </template>
    <!-- 未登录 -->
    <template v-else>
      <el-button type="primary" size="small" plain @click="authStore.showLoginDialog = true">
        登录
      </el-button>
    </template>
    <LoginDialog v-model="authStore.showLoginDialog" />
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { User, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import LoginDialog from './LoginDialog.vue'

const authStore = useAuthStore()
const router = useRouter()

function goUserManagement() {
  router.push('/admin/users')
}

function handleLogout() {
  authStore.logout()
}
</script>

<style scoped>
.user-dropdown {
  display: flex;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--color-text-primary);
}

.username {
  font-size: 14px;
}

.role-tag {
  border: none;
}
</style>
