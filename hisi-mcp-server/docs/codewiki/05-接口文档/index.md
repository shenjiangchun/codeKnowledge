# 接口文档

> 本文档列出 hisi-mcp-server 暴露的全部 **20 个 MCP 工具** 的入参 schema、返回结构与错误约定。所有工具均通过 MCP `tools/call` 调用,响应统一被包装为:
>
> ```json
> { "content": [ { "type": "text", "text": "<JSON 字符串>" } ], "isError": false }
> ```
>
> 出错时:
>
> ```json
> { "content": [ { "type": "text", "text": "{\"success\":false,\"error\":\"...\",\"tool\":\"...\"}" } ], "isError": true }
> ```

---

## 0. 通用约定

| 项 | 说明 |
|----|------|
| 调用前置 | 调用 `kg_*`(除 `kg_list_projects`)与 `hybrid_search` 之前,必须用 `kg_list_projects` 拿到 `projectPath` |
| 路径分隔符 | Windows 反斜杠会被自动归一为正斜杠 |
| `language` | 可选,枚举 `'java' | 'python'`,不传则不过滤 |
| `projectPaths` | 与 `projectPath` 互填,若都给以 `projectPaths` 为准 |
| 超时 | 客户端 -> 本服务无超时;本服务 -> 后端默认 30 秒 |

---

## 1. 知识图谱工具 (kg_*)

> 共 15 个;HTTP 全部 GET。返回结构由后端定义,本层透传 JSON。

### 1.1 `kg_list_projects`

- **描述**:列出已建图的项目路径(必须最先调用)。
- **入参**:`{}`
- **后端**:`GET /api/knowledge-graph/projects`
- **返回示例**:`["C:/projects/foo", "C:/projects/bar"]`

### 1.2 `kg_status`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `projectPath` | string | ✓ | 项目根目录绝对路径 |
| `projectPaths` | string[] | - | 多项目 |
| `language` | `'java'\|'python'` | - | 语言过滤 |

后端:`GET /api/knowledge-graph/status`

### 1.3 `kg_method_detail`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `nodeId` | string | ✓ | 方法节点 ID |
| `projectPath` | string | ✓ | 项目路径 |
| `projectPaths` / `language` | - | - | 通用 |

后端:`GET /api/knowledge-graph/method/detail`

### 1.4 `kg_method_by_class`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `className` | string | ✓ | 全限定类名 |
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/method/by-class`

### 1.5 `kg_entry_points`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `projectPath` | string | ✓ | 项目路径 |
| `entryType` | enum `CONTROLLER\|SCHEDULED\|MQ_LISTENER\|FEIGN_CLIENT\|ALL` | - | 默认 `ALL`(本层不传) |

后端:`GET /api/knowledge-graph/entry-points`

### 1.6 `kg_downstream`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `nodeId` | string | ✓ | 起点节点 |
| `projectPath` | string | ✓ | 项目路径 |
| `maxDepth` | number | - | 默认 10 |

后端:`GET /api/knowledge-graph/call-chain/downstream`

### 1.7 `kg_affecting`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `className` | string | ✓ | 全限定类名 |
| `methodName` | string | ✓ | 方法名 |
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/call-chain/affecting`

### 1.8 `kg_bridges`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `nodeId` | string | ✓ | 节点 ID |
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/call-chain/{nodeId}/bridges`

### 1.9 `kg_bridge_stats`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/bridge-stats`

### 1.10 `kg_implementations`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `interfaceName` | string | ✓ | 全限定接口名 |
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/implementations`

### 1.11 `kg_mybatis_sql`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `projectPath` | string | ✓ | 项目路径 |
| `mapperInterface` | string | - | Mapper 全限定名 |
| `statementType` | enum `SELECT\|INSERT\|UPDATE\|DELETE` | - | SQL 类型 |

后端:`GET /api/knowledge-graph/mybatis/sql`

### 1.12 `kg_feign_chain`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `serviceName` | string | ✓ | Feign 服务名(URL 编码) |
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/feign/{serviceName}/call-chain`

