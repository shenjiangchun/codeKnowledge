<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useApmStore } from '@/stores/apmStore'
import type { KgProject } from '@/types/apm'

const store = useApmStore()

function handleProjectChange(projectPath: string | null): void {
  if (!projectPath) {
    store.selectProject(null)
    return
  }
  const project = store.projects.find(p => p.projectPath === projectPath) ?? null
  store.selectProject(project)
}

/**
 * Launch with safety check: warn if no entry point was selected, since
 * without an entry node the KG-driven method-level instrumentation
 * (and the call-chain preview) will be empty.
 */
async function handleLaunch(): Promise<void> {
  if (!store.selectedEntry) {
    try {
      await ElMessageBox.confirm(
        '尚未选择入口方法。启动后将无法基于知识图谱进行方法级埋点,调用链将只包含框架默认 Span(HTTP/SQL 等),且无法捕获方法入参/返回值中间态。建议先在左侧选择一个 Controller 入口再启动。',
        '未选择入口',
        {
          confirmButtonText: '仍然启动',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
    } catch {
      return // user cancelled
    }
  }
  await store.launchSession()
}

/**
 * Force-stop a stale session for the selected project, then allow re-launch.
 */
async function handleForceStop(): Promise<void> {
  if (!store.selectedProject) return
  const success = await store.forceStopSession(store.selectedProject.projectPath)
  if (success) {
    store.resetAll()
  }
}

/** Check if a project has a lingering active session (not from current page session) */
function hasActiveSession(projectPath: string): boolean {
  return store.activeSessions.has(projectPath)
}

function getActiveSessionStatus(projectPath: string): string | null {
  const session = store.activeSessions.get(projectPath)
  return session?.status ?? null
}

/**
 * Format project display label for the collapsed select.
 * Shows: "label (last-2-dirs)" to distinguish same-name projects in different locations.
 */
function formatProjectLabel(project: KgProject): string {
  const parts = project.projectPath.replace(/\\/g, '/').split('/').filter(Boolean)
  if (parts.length <= 2) return project.projectPath
  // Show "projectName (parent/projectName)" for disambiguation
  const parent = parts[parts.length - 2]
  return `${project.label} (${parent}/${project.label})`
}

onMounted(() => {
  if (store.projects.length === 0) {
    store.loadProjects()
  }
})
</script>

<template>
  <div class="project-selector">
    <div class="selector-header">
      <span class="label">项目</span>
      <el-button
        size="small"
        text
        :loading="store.projectsLoading"
        @click="store.loadProjects()"
      >
        <el-icon><Refresh /></el-icon>
      </el-button>
    </div>
    <el-select
      :model-value="store.selectedProject?.projectPath ?? ''"
      placeholder="选择已向量化的项目"
      filterable
      clearable
      :loading="store.projectsLoading"
      class="project-select"
      @change="handleProjectChange"
    >
      <el-option
        v-for="project in store.projects"
        :key="project.projectPath"
        :label="formatProjectLabel(project)"
        :value="project.projectPath"
      >
        <div class="project-option">
          <div class="project-option-top">
            <span class="project-name">{{ project.label }}</span>
            <el-tag
              v-if="hasActiveSession(project.projectPath)"
              size="small"
              type="warning"
              effect="dark"
              class="session-badge"
            >
              {{ getActiveSessionStatus(project.projectPath) }}
            </el-tag>
          </div>
          <span class="project-path">{{ project.projectPath }}</span>
        </div>
      </el-option>
    </el-select>

    <!-- Session control -->
    <div v-if="store.selectedProject" class="session-control">
      <!-- If there's a stale session from before (and we're currently IDLE), offer force-stop -->
      <div
        v-if="store.status === 'IDLE' && hasActiveSession(store.selectedProject.projectPath)"
        class="stale-session-warning"
      >
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          class="stale-alert"
        >
          <template #title>
            <span class="stale-text">该项目有未关闭的会话</span>
          </template>
          <el-button
            type="warning"
            size="small"
            plain
            @click="handleForceStop"
          >
            强制停止旧会话
          </el-button>
        </el-alert>
      </div>

      <el-button
        v-if="(store.status === 'IDLE' && !hasActiveSession(store.selectedProject.projectPath)) || store.status === 'LAUNCHING'"
        type="primary"
        size="small"
        :loading="store.status === 'LAUNCHING'"
        :disabled="store.status === 'LAUNCHING'"
        class="launch-btn"
        @click="handleLaunch"
      >
        启动调试
      </el-button>
      <template v-if="store.status !== 'IDLE'">
        <div class="session-info">
          <el-tag
            :type="store.status === 'READY' ? 'success' : store.status === 'ERROR' ? 'danger' : 'warning'"
            size="small"
            effect="dark"
          >
            {{ store.status }}
          </el-tag>
          <el-tag
            v-if="store.wsConnected"
            type="success"
            size="small"
            effect="plain"
          >
            WS
          </el-tag>
        </div>
        <div class="session-actions">
          <el-button
            v-if="store.isActive"
            type="danger"
            size="small"
            plain
            @click="store.stopSession()"
          >
            停止
          </el-button>
          <el-button
            v-if="store.status === 'COMPLETE' || store.status === 'ERROR'"
            size="small"
            @click="store.resetAll()"
          >
            重置
          </el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.project-selector {
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.selector-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.selector-header .label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.project-select {
  width: 100%;
}

.project-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 2px 0;
}

.project-option-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.project-name {
  font-size: 13px;
  font-weight: 500;
}

.session-badge {
  flex-shrink: 0;
}

.project-path {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.session-control {
  margin-top: 12px;
}

.stale-session-warning {
  margin-bottom: 8px;
}

.stale-alert {
  padding: 8px 12px;
}

.stale-alert :deep(.el-alert__content) {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stale-text {
  font-size: 12px;
}

.launch-btn {
  width: 100%;
}

.session-info {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 8px;
}

.session-actions {
  display: flex;
  gap: 6px;
}

.session-actions .el-button {
  flex: 1;
}
</style>
