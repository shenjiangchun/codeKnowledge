# Claude 工作台设计文档

## 概述

将原有 ClaudeSession（API 调用方式）与 ClaudeTerminal（PTY 终端方式）合并为统一的 Claude 工作台页面，利用 Claude CLI 原生 session 管理能力，简化架构并提供完整的终端交互功能。

## 设计目标

1. **统一入口**：一个页面同时提供会话管理和终端交互
2. **利用原生能力**：使用 Claude CLI 的 `--resume` 恢复历史对话，无需后端存储消息
3. **保留完整功能**：支持 `/help`、`/plugin`、`/config` 等所有 CLI 命令
4. **场景化调用**：代码分析、影响分析等场景 = 创建会话 + 自动发送提示词

## 页面架构

```
┌─────────────────────────────────────────────────────────────┐
│  Claude 终端                                    [设置] [帮助]│
├─────────────────────────────────────────────────────────────┤
│ ┌─────────────┬───────────────────────────────────────────┐ │
│ │ 会话列表     │  终端面板                                  │ │
│ │ (可折叠)    │  ┌───────────────────────────────────────┐│ │
│ │             │  │ 连接状态: [已连接] [重连] [清屏]       ││ │
│ │ [搜索框]    │  ├───────────────────────────────────────┤│ │
│ │ [+新建会话] │  │                                       ││ │
│ │             │  │  xterm.js 终端区域                    ││ │
│ │ ┌─进行中─┐ │  │  (WebSocket + PTY)                    ││ │
│ │ │ 会话1 ● │ │  │                                       ││ │
│ │ │ 会话2   │ │  │                                       ││ │
│ │ └─已归档─┘ │  ├───────────────────────────────────────┤│ │
│ │             │  │ 快捷命令: /help /plugin /config /clear││ │
│ │ [提示词配置]│  └───────────────────────────────────────┘│ │
│ └─────────────┴───────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## 路由变更

| 原路由 | 新状态 |
|--------|--------|
| `/claude-session` | **移除** |
| `/claude-terminal` | **保留**（或改名 `/claude-workspace`） |

**侧边栏菜单变更：**
- 移除 "Claude 会话" 菜单项
- 保留 "Claude 终端" 菜单项

## 数据模型

### 前端类型定义

```typescript
interface ClaudeWorkspaceSession {
  id: string                    // 系统会话 ID
  claudeSessionId: string       // Claude CLI 的 session_id（核心关联）
  title: string                 // 会话标题（可编辑）
  scene: string                 // 场景类型：'general' | 'code-analysis' | 'impact-analysis'
  status: 'active' | 'archived' // 会话状态
  createdAt: Date               // 创建时间
  updatedAt: Date               // 更新时间
  workingDirectory?: string     // 工作目录
  initialPrompt?: string        // 初始提示词（场景化调用时使用）
}
```

### 后端实体类

```java
@Entity
@Table(name = "claude_workspace_session")
public class ClaudeWorkspaceSession {
    @Id
    private String id;

    @Column(unique = true)
    private String claudeSessionId;   // Claude CLI session_id

