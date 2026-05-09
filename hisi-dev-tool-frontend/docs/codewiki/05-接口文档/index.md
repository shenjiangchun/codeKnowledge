# 接口文档

> 本章列出前端调用的所有后端接口。前端通过 `/api` 代理(`vite.config.ts`)和 `/ws` 代理转发到 Spring Boot `:8080`。

---

## 1. 通用约定

### 1.1 响应包装

```json
{ "code": 200, "message": "ok", "data": { /* 业务数据 */ } }
```

- `code === 200` → 拦截器解包,业务方拿到 `data`
- `code !== 200` → reject `BusinessError(code, message)`

### 1.2 校验错误(HTTP 400)

```
message: "参数校验失败: field1: 不能为空; field2: 长度必须 >= 3"
```

前端 `parseValidationErrors` 解析为 `ValidationError[]`。

### 1.3 List 参数序列化

axios `paramsSerializer: { indexes: null }`,确保 `?projectPaths=a&projectPaths=b` 兼容 Spring `@RequestParam List<String>`。

---

## 2. REST API 清单

### 2.1 配置 `/api/config/*`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/config` | 全部配置 |
| POST | `/config` | 更新配置 |
| GET | `/config/project-dir` | 当前 PROJECT_DIR |
| POST | `/config/project-dir` | `{ path }` |
| GET | `/config/selected-project` | 当前选中项目 |
| POST | `/config/selected-project` | `SelectedProjectInfo[]` |

### 2.2 项目 `/api/projects/*`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/projects` | 列表 |
| POST | `/projects/clone` | git clone |
| POST | `/projects/pull` | git pull |
| DELETE | `/projects/:id` | 删除 |
| GET | `/projects/:id/status` | 状态 |
| POST | `/projects/scan-git` | 扫描 PROJECT_DIR 下 Git 仓库 |

### 2.3 知识图谱 `/api/knowledge-graph/*`

| 类别 | 端点 |
|------|------|
| 状态/生成 | `getStatus`、`getStatusBatch`、`generate`、`generateIncremental` |
| 调用链 | `getCalleesTree`、`getRootEntries`、`getEntryPoints`、`getCallChainByKey`、`getCallChainByType`、`getCallChainAffecting`、`getCallChainDownstream`、`getCallChainGraph`、`getCallChainCycles` |
| MyBatis | `scanMybatis`、`getMappers`、`getMapperSql`、`getSqlByStatement` |
| 桥接 | `getBridgesByType`、`getBridgesByMethod`、`getBridgesByEntry`、`getBridgeStats`、`getFeignChain`、`getMqChain` |
| 业务 | `generateBusinessFlow`、`generateUnitTest` |
| Git | `getGitStatus`、`refresh`、`crossServiceBuild` |

### 2.4 向量 `/api/vector-search` · `/api/vector-generation/*`

| 方法 | 路径 | Body |
|------|------|------|
| POST | `/vector-search` | `{ query, projectPath?, projectPaths?, limit, graphDepth?, language? }` |
| POST | `/vector-generation/start` | `{ projectPaths }` |
| GET | `/vector-generation/status` | — |
| GET | `/vector-generation/status/batch` | `?projectPaths=a&projectPaths=b` |

### 2.5 日志 `/api/log/*`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/log/query` | `LogQueryDto`(含 dslQuery/errorOnly/traceId) |
| POST | `/log/analyze` | `LogAnalyzeRequest` → `{ reportId }` |
| GET | `/log/reports` | 列表 |
| GET | `/log/reports/:id` | 详情 |
| GET | `/log/reports/:id/status` | 进度 |

### 2.6 Claude `/api/claude/*` 与会话 `/api/session/*`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET(SSE) | `/claude/stream?reportId=` | 流式诊断结果 |
| POST(fetch 流) | `/claude/chat/stream` | 流式对话 |
| POST(fetch 流) | `/claude/universal-chat` | 通用流式入口 |
| POST | `/claude/end-session` | 结束 → `claudeSessionCode` |
| POST | `/claude/resume-session` | 恢复 |
| GET | `/session` | 列表 |
| GET/PATCH/DELETE | `/session/:id` | CRUD |
| POST | `/session/:id/archive` / `export` / `clear-messages` | 操作 |

