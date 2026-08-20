# semantic-search-type Specification (delta)

## MODIFIED Requirements

### Requirement: MCP 检索工具适配
系统 SHALL 让对外提供的 KG 检索 MCP 工具（`hybrid_search`）支持 searchType 传参，且 SHALL 通过 `POST /api/search/semantic/v2` 端点检索（多路召回 + 加权 RRF），返回结果包含 `subQueries` 与 `rrfScores` 字段。

#### Scenario: MCP 传 searchType
- **WHEN** 通过 MCP 工具调用 KG 检索并传 searchType
- **THEN** MCP SHALL 将 searchType 透传到语义检索服务

#### Scenario: MCP 走 v2 端点
- **WHEN** MCP 工具 `hybrid_search` 被调用
- **THEN** MCP SHALL 请求 `POST /api/search/semantic/v2`（而非旧 v1 端点）

#### Scenario: 返回多路召回字段
- **WHEN** MCP 工具 `hybrid_search` 检索成功
- **THEN** 返回结果 SHALL 包含 `subQueries` 与 `rrfScores` 字段

## ADDED Requirements

### Requirement: 历史检索端点移除
系统 SHALL 移除两个历史 v1 检索端点：`POST /api/search/semantic`（`SemanticSearchController.semanticSearch`）与 `POST /api/vector-search`（`VectorSearchController.search`）。

#### Scenario: v1 semantic 端点不可用
- **WHEN** 调用方请求 `POST /api/search/semantic`
- **THEN** 系统 SHALL 返回 404（端点已移除）

#### Scenario: v1 vector-search 端点不可用
- **WHEN** 调用方请求 `POST /api/vector-search`
- **THEN** 系统 SHALL 返回 404（端点已移除）

#### Scenario: v2 端点不受影响
- **WHEN** 调用方请求 `POST /api/search/semantic/v2` 或 `POST /api/vector-search/v2`
- **THEN** 系统 SHALL 正常返回检索结果
