# 核心数据流

---

## 1. 数据流概览

| # | 流程名 | 触发方 | 关键路径 | 频率 | 重要性 |
|---|--------|--------|---------|------|--------|
| 1 | 知识图谱构建 | 用户 POST `/api/knowledge-graph/generate` | Scanner → JavaParser/ANTLR4 → MethodNode → Neo4j → LLM 描述 → 向量 | 低频，每仓库一次 | 核心 |
| 2 | 混合检索 | 用户 / MCP POST `/api/vector-search/search` | QueryTypeDetector → Embedding → VectorIndex + Repo 关键词 + 图扩展 → RRF | 高频 | 核心 |
| 3 | Claude 终端会话 | WS `/ws/terminal` | TerminalWSHandler ↔ PTY ↔ Claude CLI；SessionService 持久化 | 高频 | 核心 |
| 4 | 日志根因分析 | POST `/api/log/analyze` | LogCloudService → StackTraceFilter → HybridSearch → LLM → 报告 | 中频 | 核心 |
| 5 | 影响分析 | POST `/api/impact/analyze` | Neo4j 调用图遍历 → 风险评分 → 用例推荐 | 中频 | 核心 |
| 6 | 自然语言对话 | POST `/api/dialog` | NLDiagnosisCoord → IntentRecognizer → DiagnosticAgent → 工具 | 中频 | 辅助 |

### 全景图

```mermaid
flowchart LR
    subgraph 写入路径
        Build["KnowledgeGraphBuilder"]:::process
        Scan["Scanners + AST"]:::process
        Build --> Scan --> Neo[("Neo4j")]
        Build --> LLM["LLM 描述/向量"]:::ext
        LLM --> Neo
    end
    subgraph 读取路径
        HSS["HybridSearchService"]:::process
        Impact["ImpactPrediction"]:::process
        RCA["RootCauseAnalysis"]:::process
        Dlg["Dialog/Agent"]:::process
        HSS --> Neo
        Impact --> Neo
        RCA --> HSS
        Dlg --> HSS
        Dlg --> Impact
        Dlg --> RCA
    end
    subgraph 终端
        WS["TerminalWSHandler"]:::process
        WS --> PTY["claude CLI"]:::ext
        WS --> Sqlite[("SQLite Sessions")]:::data
    end

    classDef process fill:#e3f2fd
    classDef data fill:#e8f5e9
    classDef ext fill:#fce4ec
```

---

## 2. 流程 1：知识图谱构建

### 2.1 时序图

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctl as KnowledgeGraphController
    participant T as KnowledgeGraphTaskService
    participant B as KnowledgeGraphBuilder
    participant Scan as Scanners
    participant Repo as Neo4j Repo
    participant LLM as UnifiedTextService
    participant Vec as VectorGenerationService

    C->>Ctl: POST /generate { projectPath }
    Ctl->>T: createTask
    T-->>Ctl: { taskId, status:"pending" }
    Ctl-->>C: 202
    Note over T: 异步线程
    T->>B: build(projectPath)
    B->>Scan: 扫描入口/Feign/MQ/HTTP/MyBatis SQL
    B->>B: JavaParser/ANTLR4 解析 method
    B->>Repo: MERGE MethodNode + CALLS 边
    B->>LLM: 生成 description（批量+重试）
    LLM-->>B: 描述文本
    B->>Vec: 生成 descriptionEmbedding / codeEmbedding
    Vec->>Repo: 写入向量字段
    B-->>T: status=completed
    C->>Ctl: GET /tasks/status?taskId=...
    Ctl-->>C: { progress, completed, errors }
```

### 2.2 异常流程

| 异常 | 处理 |
|------|------|
| 单文件 AST 解析失败 | 跳过，统计入 errors |
| LLM 描述 401/超时 | 重试 3 次后跳过，留待 `/api/vector-generation/retry` |
| Neo4j 连接断开 | 任务标记 failed，stacktrace 存入任务表 |

---

## 3. 流程 2：混合检索

### 3.1 时序图

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctl as VectorSearchController
    participant H as HybridSearchService
    participant QTD as QueryTypeDetector
    participant E as EmbeddingService
    participant V as Neo4jVectorIndexService
    participant R as Neo4jMethodNodeRepository

    C->>Ctl: POST /search {query, scope, language?, topK, graphDepth}
    Ctl->>H: search(req)
    H->>QTD: detect(query) → QueryType
    H->>E: embed(query)（命中 QueryEmbeddingCache 则跳过）
    E-->>H: vec
    par 并行
        H->>V: db.index.vector.queryNodes(idx, topK, vec)
        V-->>H: vectorRanked
    and
        H->>R: 关键词/FQN/SQL 反查
        R-->>H: keywordRanked
    end
    H->>H: RRF 融合 (k=60)
    opt graphDepth>0
        H->>R: 邻接 callees/callers/entries/sql
        R-->>H: 邻接节点
    end
    H-->>Ctl: SearchResult
    Ctl-->>C: ApiResponse
```