### 1.13 `kg_mq_chain`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `topic` | string | ✓ | MQ 主题(URL 编码) |
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/mq/{topic}/call-chain`

### 1.14 `kg_root_entries`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `className` | string | ✓ | 全限定类名 |
| `methodName` | string | ✓ | 方法名 |
| `projectPath` | string | ✓ | 项目路径 |

后端:`GET /api/knowledge-graph/root-entries`

返回:`{ rootEntries: [...], directCallers: [{callType, callLine, ...}] }`

### 1.15 `kg_callees_tree`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `className` | string | ✓ | 全限定类名 |
| `methodName` | string | ✓ | 方法名 |
| `projectPath` | string | ✓ | 项目路径 |
| `maxDepth` | number | - | 默认 10 |

后端:`GET /api/knowledge-graph/callees-tree`,返回带层级的节点+边,含循环检测。

---

## 2. 混合检索工具

### 2.1 `hybrid_search`

> 三层混合:关键词过滤 + 向量语义匹配 + 调用链图遍历扩展(RRF 融合)。

| 参数 | 类型 | 必填 | 默认 | 描述 |
|------|------|------|------|------|
| `query` | string | ✓ | - | 自然语言查询 |
| `projectPath` | string | - | - | 与 `projectPaths` 二选一 |
| `projectPaths` | string[] | - | - | 多项目 |
| `limit` | number | - | `10` | 返回条数,5~20 |
| `graphDepth` | number | - | `0` | 0 不做图遍历 |
| `language` | `'java'\|'python'` | - | - | 语言过滤(包装为 `filters.language`) |

后端:`POST /api/search/semantic`

请求体(本层组装):

```json
{
  "query": "...",
  "projectPath": "...",
  "projectPaths": ["..."],
  "limit": 10,
  "threshold": 0.5,
  "graphDepth": 0,
  "filters": { "language": "java" }
}
```

**返回**:正常返回后端 `{ results: [...], total: N, ... }`。

**0 结果回退**:本层会自动追加:

```json
{
  "results": [],
  "total": 0,
  "_hint": "本次搜索 0 条结果。可能是 projectPath 不在已建图的项目里...",
  "availableProjects": ["..."],
  "requestedProjectPath": "..."
}
```

---

## 3. 日志工具 (log_*)

### 3.1 `log_query`

> POST `/api/log/query`,后端 DTO 为 `LogQueryDto`。

| 参数 | 类型 | 必填 | 默认 | 描述 |
|------|------|------|------|------|
| `keyword` | string | - | - | 关键字模糊匹配 |
| `logLevel` | enum `ERROR\|WARN\|INFO\|DEBUG` | - | - | 级别过滤 |
| `appId` | string | - | - | 应用 ID |
| `traceId` | string | - | - | 分布式追踪 ID |
| `contentContains` | string | - | - | 内容精确包含 |
| `dslQuery` | string | - | - | 原始 DSL,优先级最高,会覆盖其它过滤 |
| `startTime` | string (ISO) | - | - | 开始时间 |
| `endTime` | string (ISO) | - | - | 结束时间 |
| `size` | number | - | `100` | 最大 1000 |
| `errorOnly` | boolean | - | `true` | 设为 false 可查所有级别 |
| `sortOrder` | `'asc'\|'desc'` | - | `desc` | 时间排序 |

> `required: []` —— 全部可选,但建议至少有一个过滤条件。

### 3.2 `log_analyze`

> POST `/api/log/analyze`,异步提交 AI 分析,返回 `reportId`。

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `message` | string | △ | 与 `stackTrace` 至少一个 |
| `stackTrace` | string | △ | 异常堆栈 |
| `serviceName` | string | - | 服务名 |
| `traceId` | string | - | TraceId |
| `errorType` | string | - | 错误类型 |

> `required: []`(后端会校验至少一个)。

### 3.3 `log_report`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `reportId` | number | ✓ | 由 `log_analyze` 返回的 ID |

后端:`GET /api/log/report/{reportId}`

返回:含根因分析、修复建议、相关代码片段。

### 3.4 `log_report_status`

| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `reportId` | number | ✓ | 报告 ID |

后端:`GET /api/log/report/{reportId}/status`

返回:`{ status: 'pending' | 'processing' | 'completed' | 'failed' }`

---

## 4. 错误码 / 错误形态

| 来源 | 形态 | 示例 |
|------|------|------|
| MCP 协议层 | `McpError(MethodNotFound)` | 工具名未注册 |
| 工具结果层 | `isError: true` + 文本 JSON | 后端 4xx/5xx、超时 |
| 文本 JSON 字段 | `{ success:false, error:"HTTP 500: ...", tool:"kg_status" }` | - |
| 超时 | `error = "Request timeout after 30000ms"` | - |
| 未知工具(路由层) | `error = "Unknown tool: <name>"` | - |

> 本服务**不**使用统一的 `ApiResponse` 信封;后端各接口直接返回业务 JSON,本层透传。

---

> **延伸阅读**:
> - 入参类型定义 -> [06-数据模型](../06-数据模型/index.md)
> - 数据流程 -> [04-数据流程](../04-数据流程/index.md)
