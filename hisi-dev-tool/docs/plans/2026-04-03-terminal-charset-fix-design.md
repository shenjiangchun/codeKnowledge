# Web Terminal 字符错乱修复设计方案

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:writing-plans to implement this design.

## 问题概述

**现象**：Claude CLI 交互时终端字符错乱（光标错位、文本重叠、ANSI 码明文显示），普通命令正常。

**技术栈**：xterm.js + Vue3 + PTY4J + Claude CLI

## 根因分析

根据技术文档，问题核心原因：

| 问题类型 | 当前状态 | 要求状态 |
|---------|---------|---------|
| PTY 终端类型 | 未设置（默认 vt100） | `TERM=xterm-256color` |
| PTY 编码环境 | 未设置 | `LANG/LC_ALL=en_US.UTF-8` |
| Windows ANSI 支持 | 未启用 | `setWindowsAnsiColorEnabled(true)` |
| 前端 resize | 无防抖 | 50ms debounce |
| 前端 Unicode | 未配置 | `unicodeVersion: '11.0'` |

---

## 设计方案

### 一、后端 PTY4J 配置修复

**文件**：`TerminalWebSocketHandler.java`

**修改内容**：

```java
private void startClaudeProcess(...) {
    // ... 现有代码 ...

    Map<String, String> env = new HashMap<>(System.getenv());

    // 核心修复：设置终端类型和编码
    env.put("TERM", "xterm-256color");
    env.put("LANG", "en_US.UTF-8");
    env.put("LC_ALL", "en_US.UTF-8");

    // ... PATH 处理代码 ...

    builder.setEnvironment(env);

    // Windows 系统 ANSI 支持
    if (osName.contains("win")) {
        builder.setWindowsAnsiColorEnabled(true);
    }

    PtyProcess ptyProcess = builder.start();
}
```

**配置项说明**：

| 配置项 | 作用 |
|--------|------|
| `TERM=xterm-256color` | 告诉 Claude CLI 当前终端支持 256 色和完整 ANSI 转义码 |
| `LANG/LC_ALL=en_US.UTF-8` | 强制 UTF-8 编码，避免特殊字符乱码 |
| `setWindowsAnsiColorEnabled(true)` | Windows 下启用 ANSI 颜色支持 |

### 二、前端 xterm.js 配置修复

**文件**：`ClaudeTerminal.vue`

**修改内容**：

1. 安装防抖依赖：
```bash
npm install lodash-es
npm install -D @types/lodash-es
```

2. 添加导入：
```typescript
import { debounce } from 'lodash-es'
```

3. 终端配置修改：
```typescript
terminal = new Terminal({
  // ... 现有配置 ...
  windowsMode: false,  // 关闭 Windows 模式（会破坏交互式 UI）
})
```

4. resize 添加防抖：
```typescript
const debouncedFitTerminal = debounce(() => {
  if (!fitAddon || !terminal) return
  try {
    fitAddon.fit()
    if (terminalConnection) {
      terminalConnection.send({ action: 'resize', cols: terminal.cols, rows: terminal.rows })
    }
  } catch (e) {}
}, 50)  // 50ms 防抖

// 使用防抖版本
window.addEventListener('resize', debouncedFitTerminal)
```

### 三、WebSocket 数据传输修复

**文件**：`terminal.ts`

**修改内容**：

```typescript
socket.onmessage = (event: MessageEvent) => {
  try {
    let data: string
    if (event.data instanceof ArrayBuffer) {
      const decoder = new TextDecoder('utf-8')
      data = decoder.decode(event.data)
    } else {
      data = event.data
    }

    const msg: TerminalServerMessage = JSON.parse(data)
    // ... 处理消息 ...
  } catch (e) {
    // ... 错误处理 ...
  }
}
```

### 四、可选优化

如果完整修复后仍有问题，可添加 Claude CLI 参数：

```java
String[] command = {"claude", "--no-color"};
```

---

## 验证清单

| 序号 | 验证项 | 验证方法 | 预期结果 |
|------|--------|----------|----------|
| 1 | PTY 终端类型 | 后端日志查看 | `xterm-256color` |
| 2 | Claude 启动输出 | 观察欢迎界面 | 颜色正常、边框无错乱 |
| 3 | 中文显示 | 输入含中文的问题 | 中文正常显示 |
| 4 | resize 测试 | 调整窗口大小 | 光标位置正确 |
| 5 | 长时间交互 | 连续多轮对话 | 无累积性错乱 |

---

## 文件修改清单

| 文件 | 修改类型 |
|------|----------|
| `hisi-dev-tool/src/main/java/.../TerminalWebSocketHandler.java` | 后端 PTY 配置 |
| `hisi-dev-tool-frontend/src/views/claude-terminal/ClaudeTerminal.vue` | 前端终端配置 |
| `hisi-dev-tool-frontend/src/api/terminal.ts` | WebSocket 数据解码 |
| `hisi-dev-tool-frontend/package.json` | 新增 lodash-es 依赖 |