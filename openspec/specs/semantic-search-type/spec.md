# semantic-search-type Specification

## Purpose
TBD - created by archiving change class-node-semantic-search. Update Purpose after archive.
## Requirements
### Requirement: searchType 传参
系统 SHALL 在 SearchRequest 新增 searchType 枚举字段（METHOD/CLASS/SQL/ENTRY/ALL）。显式传入时走对应检索；未传入时回退到现有 QueryTypeDetector 自动检测（向后兼容）。

#### Scenario: 显式传参优先
- **WHEN** 调用方传入 searchType=CLASS
- **THEN** 系统 SHALL 走类级检索，不经过 QueryTypeDetector 自动检测

#### Scenario: 缺省回退自动检测
- **WHEN** 调用方未传 searchType
- **THEN** 系统 SHALL 走现有 QueryTypeDetector.detect 自动推断，行为与之前一致

### Requirement: 类级语义检索
系统 SHALL 支持 searchType=CLASS 时，通过 ClassNode.descriptionEmbedding 向量索引检索类节点。

#### Scenario: 类级检索
- **WHEN** 用户以 searchType=CLASS 检索「订单相关的类」
- **THEN** 系统 SHALL 返回匹配的 ClassNode 列表（含类描述、方法数）

#### Scenario: 类级结果复用统一 DTO
- **WHEN** 类级检索返回结果
- **THEN** 系统 SHALL 复用 SearchResultItem DTO，nodeType="Class"、nodeId=classId、className=类名、description=类描述、methodName/signature 留空

### Requirement: 多类型检索
系统 SHALL 支持 searchType 为 METHOD（MethodNode.descriptionEmbedding）、SQL（SqlNode.sqlEmbedding）、ENTRY（EntryPoint.briefEmbedding）的检索，ALL 类型 SHALL 合并多路结果（RRF 融合）。

#### Scenario: 类型化路由
- **WHEN** 调用方指定 searchType
- **THEN** 系统 SHALL 路由到对应实体的向量索引检索

#### Scenario: ALL 合并
- **WHEN** searchType=ALL
- **THEN** 系统 SHALL 合并方法/类/SQL/入口多路结果

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

### Requirement: 内部 agent 检索适配（DirectKgClient）
系统 SHALL 让内部唯一 KG 检索入口 DirectKgClient 支持 searchType：hybridSearch 新增 searchType 重载（默认 METHOD 保持现状），并新增 classSearch 方法返回 Seed 列表。

#### Scenario: hybridSearch 默认行为不变
- **WHEN** 内部 agent 调用 hybridSearch 不传 searchType
- **THEN** 行为 SHALL 与之前一致（走缺省自动检测）

#### Scenario: classSearch 新增
- **WHEN** 内部 agent 调用 classSearch 检索类
- **THEN** 系统 SHALL 走 searchType=CLASS 类级检索，返回 Seed 列表

### Requirement: ProjectOverviewNode 类检索优化
系统 SHALL 改造 ProjectOverviewNode 为「先类后方法」：先用 searchType=CLASS 搜相关类，再从命中类取代表方法作为 coreMethods；类检索为空时回退到现有方法检索。

#### Scenario: 先类后方法
- **WHEN** ProjectOverviewNode 执行项目总览且类描述已生成
- **THEN** 系统 SHALL 先类检索定位业务类，再从命中类取代表方法

#### Scenario: 类检索空时回退
- **WHEN** 类检索返回空（类描述未生成）
- **THEN** 系统 SHALL 回退到现有方法检索，不中断总览流程

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