### 2.7 Workspace 会话 `/api/workspace-session/*`

CRUD + `POST /workspace-session/:id/bind-claude { claudeSessionId }`。

### 2.8 MCP `/api/mcp/*`

| 方法 | 路径 |
|------|------|
| GET | `/mcp` |
| POST(SSE) | `/mcp/install` |

SSE 事件:`step` / `info` / `success` / `warning` / `error` / `log` / `done`。

### 2.9 Skill `/api/skill/*`

| 方法 | 路径 |
|------|------|
| GET | `/skill/list` |
| GET | `/skill/project-status` |
| GET | `/skill/:id/detail` |
| POST | `/skill/install`、`/skill/uninstall` |
| GET | `/skill/check-updates` |
| POST | `/skill/update` |

### 2.10 Prompt `/api/prompt/*`

`list` / `get` / `update` / `render`(`#{var}` 替换) / `extract-variables`。

### 2.11 Git `/api/git/*`

`status` / `checkout` / `pull` / `logs` / `commits` / `commit/:hash/diff` / `update-all`。

### 2.12 Code Analysis `/api/code-analysis/*`

| 端点 | 协议 |
|------|------|
| `/code-analysis/commits` | REST |
| `/code-analysis/commit/:hash` | REST |
| `/code-analysis/analyze-stream` | SSE |
| `/code-analysis/chat` | SSE |

### 2.13 自然语言对话 `/api/dialog/*`

| 端点 | 协议 |
|------|------|
| `/dialog/sessions` (GET/POST) | REST |
| `/dialog/sessions/:id/messages/stream` | fetch ReadableStream(SSE 帧) |
| `/dialog/sessions/:id/intervene` | REST |
| `/dialog/sessions/:id/context` | REST |

### 2.14 运维 `/api/ops/*`

`/health`、`/impact-analysis`、`/generate-docs`、`/logs/download`。

### 2.15 任务 `/api/task/*`

`/task/start` / `/task/:id/status` / `/task/latest`。

---

## 3. WebSocket 协议

### 3.1 `/ws/terminal`

**客户端 → 服务端**(`TerminalClientMessage`):

| action | payload |
|--------|---------|
| `start` | `{ workingDir, sessionId? }` |
| `resume` | `{ claudeSessionCode }` |
| `continue` | `{ }` |
| `input` | `{ text }` |
| `resize` | `{ cols, rows }` |
| `ping` | `{ }`(每 30s) |

**服务端 → 客户端**(`TerminalServerMessage`):

| type | payload | 说明 |
|------|---------|------|
| `output` | `{ data }` | 终端原始输出 |
| `session_info` | `{ sessionId, workingDir }` | 会话信息 |
| `ready` | — | PTY 就绪 |
| `claude_ready` | `{ claudeSessionId }` | Claude 启动完成 |
| `pong` | — | 心跳响应 |
| `error` | `{ message, code }` | 异常 |

### 3.2 `/ws/dialog`

**事件类型**(`DialogEventType`):`CONNECTED` / `INTENT_RECOGNIZED` / `STREAM_START` / `STREAM_DELTA` / `STREAM_DONE` / `FINAL_RESULT` / `INTERVENTION_ACK` / `ERROR`。

载荷见 `types/dialog.ts`。心跳 30s,断线重连 3s/最多 5 次。

### 3.3 `/ws/diagnosis`

**Agent 事件**(`AgentEventType`):`REQUEST_RECEIVED` / `AGENT_START` / `AGENT_DELTA` / `AGENT_END` / `FINAL_RESULT`。

Agent 类型:`STACK_TRACE` / `CODE_CONTEXT` / `GIT_HISTORY` / `CONSENSUS`。

---

## 4. SSE 流式约定

帧格式:

```
event: <eventName>
data: <json>

```

前端两种消费方式:

| 方式 | 适用 | 实现 |
|------|------|------|
| `EventSource` | GET 流(`/api/claude/stream`) | 浏览器原生 |
| `fetch` + `ReadableStream` | POST 流(`/api/dialog/.../stream`、`/api/mcp/install`、`/api/code-analysis/analyze-stream`) | 手动按 `\n\n` 切帧 |

> **延伸阅读**:[数据模型](../06-数据模型/index.md)
