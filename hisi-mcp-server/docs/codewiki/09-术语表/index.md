# 术语表

> 按字母 / 拼音排序,涵盖 MCP 协议、知识图谱、混合检索、日志分析等主题。

---

## A

### ApiClient
本项目封装的 HTTP 客户端类(`src/client/apiClient.ts`),基于原生 `fetch` + `AbortController`,提供 GET/POST/PUT/DELETE,以及单例工厂 `getApiClient(baseUrl?)`。

### AbortController
Web/Node 标准 API,通过 `signal` 取消 `fetch`。本项目用作请求超时(默认 30 秒)。

---

## B

### Bridge(桥接点)
知识图谱中连接不同模块或服务的关键节点,典型为 Feign 客户端、MQ 生产/消费端等跨服务调用。对应工具 `kg_bridges`、`kg_bridge_stats`。

---

## C

### CallTool
MCP 协议中客户端向服务端发起工具调用的请求类型,对应 schema `CallToolRequestSchema`。本服务在 `src/index.ts` 注册 handler。

### Callees Tree(下游调用树)
从某方法出发,沿调用关系向下展开的树状结构(含深度、循环检测)。对应 `kg_callees_tree`。

### Controller / Scheduled / MQ Listener / Feign Client
后端入口点四类典型类型,作为 `kg_entry_points` 的 `entryType` 枚举值。

---

## D

### DSL Query
Elasticsearch 风格的原始查询字符串,日志查询中优先级最高,设置后将覆盖其它过滤条件。对应 `LogQueryParams.dslQuery`。

### Downstream(下游调用链)
从指定节点向下追踪的调用关系。对应 `kg_downstream`。

---

## E

### Entry Point(入口点)
项目对外或被框架调度的方法入口,如 Spring `@RestController`、`@Scheduled`、MQ 监听器、Feign 客户端等。对应 `kg_entry_points`。

### ESM(ECMAScript Module)
本项目使用的模块体系,`package.json` 标记 `"type": "module"`,内部 import 必须带 `.js` 后缀。

---

## F

### Feign Chain(Feign 调用链)
追踪某 Feign 服务名在项目内的生产/消费链路。对应 `kg_feign_chain`,后端路径 `/api/knowledge-graph/feign/{serviceName}/call-chain`。

---

## H

### Hybrid Search(混合检索)
"关键词过滤 + 向量语义匹配 + 调用链图遍历扩展" 三层融合策略,后端使用 RRF 融合(见 RRF)。对应 `hybrid_search`。

### HISI_API_URL / HISI_DEBUG
本服务读取的两个环境变量。前者指定后端地址(默认 `http://localhost:8080`);后者为字符串 `'true'` 时打开 stderr 调试日志。

---

## J

### JSON-RPC
MCP 协议在 stdio 上承载的消息格式。本服务通过 SDK 自动收发,业务侧不直接接触。

---

## K

### KG(Knowledge Graph)
知识图谱。后端通过静态分析构建的代码结构图,本服务通过 `kg_*` 工具暴露。

### kg_list_projects
**最先调用**的 KG 工具,返回当前已建图的项目根目录数组,用作其它 `kg_*` 与 `hybrid_search` 的 `projectPath` 取值依据。

---

## M

### MCP(Model Context Protocol)
Anthropic 主导的开放协议,定义"AI 客户端 <-> 工具/上下文服务"之间的标准通信。本项目即一个 MCP Server。

### McpError / ErrorCode
SDK 提供的协议级错误。本服务仅用于"未知工具名"场景 (`MethodNotFound`)。

### MQ Chain(MQ 调用链)
追踪某 MQ 主题的生产者/消费者链路。对应 `kg_mq_chain`。

### MyBatis SQL
后端 MyBatis Mapper 解析得到的 SQL 映射信息。对应 `kg_mybatis_sql`。

---

## N

### NodeNext
TypeScript 模块解析策略,匹配 Node.js 真实 ESM 行为(import 路径必须含扩展名)。

### normalizePathArgs
路由层工具(`src/utils/pathUtils`),将入参对象中所有字符串字段的反斜杠替换为正斜杠,适配 Windows 路径。

---

## P

### projectPath / projectPaths
KG 与混合检索工具的关键过滤字段。两者互填(优先 `projectPaths`),必须取自 `kg_list_projects` 的返回。

---

## R

### Report(分析报告)
`log_analyze` 异步分析得到的产物,通过 `log_report_status` 查询进度,`log_report` 取详情(根因 / 修复建议 / 代码片段)。

### RRF(Reciprocal Rank Fusion)
多路召回结果的排名融合算法,后端用于把关键词、向量、图遍历三路召回融合。

### Root Entries(根入口点)
某方法被调用所追溯到的最顶层入口点 (Controller/MQ/Feign/定时任务等)。对应 `kg_root_entries`。

---

## S

### Schema(inputSchema)
每个 MCP 工具必须声明的 JSON Schema,描述入参结构与必填项。本服务在 `*ToolDefinitions` 数组中声明。

### StdioServerTransport
MCP SDK 提供的 stdio 传输实现。

---

## T

### TraceId
分布式追踪 ID,作为 `log_query` 与 `log_analyze` 的可选过滤字段。

---

## V

### VectorTools
本项目混合检索工具类(`src/tools/vectorTools.ts`),目前只实现 `hybridSearch`。

---

> **延伸阅读**:
> - 工具语义 -> [05-接口文档](../05-接口文档/index.md)
> - 概念关系 -> [01-项目概览 §5](../01-项目概览/index.md#5-核心概念)
