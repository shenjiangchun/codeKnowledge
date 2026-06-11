<script setup lang="ts">
import UserDropdown from '@/components/auth/UserDropdown.vue'
</script>

<template>
  <el-header class="app-header">
    <div class="header-content">
      <h1 class="app-title">HiSi DevTool</h1>
      <div class="header-right">
        <UserDropdown />
      </div>
    </div>
  </el-header>
</template>

<style scoped>
.app-header {
  background-color: #409eff;
  color: #fff;
  padding: 0 20px;
  height: 60px;
  display: flex;
  align-items: center;
}

.header-content {
  display: flex;
  align-items: center;
  height: 100%;
  width: 100%;
}

.app-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  color: #fff;
  letter-spacing: 0.5px;
}

.header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
}
</style>
