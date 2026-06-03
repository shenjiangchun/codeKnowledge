<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { useSkillStore } from '@/stores/skillStore'
import { projectApi } from '@/api/project'
import {
  Shop,
  Download,
  Delete,
  Search,
  FolderOpened,
  InfoFilled,
  RefreshRight
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { GitRepositoryInfo } from '@/types/callchain'
import type { SkillDefinition, SkillCategory } from '@/types/skill'
import { SkillCategoryInfo } from '@/types/skill'

const appStore = useAppStore()
const skillStore = useSkillStore()

// 项目选择状态
const selectedProjectPath = ref('')
const projects = ref<GitRepositoryInfo[]>([])
const projectLoading = ref(false)

// 分类标签列表
const categories = computed(() => {
  const cats: { key: SkillCategory | ''; label: string; count: number }[] = [
    { key: '', label: '全部', count: skillStore.totalSkills }
  ]
  for (const [key, value] of Object.entries(SkillCategoryInfo)) {
    cats.push({
      key: key as SkillCategory,
      label: value.label,
      count: skillStore.categoryStats[key as SkillCategory] || 0
    })
  }
  return cats
})

// 技能卡片点击
const handleSkillClick = (skill: SkillDefinition) => {
  showSkillDetail(skill)
}

// 显示技能详情对话框
const detailDialogVisible = ref(false)
const selectedSkill = ref<SkillDefinition | null>(null)

const showSkillDetail = (skill: SkillDefinition) => {
  selectedSkill.value = skill
  detailDialogVisible.value = true
}

// 安装技能
const handleInstall = async (skill: SkillDefinition) => {
  if (!selectedProjectPath.value) {
    ElMessage.warning('请先选择项目')
    return
  }

  const result = await skillStore.installSkill(skill.id, selectedProjectPath.value)
  if (result) {
    ElMessage.success(`技能 "${skill.name}" 安装成功`)
  }
}

// 卸载技能
const handleUninstall = async (skill: SkillDefinition) => {
  if (!selectedProjectPath.value) {
    ElMessage.warning('请先选择项目')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要从当前项目卸载技能 "${skill.name}" 吗？`,
      '确认卸载',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    const result = await skillStore.uninstallSkill(skill.id, selectedProjectPath.value)
    if (result) {
      ElMessage.success(`技能 "${skill.name}" 已卸载`)
    }
  } catch {
    // 用户取消
  }
}

// 判断技能是否已安装
const isInstalled = (skillId: string) => {
  return skillStore.isSkillInstalled(skillId)
}

// 判断是否正在操作
const isOperating = (skillId: string) => {
  return skillStore.isSkillOperating(skillId)
}

// 获取安装状态
const getInstallStatus = (skillId: string) => {
  return skillStore.getSkillInstallStatus(skillId)
}

// 加载项目列表
const loadProjects = async () => {
  if (!appStore.projectDirConfigured) {
    projects.value = []
    return
  }

  projectLoading.value = true
  try {
    const response = await projectApi.scanGitRepos()
    projects.value = response as unknown as GitRepositoryInfo[]
  } catch (error: any) {
    ElMessage.error('加载项目列表失败')
    console.error('Failed to load projects:', error)
  } finally {
    projectLoading.value = false
  }
}

// 选择项目
const handleSelectProject = (project: GitRepositoryInfo) => {
  selectedProjectPath.value = project.path
  skillStore.loadProjectStatus(project.path)
  ElMessage.success(`已选择项目: ${project.name}`)
}

// 清除项目选择
const handleClearProject = () => {
  selectedProjectPath.value = ''
  skillStore.projectStatus = []
}

// 刷新技能列表
const handleRefreshSkills = async () => {
  await skillStore.loadSkills()
  if (selectedProjectPath.value) {
    await skillStore.loadProjectStatus(selectedProjectPath.value)
  }
}

// 分类筛选点击
const handleCategoryClick = (category: SkillCategory | '') => {
  skillStore.setCategory(category)
}

// 搜索
const handleSearch = () => {
  skillStore.setSearchKeyword(searchInput.value)
}

const searchInput = ref('')

// 监听项目目录变化
watch(
  () => appStore.projectDir,
  () => {
    loadProjects()
  }
)

// 监听项目选择变化
watch(selectedProjectPath, (newPath) => {
  if (newPath) {
    skillStore.loadProjectStatus(newPath)
  }
})

// 初始化
onMounted(() => {
  Promise.all([skillStore.loadSkills(), loadProjects()])
})
</script>

<template>
  <div class="skill-market">
    <!-- 项目选择区域 -->
    <el-card class="project-selector-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>
            <el-icon><FolderOpened /></el-icon>
            项目选择
          </span>
          <el-button
            v-if="selectedProjectPath"
            type="danger"
            size="small"
            @click="handleClearProject"
          >
            清除选择
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="!appStore.projectDirConfigured"
        title="请先配置项目目录"
        type="warning"
        show-icon
        :closable="false"
        class="mb-4"
      />

      <div v-else>
        <div v-if="selectedProjectPath" class="selected-project-info">
          <el-tag type="success" size="large">
            已选择: {{ projects.find(p => p.path === selectedProjectPath)?.name }}
          </el-tag>
          <span class="project-path">{{ selectedProjectPath }}</span>
        </div>

        <el-scrollbar height="120px" v-loading="projectLoading">
          <div class="project-list">
            <el-tag
              v-for="project in projects"
              :key="project.path"
              :type="selectedProjectPath === project.path ? 'success' : 'info'"
              :effect="selectedProjectPath === project.path ? 'dark' : 'plain'"
              class="project-tag"
              @click="handleSelectProject(project)"
            >
              {{ project.name }}
            </el-tag>
          </div>
        </el-scrollbar>
      </div>
    </el-card>

    <!-- 分类标签筛选 -->
    <el-card class="filter-card" shadow="never">
      <div class="filter-container">
        <div class="category-tabs">
          <el-tag
            v-for="cat in categories"
            :key="cat.key"
            :type="skillStore.selectedCategory === cat.key ? 'primary' : 'info'"
            :effect="skillStore.selectedCategory === cat.key ? 'dark' : 'plain'"
            class="category-tag"
            @click="handleCategoryClick(cat.key)"
          >
            {{ cat.label }}
            <span class="count">{{ cat.count }}</span>
          </el-tag>
        </div>

        <div class="search-box">
          <el-input
            v-model="searchInput"
            placeholder="搜索技能..."
            :prefix-icon="Search"
            clearable
            @keyup.enter="handleSearch"
            @clear="handleSearch"
            style="width: 200px"
          />
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="skillStore.clearFilters()">清除筛选</el-button>
        </div>
      </div>
    </el-card>

    <!-- 技能卡片网格 -->
    <div class="skill-grid" v-loading="skillStore.loading">
      <el-row :gutter="16">
        <el-col
          v-for="skill in skillStore.filteredSkills"
          :key="skill.id"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
        >
          <el-card
            class="skill-card"
            shadow="hover"
            @click="handleSkillClick(skill)"
          >
            <div class="skill-header">
              <div class="skill-icon">
                <el-icon :size="32"><Shop /></el-icon>
              </div>
              <div class="skill-meta">
                <el-tag
                  :color="SkillCategoryInfo[skill.category].color"
                  effect="dark"
                  size="small"
                >
                  {{ SkillCategoryInfo[skill.category].label }}
                </el-tag>
                <el-tag v-if="skill.isOfficial" type="warning" size="small">
                  官方
                </el-tag>
              </div>
            </div>

            <h3 class="skill-name">{{ skill.name }}</h3>
            <p class="skill-description">{{ skill.description }}</p>

            <div class="skill-stats">
              <span class="version">v{{ skill.version }}</span>
            </div>

            <div class="skill-tags" v-if="skill.tags?.length">
              <el-tag
                v-for="tag in skill.tags.slice(0, 3)"
                :key="tag"
                size="small"
                type="info"
              >
                {{ tag }}
              </el-tag>
            </div>

            <div class="skill-actions">
              <el-button
                v-if="!isInstalled(skill.id)"
                type="primary"
                size="small"
                :icon="Download"
                :loading="isOperating(skill.id)"
                :disabled="!selectedProjectPath"
                @click.stop="handleInstall(skill)"
              >
                安装
              </el-button>
              <el-button
                v-else
                type="danger"
                size="small"
                :icon="Delete"
                :loading="isOperating(skill.id)"
                @click.stop="handleUninstall(skill)"
              >
                卸载
              </el-button>

              <el-button
                type="info"
                size="small"
                :icon="InfoFilled"
                @click.stop="showSkillDetail(skill)"
              >
                详情
              </el-button>
            </div>

            <!-- 安装状态指示 -->
            <div v-if="isInstalled(skill.id)" class="install-status">
              <el-tag type="success" size="small">
                已安装
                <span v-if="getInstallStatus(skill.id)?.installedVersion">
                  v{{ getInstallStatus(skill.id)?.installedVersion }}
                </span>
              </el-tag>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 空状态 -->
      <el-empty
        v-if="skillStore.filteredSkills.length === 0 && !skillStore.loading"
        description="没有找到匹配的技能"
      />
    </div>

    <!-- 刷新按钮 -->
    <div class="refresh-bar">
      <el-button
        type="primary"
        :icon="RefreshRight"
        :loading="skillStore.loading"
        @click="handleRefreshSkills"
      >
        刷新技能列表
      </el-button>
    </div>

    <!-- 技能详情对话框 -->
    <el-dialog
      v-model="detailDialogVisible"
      :title="selectedSkill?.name"
      width="600px"
      destroy-on-close
    >
      <div v-if="selectedSkill" class="skill-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="技能 ID">
            {{ selectedSkill.id }}
          </el-descriptions-item>
          <el-descriptions-item label="版本">
            v{{ selectedSkill.version }}
          </el-descriptions-item>
          <el-descriptions-item label="分类">
            <el-tag
              :color="SkillCategoryInfo[selectedSkill.category].color"
              effect="dark"
            >
              {{ SkillCategoryInfo[selectedSkill.category].label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="作者">
            {{ selectedSkill.author || '未知' }}
          </el-descriptions-item>
          <el-descriptions-item label="描述" :span="2">
            {{ selectedSkill.description }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 标签 -->
        <div class="detail-tags" v-if="selectedSkill.tags?.length">
          <span class="label">标签:</span>
          <el-tag
            v-for="tag in selectedSkill.tags"
            :key="tag"
            size="small"
            type="info"
          >
            {{ tag }}
          </el-tag>
        </div>

        <!-- 文件列表 -->
        <div class="skill-files" v-if="selectedSkill.files?.length">
          <h4>技能文件</h4>
          <el-table :data="selectedSkill.files" stripe size="small">
            <el-table-column prop="name" label="文件名" />
            <el-table-column prop="type" label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" show-overflow-tooltip />
          </el-table>
        </div>

        <!-- 操作按钮 -->
        <div class="detail-actions">
          <el-button
            v-if="!isInstalled(selectedSkill.id)"
            type="primary"
            size="large"
            :icon="Download"
            :loading="isOperating(selectedSkill.id)"
            :disabled="!selectedProjectPath"
            @click="handleInstall(selectedSkill)"
          >
            安装到此项目
          </el-button>
          <el-button
            v-else
            type="danger"
            size="large"
            :icon="Delete"
            :loading="isOperating(selectedSkill.id)"
            @click="handleUninstall(selectedSkill)"
          >
            从项目卸载
          </el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.skill-market {
  padding: 16px;
}

.project-selector-card,
.filter-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header span {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
}

.mb-4 {
  margin-bottom: 16px;
}

.selected-project-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.project-path {
  color: #909399;
  font-size: 12px;
}

.project-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0;
}

.project-tag {
  cursor: pointer;
  transition: all 0.2s;
}

.project-tag:hover {
  transform: scale(1.05);
}

.filter-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.category-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.category-tag {
  cursor: pointer;
  transition: all 0.2s;
}

.category-tag .count {
  margin-left: 4px;
  font-size: 12px;
}

.search-box {
  display: flex;
  gap: 8px;
}

.skill-grid {
  min-height: 400px;
}

.skill-card {
  margin-bottom: 16px;
  cursor: pointer;
  transition: all 0.2s;
}

.skill-card:hover {
  transform: translateY(-4px);
}

.skill-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}

.skill-icon {
  color: #409eff;
}

.skill-meta {
  display: flex;
  gap: 4px;
}

.skill-name {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 8px 0;
  color: #303133;
}

.skill-description {
  font-size: 13px;
  color: #606266;
  margin: 0 0 12px 0;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.skill-stats {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 12px;
  color: #909399;
}

.skill-stats span {
  display: flex;
  align-items: center;
  gap: 4px;
}

.skill-stats .version {
  color: #409eff;
}

.skill-tags {
  display: flex;
  gap: 4px;
  margin-bottom: 12px;
  overflow: hidden;
}

.skill-actions {
  display: flex;
  gap: 8px;
}

.install-status {
  position: absolute;
  top: 8px;
  right: 8px;
}

.refresh-bar {
  display: flex;
  justify-content: center;
  padding: 16px;
}

/* 详情对话框样式 */
.skill-detail {
  padding: 16px 0;
}

.detail-tags {
  margin-top: 16px;
}

.detail-tags .label {
  font-weight: 500;
  margin-right: 8px;
}

.detail-tags .el-tag {
  margin-right: 4px;
}

.skill-files {
  margin-top: 24px;
}

.skill-files h4 {
  margin-bottom: 12px;
  color: #303133;
}

.detail-actions {
  display: flex;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}
</style>