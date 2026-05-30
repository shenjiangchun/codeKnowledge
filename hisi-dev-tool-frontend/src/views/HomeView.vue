<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '@/api'

const loading = ref(false)
const status = ref<string>('正在连接后端服务...')

onMounted(async () => {
  loading.value = true
  try {
    await api.healthCheck()
    status.value = '后端服务连接正常'
  } catch {
    status.value = '后端服务连接失败，请检查后端是否启动'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="home-view">
    <el-card class="welcome-card">
      <template #header>
        <div class="card-header">
          <span>欢迎使用 HiSi Dev Tool</span>
        </div>
      </template>
      <el-skeleton :loading="loading" animated>
        <template #default>
          <div class="status-container">
            <el-tag :type="status.includes('正常') ? 'success' : 'danger'" size="large">
              {{ status }}
            </el-tag>
          </div>
        </template>
      </el-skeleton>
      <el-divider />
      <div class="features">
        <h3>功能特性</h3>
        <el-row :gutter="20">
          <el-col :span="8">
            <el-card shadow="hover">
              <el-icon size="40"><Monitor /></el-icon>
              <h4>设备管理</h4>
              <p>管理和监控 HiSi 设备</p>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover">
              <el-icon size="40"><Setting /></el-icon>
              <h4>配置工具</h4>
              <p>配置设备参数和选项</p>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover">
              <el-icon size="40"><DataAnalysis /></el-icon>
              <h4>数据分析</h4>
              <p>可视化数据分析图表</p>
            </el-card>
          </el-col>
        </el-row>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.home-view {
  max-width: 1200px;
  margin: 0 auto;
}

.welcome-card {
  margin-bottom: 20px;
}

.card-header {
  font-size: 18px;
  font-weight: 500;
}

.status-container {
  text-align: center;
  padding: 20px 0;
}

.features h3 {
  margin-bottom: 20px;
  color: #303133;
}

.features .el-card {
  text-align: center;
  padding: 20px 0;
}

.features .el-icon {
  color: #409eff;
  margin-bottom: 10px;
}

.features h4 {
  margin: 10px 0;
  color: #303133;
}

.features p {
  color: #909399;
  font-size: 14px;
}
</style>