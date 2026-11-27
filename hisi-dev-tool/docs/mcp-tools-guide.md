# MCP 工具使用指南

## 概述

MCP 工具由后端进程内的 **Spring 原生 MCP 服务端**提供（streamable HTTP），不再有独立的 Node 版 MCP 服务。
后端启动后即可用，无需另行安装、构建或启动任何东西。

---

## 一、接入方式

### 1.1 环境要求

- 后端服务运行在 `http://localhost:8080`（MCP 端点同端口，路径 `/mcp`，只允许本机回环访问）
- 需要图谱数据的工具要求项目已完成知识图谱构建与向量生成

### 1.2 配置（给 Claude Code / 其他 MCP 客户端）

把后端 MCP 服务接入客户端（例如 Claude Code 的 `mcp.json`）：

```json
{
  "mcpServers": {
    "hisi-mcp-inproc": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

仓库根目录的 `.mcp.json` 已经是这份配置。

### 1.3 后端自己的 agent 流

pi 按 `lib/pi-agent/mcp.json`（启动时由后端生成）经 streamable HTTP 连接本进程的 `/mcp`；
每个流的工具白名单在 `FlowToolManifest` 里声明。详见后端 CodeWiki 的「进程内MCP工具」与「pi-agent底座」模块。

---

## 二、工具列表（40 个）

工具名与参数与后端 REST 接口同名同义，不存在第二份定义。

### 2.1 会话范围与知识图谱（24 个）

| 工具名 | 说明 |
|--------|------|
| `kg_select_scope` | 会话级项目范围：`action=list/set/get/clear`。设好后受范围控制的工具不必再传 projectPaths |
| `kg_list_projects` | 列出所有已图谱化的项目路径 |
| `kg_status` | 图谱构建状态（节点数、关系数、入口点数） |
| `kg_method_detail` | 方法详情（按 nodeId） |
| `kg_method_by_class` | 按类查询方法 |
| `kg_root_entries` | 根入口（类 + 方法） |
| `kg_callees_tree` | 向下被调用树（maxDepth 默认 10） |
| `kg_entry_points` | 入口点列表（entryType 过滤） |
| `kg_downstream` | 向下调用链（按 nodeId） |
| `kg_affecting` | 影响链（按类 + 方法） |
| `kg_bridges` | 方法的跨模块桥接点 |
| `kg_bridge_stats` | 桥接点统计（Feign/MQ/HTTP/JPA/MAPPER） |
| `kg_implementations` | 接口的实现类 |
| `kg_mybatis_sql` | MyBatis SQL（按 Mapper 接口与语句类型过滤） |
| `kg_feign_chain` | Feign 调用链（按服务名） |
| `kg_mq_chain` | MQ 调用链（按 topic） |
| `kg_dashboard` | 架构看板（风险、域、KPI、热点、交互） |
| `kg_dsm` | 依赖结构矩阵 |
| `kg_hotspots` | 代码热点 |
| `kg_domains` | 领域划分 |
| `kg_service_topology` | 服务拓扑 |
| `kg_blast_radius` | 爆炸半径（按 nodeId + 深度） |
| `kg_api_consumers` | 入口点的前端调用方 |
| `kg_backend_deps` | ApiClient 依赖的后端接口 |

### 2.2 混合检索（1 个）

| 工具名 | 说明 |
|--------|------|
| `hybrid_search` | 关键词 + 向量 + 调用链图扩展三层混合检索（RRF 融合）。调用前先 `kg_list_projects` 确认项目 |

### 2.3 日志（4 个）

| 工具名 | 说明 |
|--------|------|
| `log_query` | 查询日志（关键字、级别、TraceId、时间范围等） |
| `log_analyze` | 分析一条日志（message + stackTrace），生成根因报告 |
| `log_report` | 取分析报告（reportId 为 Long，按数字传） |
| `log_report_status` | 报告分析状态 |

### 2.4 APM（6 个）

| 工具名 | 说明 |
|--------|------|
| `apm_start_session` | 启动被测应用并挂 OTel 探针，返回会话 ID |
| `apm_execute_request` | 向被测应用发请求（method/path/body/headers） |
| `apm_list_traces` | 会话的 span 列表 |
| `apm_get_trace` | 按 traceId 取 span |
| `apm_get_report` | 会话的调试报告 |
| `apm_stop_session` | 停止会话并回收被测应用 |

### 2.5 接口写工具（5 个，需要用户令牌）

| 工具名 | 说明 |
|--------|------|
| `act_start_analysis` | 以用户身份发起需求分析（RAM 需求分析大师） |
| `act_start_status` | 以用户身份发起项目现状分析 |
| `act_resume_session` | 继续等待中的 RAM 会话 |
| `act_submit_clarification` | 回答澄清问题 |
| `act_confirm_node` | 确认（approve）或驳回（reject）节点产出 |

---

## 三、业务流 Skill

四个给外部 MCP 客户端用的技能说明存放在 `hisi-dev-tool/docs/mcp-skills/`（工具由本指南第一节的 MCP 服务提供）：

- `hisi-business-flow.md` — 接口业务流分析
- `hisi-log-analysis.md` — 错误日志分析
- `hisi-root-cause.md` — 根因定位
- `hisi-unit-test.md` — 单元测试生成

---

## 四、与旧 Node 版 MCP 的差异

旧版 Node MCP 服务（`hisi-mcp-server`）已删除。行为上的差异：

- 业务失败现在是 MCP 错误（`isError`），不再作为普通结果返回；
- 枚举入参取值严格、大小写敏感，枚举外的值在进入工具前被拒绝；
- number 入参发布为 `integer`（多了 `format`），顶层多了 `$schema` 键；
- 不再有 Node 客户端的 30 秒 HTTP 超时（进程内直调，慢调用一直等到后端返回）；
- 必填业务入参为空时工具直接返回「不能为空」。
