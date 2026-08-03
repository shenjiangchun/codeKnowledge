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
  background-color: var(--color-surface);
  color: var(--color-text-primary);
  border-bottom: 1px solid var(--color-border);
  padding: 0 20px;
  height: 48px;
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
  font-size: 15px;
  font-weight: 700;
  margin: 0;
  color: var(--color-text-primary);
  letter-spacing: 0.3px;
}

.header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
}
</style>
