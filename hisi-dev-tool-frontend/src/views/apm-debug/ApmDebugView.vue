<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { useApmStore } from '@/stores/apmStore'
import ProjectSelector from './components/ProjectSelector.vue'
import EntryList from './components/EntryList.vue'
import CallChainPreview from './components/CallChainPreview.vue'
import RequestEditor from './components/RequestEditor.vue'
import ParameterForm from './components/ParameterForm.vue'
import TestCaseManager from './components/TestCaseManager.vue'
import TraceView from './components/TraceView.vue'
import ExecutionReport from './components/ExecutionReport.vue'
import AiDiagnosisChat from './components/AiDiagnosisChat.vue'
import ProcessLogViewer from './components/ProcessLogViewer.vue'

const store = useApmStore()

// Collapsible state for report section (executable summary)
const reportCollapsed = ref(false)

// Auto-expand the report panel every time a NEW report arrives.
// Without this, if the user collapsed the panel once, subsequent executions
// would keep it collapsed — which felt like "first execution doesn't auto-expand,
// second one does" because the very first session naturally starts expanded.
watch(
  () => store.report,
  (r) => {
    if (r) {
      reportCollapsed.value = false
    }
  },
)

// WS event handling is now done inside the store (watch on wsEvents).
// No need for a watcher here.

onMounted(() => {
  store.loadProjects()
})

// Fix: Explicitly clean up WebSocket when navigating away.
// The composable's onUnmounted doesn't fire in a Pinia store context.
onUnmounted(() => {
  store.cleanup()
})
</script>

<template>
  <div class="apm-debug-view">
    <!-- Error alert (floating) -->
    <el-alert
      v-if="store.status === 'ERROR'"
      :title="store.errorMessage || '发生错误'"
      type="error"
      show-icon
      closable
      class="error-alert"
      @close="store.resetAll()"
    />

    <!-- Three-column layout -->
    <div class="three-column-layout">
      <!-- LEFT PANEL: Project selector + Entry list + Call Chain Preview + Test Cases -->
      <div class="left-panel">
        <ProjectSelector />
        <EntryList />
        <CallChainPreview />
        <TestCaseManager />
      </div>

      <!-- CENTER PANEL: Parameter form + Request editor + Response -->
      <div class="center-panel">
        <ParameterForm v-if="store.selectedEntry" />
        <RequestEditor />
      </div>

      <!-- RIGHT PANEL: Trace waterfall -->
      <div class="right-panel" :class="{ collapsed: !store.showTracePanel }">
        <TraceView v-if="store.showTracePanel" />
        <div v-else class="trace-placeholder">
          <el-empty :image-size="60" description="执行请求后显示链路追踪" />
        </div>
      </div>
    </div>

    <!-- Process log viewer (bottom panel, visible whenever there's an active session or logs) -->
    <div
      v-if="store.status !== 'IDLE' || store.wsProcessLogs.length > 0"
      class="log-section"
    >
      <ProcessLogViewer
        :logs="store.wsProcessLogs"
        :status="store.wsProcessStatus"
        :error-banner="store.wsProcessError"
        @clear="store.clearProcessConsole()"
      />
    </div>

    <!-- Report section (full width below, collapsible) -->
    <div
      v-if="store.status === 'COMPLETE' && store.report"
      class="report-section"
      :class="{ collapsed: reportCollapsed }"
    >
      <div class="report-toggle-bar" @click="reportCollapsed = !reportCollapsed">
        <el-icon class="toggle-icon">
          <ArrowDown v-if="reportCollapsed" />
          <ArrowUp v-else />
        </el-icon>
        <span class="toggle-label">执行摘要</span>
        <el-tag
          v-if="store.report"
          :type="store.report.success ? 'success' : 'danger'"
          size="small"
          effect="dark"
        >
          {{ store.report.success ? '成功' : '失败' }} · {{ store.report.totalDurationMs }}ms · {{ store.report.totalSpanCount }} spans
        </el-tag>
        <span class="toggle-hint">{{ reportCollapsed ? '展开' : '折叠' }}</span>
      </div>
      <div v-show="!reportCollapsed" class="report-body">
        <ExecutionReport :report="store.report" />
      </div>
    </div>

    <!-- AI Diagnosis floating chat -->
    <AiDiagnosisChat />
  </div>
</template>

<style scoped>
.apm-debug-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.error-alert {
  margin: 8px 12px 0;
  flex-shrink: 0;
}

.three-column-layout {
  flex: 1;
  display: flex;
  min-height: 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

/* Left panel */
.left-panel {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--el-border-color-lighter);
  background-color: var(--el-bg-color);
  overflow: hidden;
  min-height: 0;
}

/* Ensure non-list children of left panel don't grow & push EntryList */
.left-panel > :deep(.project-selector),
.left-panel > :deep(.call-chain-preview),
.left-panel > :deep(.test-case-manager) {
  flex-shrink: 0;
}

/* EntryList takes remaining space and scrolls internally */
.left-panel > :deep(.entry-list) {
  flex: 1 1 auto;
  min-height: 120px;
  overflow: hidden;
}

/* Center panel */
.center-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* Right panel */
.right-panel {
  width: 400px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--el-border-color-lighter);
  background-color: var(--el-bg-color);
  transition: width 0.3s ease;
}

.right-panel.collapsed {
  width: 200px;
}

.trace-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* Report section */
.report-section {
  flex-shrink: 0;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  max-height: 45vh;
  transition: max-height 0.25s ease;
}

.report-section.collapsed {
  max-height: 40px;
}

.report-toggle-bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  cursor: pointer;
  user-select: none;
  background: var(--el-bg-color-page);
  border-bottom: 1px solid var(--el-border-color-lighter);
  height: 40px;
  box-sizing: border-box;
}

.report-toggle-bar:hover {
  background: var(--el-fill-color-light);
}

.toggle-icon {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.toggle-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.toggle-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.report-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 16px;
}

/* Process log viewer panel */
.log-section {
  flex-shrink: 0;
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 8px 12px;
  background: var(--el-bg-color-page);
  max-height: 50vh;
  display: flex;
  flex-direction: column;
}

.log-section :deep(.process-log-viewer) {
  flex: 1;
  min-height: 0;
}

/* Responsive: collapse right panel on smaller screens */
@media (max-width: 1200px) {
  .right-panel {
    width: 300px;
  }

  .left-panel {
    width: 240px;
  }
}

@media (max-width: 900px) {
  .three-column-layout {
    flex-direction: column;
  }

  .left-panel,
  .right-panel {
    width: 100% !important;
    border-right: none;
    border-left: none;
    border-bottom: 1px solid var(--el-border-color-lighter);
    max-height: 300px;
  }
}
</style>
