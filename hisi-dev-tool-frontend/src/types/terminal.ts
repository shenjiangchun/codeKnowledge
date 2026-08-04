/**
 * 终端相关类型定义
 */

export interface TerminalOptions {
  fontSize?: number
  fontFamily?: string
  theme?: TerminalTheme
  cursorBlink?: boolean
  cursorStyle?: 'block' | 'underline' | 'bar'
  scrollback?: number
}

export interface TerminalTheme {
  foreground?: string
  background?: string
  cursor?: string
  cursorAccent?: string
  selection?: string
  black?: string
  red?: string
  green?: string
  yellow?: string
  blue?: string
  magenta?: string
  cyan?: string
  white?: string
  brightBlack?: string
  brightRed?: string
  brightGreen?: string
  brightYellow?: string
  brightBlue?: string
  brightMagenta?: string
  brightCyan?: string
  brightWhite?: string
}

export type TerminalConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error'

export interface TerminalSession {
  id: string
  workingDirectory: string
  connectedAt: Date
  status: TerminalConnectionStatus
}

// Claude Workspace Session
export interface ClaudeWorkspaceSession {
  id: string
  claudeSessionId: string
  title: string
  scene: string
  status: 'active' | 'archived'
  createdAt: Date
  updatedAt: Date
  workingDirectory?: string
  initialPrompt?: string
}

// WebSocket Client → Server Messages
export interface TerminalClientMessage {
  action: 'start' | 'resume' | 'continue' | 'input' | 'resize' | 'ping'
  data?: string
  claudeSessionId?: string
  cols?: number
  rows?: number
  workingDirectory?: string
  initialPrompt?: string  // 新增：启动时附带的问题
}

// WebSocket Server → Client Messages
export interface TerminalServerMessage {
  type: 'output' | 'session_info' | 'error' | 'ready' | 'claude_ready' | 'pong'
  data?: string
  claudeSessionId?: string
}