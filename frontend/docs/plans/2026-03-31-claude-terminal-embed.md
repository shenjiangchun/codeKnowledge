# Claude CLI 全功能终端嵌入实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 Vue3 前端嵌入完整的 Claude CLI 终端，实现与本地终端完全一致的交互体验（包括 /plugin、/help 等所有命令）。

**Architecture:** 前端使用 @xterm/vue-next 终端模拟器，通过 WebSocket 与后端通信；后端使用 PTY4J 创建伪终端进程，启动本地 Claude CLI，转发输入输出流。

**Tech Stack:** Vue3 + TypeScript + @xterm/vue-next + WebSocket + Spring Boot + PTY4J

---

## Task 1: 创建 Git 分支

**关键要点：前后端都必须在新分支上开发！**

### Step 1: 后端创建新分支

```bash
cd hisi-dev-tool
git checkout release_2.0
git pull origin release_2.0
git checkout -b feature/claude-terminal-embed
git push -u origin feature/claude-terminal-embed
```

### Step 2: 前端创建新分支

```bash
cd hisi-dev-tool-frontend
git checkout master
git pull origin master
git checkout -b feature/claude-terminal-embed
git push -u origin feature/claude-terminal-embed
```

### Step 3: 验证分支

```bash
# 后端
cd hisi-dev-tool && git branch --show-current
# Expected: feature/claude-terminal-embed

# 前端
cd hisi-dev-tool-frontend && git branch --show-current
# Expected: feature/claude-terminal-embed
```

---

## Task 2: 后端 - 添加 PTY4J 和 WebSocket 依赖

**Files:**
- Modify: `hisi-dev-tool/pom.xml`

**Step 1: 添加 PTY4J 依赖**

在 `pom.xml` 的 `<dependencies>` 标签内添加：

```xml
<!-- PTY4J：创建伪终端会话，对接本地 Claude CLI 进程 -->
<dependency>
    <groupId>org.jetbrains.pty4j</groupId>
    <artifactId>pty4j</artifactId>
    <version>0.12.0</version>
</dependency>

<!-- WebSocket 支持 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

**Step 2: 验证依赖下载**

```bash
cd hisi-dev-tool && mvn dependency:resolve -q | grep -E "pty4j|websocket" || echo "Dependencies resolved"
```

Expected: 无错误输出

**Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add PTY4J and WebSocket dependencies for terminal emulation"
```

---

## Task 3: 后端 - 创建 WebSocket 配置类

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/config/WebSocketConfig.java`

**Step 1: 创建 WebSocket 配置类**

```java
package com.huawei.hisi.config;

import com.huawei.hisi.handler.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import lombok.RequiredArgsConstructor;

/**
 * WebSocket 配置类
 * 用于终端实时通信
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册终端 WebSocket 处理器
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal")
                .setAllowedOrigins("*");
    }
}
```

**Step 2: 验证编译（预期失败，TerminalWebSocketHandler 不存在）**

```bash
cd hisi-dev-tool && mvn compile -q 2>&1 | grep -E "ERROR|error" || echo "Compilation successful"
```

Expected: 编译失败（TerminalWebSocketHandler 不存在），这是预期的

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/config/WebSocketConfig.java
git commit -m "feat: add WebSocket configuration for terminal endpoint"
```

---

## Task 4: 后端 - 创建终端 WebSocket 处理器

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/handler/TerminalWebSocketHandler.java`

**Step 1: 创建 WebSocket 处理器**

```java
package com.huawei.hisi.handler;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.pty4j.PtyProcess;
import org.jetbrains.pty4j.PtyProcessBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端 WebSocket 处理器
 * 管理 PTY 会话，转发终端输入输出
 */
