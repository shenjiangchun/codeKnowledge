# 数据流程

本章描述前端的端到端数据流、流式协议时序与状态机。

---

## 1. 数据流总览

| # | 流程 | 触发 | 协议 | 关键模块 |
|---|------|------|------|---------|
| 1 | 项目目录配置 → 选择项目 | 用户表单 | REST | `appStore` + `configApi` + `projectApi` |
| 2 | 知识图谱生成 + 向量生成 | 用户点击 | REST(轮询状态) | `knowledgeGraphApi` + `vectorGenerationApi` |
| 3 | 语义检索 | 用户输入 | REST | `vectorSearchApi` |
| 4 | 调用链查询 | 用户点击节点 | REST | `knowledgeGraphApi` 调用链族 |
| 5 | 日志查询 → Claude 流式分析 | 用户操作 | REST + SSE(EventSource) | `logAnalysisApi` + `claudeApi.streamAnalyze` |
| 6 | 自然语言对话(意图+流式) | 用户提问 | fetch + ReadableStream | `naturalLanguageApi.streamProcess` |
| 7 | 多 Agent 诊断 | 用户提问 | WebSocket `/ws/diagnosis` | `useDiagnosis` |
| 8 | Claude 终端 | 用户输入 | WebSocket `/ws/terminal` | `terminal.ts` + xterm.js |
| 9 | MCP 安装 | 用户点击 | SSE | `mcpApi.install` |
| 10 | Skill 安装 / Git 同步 | 用户操作 | REST | `skillMarketApi` / `gitApi` |

---

## 2. 流程 1:项目目录配置

```mermaid
sequenceDiagram
    participant U as 用户
    participant V as ProjectList
    participant Store as appStore
    participant Conf as configApi
    participant Proj as projectApi

    U->>V: 打开页面
    V->>Store: loadProjectDir
    Store->>Conf: GET /config/project-dir
    Conf-->>Store: { path }
    U->>V: 配置目录
    V->>Store: updateProjectDir(path)
    Store->>Conf: POST /config/project-dir
    V->>Proj: scanGitRepos(path)
    Proj-->>V: GitRepositoryInfo[]
    U->>V: 勾选项目
    V->>Store: selectProjects(items)
    Store->>Conf: POST /config/selected-project
```

---

## 3. 流程 2:知识图谱 + 向量生成

```mermaid
flowchart TD
    Start(["点击生成"]) --> KG["knowledgeGraphApi.generate"]
    KG --> Poll["轮询 getStatusBatch"]
    Poll --> KGDone{"KG 完成?"}
    KGDone -->|否| Poll
    KGDone -->|是| VG["vectorGenerationApi.start"]
    VG --> PollV["轮询 getStatusBatch"]
    PollV --> VGDone{"向量完成?"}
    VGDone -->|否| PollV
    VGDone -->|是| Ready(["可检索"])

    style Start fill:#1565c0,color:#fff
    style Ready fill:#2e7d32,color:#fff
```

---

## 4. 流程 5:日志 → Claude 流式分析(SSE)

```mermaid
sequenceDiagram
    participant U as 用户
    participant LQ as LogQuery
    participant LApi as logAnalysisApi
    participant CApi as claudeApi
    participant ES as EventSource

    U->>LQ: 关键字+级别+时间 → 查询
    LQ->>LApi: queryLogs
    LApi-->>LQ: LogEntry[]
    U->>LQ: 选中条目 → 一键分析
    LQ->>LApi: analyze → reportId
    LQ->>CApi: streamAnalyze(reportId, callbacks)
    CApi->>ES: new EventSource('/api/claude/stream?reportId=...')
    loop 流式
        ES-->>LQ: event:delta data:{chunk}
        LQ->>LQ: 拼接到 streamingContent
    end
    ES-->>LQ: event:done
    CApi->>ES: close
    LQ->>LApi: getReport(reportId)
    LApi-->>LQ: DetailedAnalysisReport
```

---

## 5. 流程 6:自然语言对话(fetch ReadableStream)

```mermaid
sequenceDiagram
    participant U as 用户
    participant Store as naturalLanguageStore
    participant API as naturalLanguageApi
    participant F as fetch

    U->>Store: processInput(text)
    Store->>API: streamProcess(req, callbacks)
    API->>F: POST /api/dialog/.../stream
    F-->>API: Response.body Reader
    loop 解析 SSE 帧
        API-->>Store: event:intent → IntentResult
        API-->>Store: event:output → 增量
        API-->>Store: event:progress → 进度
    end
    API-->>Store: event:done
    Store->>Store: 写入 messagesCache
```

---

## 6. 流程 7-8:WebSocket 状态机

### 6.1 `/ws/terminal`

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Connecting: createTerminalConnection
    Connecting --> Open: ws.onopen
    Open --> Ready: server "ready"
    Ready --> ClaudeReady: server "claude_ready"
    ClaudeReady --> Streaming: action: input
    Streaming --> ClaudeReady: server "output" 完毕
    ClaudeReady --> Closed: ws.close
    Open --> Closed: ws.close
    Closed --> Connecting: 自动重连
    Open --> Open: ping/pong 30s
```

### 6.2 `/ws/dialog`

```mermaid
stateDiagram-v2
    [*] --> Connecting: connect()
    Connecting --> Connected: CONNECTED
    Connected --> Recognizing: sendMessage
    Recognizing --> Streaming: INTENT_RECOGNIZED + STREAM_START
    Streaming --> Streaming: STREAM_DELTA*
    Streaming --> Done: STREAM_DONE / FINAL_RESULT
    Done --> Recognizing: 下一轮
    Connected --> Reconnecting: ws.close (异常)
    Reconnecting --> Connecting: 3s 后重试(最多 5 次)
```

---

## 7. 数据校验与一致性

| 数据 | 校验位置 | 失败行为 |
|------|---------|---------|
| 表单输入 | Element Form rules | UI 红字 |
| API 入参 | 后端 400 | 拦截器解析 → ElMessage.warning |
| 项目路径 | `pathUtils.normalizePath` | 强制正斜杠 |
| 流式序号 | 拼接顺序由后端保证 | — |

---

## 8. 异常处理矩阵

| 异常 | 来源 | 处理 |
|------|------|------|
| 后端未启动 | `/api/health` 失败 | 路由守卫放行,业务页弹"网络连接失败" |
| 业务码非 200 | 拦截器 | reject `BusinessError`,组件 try/catch 自定义 |
| EventSource 失败 | onerror | 关闭 + 上抛 onError 回调 + ElMessage |
| WebSocket 异常断开 | onclose code≠1000 | 自动重连(终端/对话/诊断各自策略) |
| fetch 流读取错误 | reader.read() throw | catch 后关闭 reader + 上抛 |

---

> **延伸阅读**:[接口文档](../05-接口文档/index.md) · [Composables 与工具](../03-模块说明/Composables与工具.md)
