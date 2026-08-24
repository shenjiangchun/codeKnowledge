# frontend-code-entities Specification

## Purpose
TBD - created by archiving change kg-frontend-backend-graph. Update Purpose after archive.
## Requirements
### Requirement: 前端代码实体节点
系统 SHALL 在建图时解析前端项目（Vue/TS/JS），生成 `Component`（Vue 组件/页面）、`FrontendRoute`（路由）、`ApiClient`（API 调用点）三类实体节点，替代当前 `CodegraphToNeo4jTransformer` 将 component/route 抹平为后端 `Method` 的降级行为。

#### Scenario: 前端组件解析为 Component 节点
- **WHEN** 建图流水线扫描到 Vue 组件文件（`.vue` / `.tsx` / `.ts` 组件）
- **THEN** 生成 `Component` 节点，承载 `name`、`filePath`、`projectPath`、`language` 属性
- **并且** 该组件不再被映射为后端 `Method` 节点

#### Scenario: 前端路由解析为 FrontendRoute 节点
- **WHEN** 建图流水线扫描到前端路由定义（vue-router 路由表）
- **THEN** 生成 `FrontendRoute` 节点，承载 `path`、`componentName`、`projectPath` 属性

#### Scenario: 前端 API 调用点解析为 ApiClient 节点
- **WHEN** 前端 AST 解析器扫描到 axios/fetch 调用点（`request.get/post/...`、`fetch(`）
- **THEN** 生成 `ApiClient` 节点，承载 `url`、`method`、`sourceFile`、`componentName`、`projectPath` 属性

### Requirement: 前端内部依赖边
系统 SHALL 构建前端实体之间的内部依赖边，表达组件 import、路由→组件、组件→API 调用点三类关系，替代当前错位到后端 `Method` 语义的边。

#### Scenario: 组件 import 依赖
- **WHEN** 组件 A import 组件 B
- **THEN** 生成 `A -[:IMPORTS]-> B` 边（前端语义）

#### Scenario: 路由到组件的导航关系
- **WHEN** 路由 R 指向组件 C
- **THEN** 生成 `R -[:NAVIGATES]-> C` 边

#### Scenario: 组件到 API 调用点的发起关系
- **WHEN** 组件 C 内调用 API 调用点 P
- **THEN** 生成 `C -[:INVOKES]-> P` 边

### Requirement: 前端目录自动发现
系统 SHALL 在建图时根据 `package.json` 的物理位置自动定位前端项目目录，无需用户显式指定前端路径。

#### Scenario: 靠 package.json 定位前端项目
- **WHEN** 用户为建图入口指定一个根目录（或前端路径）
- **THEN** 系统在该目录树内查找 `package.json` 以确定前端项目边界
- **并且** 对定位到的前端项目执行前端实体化建图

#### Scenario: 未发现前端项目
- **WHEN** 指定目录树内不存在 `package.json`
- **THEN** 系统跳过前端实体化，不产生前端节点，且不报错中断建图

