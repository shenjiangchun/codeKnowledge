<script setup lang="ts">
//
</script>

<template>
  <div class="about-view">
    <el-card>
      <template #header>
        <span>关于 HiSi Dev Tool</span>
      </template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="项目名称">HiSi Dev Tool</el-descriptions-item>
        <el-descriptions-item label="版本">1.0.0</el-descriptions-item>
        <el-descriptions-item label="技术栈">
          Vue 3 + TypeScript + Vite + Element Plus
        </el-descriptions-item>
        <el-descriptions-item label="描述">
          HiSi 设备开发工具，提供设备管理、配置和数据分析功能
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<style scoped>
.about-view {
  max-width: 800px;
  margin: 0 auto;
}
</style>