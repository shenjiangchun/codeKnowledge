# 04-数据流程

## 概述

本文档描述 HiSi DevTool Frontend 中核心功能的端到端数据流程，包括 RAM 需求分析、APM 调试、图谱浏览、合并分析等主要业务流程。

---

## RAM 需求分析流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant IP as InputPage
    participant DP as DraftPage
    participant GPP as GraphPreviewPage
    participant API as REST API
    participant SSE as SSE Stream
    participant RS as ramStore

    U->>IP: 输入需求原文
    U->>IP: 选择目标项目
    U->>IP: 点击"开始分析"
    IP->>API: POST /ram/sessions
    API-->>IP: { sessionId }
    IP->>DP: 跳转到 DraftPage

    DP->>API: GET /ram/sessions/:sid
    API-->>DP: 会话状态
    DP->>SSE: 创建 EventSource
    SSE-->>DP: SSE 事件流

    loop 实时事件
        SSE-->>DP: CHECKPOINT 事件
        DP->>DP: 更新节点状态
        DP->>DP: 渲染 Markdown
    end

    SSE-->>DP: CLARIFY_REQUIRED
    DP->>U: 显示澄清弹窗
    U->>DP: 提交答案
    DP->>API: POST /ram/sessions/:sid/clarify
    API-->>DP: 接受

    SSE-->>DP: HITL_REQ
    DP->>U: 显示确认弹窗
    U->>DP: 审批节点输出
    DP->>API: POST /ram/sessions/:sid/confirm
    API-->>DP: 接受

    SSE-->>DP: RUN_COMPLETED
    DP->>RS: setImpact(sid, payload)
    DP->>U: 显示"查看图谱"按钮

    U->>DP: 点击"查看图谱"
    DP->>GPP: 跳转到 GraphPreviewPage
    GPP->>RS: 读取 impact 数据
    GPP->>U: 渲染 ThreeRingGraph
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 输入阶段 | 需求原文、项目路径 | 创建 RAM 会话 | sessionId |
| 分析阶段 | SSE 事件流 | 解析事件、更新状态 | 节点状态、Markdown 内容 |
| 澄清阶段 | 用户答案 | 提交澄清 | 确认响应 |
| 确认阶段 | 用户审批 | 提交确认 | 确认响应 |
| 可视化阶段 | ImpactPayload | 渲染图表 | 三环影响图 |

---

## APM 调试流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant ADV as ApmDebugView
    participant EL as EntryList
    participant RE as RequestEditor
    participant BSF as BodySchemaForm
    participant API as REST API
    participant ER as ExecutionReport

    U->>ADV: 选择项目
    ADV->>API: GET /apm/entries
    API-->>ADV: 入口点列表
    ADV->>EL: 展示入口点

    U->>EL: 选择入口点
    EL->>API: GET /apm/schema/:entryId
    API-->>EL: DTO Schema
    EL->>RE: 传递 Schema

    RE->>BSF: 渲染表单
    U->>BSF: 填写参数
    BSF->>RE: 返回请求体

    U->>RE: 点击"执行"
    RE->>API: POST /apm/execute
    API-->>RE: 执行结果
    RE->>ER: 展示结果
    ER->>U: 显示响应
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 项目选择 | 项目路径 | 获取入口点列表 | EntryPoint[] |
| 入口选择 | 入口点 ID | 获取 DTO Schema | SchemaNode |
| 参数编辑 | Schema、用户输入 | 渲染表单、收集数据 | RequestBody |
| 请求执行 | 请求参数 | 发送 HTTP 请求 | ExecutionResult |
| 结果展示 | 执行结果 | 渲染报告 | 执行报告 |

---

## 图谱浏览流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant KGV as KnowledgeGraphView
    participant GET as GraphExplorerTab
    participant SST as SemanticSearchPanel
    participant API as REST API
    participant CCV as CallChainGraph

    U->>KGV: 打开知识图谱页面
    KGV->>API: 获取项目信息
    API-->>KGV: 项目配置

    U->>GET: 输入搜索关键词
    GET->>API: GET /search/methods
    API-->>GET: 方法列表
    GET->>U: 展示方法列表

    U->>GET: 选择方法
    GET->>API: GET /callchain/method/:id
    API-->>GET: 调用链数据
    GET->>CCV: 渲染调用链
    CCV->>U: 展示调用链图

    U->>SST: 输入自然语言查询
    SST->>API: POST /search/semantic
    API-->>SST: 搜索结果
    SST->>U: 展示结果列表
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 方法搜索 | 搜索关键词 | 调用搜索 API | MethodInfo[] |
| 调用链获取 | 方法 ID | 调用调用链 API | CallChain |
| 语义搜索 | 自然语言查询 | 调用语义搜索 API | SearchResult[] |
| 图谱渲染 | 调用链数据 | dagre 布局 + Vue Flow | 调用链图 |

---

