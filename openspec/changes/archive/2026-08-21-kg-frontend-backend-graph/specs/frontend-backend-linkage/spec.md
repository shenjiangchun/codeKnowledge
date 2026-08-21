# frontend-backend-linkage Specification

## Purpose

通过静态 URL 匹配，自动构建「前端 API 调用点 → 后端 EntryPoint」的跨层关系，使图谱能够回答「这个前端组件调用了哪些后端接口」「改这个后端接口会影响哪些前端组件」。

> 数据源边界（grilling 共识）：`ApiClient` 节点与 `Component -[:INVOKES]-> ApiClient` 边由 `frontend-code-entities` 能力的新建前端 AST 解析器产出；本能力只负责「ApiClient → 后端 EntryPoint」的跨层链接。

## ADDED Requirements

### Requirement: 跨层 INVOKES_API 边（独立链接阶段）
系统 SHALL 在前端图与后端图均建图完成后，通过**独立的链接阶段**（同构现有 `CrossServiceLinker`）用静态 URL 匹配，将前端 `ApiClient` 节点与后端 `EntryPoint` 节点关联，生成 `INVOKES_API` 边。

#### Scenario: 精确匹配生成跨层边
- **WHEN** 前端 `ApiClient` 的 URL（经 `/api` baseURL 归一化后）与后端 `EntryPoint` 的 `entryKey`（形如 `GET /api/knowledge-graph/dashboard`）路径与方法均匹配
- **THEN** 生成 `ApiClient -[:INVOKES_API]-> EntryPoint` 边
- **并且** 该边关联到后端入口对应的 `methodNodeId`

#### Scenario: 路径参数归一化后匹配
- **WHEN** 前端 URL 含路径参数（模板 `` `/callchain/.../${projectName}` ``）而后端 `entryKey` 用 `@PathVariable` 占位（如 `DELETE /callchain/analysis/project/{projectName}`）
- **THEN** 系统将两侧路径参数（`${var}` 与 `{var}`）归一化为统一占位符后再匹配

#### Scenario: 链接阶段在前端图与后端图就绪后运行
- **WHEN** 前端图（projectPath=前端实际目录）与后端图（projectPath=后端目录）均已建图完成
- **THEN** 独立链接阶段按「后端 projectPath + 前端 projectPath」配对，执行静态 URL 匹配并生成跨层边
- **并且** 链接阶段可独立重跑（增量重建跨层边），不触发前后端图的全量重建

#### Scenario: 匹配失败时不留孤儿边
- **WHEN** 前端 `ApiClient` 无法匹配到任何后端 `EntryPoint`（动态拼接、代理前缀、环境变量等）
- **THEN** 不生成 `INVOKES_API` 边，该 `ApiClient` 节点保留且标记为未匹配（不报错中断）

### Requirement: MCP 层 language 放开 typescript
系统 SHALL 将 MCP `kg_*` 工具的 `language` 枚举从 `java|python` 扩展到含 `typescript`，使前端实体可被检索。

#### Scenario: language=typescript 返回前端实体
- **WHEN** 以 `language=typescript` 调用 MCP 检索工具
- **THEN** 返回 `Component`/`FrontendRoute`/`ApiClient` 前端实体节点

### Requirement: 跨层查询工具
系统 SHALL 提供 MCP 工具，用于查询「调用某后端接口的前端组件列表」及「某前端组件调用的后端接口列表」。

#### Scenario: 查某后端接口的前端调用方
- **WHEN** 指定一个后端 `EntryPoint`（或 `methodNodeId`）查询其前端调用方
- **THEN** 返回沿 `INVOKES_API` 边反向关联的 `ApiClient`/`Component` 列表

#### Scenario: 查某前端组件的后端依赖
- **WHEN** 指定一个前端 `Component`/`ApiClient` 查询其调用的后端接口
- **THEN** 返回沿 `INVOKES_API` 边正向关联的 `EntryPoint` 列表
