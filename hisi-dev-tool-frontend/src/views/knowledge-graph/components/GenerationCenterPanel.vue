<script setup lang="ts">
import { ref } from 'vue'
import { knowledgeGraphApi, type TestSuggestion, type RefactorSuggestion, type MethodNode } from '@/api/knowledgeGraph'
import { ElAlert, ElSelect, ElOption, ElInput, ElButton, ElSkeleton, ElEmpty, ElTag, ElCard, ElMessage, ElTabs, ElTabPane } from 'element-plus'

const props = defineProps<{ projectPaths: string[] }>()

const activeTab = ref('test')
// 测试建议
const testNodeId = ref('')
const testLoading = ref(false)
const testCases = ref<TestSuggestion[]>([])
// 方法模糊搜索下拉
const searchLoading = ref(false)
const searchOptions = ref<MethodNode[]>([])
// 重构建议
const refactorModule = ref('')
const refactorLoading = ref(false)
const suggestions = ref<RefactorSuggestion[]>([])

const priorityTag: Record<string, string> = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
const typeLabel: Record<string, string> = { UNIT: '单元测试', INTEGRATION: '集成测试', EXCEPTION: '异常测试', BOUNDARY: '边界测试' }

async function searchMethods(query: string) {
  if (!query.trim()) {
    searchOptions.value = []
    return
  }
  searchLoading.value = true
  try {
    searchOptions.value = await knowledgeGraphApi.searchMethods(query.trim(), props.projectPaths)
  } catch (e) {
    searchOptions.value = []
  } finally {
    searchLoading.value = false
  }
}

function methodLabel(m: MethodNode) {
  return `${m.className}.${m.methodName}${m.signature ? '(' + m.signature + ')' : ''}`
}

async function genTest() {
  if (!testNodeId.value.trim()) {
    ElMessage.warning('请选择方法')
    return
  }
  testLoading.value = true
  testCases.value = []
  try {
    const res = await knowledgeGraphApi.generateTestSuggestions(testNodeId.value.trim(), props.projectPaths)
    testCases.value = res.testCases ?? []
  } catch (e: any) {
    ElMessage.error(`生成测试建议失败: ${e.message || e}`)
  } finally {
    testLoading.value = false
  }
}

async function genRefactor() {
  if (!refactorModule.value.trim()) {
    ElMessage.warning('请输入模块名')
    return
  }
  refactorLoading.value = true
  suggestions.value = []
  try {
    const res = await knowledgeGraphApi.generateRefactorSuggestions(refactorModule.value.trim(), props.projectPaths)
    suggestions.value = res.suggestions ?? []
  } catch (e: any) {
    ElMessage.error(`生成重构建议失败: ${e.message || e}`)
  } finally {
    refactorLoading.value = false
  }
}
</script>

<template>
  <div>
    <el-alert type="info" :closable="false" class="desc-bar">
      <template #title>
        <b>生成中心</b>：基于图谱数据（爆炸半径 / 热点 / DSM）用 LLM 生成<strong>测试建议</strong>与<strong>重构建议</strong>，辅助架构师决策。
      </template>
    </el-alert>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="测试建议" name="test">
        <div class="query-row">
          <el-select
            v-model="testNodeId"
            filterable
            remote
            clearable
            :remote-method="searchMethods"
            :loading="searchLoading"
            placeholder="输入类名或方法名模糊搜索"
            style="flex:1"
          >
            <el-option
              v-for="m in searchOptions"
              :key="m.nodeId"
              :label="methodLabel(m)"
              :value="m.nodeId"
            >
              <div class="option-row">
                <span class="option-label">{{ m.className }}.{{ m.methodName }}</span>
                <span v-if="m.description" class="option-desc">{{ m.description }}</span>
              </div>
            </el-option>
          </el-select>
          <el-button type="primary" :loading="testLoading" @click="genTest">生成测试建议</el-button>
        </div>
        <el-skeleton v-if="testLoading" :rows="5" animated />
        <el-empty v-else-if="!testCases.length" description="搜索并选择方法生成测试建议" :image-size="80" />
        <el-card v-for="(tc, i) in testCases" :key="i" class="suggestion-card" shadow="hover">
          <div class="suggestion-head">
            <el-tag :type="priorityTag[tc.priority] as any" size="small">{{ tc.priority }}</el-tag>
            <el-tag type="info" size="small" effect="plain">{{ typeLabel[tc.type] ?? tc.type }}</el-tag>
          </div>
          <div class="suggestion-body">{{ tc.scenario }}</div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="重构建议" name="refactor">
        <div class="query-row">
          <el-input v-model="refactorModule" placeholder="输入模块名（如 com.foo.service）" clearable style="flex:1" @keyup.enter="genRefactor" />
          <el-button type="primary" :loading="refactorLoading" @click="genRefactor">生成重构建议</el-button>
        </div>
        <el-skeleton v-if="refactorLoading" :rows="5" animated />
        <el-empty v-else-if="!suggestions.length" description="输入模块名生成重构建议" :image-size="80" />
        <el-card v-for="(s, i) in suggestions" :key="i" class="suggestion-card" shadow="hover">
          <div class="suggestion-head">
            <el-tag :type="priorityTag[s.priority] as any" size="small">{{ s.priority }}</el-tag>
            <el-tag type="info" size="small" effect="plain">{{ s.direction }}</el-tag>
          </div>
          <div class="suggestion-body">{{ s.issue }}</div>
          <div class="suggestion-impact">影响：{{ s.impact }}</div>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.desc-bar { margin-bottom: 12px; }
.query-row { display: flex; gap: 12px; margin-bottom: 16px; }
.suggestion-card { margin-bottom: 10px; }
.suggestion-head { display: flex; gap: 8px; margin-bottom: 8px; }
.suggestion-body { font-size: 14px; color: #303133; line-height: 1.6; }
.suggestion-impact { margin-top: 6px; font-size: 12px; color: #909399; }
.option-row { display: flex; flex-direction: column; line-height: 1.3; }
.option-label { font-size: 14px; color: #303133; }
.option-desc { font-size: 12px; color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