## 合并分析流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant IP as InputPage
    participant DPP as DiffPreviewPage
    participant AP as AnalysisPage
    participant API as REST API
    participant SSE as SSE Stream

    U->>IP: 输入分支信息
    U->>IP: 点击"开始分析"
    IP->>API: POST /merge-analysis/start
    API-->>IP: { sessionId }
    IP->>DPP: 跳转到 Diff 预览

    DPP->>API: GET /merge-analysis/diff/:sessionId
    API-->>DPP: Diff 结果
    DPP->>U: 展示差异列表

    U->>DPP: 点击"继续分析"
    DPP->>AP: 跳转到分析结果

    AP->>SSE: 创建 EventSource
    SSE-->>AP: SSE 事件流

    loop 实时事件
        SSE-->>AP: PROGRESS 事件
        AP->>U: 更新进度条
    end

    SSE-->>AP: COMPLETED
    AP->>U: 展示分析报告
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 分支输入 | 源分支、目标分支 | 创建分析会话 | sessionId |
| Diff 预览 | 会话 ID | 获取 Diff 结果 | DiffResult |
| 影响分析 | SSE 事件流 | 解析事件、更新状态 | AnalysisResult |
| 报告展示 | 分析结果 | 渲染报告 | 分析报告 |

---

## 知识图谱构建流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant KGV as KnowledgeGraphView
    participant API as REST API
    participant WS as WebSocket

    U->>KGV: 选择项目
    KGV->>API: POST /knowledge-graph/scan
    API-->>KGV: 扫描任务 ID

    KGV->>WS: 订阅进度
    WS-->>KGV: 进度更新

    loop 扫描进度
        WS-->>KGV: SCAN_PROGRESS
        KGV->>U: 更新进度条
    end

    WS-->>KGV: SCAN_COMPLETED
    KGV->>API: POST /knowledge-graph/generate
    API-->>KGV: 生成任务 ID

    loop 生成进度
        WS-->>KGV: GENERATE_PROGRESS
        KGV->>U: 更新进度条
    end

    WS-->>KGV: GENERATE_COMPLETED
    KGV->>U: 展示图谱
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 项目扫描 | 项目路径 | 扫描代码文件 | 文件列表 |
| 图谱生成 | 文件列表 | 解析代码、构建图谱 | 知识图谱 |
| 向量生成 | 知识图谱 | 生成 embedding | 向量索引 |

---

## 语义搜索流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant SST as SemanticSearchPanel
    participant API as REST API

    U->>SST: 输入搜索查询
    SST->>API: POST /search/semantic
    API-->>SST: 搜索结果
    SST->>U: 展示结果列表

    U->>SST: 选择结果
    SST->>SST: 展示代码预览
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 查询输入 | 自然语言查询 | 构建搜索请求 | SearchRequest |
| 向量搜索 | 搜索请求 | 调用搜索 API | SearchResult[] |
| 结果展示 | 搜索结果 | 渲染结果列表 | 结果列表 |

---

## 日志分析流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant LQ as LogQuery
    participant RD as ReportDetail
    participant API as REST API

    U->>LQ: 输入查询条件
    LQ->>API: GET /log/query
    API-->>LQ: 日志结果
    LQ->>U: 展示日志列表

    U->>LQ: 选择日志
    LQ->>API: POST /log/analyze
    API-->>LQ: 报告 ID
    LQ->>RD: 跳转到报告详情

    RD->>API: GET /log/report/:id
    API-->>RD: 分析报告
    RD->>U: 展示报告
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 日志查询 | 查询条件 | 调用查询 API | LogEntry[] |
| 日志分析 | 日志 ID | 调用分析 API | reportId |
| 报告展示 | 报告 ID | 获取报告详情 | LogReport |

---

## Claude 终端流程

### 流程概览

```mermaid
sequenceDiagram
    participant U as 用户
    participant CT as ClaudeTerminal
    participant WS as WebSocket
    participant CLI as Claude CLI

    U->>CT: 打开终端
    CT->>WS: 创建 WebSocket 连接
    WS-->>CT: 连接成功
    CT->>U: 显示终端

    U->>CT: 输入命令
    CT->>WS: 发送输入
    WS->>CLI: 转发到 Claude CLI
    CLI-->>WS: 输出结果
    WS-->>CT: 转发输出
    CT->>U: 显示输出
```

### 数据流转

| 阶段 | 输入 | 处理 | 输出 |
|------|------|------|------|
| 连接建立 | 会话 ID | 创建 WebSocket | 连接状态 |
| 命令输入 | 用户输入 | 发送到 WebSocket | 输入数据 |
| 输出显示 | WebSocket 消息 | 写入终端 | 终端输出 |

---

## 数据流向图

### 整体数据流

```mermaid
graph TB
    subgraph "用户输入"
        UI[用户界面输入]
    end

    subgraph "API 层"
        REST[REST API]
        SSE[SSE Stream]
        WS[WebSocket]
    end

    subgraph "状态管理"
        PINIA[Pinia Stores]
        COMPOSABLE[Composables]
    end

    subgraph "后端服务"
        BE[Spring Boot]
        NEO4J[Neo4j]
        AI[AI 服务]
    end

    UI --> REST
    UI --> SSE
    UI --> WS

    REST --> PINIA
    SSE --> COMPOSABLE
    WS --> COMPOSABLE

    PINIA --> UI
    COMPOSABLE --> UI

    REST --> BE
    SSE --> BE
    WS --> BE

    BE --> NEO4J
    BE --> AI
```

---

## 下一步

- [RAM需求评估UI](../03-模块说明/RAM需求评估UI.md) - 深入了解 RAM 流程
- [APM诊断UI](../03-模块说明/APM诊断UI.md) - 深入了解 APM 流程
- [接口文档](../05-接口文档/index.md) - 了解 API 接口详情
