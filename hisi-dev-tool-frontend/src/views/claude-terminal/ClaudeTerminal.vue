<template>
  <div class="claude-workspace">
    <SessionList
      ref="sessionListRef"
      @new-session="handleNewSession"
      @select-session="handleSelectSession"
      @delete-session="handleDeleteSession"
    />
    <div class="terminal-wrapper">
      <div class="terminal-header">
        <div class="terminal-title">
          <el-icon><Monitor /></el-icon>
          <span>{{ currentSessionTitle }}</span>
          <span v-if="workspaceStore.currentSession?.workingDirectory" class="working-directory">
            {{ truncatePath(workspaceStore.currentSession.workingDirectory) }}
          </span>
        </div>
      </div>
      <div class="terminal-container" ref="terminalContainerRef"></div>
    </div>
    <TerminalSidebar
      :connection-status="connectionStatus"
      :terminal-cols="terminal?.cols"
      :terminal-rows="terminal?.rows"
      :session-duration="sessionDuration"
      @execute-command="executeCommand"
      @reconnect="handleReconnect"
      @clear="handleClear"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import '@xterm/xterm/css/xterm.css'
import { Monitor } from '@element-plus/icons-vue'
import { createTerminalConnection } from '@/api/terminal'
import type { TerminalConnectionStatus, TerminalClientMessage, ClaudeWorkspaceSession } from '@/types/terminal'
import { ElMessage } from 'element-plus'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { useThemeStore } from '@/stores/themeStore'
import SessionList from './components/SessionList.vue'
import TerminalSidebar from './components/TerminalSidebar.vue'
import { debounce } from 'lodash-es'

const workspaceStore = useWorkspaceStore()
const themeStore = useThemeStore()
const route = useRoute()

const sessionListRef = ref<{ focusSearch: () => void } | null>(null)
const terminalContainerRef = ref<HTMLElement | null>(null)
const terminal = ref<Terminal | null>(null)
let fitAddon: FitAddon | null = null
let onDataDisposable: { dispose: () => void } | null = null
const connectionStatus = ref<TerminalConnectionStatus>('disconnected')
let terminalConnection: ReturnType<typeof createTerminalConnection> | null = null
let isManualDisconnect = false

// Session duration timer
const sessionDuration = ref(0)
let durationTimer: ReturnType<typeof setInterval> | null = null

function startDurationTimer() {
  if (durationTimer) clearInterval(durationTimer)
  sessionDuration.value = 0
  durationTimer = setInterval(() => {
    if (connectionStatus.value === 'connected') {
      sessionDuration.value++
    }
  }, 1000)
}

function stopDurationTimer() {
  if (durationTimer) {
    clearInterval(durationTimer)
    durationTimer = null
  }
}

const currentSessionTitle = computed(() => {
  return workspaceStore.currentSession?.title || 'Claude CLI Terminal'
})

// Watch for route query changes (when navigating from ProjectList while already on this page)
watch(() => route.query.sessionId, async (newSessionId) => {
  if (newSessionId && typeof newSessionId === 'string') {
    const targetSession = workspaceStore.sessions.find(s => s.id === newSessionId)
    if (targetSession) {
      // Disconnect current connection
      isManualDisconnect = true
      terminalConnection?.close()
      terminal.value?.clear()
      stopDurationTimer()

      // Select and connect to new session
      workspaceStore.selectSession(targetSession.id)
      if (targetSession.claudeSessionId) {
        connectTerminal('resume', targetSession.claudeSessionId)
      } else {
        connectTerminal('start')
      }
    } else {
      await workspaceStore.loadSessions('active')
      const loadedSession = workspaceStore.sessions.find(s => s.id === newSessionId)
      if (loadedSession) {
        workspaceStore.selectSession(loadedSession.id)
        if (loadedSession.claudeSessionId) {
          connectTerminal('resume', loadedSession.claudeSessionId)
        } else {
          connectTerminal('start')
        }
      }
    }
  }
})

// Watch for theme changes and update xterm.js theme
watch(() => themeStore.currentTheme, () => {
  if (terminal.value) {
    terminal.value.options.theme = themeStore.getTerminalTheme()
  }
}, { deep: true })

function initTerminal() {
  if (terminal.value) {
    terminal.value.dispose()
    terminal.value = null
  }
  if (!terminalContainerRef.value) return

  terminal.value = new Terminal({
    cursorBlink: true,
    cursorStyle: 'block',
    convertEol: true,
    scrollback: 5000,
    allowProposedApi: true,
    theme: themeStore.getTerminalTheme(),
    fontFamily: '"JetBrains Mono", "Fira Code", "Consolas", monospace',
    fontSize: 14,
    lineHeight: 1.4,
    windowsPty: {
      backend: 'conpty'
    }
  })

  fitAddon = new FitAddon()
  terminal.value.loadAddon(fitAddon)
  terminal.value.loadAddon(new WebLinksAddon())
  terminal.value.open(terminalContainerRef.value)

  onDataDisposable = terminal.value.onData((data) => {
    terminalConnection?.send({ action: 'input', data })
  })
}

