<script setup lang="ts">
import { onMounted } from 'vue'
import AppHeader from './AppHeader.vue'
import AppSidebar from './AppSidebar.vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
onMounted(() => authStore.init())
</script>

<template>
  <el-container class="app-layout">
    <AppSidebar />
    <el-container class="main-container">
      <AppHeader />
      <el-main class="app-main">
        <router-view v-slot="{ Component, route }">
          <keep-alive>
            <component :is="Component" :key="route.fullPath" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-layout {
  height: 100vh;
  width: 100%;
}

.main-container {
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}

.app-main {
  background-color: #f5f7fa;
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}
</style>