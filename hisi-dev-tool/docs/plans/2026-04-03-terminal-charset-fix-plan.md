# Web Terminal 字符错乱修复实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复 Claude CLI 在 Web Terminal 中的字符错乱问题，实现 ANSI 转义码正确解析和 UTF-8 编码统一。

**Architecture:** 从后端 PTY 环境变量、前端 xterm.js 配置、WebSocket 数据传输三个层次进行端到端修复。

**Tech Stack:** Java 17 + PTY4J + Vue 3 + xterm.js + TypeScript

---

## Task 1: 后端 PTY4J 环境变量配置

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/handler/TerminalWebSocketHandler.java`

**Step 1: 添加 TERM 环境变量**

在 `startClaudeProcess` 方法中，找到 `Map<String, String> env = new HashMap<>(System.getenv());` 这一行，在其后添加：

```java
// 核心修复：设置终端类型为 xterm-256color
env.put("TERM", "xterm-256color");
```

**Step 2: 添加 UTF-8 编码环境变量**

在 TERM 设置后添加：

```java
// 强制 UTF-8 编码
env.put("LANG", "en_US.UTF-8");
env.put("LC_ALL", "en_US.UTF-8");
```

**Step 3: 添加 Windows ANSI 支持**

在 `builder.setInitialRows(termRows);` 之后、`PtyProcess ptyProcess = builder.start();` 之前添加：

```java
// Windows 系统启用 ANSI 颜色支持
if (osName.contains("win")) {
    builder.setWindowsAnsiColorEnabled(true);
}
```

**Step 4: 同步修改 continueRecentSession 方法**

在 `continueRecentSession` 方法中，同样添加环境变量配置（复制 Step 1-2 的代码）。

**Step 5: 验证后端编译**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add src/main/java/com/huawei/hisi/handler/TerminalWebSocketHandler.java
git commit -m "fix: add PTY environment variables for ANSI support

- Set TERM=xterm-256color for full ANSI escape code support
- Set LANG/LC_ALL=en_US.UTF-8 for UTF-8 encoding
- Enable Windows ANSI color support"
```

---

## Task 2: 前端安装防抖依赖

**Files:**
- Modify: `hisi-dev-tool-frontend/package.json`

**Step 1: 安装 lodash-es**

Run: `cd hisi-dev-tool-frontend && npm install lodash-es && npm install -D @types/lodash-es`
Expected: 安装成功，package.json 更新

**Step 2: Commit**

```bash
git add package.json package-lock.json
git commit -m "chore: add lodash-es for debounce support"
```

---

## Task 3: 前端 xterm.js 配置修复

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/claude-terminal/ClaudeTerminal.vue`

**Step 1: 添加 lodash-es 导入**

在文件顶部的 import 区域添加：

```typescript
import { debounce } from 'lodash-es'
```

**Step 2: 添加 windowsMode 配置**

在 `initTerminal` 函数的 Terminal 配置中，找到 `lineHeight: 1.4,` 这一行，在其后添加：

```typescript
    windowsMode: false,  // 关闭 Windows 模式，避免破坏 ANSI 转义码
```

**Step 3: 创建防抖 fitTerminal 函数**

在现有的 `fitTerminal` 函数之后，添加防抖版本：

```typescript
// 防抖版本的 fitTerminal，避免频繁触发导致光标错位
const debouncedFitTerminal = debounce(() => {
  fitTerminal()
}, 50)
```

**Step 4: 更新 ResizeObserver 使用防抖版本**

找到 ResizeObserver 的回调：
```typescript
resizeObserver = new ResizeObserver(() => {
  fitTerminal()
})
```

改为：
```typescript
resizeObserver = new ResizeObserver(() => {
  debouncedFitTerminal()
})
```

**Step 5: 更新 window resize 监听器**

找到 `window.addEventListener('resize', fitTerminal)` 改为：
```typescript
window.addEventListener('resize', debouncedFitTerminal)
```

找到 `window.removeEventListener('resize', fitTerminal)` 改为：
```typescript
window.removeEventListener('resize', debouncedFitTerminal)
```

**Step 6: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: 构建成功，无错误

**Step 7: Commit**

```bash
git add src/views/claude-terminal/ClaudeTerminal.vue
git commit -m "fix: add debounce to terminal resize and windowsMode config

- Add 50ms debounce to resize handler to prevent cursor issues
- Set windowsMode: false for proper ANSI escape code handling"
```

---

## Task 4: WebSocket 数据传输修复

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/terminal.ts`

**Step 1: 添加 ArrayBuffer 处理逻辑**

在 `socket.onmessage` 回调中，找到现有的消息处理代码，修改为：

```typescript
socket.onmessage = (event: MessageEvent) => {
  try {
    let data: string
    // 处理二进制数据：使用 TextDecoder UTF-8 解码
    if (event.data instanceof ArrayBuffer) {
      const decoder = new TextDecoder('utf-8')
      data = decoder.decode(event.data)
    } else {
      data = event.data
    }

    const msg: TerminalServerMessage = JSON.parse(data)
    switch (msg.type) {
      case 'output':
        callbacks.onOutput?.(msg.data || '')
        break
      case 'session_info':
        callbacks.onSessionInfo?.(msg.claudeSessionId || '')
        break
      case 'ready':
        callbacks.onReady?.()
        break
      case 'claude_ready':
        callbacks.onClaudeReady?.()
        break
      case 'pong':
        break
      case 'error':
        callbacks.onError?.(msg.data || 'Unknown error')
        break
    }
  } catch (e) {
    if (typeof event.data === 'string') {
      callbacks.onOutput?.(event.data)
    }
  }
}
```

**Step 2: 验证前端编译**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: 构建成功，无错误

**Step 3: Commit**

```bash
git add src/api/terminal.ts
git commit -m "fix: add UTF-8 TextDecoder for WebSocket binary data

- Handle ArrayBuffer messages with proper UTF-8 decoding
- Prevents multi-byte character truncation in terminal output"
```

---

## Task 5: 集成测试验证

**Step 1: 重启后端服务**

Run: `cd hisi-dev-tool && mvn spring-boot:run`
Expected: 服务启动成功

**Step 2: 启动前端开发服务器**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Expected: 开发服务器启动

**Step 3: 功能验证清单**

| 验证项 | 操作 | 预期结果 |
|--------|------|----------|
| Claude 启动 | 访问终端页面，观察 Claude 启动 | 颜色正常、边框无错乱 |
| 中文输入 | 输入含中文的问题 | 中文正常显示 |
| 特殊字符 | 输入 emoji 或特殊字符 | 正常显示无乱码 |
| 窗口调整 | 调整浏览器窗口大小 | 光标位置正确、内容不错位 |
| 多轮对话 | 连续进行 3-5 轮对话 | 无累积性错乱 |

**Step 4: 最终 Commit（如果验证通过）**

```bash
git add docs/plans/2026-04-03-terminal-charset-fix-design.md
git add docs/plans/2026-04-03-terminal-charset-fix-plan.md
git commit -m "docs: add terminal charset fix design and implementation plan"
```

---

## 文件修改汇总

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `TerminalWebSocketHandler.java` | 修改 | PTY 环境变量配置 |
| `package.json` | 修改 | 新增 lodash-es 依赖 |
| `ClaudeTerminal.vue` | 修改 | 终端配置和防抖 |
| `terminal.ts` | 修改 | WebSocket 数据解码 |