function fitTerminal() {
  if (!fitAddon || !terminal.value) return
  try {
    fitAddon.fit()
    // Send resize to backend
    if (terminalConnection) {
      terminalConnection.send({ action: 'resize', cols: terminal.value.cols, rows: terminal.value.rows })
    }
  } catch (e) {
    // Ignore fit errors
  }
}

// Debounced version to avoid excessive resize calls
const debouncedFitTerminal = debounce(fitTerminal, 50)

function connectTerminal(action: 'start' | 'resume', claudeSessionId?: string) {
  const session = workspaceStore.currentSession
  const initialPrompt = session?.initialPrompt
  connectionStatus.value = 'connecting'
  isManualDisconnect = false
  stopDurationTimer()

  terminalConnection = createTerminalConnection({
    onOpen: () => {
      connectionStatus.value = 'connected'
      startDurationTimer()
    },
    onClose: () => {
      connectionStatus.value = 'disconnected'
      stopDurationTimer()
    },
    onError: (error) => {
      connectionStatus.value = 'error'
      stopDurationTimer()
      if (!isManualDisconnect) {
        ElMessage.error(`终端连接错误: ${error}`)
      }
    },
    onOutput: (data) => terminal.value?.write(data),
    onSessionInfo: (claudeSessionId) => {
      if (workspaceStore.currentSessionId) {
        workspaceStore.bindClaudeSession(workspaceStore.currentSessionId, claudeSessionId)
      }
    },
    onReady: async () => {
      // Fit terminal first to get correct dimensions
      fitTerminal()
      // Wait a bit for fit to complete
      await new Promise(resolve => setTimeout(resolve, 50))
      // Send start or resume action with terminal size
      const cols = terminal.value?.cols || 120
      const rows = terminal.value?.rows || 30
      const message: TerminalClientMessage = {
        action,
        cols,
        rows,
        initialPrompt: action === 'start' ? initialPrompt : undefined
      }
      if (claudeSessionId) {
        message.claudeSessionId = claudeSessionId
      }
      terminalConnection?.send(message)
    },
    onClaudeReady: () => {
      // initialPrompt is now sent directly in the start command
    }
  })
}

function handleNewSession() {
  isManualDisconnect = true
  terminalConnection?.close()
  terminal.value?.clear()
  stopDurationTimer()

  workspaceStore.createSession().then(() => {
    initTerminal()
    connectTerminal('start')
  })
}

function handleSelectSession(sessionId: string) {
  workspaceStore.selectSession(sessionId)
  const session = workspaceStore.sessions.find(s => s.id === sessionId)

  isManualDisconnect = true
  terminalConnection?.close()
  terminal.value?.clear()
  stopDurationTimer()

  if (!terminal.value) {
    initTerminal()
  }

  if (session?.claudeSessionId) {
    connectTerminal('resume', session.claudeSessionId)
  } else {
    connectTerminal('start')
  }
}

function executeCommand(command: string) {
  terminalConnection?.send({ action: 'input', data: command + '\n' })
}

function handleReconnect() {
  isManualDisconnect = true
  terminalConnection?.close()
  terminal.value?.clear()
  stopDurationTimer()

  const session = workspaceStore.currentSession
  if (session?.claudeSessionId) {
    connectTerminal('resume', session.claudeSessionId)
  } else {
    connectTerminal('start')
  }
}

function handleClear() {
  terminal.value?.clear()
}

function handleDeleteSession(_sessionId: string) {
  isManualDisconnect = true
  terminalConnection?.close()
  terminal.value?.clear()
  stopDurationTimer()

  if (workspaceStore.activeSessions.length > 0) {
    const firstSession = workspaceStore.activeSessions[0]
    workspaceStore.selectSession(firstSession.id)
    if (firstSession.claudeSessionId) {
      connectTerminal('resume', firstSession.claudeSessionId)
    } else {
      connectTerminal('start')
    }
  } else {
    handleNewSession()
  }
}

function truncatePath(path: string): string {
  if (!path) return ''
  const parts = path.replace(/\\/g, '/').split('/')
  const lastTwo = parts.slice(-2).join('/')
  return '.../' + lastTwo
}

// Keyboard shortcuts handler
function handleKeyboard(e: KeyboardEvent) {
  // Only handle shortcuts when this page is active (not in other pages)
  // Check if we're in the Claude Terminal route
  if (route.path !== '/claude-terminal') return

  // Ctrl+N - New session
  if (e.ctrlKey && e.key === 'n') {
    e.preventDefault()
    handleNewSession()
    return
  }

  // Ctrl+L - Clear screen
  if (e.ctrlKey && e.key === 'l') {
    e.preventDefault()
    handleClear()
    return
  }

  // Ctrl+R - Reconnect terminal
  if (e.ctrlKey && e.key === 'r') {
    e.preventDefault()
    handleReconnect()
    return
  }

  // Ctrl+F - Focus search input in SessionList
  if (e.ctrlKey && e.key === 'f') {
    e.preventDefault()
    sessionListRef.value?.focusSearch()
    return
  }

  // Escape - Close any dialogs/settings (future implementation)
  if (e.key === 'Escape') {
    // Currently no settings panel is visible
    // This will be implemented when settings panel is added
    // For now, we can clear search keyword or close any open dialogs
    return
  }
}

