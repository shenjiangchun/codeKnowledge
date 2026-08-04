# 接口文档

> 本节聚合 23 个 Controller 的全部 REST 端点（约 162 个）+ Terminal WebSocket 协议。详细字段以源码 DTO 为准（见 `model/`）。

---

## 1. 接口概览

| 类型 | 数量 | 基础路径 | 协议 | 认证 |
|------|------|---------|------|------|
| REST API | 约 162 | `/api/...` | HTTP/JSON | 无（本机工具） |
| WebSocket | 1 | `/ws/terminal` | WS / TextFrame | 无 |

### 通用响应

```json
// 成功
{ "success": true, "data": {...}, "error": null }
// 失败
{ "success": false, "data": null, "error": "错误描述" }
```

HTTP 状态默认 200，业务错误用 `success=false` 区分。

### CORS

`application.yml: cors.allowed-origins`，默认允许本机端口 5173 / 5174 / 5175 / 3000。

---

## 2. 知识图谱（`/api/knowledge-graph` + 派生）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/knowledge-graph/tasks/generate` | 创建图谱构建任务 |
| GET | `/api/knowledge-graph/tasks/status` | 任务状态 |
| GET | `/api/knowledge-graph/tasks/latest` | 最近一次任务 |
| GET | `/api/knowledge-graph/projects` | 已建图项目列表 |
| GET | `/api/knowledge-graph/classes` | 类列表 |
| GET | `/api/knowledge-graph/git-status` | Git 状态（用于增量） |
| POST | `/api/knowledge-graph/incremental` | 增量更新 |
| POST | `/api/knowledge-graph/generate` | 同步生成入口（兼容） |
| GET | `/api/knowledge-graph/status` | 单查询状态 |
| GET | `/api/knowledge-graph/status/batch` | 批量状态 |
| GET | `/api/knowledge-graph/root-entries` | 上游根入口（rootEntries + directCallers） |
| GET | `/api/knowledge-graph/callees-tree` | 完整下游调用树 |
| GET | `/api/knowledge-graph/entry-points` | 项目入口点列表 |
| GET | `/api/knowledge-graph/call-chain/by-key` | 通过 nodeId 查调用链 |
| GET | `/api/knowledge-graph/call-chain/by-type` | 按类型查 |
| GET | `/api/knowledge-graph/call-chain/affecting` | 影响（上游）调用链 |
| GET | `/api/knowledge-graph/call-chain/downstream` | 下游 |
| GET | `/api/knowledge-graph/call-chain/graph` | DAG 图数据 |
| GET | `/api/knowledge-graph/call-chain/{nodeId}/bridges` | 节点的桥接点 |
| GET | `/api/knowledge-graph/cycles/detect` | 环检测 |
| GET | `/api/knowledge-graph/implementations` | 接口实现 |
| GET | `/api/knowledge-graph/interfaces` | 接口列表 |
| GET | `/api/knowledge-graph/method/detail` | 方法详情 |
| GET | `/api/knowledge-graph/method/by-class` | 类内方法列表 |
| POST | `/api/knowledge-graph/mybatis/scan` | MyBatis 扫描 |
| GET | `/api/knowledge-graph/mybatis/mappers` | Mapper 列表 |
| GET | `/api/knowledge-graph/mybatis/sql` | SQL 列表 |
| GET | `/api/knowledge-graph/mapper/{mapperInterface}/sql` | 单 mapper SQL |
| GET | `/api/knowledge-graph/feign/{serviceName}/call-chain` | Feign 服务调用链 |
| GET | `/api/knowledge-graph/mq/{topic}/call-chain` | MQ topic 调用链 |
| GET | `/api/knowledge-graph/bridge-stats` | 桥接统计 |
| GET | `/api/knowledge-graph/bridges/by-type` | 按类型查桥接 |
| POST | `/api/knowledge-graph/cross-service/build` | 跨服务图谱构建 |

---

## 3. 检索（`/api/vector-search` / `/api/search` / `/api/vector-generation`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/vector-search/search` | **混合检索主入口**：关键词 + 向量 + 图扩展（RRF） |
| POST | `/api/search/...` | 旧版语义搜索（部分迁移） |
| POST | `/api/vector-generation/...` | 离线/重试生成方法描述/向量 |

### `POST /api/vector-search/search` 请求示例

```json
{
  "query": "处理支付回调的方法",
  "scope": "C:/projects/foo",
  "language": "java",
  "topK": 10,
  "graphDepth": 2
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | 是 | 自然语言或代码片段 |
| `scope` | string | 否 | 范围（`projectPath` 或 `publicProjectPath`） |
| `language` | `"java" / "python"` | 否 | 仅过滤指定语言 |
| `topK` | int | 否 | 默认 10 |
| `graphDepth` | int | 否 | 默认 2，0 表示不做图扩展 |

响应：`SearchResult { items: SearchResultItem[], total, queryIntent }`，详见 `neo4j/model/`。

---

## 4. 影响 / 风险 / 异常路径

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/impact/analyze` | 影响分析 |
| POST | `/api/impact/testcases` | 推荐用例 |
| POST | `/api/impact/risk` | 风险评分 |
| GET | `/api/impact/preview` | 预览 |
| POST | `/api/impact/batch` | 批量 |
| GET | `/api/impact/summary/{reportId}` | 历史摘要 |
| GET | `/api/semantic/exception/analyze` | 异常路径（单） |
| POST | `/api/semantic/exception/analyze/batch` | 批量 |
| GET | `/api/semantic/exception/top-sources` | Top 异常源 |
| GET | `/api/semantic/exception/status` | 状态 |