@Slf4j
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    @Value("${claude.working-directory:${user.dir}}")
    private String defaultWorkingDirectory;

    @Value("${claude.claude-path:claude}")
    private String claudePath;

    // 存储 WebSocket 会话与对应的 PTY 进程
    private final Map<WebSocketSession, PtyProcess> ptyProcessMap = new ConcurrentHashMap<>();

    /**
     * WebSocket 连接建立后：创建 PTY 会话，启动 Claude CLI 进程
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        log.info("Terminal WebSocket connection established: {}", session.getId());

        try {
            // 配置 Claude CLI 进程启动命令
            String[] command;
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("win")) {
                // Windows 系统
                command = new String[]{"cmd.exe", "/c", claudePath};
            } else {
                // Linux/Mac 系统
                command = new String[]{claudePath};
            }

            PtyProcessBuilder builder = new PtyProcessBuilder(command);

            // 配置 PTY 会话
            builder.setDirectory(new File(defaultWorkingDirectory));

            // 设置环境变量
            Map<String, String> env = new java.util.HashMap<>(System.getenv());
            builder.setEnvironment(env);

            // 设置终端窗口大小
            builder.setInitialColumns(120);
            builder.setInitialRows(30);

            // 创建 PTY 进程
            PtyProcess ptyProcess = builder.start();
            ptyProcessMap.put(session, ptyProcess);

            log.info("Claude CLI process started for session: {}", session.getId());

            // 监听 PTY 标准输出流
            InputStream inputStream = ptyProcess.getInputStream();
            Thread outputThread = new Thread(() -> {
                byte[] buffer = new byte[4096];
                int len;
                try {
                    while ((len = inputStream.read(buffer)) != -1) {
                        String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(output));
                        }
                    }
                } catch (IOException e) {
                    log.debug("Output stream closed for session: {}", session.getId());
                } finally {
                    cleanupProcess(session);
                }
            }, "pty-output-" + session.getId());
            outputThread.setDaemon(true);
            outputThread.start();

            // 监听 PTY 标准错误流
            InputStream errorStream = ptyProcess.getErrorStream();
            Thread errorThread = new Thread(() -> {
                byte[] buffer = new byte[4096];
                int len;
                try {
                    while ((len = errorStream.read(buffer)) != -1) {
                        String errorOutput = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(errorOutput));
                        }
                    }
                } catch (IOException e) {
                    log.debug("Error stream closed for session: {}", session.getId());
                }
            }, "pty-error-" + session.getId());
            errorThread.setDaemon(true);
            errorThread.start();

        } catch (Exception e) {
            log.error("Failed to start Claude CLI process: {}", e.getMessage(), e);
            session.sendMessage(new TextMessage("\r\n\033[31mError: Failed to start Claude CLI: " + e.getMessage() + "\033[0m\r\n"));
            session.close();
        }
    }

    /**
     * 接收前端终端输入：转发到 PTY 进程
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        PtyProcess ptyProcess = ptyProcessMap.get(session);
        if (ptyProcess == null || !session.isOpen()) {
            return;
        }

        try {
            OutputStream outputStream = ptyProcess.getOutputStream();
            outputStream.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException e) {
            log.error("Failed to write to PTY: {}", e.getMessage());
            session.sendMessage(new TextMessage("\r\n\033[31mError: Terminal write failed\033[0m\r\n"));
        }
    }

    /**
     * WebSocket 连接关闭：销毁 PTY 进程
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);
        log.info("Terminal WebSocket connection closed: {}, status: {}", session.getId(), status);
        cleanupProcess(session);
    }

    /**
     * WebSocket 传输错误
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        super.handleTransportError(session, exception);
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        cleanupProcess(session);
    }

    /**
     * 清理 PTY 进程资源
     */
    private void cleanupProcess(WebSocketSession session) {
        PtyProcess ptyProcess = ptyProcessMap.remove(session);
        if (ptyProcess != null) {
            try {
                ptyProcess.destroy();
                log.info("PTY process destroyed for session: {}", session.getId());
            } catch (Exception e) {
                log.warn("Error destroying PTY process: {}", e.getMessage());
            }
        }
    }
}
```

**Step 2: 验证编译**

```bash
cd hisi-dev-tool && mvn compile -q 2>&1 | tail -20
```

Expected: 编译成功，无错误

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/handler/TerminalWebSocketHandler.java
git commit -m "feat: add TerminalWebSocketHandler for PTY process management"
```