    private String title;
    private String scene;
    private String status;            // 'active' | 'archived'
    private Date createdAt;
    private Date updatedAt;
    private String workingDirectory;
    private String initialPrompt;
}
```

**关键设计：**
- `claudeSessionId` 是核心，用于 `claude --resume` 恢复会话
- 消息历史由 Claude CLI 管理，后端不再存储

## WebSocket 消息协议

### 前端 → 后端

```typescript
interface TerminalClientMessage {
  action: 'start' | 'resume' | 'input' | 'resize'
  data?: string
  claudeSessionId?: string   // 仅 resume 时需要
  cols?: number              // 仅 resize 时需要
  rows?: number              // 仅 resize 时需要
}
```

**示例：**
```json
{ "action": "start" }
{ "action": "resume", "claudeSessionId": "abc123" }
{ "action": "input", "data": "/help\n" }
{ "action": "resize", "cols": 120, "rows": 30 }
```

### 后端 → 前端

```typescript
interface TerminalServerMessage {
  type: 'output' | 'session_info' | 'error' | 'ready'
  data?: string
  claudeSessionId?: string   // 仅 session_info 时需要
}
```

**示例：**
```json
{ "type": "output", "data": "Hello from Claude..." }
{ "type": "session_info", "claudeSessionId": "abc123" }
{ "type": "ready" }
{ "type": "error", "data": "Failed to start Claude CLI" }
```

## 核心流程

### 新建会话流程

```
1. 用户点击 [新建会话]
2. WebSocket 连接后端
3. 发送 { action: 'start' }
4. 后端启动 claude 命令（不带 --resume）
5. 后端发送 { type: 'ready' }
6. Claude CLI 输出，包含 session_id
7. 后端解析输出，发送 { type: 'session_info', claudeSessionId }
8. 前端捕获 session_id，调用 API 保存会话
9. 会话出现在左侧列表
```

### 恢复会话流程

```
1. 用户点击已有会话
2. 断开当前 WebSocket（如有）
3. 获取会话的 claudeSessionId
4. WebSocket 重新连接
5. 发送 { action: 'resume', claudeSessionId }
6. 后端启动 claude --resume <claudeSessionId>
7. 终端显示历史对话内容（Claude CLI 自动恢复）
```

### 场景化调用流程（代码分析）

```
1. 项目管理页点击 [提交分析]
2. 创建新会话（scene: 'code-analysis', initialPrompt: 生成的提示词）
3. 保存会话到后端
4. 导航到 Claude 终端页面
5. WebSocket 连接，发送 { action: 'start' }
6. 终端就绪后，自动发送 initialPrompt
7. Claude CLI 开始分析交互
```

## 后端 API 变化

### 移除的 API

| API | 说明 |
|-----|------|
| `/api/sessions/{id}/messages` | 消息历史由 CLI 管理 |
| `/api/claude/chat` | 改为 WebSocket |
| `/api/claude/universal-chat` | 改为终端 + 提示词 |

### 保留并简化的 API

```java
@RestController
@RequestMapping("/api/workspace-sessions")
public class WorkspaceSessionController {

    // 列表（支持按状态筛选）
    @GetMapping
    public ApiResponse<List<ClaudeWorkspaceSession>> list(String status);

    // 创建（可选带初始提示词）
    @PostMapping
    public ApiResponse<ClaudeWorkspaceSession> create(
        @RequestParam(required = false) String scene,
        @RequestParam(required = false) String initialPrompt
    );

    // 更新标题等元信息
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id, @RequestBody UpdateRequest req);

    // 删除
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id);

    // 归档
    @PostMapping("/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable String id);
}
```

## 前端组件结构

```
src/views/claude-terminal/
├── ClaudeTerminal.vue        # 重命名为 ClaudeWorkspace.vue
├── components/
│   ├── SessionList.vue       # 左侧会话列表（从 ClaudeSession 提取）
│   ├── TerminalPanel.vue     # 右侧终端面板（现有代码）
│   └── QuickActions.vue      # 快捷命令按钮
├── composables/
│   ├── useTerminal.ts        # 终端 WebSocket 连接逻辑
│   └── useSessionManager.ts  # 会话管理逻辑
└── types.ts                  # 类型定义
```

## 依赖

### 前端
- `@xterm/xterm` - 终端模拟器
- `@xterm/addon-fit` - 自适应尺寸
- `@xterm/addon-web-links` - 链接点击

### 后端
- `PTY4J 0.13.10` - 伪终端进程管理
- `spring-boot-starter-websocket` - WebSocket 支持

## 前提条件

用户机器上需安装 Claude CLI：
```bash
# 验证安装
claude --version
```

## 迁移策略

1. **保留终端功能**：现有 ClaudeTerminal.vue 的 PTY 连接逻辑不变
2. **提取会话列表**：从 ClaudeSession.vue 提取左侧面板组件
3. **整合组件**：合并为 ClaudeWorkspace.vue
4. **简化后端**：移除消息存储，简化 SessionController
5. **更新路由**：移除 `/claude-session`，更新菜单项