### 3.2 数据转换节点

| 节点 | 输入 | 输出 | 实现 |
|------|------|------|------|
| QueryType 判别 | string | `QueryType` 枚举 | `QueryTypeDetector` |
| 查询向量化 | string | float[] | `EmbeddingService.embed` |
| 向量召回 | float[], topK | `MethodWithScore[]` | `Neo4jVectorIndexService` |
| 关键词召回 | string, scope | `MethodWithScore[]` | `Neo4jMethodNodeRepository.*ByScope` |
| RRF 融合 | List<List<Item>> | List<Item> | `HybridSearchService` 内部 |

---

## 4. 流程 3：Claude 终端会话

```mermaid
stateDiagram-v2
    [*] --> Connecting
    Connecting --> Started: action=start
    Connecting --> Resumed: action=resume
    Started --> Streaming: stdout 来流
    Resumed --> Streaming
    Streaming --> Streaming: action=input / 接收 stdout
    Streaming --> Resized: action=resize
    Resized --> Streaming
    Streaming --> Closed: client close / PTY exit
    Closed --> [*]
```

`SessionService` 在另一条路径上独立持久化（前端按需调用，不阻塞 PTY 流）。

---

## 5. 流程 4：日志根因分析

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctl as LogAnalysisController
    participant E as LogAnalysisExecutor
    participant RCA as RootCauseAnalysisService
    participant LC as LogCloudService
    participant H as HybridSearch
    participant LLM as TextService

    C->>Ctl: POST /analyze {message, stackTrace, traceId}
    Ctl->>E: submit
    E-->>Ctl: reportId
    Ctl-->>C: { reportId }
    E->>RCA: run
    RCA->>LC: query(traceId)
    LC-->>RCA: log lines
    RCA->>RCA: StackTraceFilter 提取业务行
    RCA->>H: 检索可疑方法
    H-->>RCA: methods
    RCA->>LLM: 生成根因 + 修复建议
    LLM-->>RCA: text
    RCA->>RCA: save report
```

---

## 6. 流程 5：影响分析

```mermaid
flowchart LR
    Start["POST /api/impact/analyze"]:::entry
    Resolve["解析方法 → MethodNode 列表"]:::process
    Tree["调用图遍历 (callees/callers, depth)"]:::process
    Aggregate["聚合 + 去重"]:::process
    Score["RiskAnalysisService.score"]:::process
    Sug["SuggestionService 推荐用例"]:::process
    Out["ImpactReport"]:::done

    Start --> Resolve --> Tree --> Aggregate --> Score --> Sug --> Out

    classDef entry fill:#1565c0,color:#fff
    classDef process fill:#e3f2fd
    classDef done fill:#2e7d32,color:#fff
```

---

## 7. 数据校验

| 数据 | 校验 | 校验位置 |
|------|------|---------|
| `projectPath` | 路径存在、绝对路径、不含 `..` | `PathUtils` + Controller |
| `query` 长度 | < 5000 字符 | `HybridSearchService` |
| `topK` / `graphDepth` | 范围 1-100 / 0-10 | Controller 参数默认值 + Service 内部 clamp |
| 嵌入维度 | 与索引一致 | `Neo4jVectorIndexService` |

---

## 8. 性能关键路径

| 路径 | 瓶颈 | 优化 |
|------|------|------|
| 全量构建 | JavaParser 符号解析内存 | 分批处理、`GlobalAnalysisCache` |
| LLM 描述 | 网络 RTT | 批量 + 重试 + 队列；可禁用 |
| 向量检索 | Neo4j VECTOR INDEX 查询 | 控制 topK、`QueryEmbeddingCache` 缓存查询向量 |
| 影响分析 | 大图遍历 | 限制 `depth`，使用 `MATCH ... LIMIT` |

---

## 9. 数据安全与脱敏

| 数据 | 敏感级别 | 措施 |
|------|---------|-----|
| API Key | 高 | 环境变量，logback 不打印 |
| 仓库源码 | 中 | 仅本机存储，不外发（LLM 调用仅发送方法体片段） |
| 日志原文 | 中 | 不持久化原始日志，仅保留分析报告 |
| Claude 会话 | 低（本机） | SQLite 本地，不上传 |
