# 前后端完整图谱构建 + 绑定驱动 + 检索/总览适配

## Why

当前前端图谱被「寄生」在后端 KG 构建的收尾阶段：`KgGenerationQueue.processItem` 在后端全量建图后自动调用 `frontendGraphOrchestrator.run`，靠固定命名约定（`<后端名>-frontend`）自动发现前端目录。这套机制既隐晦（用户无感知、无独立入口）又脆弱（改目录名即失效），且失败静默吞掉、用户看到「构建成功」实则前端图为空。同时前端节点（Component/ApiClient/FrontendRoute）**没有向量字段**，语义检索只命中后端 `MethodNode`，导致「选前端目录看不到后端、选后端目录看不到前端」——图谱检索与总览在前后端之间是割裂的。此外，跨服务构建存在静默失败（先删边后建边 + 策略异常吞掉 + 恒返回 `completed`）、空壳占位符策略、无空选择校验、远端路径未规范化等缺陷。

本次变更把「前后端联合」从隐式寄生改为**显式绑定驱动**：用户在前端选择前后端目录建立绑定，一键构建完整图谱；检索/总览在绑定范围内合并前后端并按节点类型区分展示；顺带修复跨服务构建全部已知缺陷。

## What Changes

- **新增前后端绑定模型**（SQLite 表）+ CRUD API：绑定 = 「后端目录 ↔ 前端目录」二元关系，可增删改查。
- **移除后端自动触发**：删除 `KgGenerationQueue.processItem` 收尾处对 `frontendGraphOrchestrator.run` 的自动调用；前端实体化/跨层链接不再由后端全量建图隐式触发。
- **新增「构建前后端完整图谱」按钮 + 端点**：前端选择前后端目录 → 建立绑定 → 触发前端实体化 + 前端向量化 + 跨层链接。
- **前端节点向量化**：给 Component/FrontendRoute/ApiClient 生成 `descriptionEmbedding` 并建向量索引，使语义检索可命中前端节点。
- **检索/总览绑定范围展开 + 节点区分**：给定任一（前端或后端）目录，检索/总览入口自动展开为绑定组内的前后端路径集合；结果按「前端节点 vs 后端节点」区分并采用各自的字段与展示形式。
- **修复跨服务构建**：P1 静默失败显式化（失败即报错/返回结构化结果，不再假 `completed`）、P2 移除 OpenAPI/gRPC 空壳占位符策略、P3 空选择校验、P4 远端 `localPath` 规范化。

## Capabilities

### New Capabilities

- `kg-frontend-backend-binding`: 前后端绑定关系管理——SQLite 绑定表 + CRUD + 「构建前后端完整图谱」端点 + 检索/总览入口的绑定范围展开。
- `cross-service-build`: 跨服务依赖构建——为现有（无 spec 的）跨服务构建补规范，并修复静默失败/空壳策略/空选择校验/路径规范化缺陷。
- `frontend-node-vectorization`: 前端节点向量化——为 Component/FrontendRoute/ApiClient 生成 embedding 并建向量索引，使语义检索可命中前端节点。

### Modified Capabilities

- `frontend-code-entities`: 「前端目录自动发现」需求改为**绑定驱动的显式选择**（不再靠 `<后端名>-frontend` 命名约定自动探测）；前端节点新增向量字段（`descriptionEmbedding`）。
- `frontend-backend-linkage`: 跨层 INVOKES_API 链接的**触发方式**从「后端 KG 构建收尾自动触发」改为「绑定构建端点显式触发」；删除自动触发路径。

## Impact

- **后端**（`hisi-dev-tool`）：
  - `knowledgegraph/service/KgGenerationQueue.java`：删除收尾处前端图编排自动调用。
  - `knowledgegraph/service/FrontendGraphOrchestrator.java` / `FrontendProjectDiscoverer.java` / `FrontendAstParser.java` / `link/FrontendBackendLinker.java`：保留，改由新绑定构建端点显式调用；自动发现逻辑收敛为显式路径校验。
  - 新增绑定模型：SQLite 表（`SQLiteSchemaInitializer`）+ Repository + Service + Controller（CRUD + 联合构建端点）。
  - 前端节点向量化：`neo4j/model/ComponentNode.java` / `FrontendRouteNode.java` / `ApiClientNode.java` 加 `descriptionEmbedding`；`Neo4jInitializer` 加前端节点向量索引；`EmbeddingService` 扩展前端节点描述生成。
  - 检索适配：`neo4j/service/HybridSearchService.java`（命中前端节点 + 返回类型扩展）、`neo4j/controller/VectorSearchController.java`（绑定范围展开）、`QueryTypeDetector`（前端节点类型识别）。
  - 跨服务构建修复：`CrossServiceBuildService.java` / `CrossServiceLinker.java` / `CrossServiceBuildController.java` / `link/OpenApiLinkStrategy.java` / `GrpcLinkStrategy.java`。
- **前端**（`hisi-dev-tool-frontend`）：
  - `views/project/ProjectList.vue`：跨服务构建按钮修复（空选择校验、错误提示）。
  - `views/knowledge-graph/KnowledgeGraphView.vue` + 新组件：新增「构建前后端完整图谱」按钮 + 绑定选择/管理 UI。
  - `views/knowledge-graph/components/SemanticSearchPanel.vue` + 各总览组件（DashboardPanel 等）：结果区分前端/后端节点并差异化展示。
  - `api/`：新增绑定 API 模块 + `vectorSearch.ts` 请求/响应类型扩展（前端节点字段）。
- **MCP**（`hisi-mcp-server`）：`knowledgeGraphTools.ts` 检索工具适配前端节点返回（`language=typescript` 已放开，需补前端节点类型展示）。
- **依赖**：无新增外部依赖（复用现有智谱 embedding、Neo4j 原生 VECTOR INDEX、SQLite）。