---

## 5. 日志 / 运维

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/log/query` | 查询日志（DSL / 关键字 / 时间范围） |
| POST | `/api/log/analyze` | 异步分析（返回 reportId） |
| GET | `/api/log/reports` | 列表 |
| GET | `/api/log/report/{id}` | 详情 |
| GET | `/api/log/report/{id}/status` | 状态 |
| GET | `/api/ops/health` | 健康检查 |
| POST | `/api/ops/analysis/impact` | 通用影响分析 |
| GET | `/api/ops/docs/interface` | 文档接口 |
| POST | `/api/ops/logs/download` | 日志下载 |

---

## 6. 会话 / 工作区 / 对话 / 诊断

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sessions` | 列表 |
| GET | `/api/sessions/{id}` | 详情 + messages |
| PATCH | `/api/sessions/{id}` | 更新 |
| DELETE | `/api/sessions/{id}` | 删除 |
| POST | `/api/sessions/{id}/archive` | 归档 |
| GET | `/api/sessions/{id}/export` | 导出（下载） |
| DELETE | `/api/sessions/{id}/messages` | 清空消息 |
| GET / POST / PUT / DELETE | `/api/workspace-sessions[/{id}]` | 工作区 CRUD |
| POST | `/api/workspace-sessions/{id}/archive` | 归档 |
| POST | `/api/workspace-sessions/{id}/bind-claude-session` | 绑定 Claude 会话 |
| POST | `/api/dialog/...` | 自然语言对话 |
| POST | `/api/diagnosis/...` | 诊断 Agent |

---

## 7. 项目 / Git / 配置 / 设置

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/projects/list` | 已知项目列表 |
| POST | `/api/projects/clone` | 从 CodeHub 克隆 |
| GET | `/api/projects/status` | 项目状态 |
| GET | `/api/projects/scan-git-repos` | 扫描磁盘 Git 仓库 |
| GET | `/api/git/status` | git status |
| POST | `/api/git/checkout` | 切分支 |
| POST | `/api/git/pull` | pull |
| GET | `/api/git/logs` | 提交历史 |
| GET | `/api/git/commits` | commit 列表 |
| GET | `/api/git/commit-diff` | diff |
| POST | `/api/git/update-all` | 批量更新 |
| POST | `/api/git/fetch` | 从 CodeHub fetch（`CodeHubFetchController`） |
| GET / PUT | `/api/config[/...]` | 应用配置 |
| GET | `/api/config/project-dir` | 项目目录 |
| GET | `/api/config/selected-project` | 当前项目 |
| GET | `/api/settings/config` | 系统配置 |
| POST | `/api/settings/config` | 保存系统配置 |
| GET / POST | `/api/settings/proxy` | HTTP 代理设置 |

---

## 8. 提示词 / 技能 / MCP

| 方法 | 路径 | 说明 |
|------|------|------|
| GET / GET / PUT | `/api/prompts[/{key}]` | 提示词管理 |
| POST | `/api/prompts/{key}/render` | 渲染 |
| POST | `/api/prompts/extract-variables` | 抽取变量 |
| GET | `/api/skill-market/list` 等 | 详见 [技能市场与提示词](../03-模块说明/技能市场与提示词.md) |
| GET / POST | `/api/skills/...` | 本地技能 CRUD |
| GET | `/api/mcp/info` | MCP 包信息 |
| GET | `/api/mcp/download` | 下载 |
| POST (SSE) | `/api/mcp/install` | 安装（流式输出 `text/event-stream`） |
| GET | `/api/mcp/status` | 状态 |
| GET | `/api/mcp/config-template` | 配置模板 |
| GET | `/api/mcp/install-script` | 安装脚本 |

---

## 9. WebSocket：`/ws/terminal`

详见 [终端与WebSocket](../03-模块说明/终端与WebSocket.md)。

**客户端 → 服务端**

```json
{ "action": "start", "cwd": "..." }
{ "action": "resume", "sessionId": "..." }
{ "action": "input", "data": "..." }
{ "action": "resize", "cols": 120, "rows": 40 }
```

**服务端 → 客户端**

```json
{ "type": "stdout", "data": "..." }
{ "type": "claude-ready" }
{ "type": "session-id", "sessionId": "..." }
{ "type": "exit", "code": 0 }
```

无心跳协议，依赖 TCP keepalive；客户端需重连时自行重发 `resume`。

---

## 10. 错误码

由 `GlobalExceptionHandler` 统一处理，绝大多数返回 200 + `success=false`。少量场景：

| 业务异常类 | 代码位置 |
|-----------|---------|
| `KnowledgeGraphException` | `knowledgegraph/exception/` |
| `SearchException` | `neo4j/model/SearchException` |

`SearchErrorCode`（`neo4j/model/SearchErrorCode.java`）提供细分错误码（如 `EMBED_FAILED`、`INDEX_NOT_FOUND` 等），具体值见源码。

---

> **延伸阅读**：
> - 数据模型 → [06-数据模型/index.md](../06-数据模型/index.md)
> - 数据流程 → [04-数据流程/index.md](../04-数据流程/index.md)
