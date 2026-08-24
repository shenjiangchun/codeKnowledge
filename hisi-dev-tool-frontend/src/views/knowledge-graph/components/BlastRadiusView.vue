<script setup lang="ts">
import { ref } from 'vue'
import { knowledgeGraphApi, type BlastRadiusData } from '@/api/knowledgeGraph'
import { ElAlert, ElInput, ElButton, ElSkeleton, ElEmpty, ElTag, ElDescriptions, ElDescriptionsItem, ElMessage } from 'element-plus'

const props = defineProps<{ projectPaths: string[] }>()
const nodeId = ref('')
const loading = ref(false)
const data = ref<BlastRadiusData | null>(null)

const riskColor: Record<string, string> = { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger' }

async function query() {
  if (!nodeId.value.trim()) {
    ElMessage.warning('请输入方法 nodeId')
    return
  }
  loading.value = true
  data.value = null
  try {
    data.value = await knowledgeGraphApi.getBlastRadius(nodeId.value.trim(), props.projectPaths)
  } catch (e: any) {
    ElMessage.error(`爆炸半径查询失败: ${e.message || e}`)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" class="desc-bar">
      <template #title>
        <b>爆炸半径</b>：给定一个方法，展示其下游影响面（它调了谁）、上游入口点（谁调它）、以及整体风险。用于评估「改这个方法会影响多少地方」。
      </template>
    </el-alert>

    <div class="query-row">
      <el-input v-model="nodeId" placeholder="输入方法 nodeId（如 projectPath:className:methodName）" clearable style="flex:1" @keyup.enter="query" />
      <el-button type="primary" :loading="loading" @click="query">查询爆炸半径</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-empty v-else-if="!data" description="输入方法 nodeId 查询其影响范围" :image-size="80" />

    <div v-else class="result">
      <!-- 中心节点 -->
      <el-descriptions :column="1" border>
        <el-descriptions-item label="目标方法">
          {{ data.centerNode.className }}.{{ data.centerNode.methodName }}
        </el-descriptions-item>
        <el-descriptions-item label="nodeId">{{ data.centerNode.nodeId }}</el-descriptions-item>
      </el-descriptions>

      <!-- 影响指标 -->
      <div class="metrics">
        <el-tag type="info" effect="plain">下游影响 {{ data.downstream.totalAffectedMethods }} 个方法</el-tag>
        <el-tag type="info" effect="plain">上游调用者 {{ data.upstream.totalCallers }} 个</el-tag>
        <el-tag type="info" effect="plain">受影响入口点 {{ data.affectedEntryPoints }} 个</el-tag>
        <el-tag :type="riskColor[data.riskSummary.overallRisk] as any" effect="dark">风险：{{ data.riskSummary.overallRisk }}</el-tag>
      </div>

      <!-- 风险原因 -->
      <el-descriptions title="风险摘要" :column="1" border>
        <el-descriptions-item v-for="(r, i) in data.riskSummary.reasons" :key="i" :label="`原因 ${i + 1}`">
          {{ r }}
        </el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<style scoped>
.desc-bar { margin-bottom: 12px; }
.query-row { display: flex; gap: 12px; margin-bottom: 16px; }
.result { display: flex; flex-direction: column; gap: 12px; }
.metrics { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