let resizeObserver: ResizeObserver | null = null

onMounted(async () => {
  // Initialize theme store and apply CSS variables
  themeStore.init()

  // Check for sessionId in query params (from navigation with specific session)
  const querySessionId = route.query.sessionId as string | undefined

  // Wait for DOM to be ready
  await nextTick()
  initTerminal()

  // Fit after a small delay to ensure container has correct size
  setTimeout(() => fitTerminal(), 100)

  // Setup resize observer
  if (terminalContainerRef.value) {
    resizeObserver = new ResizeObserver(() => {
      debouncedFitTerminal()
    })
    resizeObserver.observe(terminalContainerRef.value)
  }
  window.addEventListener('resize', debouncedFitTerminal)

  // Setup keyboard shortcuts
  document.addEventListener('keydown', handleKeyboard)

  // Determine which session to use
  let targetSession: ClaudeWorkspaceSession | null = null

  // If querySessionId is provided, wait for it to be available
  if (querySessionId) {
    // Try to find the session, with retries
    for (let i = 0; i < 20; i++) {
      targetSession = workspaceStore.sessions.find(s => s.id === querySessionId) || null
      if (targetSession) {
        break
      }
      // Wait a bit for the session to be added to the store
      await new Promise(resolve => setTimeout(resolve, 50))
    }

    // If still not found, try loading from server
    if (!targetSession) {
      await workspaceStore.loadSessions('active')
      targetSession = workspaceStore.sessions.find(s => s.id === querySessionId) || null
    }

    if (targetSession) {
      workspaceStore.selectSession(targetSession.id)
    }
  }

  // Fallback to currentSession
  if (!targetSession && workspaceStore.currentSession) {
    targetSession = workspaceStore.currentSession
  }

  // Fallback to first active session
  if (!targetSession) {
    // Load sessions if not loaded
    if (workspaceStore.sessions.length === 0) {
      await workspaceStore.loadSessions('active')
    }
    if (workspaceStore.activeSessions.length > 0) {
      targetSession = workspaceStore.activeSessions[0]
      workspaceStore.selectSession(targetSession.id)
    }
  }

  // Connect to the determined session
  if (targetSession) {
    if (targetSession.claudeSessionId) {
      connectTerminal('resume', targetSession.claudeSessionId)
    } else {
      connectTerminal('start')
    }
  } else {
    handleNewSession()
  }
})

onUnmounted(() => {
  isManualDisconnect = true
  terminalConnection?.close()
  onDataDisposable?.dispose()
  terminal.value?.dispose()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', debouncedFitTerminal)
  document.removeEventListener('keydown', handleKeyboard)
  stopDurationTimer()
})
</script>

<style scoped>
/* CSS 变量默认值（防止 JS 加载前页面空白） */
.claude-workspace {
  --ct-bg-level-1: #1a1a1a;
  --ct-bg-level-2: #1e1e1e;
  --ct-bg-level-3: #252526;
  --ct-bg-level-4: #404040;
  --ct-text-primary: #e0e0e0;
  --ct-text-secondary: #909399;
  --ct-text-muted: #666666;
  --ct-accent-primary: #409eff;
  --ct-accent-success: #67c23a;
  --ct-accent-warning: #e6a23c;
  --ct-accent-danger: #f56c6c;
  --ct-text-on-accent: #ffffff;
  --ct-text-on-accent-secondary: rgba(255, 255, 255, 0.8);
  --ct-success-light-bg: rgba(103, 194, 58, 0.1);
  --ct-border-hover: #505050;
  --ct-success-text-on-accent: #a5d6a7;
}

.claude-workspace {
  display: flex;
  height: calc(100vh - 120px);
  background: var(--ct-bg-level-1);
  gap: 16px;
  padding: 16px;
}
.terminal-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  background: var(--ct-bg-level-2);
  border-radius: 12px;
}
.terminal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--ct-bg-level-3);
  border-radius: 12px 12px 0 0;
  border-bottom: 1px solid var(--ct-bg-level-4);
}
.terminal-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ct-text-primary);
  font-weight: 500;
}

.working-directory {
  font-family: monospace;
  font-size: 12px;
  color: var(--ct-accent-success);
  font-weight: normal;
  padding: 2px 6px;
  background: var(--ct-success-light-bg);
  border-radius: 4px;
}
.terminal-container {
  flex: 1;
  padding: 8px;
  min-height: 300px;
  height: 0;
  background: var(--ct-bg-level-2);
  border-radius: 0 0 12px 12px;
}
</style>