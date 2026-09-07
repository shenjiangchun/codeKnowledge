# 实施任务清单

## 1. 跨服务构建修复（先做，独立且风险低）

- [x] 1.1 `LinkStrategy` 接口重构为 `List<Map<String,Object>> link(List<String> projectPaths)`（纯计算、返回命中关系、不写库），更新 Javadoc
- [x] 1.2 `HttpRestLinkStrategy.link` 改为返回命中关系列表，移除内部 `createCallRelations` 调用
- [x] 1.3 `MqLinkStrategy.link` 改为返回命中关系列表，移除内部 `createCallRelations` 调用
- [x] 1.4 删除 `OpenApiLinkStrategy`、`GrpcLinkStrategy` 两个空壳占位符
- [x] 1.5 `CrossServiceLinker.link` 汇总各策略命中关系并返回（含各策略计数）
- [x] 1.6 `CrossServiceBuildService.build` 重排：校验 → 增量刷新 → 计算命中关系 → 删旧边 → 建新边；返回结构化计数（删除旧边数 + 各策略命中边数）
- [x] 1.7 `CrossServiceBuildController`：空选择校验（`projectPaths` 少于 2 返回 400）、失败显式化（非 `completed`）、返回结构化结果
- [x] 1.8 远端项目路径规范化（复用/抽取 `normalizePath`，统一绝对路径与分隔符）
- [x] 1.9 单测：`HttpRestLinkStrategy`/`MqLinkStrategy` 返回命中关系、`CrossServiceBuildService` 先算后写顺序、空选择校验、路径规范化

## 2. 绑定数据模型 + CRUD（SQLite）

- [ ] 2.1 `SQLiteSchemaInitializer` 新增 `kg_project_binding` 建表（`id`/`backend_path`/`frontend_path`/`created_at`/`updated_at` + `UNIQUE(backend_path, frontend_path)`）
- [ ] 2.2 新增 `ProjectBinding` 模型 + `ProjectBindingRepository`（JdbcTemplate CRUD，仿 `GlossaryTermRepository`）
- [ ] 2.3 新增 `ProjectBindingService`（`resolve(projectPath) -> List<String>` 范围展开 + `create`/`list`/`delete`）
- [ ] 2.4 新增 `ProjectBindingController`（CRUD 端点 + `/build` 构建端点骨架）
- [ ] 2.5 单测：绑定 CRUD、唯一约束冲突、`resolve` 展开（后端命中前端 / 前端命中后端 / 无绑定退化单项目）

## 3. 移除自动触发 + 前端实体化改显式

- [ ] 3.1 删除 `KgGenerationQueue.processItem` 收尾处对 `frontendGraphOrchestrator.run` 的自动调用
- [ ] 3.2 `FrontendGraphOrchestrator.run` 改为显式 `(backendProjectPath, frontendProjectPath)` 入参，不再自动发现前端目录
- [ ] 3.3 `FrontendProjectDiscoverer` 收敛为显式路径校验（或移除自动探测逻辑）
- [ ] 3.4 `/build` 端点串联：绑定建立 → `FrontendGraphOrchestrator` 前端实体化 → 前端向量化 → `FrontendBackendLinker` 跨层链接
- [ ] 3.5 单测：`FrontendGraphOrchestrator` 显式路径、无 `package.json` 报错、后端未建图报错

## 4. 前端节点向量化

- [ ] 4.1 `ComponentNode`/`FrontendRouteNode`/`ApiClientNode` 新增 `descriptionEmbedding`（`double[]`）字段
- [ ] 4.2 新增前端节点描述生成器（Component→组件名+描述；FrontendRoute→路径+目标组件；ApiClient→method+URL+源文件+组件名）
- [ ] 4.3 `EmbeddingService` 扩展：为三类前端节点批量生成向量
- [ ] 4.4 前端节点 repository 新增「批量读取无向量节点 + 写回 embedding」方法
- [ ] 4.5 `Neo4jInitializer` 为三类前端节点建 VECTOR INDEX（cosine）
- [ ] 4.6 `/build` 流程集成前端向量化并返回「已向量化前端节点数 / 失败数」
- [ ] 4.7 单测：描述生成、向量写回、失败不阻断实体化

## 5. 检索/总览绑定展开 + 前端节点命中

- [ ] 5.1 新增统一结果 DTO `KgSearchHit`（`nodeType` + 通用字段 + 类型特化字段，保留后端方法字段向后兼容）
- [ ] 5.2 `HybridSearchService` 增加前端节点向量检索（Component/FrontendRoute/ApiClient 三个索引），结果映射为 `KgSearchHit`
- [ ] 5.3 `VectorSearchController` 调用 `ProjectBindingService.resolve` 做绑定范围展开，返回 `KgSearchHit`
- [ ] 5.4 总览入口（`dashboard` 等）绑定范围展开 + 前后端节点计数区分
- [ ] 5.5 `QueryTypeDetector` 识别前端节点类型（Component/FrontendRoute/ApiClient）
- [ ] 5.6 单测：检索命中前端节点、绑定展开后前后端同返、`nodeType` 标识正确

## 6. 前端 UI

- [ ] 6.1 新增 `api/binding.ts`（绑定 CRUD + 联合构建端点）
- [ ] 6.2 `api/vectorSearch.ts` 请求/响应类型扩展（`KgSearchHit` + `nodeType`）
- [ ] 6.3 `KnowledgeGraphView` 新增「构建前后端完整图谱」按钮 + 绑定选择弹窗（后端下拉 + 前端目录选择）
- [ ] 6.4 绑定管理 UI（绑定列表 + 删除）
- [ ] 6.5 `SemanticSearchPanel` 检索结果按 `nodeType` 区分展示（前端节点用组件/路由/调用点字段与图标）
- [ ] 6.6 总览组件（`DashboardPanel` 等）前后端节点区分展示
- [ ] 6.7 `ProjectList.vue` 跨服务构建按钮空选择校验 + 错误提示修复
- [ ] 6.8 `pnpm build` 通过

## 7. MCP 适配

- [ ] 7.1 `knowledgeGraphTools.ts` 检索工具适配前端节点返回（`nodeType` 展示）

## 8. 回归验证

- [ ] 8.1 `mvn test` 全绿
- [ ] 8.2 `pnpm build` 通过
- [ ] 8.3 端到端：绑定构建 → 语义检索命中前端节点 → 总览前后端区分展示
