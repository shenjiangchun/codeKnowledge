<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
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

    <!-- Report section (full width below) -->
    <div v-if="store.status === 'COMPLETE' && store.report" class="report-section">
      <ExecutionReport :report="store.report" />
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
  padding: 16px;
  max-height: 400px;
  overflow: auto;
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
