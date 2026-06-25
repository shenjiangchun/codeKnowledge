<template>
  <div class="knowledge-graph-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>知识图谱分析</span>
          <div class="header-actions">
            <!-- Task 74: 分组/扁平视图切换 -->
            <el-button
              v-if="projectNameGroups.length > 0"
              :type="showGroupedView ? 'primary' : 'default'"
              size="small"
              @click="showGroupedView = !showGroupedView"
            >
              {{ showGroupedView ? '分组视图' : '扁平视图' }}
            </el-button>
            <!-- Task 74: 管理分组按钮 -->
            <el-button size="small" @click="showProjectNameGroupDialog = true">
              管理分组
            </el-button>
            <!-- 分组视图 -->
            <div v-if="showGroupedView && projectNameGroups.length > 0" class="grouped-project-selector">
              <div v-for="item in groupedProjectTree" :key="item.name" class="group-item">
                <template v-if="item.type === 'group'">
                  <div class="group-header" @click="toggleGroupExpand(item.name)">
                    <el-icon :class="{ 'is-expanded': expandedGroups.has(item.name) }">
                      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="14" height="14">
                        <path fill="currentColor" d="M384 192v640l384-320.064z"/>
                      </svg>
                    </el-icon>
                    <span class="group-name">{{ item.name }}</span>
                    <el-tag size="small" type="info">{{ item.children?.length || 0 }}</el-tag>
                    <el-button text size="small" @click.stop="selectGroupProjects({ groupName: item.name, projectNames: item.children?.map(c => c.name) || [] })">
                      全选
                    </el-button>
                  </div>
                  <div v-show="expandedGroups.has(item.name)" class="group-children">
                    <el-checkbox
                      v-for="child in item.children"
                      :key="child.name"
                      :model-value="selectedProjectNames.includes(child.name)"
                      :label="child.name"
                      @change="(val: boolean) => toggleProjectSelection(child.name, val)"
                    />
                  </div>
                </template>
                <template v-else>
                  <el-checkbox
                    :model-value="selectedProjectNames.includes(item.name)"
                    :label="item.name"
                    @change="(val: boolean) => toggleProjectSelection(item.name, val)"
                  />
                </template>
              </div>
            </div>
            <!-- 扁平视图 - 原有的 el-select -->
            <el-select
              v-else
              v-model="selectedProjectNames"
              placeholder="选择项目"
              filterable
              multiple
              collapse-tags
              collapse-tags-tooltip
              @change="handleProjectChange"
              style="width: 350px;"
            >
              <el-option
                v-for="proj in storeSelectedProjects"
                :key="proj.name"
                :label="proj.name"
                :value="proj.name"
              />
            </el-select>
            <el-tag v-if="graphStatus" :type="getStatusTagType(graphStatus.status)">
              {{ getStatusText(graphStatus.status) }}
            </el-tag>
            <!-- 向量状态显示 -->
            <div v-if="vectorStatus" class="vector-status-display">
              <el-tag :type="getVectorStatusTagType(vectorStatus.status)" size="small">
                向量: {{ getVectorStatusText(vectorStatus.status) }}
              </el-tag>
              <span v-if="vectorStatus.status === 'RUNNING' || vectorStatus.status === 'COMPLETED'" class="vector-progress">
                {{ vectorStatus.processedMethods }}/{{ vectorStatus.totalMethods }}
              </span>
            </div>
            <!-- 向量生成按钮 -->
            <el-button
              type="primary"
              :loading="isGeneratingVector"
              @click="handleGenerateVector"
              :disabled="!projectPath || isGeneratingVector || isInCooldown"
            >
              生成向量
            </el-button>
            <!-- 术语配置按钮 -->
            <el-button
              @click="showGlossaryDialog = true"
              :disabled="!projectPath"
            >
              术语配置
            </el-button>
            <!-- KG 路径诊断按钮 -->
            <el-button
              type="info"
              @click="showPathDiagnosisDialog = true"
            >
              路径诊断
            </el-button>
            <!-- 补齐缺失向量按钮 -->
            <el-button
              v-if="missingInfo && missingInfo.missingCount > 0"
              type="warning"
              :loading="isRefreshingMissing"
              @click="handleRefreshMissing"
              :disabled="!projectPath || isRefreshingMissing || isInCooldown"
            >
              补齐缺失 ({{ missingInfo.missingCount }})
            </el-button>
            <!-- 查看缺失详情 -->
            <el-button
              v-if="missingInfo && missingInfo.missingCount > 0"
              text
              type="info"
              @click="showMissingDrawer = true"
            >
              查看缺失
            </el-button>
            <!-- 全量生成按钮 -->
            <el-button
              type="primary"
              :loading="isGenerating"
              @click="handleFullGenerate"
              :disabled="!projectPath || isGenerating || isInCooldown"
            >
              全量生成
            </el-button>
            <!-- 增量生成按钮（仅在有历史记录时显示） -->
            <el-button
              v-if="hasGeneratedRecord"
              type="success"
              :loading="isGenerating"
              @click="handleIncrementalGenerate"
              :disabled="!projectPath || isGenerating || isInCooldown"
            >
              增量生成{{ lastGeneratedCommit ? ` (基于 ${lastGeneratedCommit})` : '' }}
            </el-button>
          </div>
        </div>
      </template>

      <!-- 统计概览 -->
      <div class="stats-overview" v-if="graphStatus">
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.methodNodeCount }}</span>
          <span class="stat-label">方法节点</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.callRelationCount }}</span>
          <span class="stat-label">调用关系</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.entryPointCount }}</span>
          <span class="stat-label">入口点</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.interfaceImplCount }}</span>
          <span class="stat-label">接口实现</span>
        </div>
        <div class="stat-item">
          <span class="stat-value">{{ graphStatus.callChainCount }}</span>
          <span class="stat-label">调用链</span>
        </div>
        <div v-if="missingInfo" class="stat-item stat-item-vector">
          <span class="stat-value" :class="missingInfo.missingCount > 0 ? 'stat-value-warning' : 'stat-value-success'">
            {{ missingInfo.generatedCount }}/{{ missingInfo.totalMethods }}
          </span>
          <span class="stat-label">
            已向量化
            <el-tag v-if="missingInfo.missingCount > 0" size="small" type="warning" effect="plain" style="margin-left: 4px">
              缺 {{ missingInfo.missingCount }}
            </el-tag>
          </span>
        </div>
      </div>

      <!-- Tab 切换 -->
      <el-tabs v-model="activeTab" class="main-tabs">
        <el-tab-pane label="代码理解" name="understand">
          <CodeUnderstandingTab
            v-if="projectPath"
            :project-path="projectPath"
            :project-paths="projectPaths"
          />
          <el-empty v-else>
            <template #description>
              <div style="text-align: center;">
                <p>请先选择项目</p>
                <p style="color: #909399; font-size: 12px; margin-top: 8px;">
                  在「项目管理」页面勾选项目后，点击「确认选择」按钮，再返回本页面
                </p>
              </div>
            </template>
            <el-button type="primary" @click="router.push('/project')">去项目管理</el-button>
          </el-empty>
        </el-tab-pane>
        <el-tab-pane label="语义搜索" name="semanticSearch">
          <SemanticSearchPanel
            v-if="projectPath"
            :project-path="projectPath"
            :project-paths="projectPaths"
            @view-detail="handleViewDetail"
            @view-call-chain="handleViewCallChain"
          />
          <el-empty v-else>
            <template #description>
              <div style="text-align: center;">
                <p>请先选择项目</p>
                <p style="color: #909399; font-size: 12px; margin-top: 8px;">
                  在「项目管理」页面勾选项目后，点击「确认选择」按钮
                </p>
              </div>
            </template>
          </el-empty>
        </el-tab-pane>
        <el-tab-pane label="引用分析" name="methodRef">
          <MethodReferenceGraph ref="methodRefGraphRef" :project-paths="projectPaths" />
        </el-tab-pane>
        <el-tab-pane label="跨服务调用" name="crossService">
          <CrossServiceBridgeTab
            v-if="projectPaths.length > 0"
            :project-path="projectPath"
            :project-paths="projectPaths"
          />
          <el-empty v-else>
            <template #description>
              <div style="text-align: center;">
                <p>请先选择项目</p>
                <p style="color: #909399; font-size: 12px; margin-top: 8px;">
                  在「项目管理」页面勾选项目后，点击「确认选择」按钮
                </p>
              </div>
            </template>
          </el-empty>
        </el-tab-pane>
        <el-tab-pane label="图谱探索" name="explorer">
          <GraphExplorerTab
            v-if="projectPath"
            :project-path="projectPath"
            :project-paths="projectPaths"
          />
          <el-empty v-else>
            <template #description>
              <div style="text-align: center;">
                <p>请先选择项目</p>
                <p style="color: #909399; font-size: 12px; margin-top: 8px;">
                  在「项目管理」页面勾选项目后，点击「确认选择」按钮
                </p>
              </div>
            </template>
          </el-empty>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 缺失向量方法详情抽屉 -->
    <el-drawer
      v-model="showMissingDrawer"
      title="缺失向量的方法"
      direction="rtl"
      size="480px"
    >
      <div v-if="missingInfo" class="missing-drawer-content">
        <div class="missing-summary">
          <el-statistic title="总方法" :value="missingInfo.totalMethods" />
          <el-statistic title="已生成" :value="missingInfo.generatedCount" />
          <el-statistic title="缺失" :value="missingInfo.missingCount" />
        </div>
        <el-divider />
        <p class="missing-hint">以下为缺失向量的方法预览（最多 50 条）。点击"补齐缺失"将自动跑增量向量生成。</p>
        <el-table :data="missingInfo.preview" size="small" stripe max-height="500">
          <el-table-column prop="className" label="类名" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <code style="font-size: 11px">{{ row.className?.split('.').pop() }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="methodName" label="方法名" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <code style="font-size: 11px">{{ row.methodName }}</code>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="missingInfo.missingCount > missingInfo.preview.length" class="missing-more">
          还有 {{ missingInfo.missingCount - missingInfo.preview.length }} 个方法未显示
        </div>
        <el-button
          type="warning"
          :loading="isRefreshingMissing"
          @click="handleRefreshMissing"
          style="margin-top: 16px; width: 100%"
        >
          补齐缺失向量 ({{ missingInfo.missingCount }})
        </el-button>
      </div>
    </el-drawer>

    <!-- 术语管理对话框 -->
    <el-dialog
      v-model="showGlossaryDialog"
      title="术语配置"
      width="700px"
      destroy-on-close
    >
      <p style="color: #909399; margin: 0 0 16px 0; font-size: 13px;">
        配置术语对照表后，LLM 生成语义描述时将自动遵守术语规范（重新生成向量后生效）
      </p>

      <div style="display: flex; justify-content: flex-end; margin-bottom: 12px;">
        <el-button type="primary" size="small" @click="glossaryShowForm = true">
          <el-icon><Plus /></el-icon>
          新增术语
        </el-button>
      </div>

      <el-table :data="glossaryTerms" v-loading="glossaryLoading" empty-text="暂无术语" stripe size="small">
        <el-table-column prop="term" label="术语" width="140">
          <template #default="{ row }">
            <el-tag type="success" effect="plain" size="small">{{ row.term }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="" width="40" align="center">
          <template #default>→</template>
        </el-table-column>
        <el-table-column prop="synonym" label="同义词" width="140">
          <template #default="{ row }">
            <el-tag type="info" effect="plain" size="small">{{ row.synonym }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="context" label="说明" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="glossaryEditRow(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="glossaryDeleteRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- KG 路径诊断对话框 -->
    <el-dialog
      v-model="showPathDiagnosisDialog"
      title="KG 路径诊断"
      width="800px"
      destroy-on-close
    >
      <p style="color: #909399; margin: 0 0 16px 0; font-size: 13px;">
        当 PROJECT_DIR 配置变更后，KG 数据可能使用旧路径存储，导致查询失败。使用此工具诊断和修复路径不一致问题。
      </p>

      <el-card v-loading="pathDiagnosisLoading" shadow="never">
        <template #header>
          <div style="display: flex; align-items: center; gap: 12px;">
            <span>诊断结果</span>
            <el-button size="small" @click="loadPathDiagnosis">刷新</el-button>
          </div>
        </template>

        <el-descriptions v-if="pathDiagnosisResult" :column="2" border>
          <el-descriptions-item label="当前 PROJECT_DIR">
            <el-tag type="success">{{ pathDiagnosisResult.currentProjectDir }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="KG 项目路径数">
            {{ pathDiagnosisResult.totalKgPaths }}
          </el-descriptions-item>
          <el-descriptions-item label="不一致路径数">
            <el-tag :type="pathDiagnosisResult.inconsistentCount > 0 ? 'danger' : 'success'">
              {{ pathDiagnosisResult.inconsistentCount }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="pathDiagnosisResult?.inconsistentPaths?.length > 0" style="margin-top: 16px;">
          <p style="color: #e6a23c; font-weight: 600;">以下路径与当前配置不一致：</p>
          <el-table :data="pathDiagnosisResult.inconsistentPaths" stripe size="small" max-height="300">
            <el-table-column prop="path" label="KG 存储路径" show-overflow-tooltip />
            <el-table-column prop="expectedPath" label="期望路径" show-overflow-tooltip />
            <el-table-column prop="projectName" label="项目名" width="150" />
          </el-table>
        </div>

        <el-empty v-else-if="pathDiagnosisResult && !pathDiagnosisLoading" description="所有路径与当前配置一致，无需迁移" />
      </el-card>

      <!-- 迁移操作 -->
      <div v-if="pathDiagnosisResult?.inconsistentCount > 0" style="margin-top: 20px;">
        <el-divider>路径迁移</el-divider>
        <el-form label-width="140px">
          <el-form-item label="旧基础目录">
            <el-input
              v-model="migrationOldBaseDir"
              placeholder="输入旧的基础目录，如 D:/codeknowledge"
              style="width: 300px;"
            />
          </el-form-item>
          <el-form-item label="预览模式">
            <el-switch v-model="migrationDryRun" />
            <span style="color: #909399; font-size: 12px; margin-left: 8px;">
              开启预览模式只显示将要更新的节点，不执行实际迁移
            </span>
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              :loading="pathMigrationLoading"
              @click="handleMigratePaths"
            >
              {{ migrationDryRun ? '预览迁移' : '执行迁移' }}
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-dialog>

    <!-- 术语新增/编辑子对话框 -->
    <el-dialog
      v-model="glossaryShowForm"
      :title="glossaryEditingId ? '编辑术语' : '新增术语'"
      width="420px"
      append-to-body
      destroy-on-close
    >
      <el-form :model="glossaryForm" label-width="80px">
        <el-form-item label="术语" required>
          <el-input v-model="glossaryForm.term" placeholder="标准术语，如：知识图谱" />
        </el-form-item>
        <el-form-item label="同义词" required>
          <el-input v-model="glossaryForm.synonym" placeholder="LLM 可能使用的同义词，如：KG" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="glossaryForm.context" placeholder="可选，如适用场景" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="glossaryShowForm = false">取消</el-button>
        <el-button type="primary" @click="glossarySubmit" :loading="glossarySubmitting">
          {{ glossaryEditingId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Task 74: 项目名称分组管理对话框 -->
    <el-dialog
      v-model="showProjectNameGroupDialog"
      :title="editingGroupName ? '编辑分组' : '项目名称分组管理'"
      width="600px"
      destroy-on-close
    >
      <div v-if="!editingGroupName">
        <el-button type="primary" size="small" @click="resetGroupNameForm(); editingGroupName = 'new'">
          <el-icon><Plus /></el-icon>
          新增分组
        </el-button>
        <el-table :data="projectNameGroups" v-loading="loadingProjectNameGroups" size="small" stripe style="margin-top: 12px">
          <el-table-column prop="groupName" label="分组名称" width="140" />
          <el-table-column prop="groupPattern" label="匹配模式" width="120">
            <template #default="{ row }">
              <code style="font-size: 11px">{{ row.groupPattern }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="projectNames" label="项目数" width="80">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.projectNames.length }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="120" show-overflow-tooltip />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEditGroupName(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="deleteGroupNameGroup(row.groupName)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="projectNameGroups.length === 0 && !loadingProjectNameGroups" style="text-align: center; color: #909399; padding: 24px">
          暂无分组，点击上方按钮新增
        </div>
      </div>

      <el-form v-else :model="groupNameForm" label-width="100px">
        <el-form-item label="分组名称" required>
          <el-input v-model="groupNameForm.groupName" placeholder="如: HiSi DevTool 系列" />
        </el-form-item>
        <el-form-item label="匹配模式" required>
          <el-input v-model="groupNameForm.groupPattern" placeholder="如: hisi-*（支持 * 通配符）" />
        </el-form-item>
        <el-form-item label="项目列表">
          <el-select
            v-model="groupNameForm.projectNames"
            multiple
            filterable
            placeholder="选择要加入分组的项目"
            style="width: 100%"
          >
            <el-option
              v-for="proj in projects"
              :key="proj.name"
              :label="proj.name"
              :value="proj.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="groupNameForm.description" placeholder="可选" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="resetGroupNameForm(); editingGroupName = ''">取消</el-button>
        <el-button v-if="editingGroupName" type="primary" @click="saveGroupNameGroup">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { projectApi } from '@/api/project'
import { knowledgeGraphApi, type KnowledgeGraphStatus, type GitStatus } from '@/api/knowledgeGraph'
import { getVectorGenerationStatus, startVectorGeneration, getMissingEmbeddings, refreshMissing, type VectorGenerationTask, type MissingEmbeddingInfo } from '@/api/vectorGeneration'
import { glossaryApi } from '@/api/glossary'
import { listRemoteProjects } from '@/api/remote-project'
import { projectNameGroupApi, type ProjectNameGroup } from '@/api/projectNameGroup'
import type { GlossaryTerm } from '@/types/glossary'
import type { RemoteProject } from '@/types/remote-project'
import { useAppStore } from '@/stores/app'
import CodeUnderstandingTab from './components/CodeUnderstandingTab.vue'
import SemanticSearchPanel from './components/SemanticSearchPanel.vue'
import MethodReferenceGraph from '@/views/call-chain/MethodReferenceGraph.vue'
import CrossServiceBridgeTab from './components/CrossServiceBridgeTab.vue'
import GraphExplorerTab from './components/GraphExplorerTab.vue'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()

interface ProjectInfo {
  name: string
  path: string
}

const projects = ref<ProjectInfo[]>([])
const selectedProjectNames = ref<string[]>(
  appStore.selectedProjectNames.length > 0
    ? [...appStore.selectedProjectNames]
    : (route.query.project ? [route.query.project as string] : (appStore.selectedProject ? [appStore.selectedProject] : []))
)
const activeTab = ref(route.query.tab as string || 'understand')

// Task 74: 项目名称分组
const projectNameGroups = ref<ProjectNameGroup[]>([])
const loadingProjectNameGroups = ref<boolean>(false)
const showGroupedView = ref<boolean>(false)
const expandedGroups = ref<Set<string>>(new Set())

// Task 74: 分组管理对话框
const showProjectNameGroupDialog = ref<boolean>(false)
const editingGroupName = ref<string>('')
const groupNameForm = reactive({
  groupName: '',
  groupPattern: '',
  projectNames: [] as string[],
  description: ''
})

const resetGroupNameForm = () => {
  editingGroupName.value = ''
  Object.assign(groupNameForm, {
    groupName: '',
    groupPattern: '',
    projectNames: [],
    description: ''
  })
}

const openEditGroupName = (group: ProjectNameGroup) => {
  editingGroupName.value = group.groupName
  Object.assign(groupNameForm, {
    groupName: group.groupName,
    groupPattern: group.groupPattern,
    projectNames: [...group.projectNames],
    description: group.description || ''
  })
  showProjectNameGroupDialog.value = true
}

const saveGroupNameGroup = async () => {
  if (!groupNameForm.groupName.trim()) {
    ElMessage.warning('分组名称不能为空')
    return
  }
  if (!groupNameForm.groupPattern.trim()) {
    ElMessage.warning('分组模式不能为空')
    return
  }
  try {
    await projectNameGroupApi.saveGroup({
      groupName: groupNameForm.groupName.trim(),
      groupPattern: groupNameForm.groupPattern.trim(),
      projectNames: groupNameForm.projectNames,
      description: groupNameForm.description.trim()
    })
    ElMessage.success('分组已保存')
    showProjectNameGroupDialog.value = false
    resetGroupNameForm()
    await loadProjectNameGroups()
  } catch (e: any) {
    ElMessage.error('保存失败: ' + e.message)
  }
}

const deleteGroupNameGroup = async (groupName: string) => {
  try {
    await ElMessageBox.confirm(`确认删除分组 "${groupName}"？`, '删除确认', { type: 'warning' })
    await projectNameGroupApi.deleteGroup(groupName)
    ElMessage.success('分组已删除')
    await loadProjectNameGroups()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + e.message)
    }
  }
}

// 计算分组后的项目树
const groupedProjectTree = computed(() => {
  if (!showGroupedView.value || projectNameGroups.value.length === 0) {
    // 无分组时返回扁平列表
    return projects.value.map(p => ({
      type: 'project',
      name: p.name,
      path: p.path
    }))
  }

  const tree: Array<{ type: 'group' | 'project', name: string, path?: string, children?: any[] }> = []
  const groupedProjects = new Set<string>()

  // 先处理已分组的
  for (const group of projectNameGroups.value) {
    const groupChildren: Array<{ type: 'project', name: string, path: string }> = []
    for (const projName of group.projectNames) {
      const proj = projects.value.find(p => p.name === projName)
      if (proj) {
        groupedProjects.add(projName)
        groupChildren.push({ type: 'project', name: proj.name, path: proj.path })
      }
    }
    if (groupChildren.length > 0) {
      tree.push({
        type: 'group',
        name: group.groupName,
        children: groupChildren
      })
    }
  }

  // 未分组的项目放入"其他"组
  const ungrouped = projects.value.filter(p => !groupedProjects.has(p.name))
  if (ungrouped.length > 0) {
    tree.push({
      type: 'group',
      name: '其他',
      children: ungrouped.map(p => ({ type: 'project', name: p.name, path: p.path }))
    })
  }

  return tree
})

// 加载项目名称分组
const loadProjectNameGroups = async () => {
  loadingProjectNameGroups.value = true
  try {
    projectNameGroups.value = await projectNameGroupApi.getGroups()
  } catch {
    projectNameGroups.value = []
  } finally {
    loadingProjectNameGroups.value = false
  }
}

// 切换分组展开状态
const toggleGroupExpand = (groupName: string) => {
  if (expandedGroups.value.has(groupName)) {
    expandedGroups.value.delete(groupName)
  } else {
    expandedGroups.value.add(groupName)
  }
}

// 选择分组下的所有项目
const selectGroupProjects = (group: ProjectNameGroup) => {
  const existing = new Set(selectedProjectNames.value)
  for (const name of group.projectNames) {
    if (projects.value.find(p => p.name === name)) {
      existing.add(name)
    }
  }
  selectedProjectNames.value = Array.from(existing)
  handleProjectChange()
}

// 切换单个项目选择状态
const toggleProjectSelection = (name: string, selected: boolean) => {
  if (selected) {
    if (!selectedProjectNames.value.includes(name)) {
      selectedProjectNames.value.push(name)
    }
  } else {
    selectedProjectNames.value = selectedProjectNames.value.filter(n => n !== name)
  }
  handleProjectChange()
}

const graphStatus = ref<KnowledgeGraphStatus | null>(null)
const vectorStatus = ref<VectorGenerationTask | null>(null)
const gitStatus = ref<GitStatus | null>(null)
const isGenerating = ref(false)
const isGeneratingVector = ref(false)
const missingInfo = ref<MissingEmbeddingInfo | null>(null)
const isRefreshingMissing = ref(false)
const showMissingDrawer = ref(false)
let pollingTimer: number | null = null
let vectorPollingTimer: number | null = null

// 前端防呆：记录每个项目上次点击生成的时间（内存中，刷新可重置）
const lastGenerateTimes = reactive<Record<string, number>>({})
const ONE_MINUTE_MS = 60 * 1000

// 标准化路径格式（将反斜杠转换为正斜杠，与后端保持一致）
const normalizePath = (path: string): string => {
  if (!path) return ''
  return path.trim().replace(/\\/g, '/').replace(/\/+$/, '')
}

// 检查当前选中项目是否在冷却时间内
const isInCooldown = computed(() => {
  const currentPath = projectPath.value
  if (!currentPath) return false
  const normalizedPath = normalizePath(currentPath)
  const lastTime = lastGenerateTimes[normalizedPath]
  if (!lastTime) return false
  return Date.now() - lastTime < ONE_MINUTE_MS
})

// 记录当前选中项目的点击时间
const recordGenerateTime = () => {
  const currentPath = projectPath.value
  if (!currentPath) return
  const normalizedPath = normalizePath(currentPath)
  lastGenerateTimes[normalizedPath] = Date.now()
}

// 从 store 的已选项目列表中过滤出可用项目（确保 projects 加载后才过滤）
const storeSelectedProjects = computed(() => {
  const storeNames = appStore.selectedProjectNames
  if (storeNames.length === 0 || projects.value.length === 0) return projects.value
  const filtered = projects.value.filter(p => storeNames.includes(p.name))
  return filtered.length > 0 ? filtered : projects.value
})

// 检查是否有历史生成记录
const hasGeneratedRecord = computed(() => {
  return graphStatus.value &&
    (graphStatus.value.status === 'generated' ||
     graphStatus.value.status === 'completed' ||
     graphStatus.value.methodNodeCount > 0)
})

// 获取上次生成的 commit hash（用于显示在按钮上）
const lastGeneratedCommit = computed(() => {
  return gitStatus.value?.commitHash?.substring(0, 7) || ''
})

// 多项目路径列表
const projectPaths = computed(() => {
  const result: string[] = []
  for (const name of selectedProjectNames.value) {
    const proj = projects.value.find(p => p.name === name)
    if (proj) {
      result.push(proj.path.replace(/\\/g, '/'))
    } else {
      // Fallback: look up from appStore (handles remote projects not yet in projects list)
      const storeProj = appStore.selectedProjects.find(p => p.name === name)
      if (storeProj?.path) {
        result.push(storeProj.path.replace(/\\/g, '/'))
      }
    }
  }
  return result
})

// 向后兼容：第一个选中项目的路径
const projectPath = computed(() => projectPaths.value[0] || '')

// 获取状态标签类型
const getStatusTagType = (status: string): '' | 'success' | 'warning' | 'danger' | 'info' => {
  const typeMap: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    completed: 'success',
    generated: 'success',
    running: 'warning',
    pending: 'warning',
    failed: 'danger',
    not_generated: 'info'
  }
  return typeMap[status] || 'info'
}

// 获取状态文本
const getStatusText = (status: string): string => {
  const textMap: Record<string, string> = {
    completed: '已生成',
    generated: '已生成',
    running: '生成中',
    pending: '等待中',
    failed: '生成失败',
    not_generated: '未生成'
  }
  return textMap[status] || status
}

// 向量状态相关
const getVectorStatusTagType = (status: string): '' | 'success' | 'warning' | 'danger' | 'info' => {
  const typeMap: Record<string, '' | 'success' | 'warning' | 'danger' | 'info'> = {
    COMPLETED: 'success',
    RUNNING: 'warning',
    PENDING: 'warning',
    FAILED: 'danger'
  }
  return typeMap[status] || 'info'
}

const getVectorStatusText = (status: string): string => {
  const textMap: Record<string, string> = {
    COMPLETED: '已完成',
    RUNNING: '生成中',
    PENDING: '等待中',
    FAILED: '失败'
  }
  return textMap[status] || '未生成'
}

// 加载向量生成状态
const loadVectorStatus = async () => {
  if (!projectPath.value) {
    vectorStatus.value = null
    return
  }
  try {
    console.log('[KnowledgeGraph] Loading vector status for path:', projectPath.value)
    const status = await getVectorGenerationStatus(projectPath.value)
    console.log('[KnowledgeGraph] Vector status loaded:', status)
    vectorStatus.value = status
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load vector status:', error)
    vectorStatus.value = null
  }
}

// 开始向量状态轮询
const startVectorPolling = () => {
  console.log('[KnowledgeGraph] Starting vector polling')
  if (vectorPollingTimer) {
    clearInterval(vectorPollingTimer)
  }
  vectorPollingTimer = window.setInterval(async () => {
    console.log('[KnowledgeGraph] Vector poll - current status:', vectorStatus.value?.status)
    if (vectorStatus.value?.status === 'PENDING' || vectorStatus.value?.status === 'RUNNING') {
      await loadVectorStatus()
      // 检查任务是否完成
      if (vectorStatus.value?.status === 'COMPLETED' || vectorStatus.value?.status === 'FAILED') {
        console.log('[KnowledgeGraph] Vector task completed, stopping polling')
        stopVectorPolling()
        loadMissingInfo()
      }
    }
  }, 5000)
}

// 停止向量状态轮询
const stopVectorPolling = () => {
  if (vectorPollingTimer) {
    clearInterval(vectorPollingTimer)
    vectorPollingTimer = null
  }
}

// 加载项目列表
const loadProjects = async () => {
  try {
    // 并行加载本地项目和远端项目
    const [localRes, remoteRes] = await Promise.all([
      projectApi.scanGitRepos(),
      listRemoteProjects().catch(() => []) // 远端项目加载失败不影响
    ])

    const scannedRepos = Array.isArray(localRes) ? localRes : []
    const remoteProjects = Array.isArray(remoteRes) ? remoteRes : []

    // 直接使用后端返回的项目信息（包含正确的 path）
    const localProjects: ProjectInfo[] = scannedRepos.map((repo: any) => ({
      name: repo.name,
      path: repo.path
    }))

    // 将已克隆的远端项目也加入项目列表
    const remoteProjectsCloned: ProjectInfo[] = remoteProjects
      .filter((rp: RemoteProject) => rp.cloneStatus === 'CLONED' && rp.localPath)
      .map((rp: RemoteProject) => ({
        name: rp.name,
        path: rp.localPath
      }))

    // 合并去重（以 name 为 key）
    const nameMap = new Map<string, ProjectInfo>()
    localProjects.forEach(p => nameMap.set(p.name, p))
    remoteProjectsCloned.forEach(p => {
      if (!nameMap.has(p.name)) {
        nameMap.set(p.name, p)
      }
    })

    projects.value = Array.from(nameMap.values())

    // 如果当前没有选中，自动选择 store 中已选项目
    if (selectedProjectNames.value.length === 0 && appStore.selectedProjectNames.length > 0) {
      selectedProjectNames.value = [...appStore.selectedProjectNames]
    } else if (projects.value.length === 1 && selectedProjectNames.value.length === 0) {
      selectedProjectNames.value = [projects.value[0].name]
    }
    // Restore from URL query param if still unselected
    const urlProject = route.query.project as string | undefined
    if (urlProject && selectedProjectNames.value.length === 0) {
      const names = urlProject.split(',').filter(n => nameMap.has(n))
      if (names.length > 0) selectedProjectNames.value = names
    }
  } catch (error) {
    console.error('Failed to load projects:', error)
  }
}

// 加载知识图谱状态：使用多项目合并统计
const loadGraphStatus = async () => {
  if (projectPaths.value.length === 0) {
    graphStatus.value = null
    return
  }

  try {
    // 使用多项目合并查询，传入所有 projectPaths
    const result = await knowledgeGraphApi.getStatus(projectPaths.value)
    graphStatus.value = result as unknown as KnowledgeGraphStatus
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load graph status:', error)
    graphStatus.value = null
  }

  // 同时加载向量状态
  await loadVectorStatus()
}

// 开始状态轮询
const startPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
  }
  pollingTimer = window.setInterval(async () => {
    if (graphStatus.value?.status === 'pending' || graphStatus.value?.status === 'running' || isGenerating.value) {
      await loadGraphStatus()
      // 检查任务是否完成
      if (graphStatus.value?.status === 'completed' || graphStatus.value?.status === 'failed' || graphStatus.value?.status === 'generated') {
        stopPolling()
      }
    }
  }, 2000)
}

// 停止状态轮询
const stopPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

// 处理项目变化
const handleProjectChange = () => {
  loadGraphStatus()
  loadGitStatus()
  loadVectorStatus()
  // Persist project selection to URL so refresh/bookmark restores it
  router.replace({ query: { ...route.query, project: selectedProjectNames.value.join(',') } })
}

// 加载 Git 状态
const loadGitStatus = async () => {
  if (!projectPath.value) return
  try {
    const status = await knowledgeGraphApi.getGitStatus([projectPath.value])
    gitStatus.value = status as unknown as GitStatus
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load git status:', error)
    gitStatus.value = null
  }
}

// 全量生成知识图谱
const handleFullGenerate = async () => {
  if (!projectPath.value) return

  // 检查 Git 状态
  try {
    const status = await knowledgeGraphApi.getGitStatus([projectPath.value])
    const gitStatusData = status as unknown as GitStatus

    if (gitStatusData.hasUncommittedChanges) {
      ElMessage.warning('请先提交代码后再生成知识图谱')
      return
    }
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to check git status:', error)
    ElMessage.error('检查 Git 状态失败，请稍后重试')
    return
  }

  try {
    recordGenerateTime()
    isGenerating.value = true
    ElMessage.info('已启动知识图谱生成任务，请稍候...')
    await knowledgeGraphApi.startGenerateTask(projectPath.value)
    ElMessage.success('知识图谱生成任务已启动')
    // 立即刷新状态并开始轮询
    startPolling()
    startVectorPolling()
    // 提示用户：全量生成会清除跨服务依赖关系
    if (selectedProjectNames.value.length > 1) {
      setTimeout(() => {
        ElMessage.warning('全量生成完成后，请到项目管理页重新执行「跨服务依赖构建」以恢复跨项目调用关系')
      }, 1500)
    }
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to start generate task:', error)
    ElMessage.error('启动生成任务失败')
  } finally {
    isGenerating.value = false
  }
}

// 增量生成知识图谱
const handleIncrementalGenerate = async () => {
  if (!projectPath.value) return

  // 检查 Git 状态
  try {
    const status = await knowledgeGraphApi.getGitStatus([projectPath.value])
    const gitStatusData = status as unknown as GitStatus

    if (gitStatusData.hasUncommittedChanges) {
      ElMessage.warning('请先提交代码后再生成知识图谱')
      return
    }
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to check git status:', error)
    ElMessage.error('检查 Git 状态失败，请稍后重试')
    return
  }

  try {
    recordGenerateTime()
    isGenerating.value = true
    ElMessage.info('已启动增量生成任务，请稍候...')
    await knowledgeGraphApi.incrementalGenerate(projectPath.value)
    ElMessage.success('增量生成任务已启动')
    // 立即刷新状态并开始轮询
    startPolling()
    startVectorPolling()
  } catch (error: any) {
    console.error('[KnowledgeGraph] Failed to start incremental generate:', error)
    // 检查是否是"无变更"的错误
    if (error?.response?.data?.message?.includes('无变更') ||
        error?.message?.includes('无变更')) {
      ElMessage.info('无变更，无需更新')
    } else {
      ElMessage.error('启动增量生成任务失败')
    }
  } finally {
    isGenerating.value = false
  }
}

// 生成向量
const handleGenerateVector = async () => {
  if (!projectPath.value) return

  try {
    recordGenerateTime()
    isGeneratingVector.value = true
    ElMessage.info('已启动向量生成任务，请稍候...')
    console.log('[KnowledgeGraph] Starting vector generation for path:', projectPath.value)
    await startVectorGeneration(projectPath.value)
    ElMessage.success('向量生成任务已启动')
    // 立即刷新状态并开始轮询
    await loadVectorStatus()
    startVectorPolling()
  } catch (error: any) {
    console.error('[KnowledgeGraph] Failed to start vector generation:', error)
    ElMessage.error('启动向量生成任务失败')
  } finally {
    isGeneratingVector.value = false
  }
}

// 加载缺失向量信息
const loadMissingInfo = async () => {
  if (!projectPath.value) {
    missingInfo.value = null
    return
  }
  try {
    missingInfo.value = await getMissingEmbeddings(projectPath.value)
  } catch (error) {
    console.error('[KnowledgeGraph] Failed to load missing info:', error)
    missingInfo.value = null
  }
}

// 补齐缺失向量
const handleRefreshMissing = async () => {
  if (!projectPath.value) return
  try {
    isRefreshingMissing.value = true
    const msg = await refreshMissing(projectPath.value)
    ElMessage.success(msg || '补齐任务已启动')
    await loadVectorStatus()
    startVectorPolling()
  } catch (error: any) {
    console.error('[KnowledgeGraph] Failed to refresh missing:', error)
    ElMessage.error('补齐任务启动失败')
  } finally {
    isRefreshingMissing.value = false
  }
}

// 监听项目路径变化
watch(projectPath, (path) => {
  if (path) {
    loadGraphStatus()
    loadGitStatus()
    loadMissingInfo()
  }
})

// 处理查看方法详情
function handleViewDetail(_result: any) {
  // 跳转到方法详情或打开弹窗
}

// 处理查看调用链：切到引用分析 tab 并自动以该方法为入口查向下调用
const methodRefGraphRef = ref<InstanceType<typeof MethodReferenceGraph> | null>(null)
function handleViewCallChain(result: any) {
  if (!result || !result.className || !result.methodName) {
    ElMessage.warning('该结果缺少类名或方法名，无法跳转')
    return
  }
  const fqn = `${result.className}.${result.methodName}`
  activeTab.value = 'methodRef'
  // 等待 tab 渲染后再调用子组件
  nextTick(() => {
    const inst = methodRefGraphRef.value as any
    if (inst && typeof inst.setAndSearch === 'function') {
      inst.setAndSearch(fqn, result.nodeId, 'downstream')
    } else {
      ElMessage.warning('引用分析组件未就绪')
    }
  })
}

// ==================== 术语管理 ====================
const showGlossaryDialog = ref(false)
const glossaryTerms = ref<GlossaryTerm[]>([])
const glossaryLoading = ref(false)
const glossaryShowForm = ref(false)
const glossarySubmitting = ref(false)
const glossaryEditingId = ref<number | null>(null)
const glossaryForm = ref({ term: '', synonym: '', context: '' })

const loadGlossaryTerms = async () => {
  if (!projectPath.value) return
  glossaryLoading.value = true
  try {
    glossaryTerms.value = await glossaryApi.list(projectPath.value) as unknown as GlossaryTerm[]
  } catch {
    ElMessage.error('加载术语列表失败')
  } finally {
    glossaryLoading.value = false
  }
}

watch(showGlossaryDialog, (visible) => {
  if (visible) loadGlossaryTerms()
})

const glossaryEditRow = (row: GlossaryTerm) => {
  glossaryEditingId.value = row.id!
  glossaryForm.value = {
    term: row.term,
    synonym: row.synonym,
    context: row.context || ''
  }
  glossaryShowForm.value = true
}

const glossaryDeleteRow = async (row: GlossaryTerm) => {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.term}（${row.synonym}）」？`,
      '删除确认',
      { type: 'warning' }
    )
    await glossaryApi.delete(row.id!)
    ElMessage.success('已删除')
    await loadGlossaryTerms()
  } catch {
    // cancelled
  }
}

const glossarySubmit = async () => {
  if (!glossaryForm.value.term.trim() || !glossaryForm.value.synonym.trim()) {
    ElMessage.warning('术语和同义词不能为空')
    return
  }
  glossarySubmitting.value = true
  try {
    const payload: GlossaryTerm = {
      projectPath: projectPath.value,
      term: glossaryForm.value.term.trim(),
      synonym: glossaryForm.value.synonym.trim(),
      context: glossaryForm.value.context.trim() || undefined
    }
    if (glossaryEditingId.value) {
      await glossaryApi.update(glossaryEditingId.value, payload)
      ElMessage.success('术语已更新')
    } else {
      await glossaryApi.create(payload)
      ElMessage.success('术语已创建')
    }
    glossaryShowForm.value = false
    glossaryEditingId.value = null
    glossaryForm.value = { term: '', synonym: '', context: '' }
    await loadGlossaryTerms()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    glossarySubmitting.value = false
  }
}

// ==================== KG 路径诊断 ====================
const showPathDiagnosisDialog = ref(false)
const pathDiagnosisLoading = ref(false)
const pathDiagnosisResult = ref<any>(null)
const pathMigrationLoading = ref(false)
const migrationOldBaseDir = ref('')
const migrationDryRun = ref(true)

const loadPathDiagnosis = async () => {
  pathDiagnosisLoading.value = true
  try {
    pathDiagnosisResult.value = await knowledgeGraphApi.diagnosePaths()
  } catch (e: any) {
    ElMessage.error('路径诊断失败: ' + (e.message || '未知错误'))
  } finally {
    pathDiagnosisLoading.value = false
  }
}

watch(showPathDiagnosisDialog, (visible) => {
  if (visible) loadPathDiagnosis()
})

const handleMigratePaths = async () => {
  if (!migrationOldBaseDir.value.trim()) {
    ElMessage.warning('请输入旧的基础目录路径')
    return
  }
  pathMigrationLoading.value = true
  try {
    const result = await knowledgeGraphApi.migratePaths(
      migrationOldBaseDir.value.trim(),
      migrationDryRun.value
    )
    ElMessage.success(result.data.message)
    if (!migrationDryRun.value && result.data.totalAffected > 0) {
      // 迁移完成后刷新诊断结果
      await loadPathDiagnosis()
      // 清空旧目录输入
      migrationOldBaseDir.value = ''
    }
  } catch (e: any) {
    ElMessage.error('路径迁移失败: ' + (e.message || '未知错误'))
  } finally {
    pathMigrationLoading.value = false
  }
}

onMounted(async () => {
  // 轮询立即启动，不等 loadProjects
  startPolling()
  startVectorPolling()
  // 先加载项目列表（确定 projectPath）
  await loadProjects()
  // Task 74: 加载项目名称分组
  await loadProjectNameGroups()
  // 项目列表加载完成后，再加载图谱数据
  // 注意：projectPaths computed 会使用 fallback 从 appStore.selectedProjects 获取路径
  // 所以即使 projects.value 还没包含远端项目，路径也应该正确
  if (projectPath.value) {
    console.log('[KnowledgeGraph] onMounted - projectPath:', projectPath.value)
    console.log('[KnowledgeGraph] onMounted - projectPaths:', projectPaths.value)
    console.log('[KnowledgeGraph] onMounted - appStore.selectedProjects:', appStore.selectedProjects)
    loadGraphStatus()
    loadGitStatus()
    loadVectorStatus()
    loadMissingInfo()
  } else {
    console.warn('[KnowledgeGraph] onMounted - projectPath is empty')
    console.log('[KnowledgeGraph] onMounted - selectedProjectNames:', selectedProjectNames.value)
    console.log('[KnowledgeGraph] onMounted - projects:', projects.value.map(p => p.name))
    console.log('[KnowledgeGraph] onMounted - appStore.selectedProjectNames:', appStore.selectedProjectNames)
  }
})

onUnmounted(() => {
  stopPolling()
  stopVectorPolling()
})

// 当项目路径变化时重新加载数据
watch(projectPaths, (newPaths) => {
  if (newPaths.length > 0) {
    loadGraphStatus()
    loadGitStatus()
    loadVectorStatus()
    loadMissingInfo()
  }
}, { deep: true })

// 当 store 选中项目变化时，同步到本地
watch(() => appStore.selectedProjectNames, (newNames) => {
  if (newNames.length > 0) {
    selectedProjectNames.value = [...newNames]
    // 等待 nextTick 确保 projectPaths 计算完成
    nextTick(() => {
      handleProjectChange()
    })
  }
})
</script>

<style scoped>
.knowledge-graph-view {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stats-overview {
  display: flex;
  gap: 24px;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 16px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 80px;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #409EFF;
}

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.vector-status-display {
  display: flex;
  align-items: center;
  gap: 8px;
}

.vector-progress {
  font-size: 12px;
  color: #606266;
  background: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
}

.main-tabs {
  min-height: 500px;
}

:deep(.el-tabs__content) {
  height: calc(100vh - 350px);
  overflow: auto;
}

.stat-value-warning {
  color: #E6A23C !important;
}

.stat-value-success {
  color: #67C23A !important;
}

.stat-item-vector {
  border-left: 1px solid #dcdfe6;
  padding-left: 24px;
}

.missing-drawer-content {
  padding: 0 4px;
}

.missing-summary {
  display: flex;
  gap: 24px;
  justify-content: center;
}

.missing-hint {
  font-size: 13px;
  color: #909399;
  margin-bottom: 12px;
}

.missing-more {
  font-size: 12px;
  color: #909399;
  text-align: center;
  margin-top: 8px;
}

/* Task 74: 分组项目选择器样式 */
.grouped-project-selector {
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 8px;
  background: #fafafa;
  min-width: 300px;
}

.group-item {
  margin-bottom: 4px;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: #f5f7fa;
  border-radius: 4px;
  cursor: pointer;
  font-weight: 500;
}

.group-header:hover {
  background: #e4e7ed;
}

.group-header .el-icon {
  transition: transform 0.2s;
}

.group-header .el-icon.is-expanded {
  transform: rotate(90deg);
}

.group-name {
  flex: 1;
}

.group-children {
  padding: 8px 16px 8px 24px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
</style>