---

## Task 5: 后端 - 更新 application.yml 配置

**Files:**
- Modify: `hisi-dev-tool/src/main/resources/application.yml`

**Step 1: 确认 Claude 路径配置存在**

确保 `application.yml` 的 `claude:` 部分包含 `claude-path` 配置：

```yaml
claude:
  working-directory: ${user.dir}
  model: glm-5
  permission-mode: dangerously-skip-permissions
  timeout: 10m
  max-tokens: 4096
  claude-path: C:\Users\47583\AppData\Roaming\npm\claude.cmd
  system-prompt: |
    你是一个专业的日志分析和代码诊断专家。
  prompts-dir: classpath:prompts/
```

**Step 2: Commit（如有修改）**

```bash
git add src/main/resources/application.yml
git commit -m "chore: update Claude CLI path configuration"
```

---

## Task 6: 前端 - 安装 @xterm/vue-next 依赖

**Files:**
- Modify: `hisi-dev-tool-frontend/package.json`

**Step 1: 安装 xterm 依赖**

```bash
cd hisi-dev-tool-frontend && npm install @xterm/vue-next xterm @xterm/addon-fit @xterm/addon-web-links
```

Expected: 安装成功

**Step 2: 验证安装**

```bash
cd hisi-dev-tool-frontend && npm list @xterm/vue-next xterm 2>/dev/null | head -5
```

Expected: 显示安装的版本号

**Step 3: Commit**

```bash
git add package.json package-lock.json && git commit -m "feat: add @xterm/vue-next and addons for terminal emulation"
```

---

## Task 7: 前端 - 创建终端类型定义

**Files:**
- Create: `hisi-dev-tool-frontend/src/types/terminal.ts`

**Step 1: 创建类型定义文件**

```typescript
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
```

**Step 2: Commit**

```bash
git add src/types/terminal.ts && git commit -m "feat: add terminal type definitions"
```

---

## Task 8: 前端 - 创建终端 API 模块

**Files:**
- Create: `hisi-dev-tool-frontend/src/api/terminal.ts`

**Step 1: 创建 WebSocket 终端 API**

```typescript
import type { TerminalConnectionStatus } from '@/types/terminal'

export interface TerminalCallbacks {
  onOpen?: () => void
  onClose?: () => void
  onError?: (error: string) => void
  onData?: (data: string) => void
  onStatusChange?: (status: TerminalConnectionStatus) => void
}

export function createTerminalConnection(callbacks: TerminalCallbacks): {
  send: (data: string) => void
  close: () => void
  getStatus: () => TerminalConnectionStatus
} {
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsHost = window.location.host
  const wsUrl = `${wsProtocol}//${wsHost}/ws/terminal`

  let status: TerminalConnectionStatus = 'disconnected'
  let socket: WebSocket | null = null

  const updateStatus = (newStatus: TerminalConnectionStatus) => {
    status = newStatus
    callbacks.onStatusChange?.(newStatus)
  }

  const connect = () => {
    updateStatus('connecting')

    try {
      socket = new WebSocket(wsUrl)

      socket.onopen = () => {
        updateStatus('connected')
        callbacks.onOpen?.()
      }

      socket.onclose = () => {
        updateStatus('disconnected')
        callbacks.onClose?.()
      }

      socket.onerror = () => {
        updateStatus('error')
        callbacks.onError?.('WebSocket connection error')
      }

      socket.onmessage = (event) => {
        if (typeof event.data === 'string') {
          callbacks.onData?.(event.data)
        }
      }
    } catch (error) {
      updateStatus('error')
      callbacks.onError?.(error instanceof Error ? error.message : 'Connection failed')
    }
  }

  const send = (data: string) => {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(data)
    }
  }

  const close = () => {
    if (socket) {
      socket.close()
      socket = null
    }
    updateStatus('disconnected')
  }

  const getStatus = () => status

  connect()

  return { send, close, getStatus }
}
```

**Step 2: Commit**

```bash
git add src/api/terminal.ts && git commit -m "feat: add terminal WebSocket API module"
```

---

## Task 9: 前端 - 创建 ClaudeTerminal 主组件

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/claude-terminal/ClaudeTerminal.vue`

