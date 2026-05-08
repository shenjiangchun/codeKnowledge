# 术语表

---

## 1. 通用术语

| 术语 | 全称 / 英文 | 定义 | 相关代码 |
|------|----------|------|---------|
| **方法节点** | MethodNode | Neo4j 中的最小代码单元 | `neo4j/model/MethodNode` |
| **入口点** | EntryPoint | Controller / Scheduled / MQ Listener / Feign 等可被外部触达的方法 | `neo4j/model/EntryPointNode`、`scanner/EndpointScanner` |
| **桥接点** | Bridge | 跨服务（Feign / MQ / HTTP）调用关系 | `knowledgegraph/link/`、`/api/knowledge-graph/bridge-stats` |
| **范围** | scope | 检索范围键，等于 `coalesce(publicProjectPath, projectPath)` | `HybridSearchService.search(scope=...)` |
| **公共图谱** | Public Project Graph | 多个 `projectPath` 共享同一 `publicProjectPath`，跨项目检索 | `MethodNode.publicProjectPath` |
| **混合检索** | Hybrid Search | 关键词 + 向量 + 调用链图遍历，RRF 融合 | `HybridSearchService` |
| **RRF** | Reciprocal Rank Fusion | `score = 1/(k+rank)`，本项目 k=60 | `HybridSearchService.RRF_K` |
| **查询类型** | QueryType | 9 种检索路由策略枚举 | `neo4j/model/QueryType` |
| **嵌入** | Embedding | 文本/代码向量化结果 | `descriptionEmbedding` / `codeEmbedding` |
| **图遍历深度** | graphDepth | 在向量召回后扩展的邻接跳数（默认 2） | `HybridSearchService.DEFAULT_GRAPH_DEPTH` |

---

## 2. AI / LLM 术语

| 术语 | 定义 | 备注 |
|------|------|------|
| **OpenAI 兼容协议** | 提供 `/embeddings` + `/chat/completions` 接口的服务 | 智谱 / SiliconFlow / 讯飞 / OpenAI 等 |
| **Embedding 模型** | 把文本变向量的模型 | 默认 Qwen3-VL-Embedding-8B 4096 维 |
| **Text 模型** | 自然语言生成模型 | 默认 glm-4-flash |
| **HIL** | Human-in-the-Loop（人在回路） | `service/intent/InterventionHandler` |
| **意图识别** | Intent Recognition | 文本 → IntentType | `service/intent/IntentResult` |
| **实体抽取** | Entity Extraction | 提取类名/方法名/异常类型 | `service/intent/EntityExtraction` |
| **Agent / Orchestrator** | 编排多步工具调用的执行单元 | `agent/DiagnosticAgent`、`agent/orchestrator/` |
| **DSL Query** | 日志云的查询语法 | `logcloud.api.query-path` |

---

## 3. 后端 / Spring 术语

| 术语 | 定义 |
|------|------|
| `ApiResponse<T>` | 统一响应包装：`{success, data, error}` |
| `@ConditionalOnProperty(neo4j.uri)` | 缺 `neo4j.uri` 时不装配 Neo4j 相关 Bean，实现降级 |
| `@RestControllerAdvice` | 全局异常处理（`GlobalExceptionHandler`） |
| `RequiredArgsConstructor` | Lombok 注解；`final` 字段构造注入 |
| `@PostConstruct` | Bean 初始化钩子（`SQLiteSchemaInitializer`） |
| `ApplicationReadyEvent` | 应用就绪事件（`Neo4jInitializer` 监听） |

---

## 4. 图谱 / 检索术语

| 术语 | 定义 |
|------|------|
| **CALLS** | Neo4j 关系类型：方法之间的调用 |
| **ENTRY_OF** | 入口点 → 方法的关系 |
| **EXECUTES_SQL** | 方法 → SQL 节点 |
| **VECTOR INDEX** | Neo4j 5.11+ 原生向量索引（cosine） |
| **callees-tree** | 完整的下游调用树（`/api/knowledge-graph/callees-tree`） |
| **rootEntries** | 给定方法的上游入口集合（Controller/MQ/Feign/定时任务） |
| **directCallers** | 直接调用方（含 callType / callLine） |
| **affecting** | 上游影响调用链 |

---

## 5. 终端 / 会话术语

| 术语 | 定义 |
|------|------|
| **PTY** | Pseudo Terminal（伪终端） |
| **PtyProcess** | PTY4J 的进程对象 |
| **ClaudeSession** | 一次 Claude CLI 对话上下文 |
| **WorkspaceSession** | 用户工作区，绑定项目路径 + 多个 ClaudeSession |
| **claude-ready** | 服务端通过正则识别 Claude CLI 启动完毕后发给前端的事件 |

---

## 6. 缩写

| 缩写 | 全称 | 说明 |
|------|------|------|
| KG | Knowledge Graph | 知识图谱 |
| FQN | Fully Qualified Name | 全限定名 |
| AST | Abstract Syntax Tree | 抽象语法树 |
| DAG | Directed Acyclic Graph | 有向无环图 |
| MCP | Model Context Protocol | Anthropic 提出的工具协议（本仓库有 MCP 服务对接） |
| ADR | Architecture Decision Record | 架构决策记录 |
| HIL | Human-in-the-Loop | 人在回路 |
| SDN | Spring Data Neo4j | Neo4j 的 Spring Data 实现 |
| RRF | Reciprocal Rank Fusion | 倒数排名融合 |
| SSE | Server-Sent Events | 服务器推送事件 |
| DSL | Domain-Specific Language | 领域专用语言（如日志云查询） |
| GDS | Graph Data Science | Neo4j 图算法插件 |

---

## 7. 项目演进相关

| 术语 | 说明 |
|------|------|
| `hisi-vector-service` | 早期 Python FastAPI 向量服务，**已废弃**，主链路不再调用 |
| `ChromaDB` | 早期向量数据库，**已被 Neo4j VECTOR INDEX 取代** |
| `OpenGauss` / `MySQL` | 早期关系型存储，**已被 Neo4j + SQLite 取代** |
| `embedding` 字段（无前缀） | 旧版字段，不再写入；新数据使用 `descriptionEmbedding` / `codeEmbedding` / `sqlEmbedding` |

---

> **延伸阅读**：
> - 项目概览（核心概念详解） → [01-项目概览](../01-项目概览/index.md)
> - 各模块专有术语 → [03-模块说明](../03-模块说明/)