**Step 1: 创建目录**

```bash
mkdir -p src/views/claude-terminal
```

**Step 2: 创建终端组件**

```vue
<template>
  <div class="claude-terminal-page">
    <div class="terminal-wrapper" ref="terminalWrapperRef">
      <div class="terminal-header">
        <div class="terminal-title">
          <el-icon><Monitor /></el-icon>
          <span>Claude CLI Terminal</span>
        </div>
        <div class="terminal-status">
          <el-tag :type="statusTagType" size="small">{{ statusText }}</el-tag>
          <el-button-group size="small">
            <el-button @click="handleReconnect" :disabled="connectionStatus === 'connected'">
              <el-icon><RefreshRight /></el-icon>重连
            </el-button>
            <el-button @click="handleClear">
              <el-icon><Delete /></el-icon>清屏
            </el-button>
          </el-button-group>
        </div>
      </div>
      <div class="terminal-container" ref="terminalContainerRef">
        <Xterm ref="terminalRef" :options="terminalOptions" @data="handleTerminalData" />
      </div>
    </div>
    <div class="quick-actions">
      <span class="actions-label">快捷命令：</span>
      <el-button v-for="action in quickActions" :key="action.command" size="small" @click="executeCommand(action.command)">
        {{ action.label }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Xterm } from '@xterm/vue-next'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import type { Terminal } from 'xterm'
import type { ITerminalOptions } from 'xterm'
import 'xterm/css/xterm.css'
import { Monitor, RefreshRight, Delete } from '@element-plus/icons-vue'
import { createTerminalConnection } from '@/api/terminal'
import type { TerminalConnectionStatus } from '@/types/terminal'
import { ElMessage } from 'element-plus'

const terminalContainerRef = ref<HTMLElement | null>(null)
const terminalRef = ref<{ terminal: Terminal } | null>(null)
const fitAddon = ref<FitAddon | null>(null)
const connectionStatus = ref<TerminalConnectionStatus>('disconnected')
let terminalConnection: ReturnType<typeof createTerminalConnection> | null = null

const terminalOptions: ITerminalOptions = {
  cursorBlink: true,
  cursorStyle: 'block',
  convertEol: true,
  scrollback: 5000,
  theme: {
    foreground: '#ECECEC',
    background: '#1E1E1E',
    cursor: '#FFFFFF',
    selection: 'rgba(100, 100, 100, 0.5)',
  },
  fontFamily: '"JetBrains Mono", "Fira Code", "Consolas", monospace',
  fontSize: 14,
  lineHeight: 1.4,
  allowTransparency: true,
}

const quickActions = [
  { label: '/help', command: '/help' },
  { label: '/plugin', command: '/plugin' },
  { label: '/config', command: '/config' },
  { label: '/clear', command: '/clear' },
]

const statusText = computed(() => {
  switch (connectionStatus.value) {
    case 'connected': return '已连接'
    case 'connecting': return '连接中...'
    case 'disconnected': return '已断开'
    case 'error': return '连接错误'
    default: return '未知'
  }
})

const statusTagType = computed(() => {
  switch (connectionStatus.value) {
    case 'connected': return 'success'
    case 'connecting': return 'warning'
    case 'disconnected': return 'info'
    case 'error': return 'danger'
    default: return 'info'
  }
})

function initTerminal() {
  if (!terminalRef.value) return
  const terminal = terminalRef.value.terminal
  fitAddon.value = new FitAddon()
  terminal.loadAddon(fitAddon.value)
  terminal.loadAddon(new WebLinksAddon())
  fitTerminal()

  terminalConnection = createTerminalConnection({
    onOpen: () => ElMessage.success('终端连接成功'),
    onClose: () => ElMessage.warning('终端连接已断开'),
    onError: (error) => ElMessage.error(`终端连接错误: ${error}`),
    onData: (data) => terminal.write(data),
    onStatusChange: (status) => { connectionStatus.value = status },
  })
}

function handleTerminalData(data: string) {
  terminalConnection?.send(data)
}

function executeCommand(command: string) {
  terminalConnection?.send(command + '\r')
}

function handleReconnect() {
  terminalConnection?.close()
  initTerminal()
}

function handleClear() {
  terminalRef.value?.terminal.clear()
}

function fitTerminal() {
  fitAddon.value?.fit()
}

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  initTerminal()
  if (terminalContainerRef.value) {
    resizeObserver = new ResizeObserver(fitTerminal)
    resizeObserver.observe(terminalContainerRef.value)
  }
  window.addEventListener('resize', fitTerminal)
})

onUnmounted(() => {
  terminalConnection?.close()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', fitTerminal)
})
</script>

<style scoped>
.claude-terminal-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #1a1a1a;
  padding: 16px;
}
.terminal-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  border-radius: 12px;
  overflow: hidden;
  background: #1E1E1E;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
}
.terminal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #2d2d2d;
  border-bottom: 1px solid #404040;
}
.terminal-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e0e0e0;
  font-weight: 500;
}
.terminal-status {
  display: flex;
  align-items: center;
  gap: 12px;
}
.terminal-container {
  flex: 1;
  padding: 8px;
  min-height: 400px;
}
.quick-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #2d2d2d;
  border-radius: 8px;
  margin-top: 12px;
}
.actions-label {
  color: #909399;
  font-size: 13px;
}
</style>
```

**Step 3: Commit**

```bash
git add src/views/claude-terminal/ && git commit -m "feat: add ClaudeTerminal component with xterm integration"
```

---

## Task 10: 前端 - 添加路由配置

**Files:**
- Modify: `hisi-dev-tool-frontend/src/router/index.ts`

**Step 1: 添加终端路由**

在路由配置数组中添加：

```typescript
{
  path: '/claude-terminal',
  name: 'ClaudeTerminal',
  component: () => import('@/views/claude-terminal/ClaudeTerminal.vue'),
  meta: { title: 'Claude 终端' }
}
```

**Step 2: Commit**

```bash
git add src/router/index.ts && git commit -m "feat: add Claude terminal route"
```

---

## Task 11: 前端 - 添加导航菜单

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`

**Step 1: 添加终端菜单项**

在侧边栏菜单配置中添加：

```typescript
{
  index: '/claude-terminal',
  title: 'Claude 终端',
  icon: Monitor,
  menuKey: 'claude-terminal'
}
```

**Step 2: Commit**

```bash
git add src/components/layout/AppSidebar.vue && git commit -m "feat: add Claude terminal navigation menu"
```

---

## Task 12: 集成测试

**Step 1: 启动后端服务**

```bash
cd hisi-dev-tool && mvn spring-boot:run
```

**Step 2: 启动前端服务**

```bash
cd hisi-dev-tool-frontend && npm run dev
```

**Step 3: 验证功能**

1. 访问 `http://localhost:5173/claude-terminal`
2. 检查终端是否显示 Claude CLI 欢迎信息
3. 输入 `/help` 验证命令响应
4. 输入 `/plugin` 验证插件管理界面

---

## Task 13: 推送所有更改

**Step 1: 检查并推送后端**

```bash
cd hisi-dev-tool
git status
git push origin feature/claude-terminal-embed
```

**Step 2: 检查并推送前端**

```bash
cd hisi-dev-tool-frontend
git status
git push origin feature/claude-terminal-embed
```

---

## 验证清单

- [ ] 后端分支 `feature/claude-terminal-embed` 创建成功
- [ ] 前端分支 `feature/claude-terminal-embed` 创建成功
- [ ] 后端 WebSocket 端点 `/ws/terminal` 可访问
- [ ] 后端 PTY 进程成功启动 Claude CLI
- [ ] 前端终端显示 Claude CLI 输出
- [ ] `/plugin` 命令显示插件管理界面
- [ ] `/help` 命令显示帮助信息
- [ ] ANSI 转义码正确渲染
- [ ] 终端自适应窗口大小