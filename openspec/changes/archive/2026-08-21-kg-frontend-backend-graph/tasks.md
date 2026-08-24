# 前端代码实体化 + 前后端跨层关系：实施任务清单

## 执行规则
- 权威状态源：`openspec/changes/kg-frontend-backend-graph/`
- 风险/闸门：High；实现闸门单题放行（含代价/风险/回滚摘要）
- 禁止范围：不建手动拖拽编辑器；不重写 JavaParser/codegraph 核心；不引入新图数据库；不落地 OpenAPI 契约关联与运行时追踪
- 必须执行的最终验证：`mvn -pl hisi-dev-tool test` 全绿 + `openspec validate kg-frontend-backend-graph`

## 任务

- [x] 任务 1：把 codegraph 的 component/route 映射为前端实体节点，替换「抹平成 Method」的降级行为
  - 对应需求/场景：frontend-code-entities「前端代码实体节点」「前端内部依赖边」
  - 前置依赖：无（先 grep 确认「前端=Method」既有消费者）
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/codegraph/CodegraphToNeo4jTransformer.java`（transform / toMethodNode / toEntryPoint）、新增 `neo4j/model/ComponentNode.java`、`FrontendRouteNode.java`
  - 允许修改：CodegraphToNeo4jTransformer 的节点映射逻辑、新增节点 model、Neo4jInitializer 约束
  - 禁止修改：JavaParser 建图主流程、后端 Method/Class/EntryPoint 现有语义
  - 实施步骤：1) grep 既有「前端以 Method 存在」的消费者；2) 新增 ComponentNode model；3) 改 transformer 把 component→Component 节点（route 保持 HTTP 端点 EntryPoint 语义不变）；4) Neo4jInitializer 加约束
  - 失败测试或已批准替代验证：先写单测断言「component 节点不再生成 Method，而生成 Component」
  - 验证命令/动作：`mvn -pl hisi-dev-tool test -Dtest='CodegraphToNeo4jTransformerTest'`
  - 预期结果：单测通过，component 映射为 Component 节点，route 仍映射 EntryPoint，无回归
  - 迁移/回滚：回滚 = 还原 transformer 映射 + 移除新约束
  - 完成定义：component 映射为 Component 实体，route 语义不变，单测绿
  - 负责人/冲突说明：独占 CodegraphToNeo4jTransformer.java，不与 T2 并行

- [x] 任务 2：新建前端 AST 解析器，提取 ApiClient + FrontendRoute 节点 + Component→ApiClient 边
  - 对应需求/场景：frontend-code-entities「前端 API 调用点解析为 ApiClient 节点」「前端路由解析为 FrontendRoute 节点」「组件到 API 调用点的发起关系」「路由到组件的导航关系」
  - 前置依赖：T1
  - 目标文件/符号：新增 `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/codegraph/FrontendAstParser.java`、`neo4j/model/ApiClientNode.java`、`neo4j/model/FrontendRouteNode.java`
  - 允许修改：新增解析器 + ApiClient/FrontendRoute 节点 model + Neo4jInitializer 约束
  - 禁止修改：codegraph sidecar 本体（CodegraphSidecarService）
  - 实施步骤：1) 实现 FrontendAstParser（扫 .vue/.ts 的 request.get/post/... + fetch( 提取 ApiClient；扫 router/*.ts 提取 vue-router 路由表生成 FrontendRoute + NAVIGATES 边）；2) 生成 ApiClient 节点 + Component-INVOKES->ApiClient 边 + FrontendRoute 节点；3) 落库
  - 失败测试或已批准替代验证：先写单测断言「request.get('/api/xxx') 被提取为 ApiClient 节点」「vue-router 路由表被提取为 FrontendRoute 节点」
  - 验证命令/动作：`mvn -pl hisi-dev-tool test -Dtest='FrontendAstParserTest'`
  - 预期结果：单测通过，提取 url/method/componentName + 路由 path/componentName 正确
  - 迁移/回滚：回滚 = 移除解析器调用
  - 完成定义：ApiClient 节点 + FrontendRoute 节点 + INVOKES/NAVIGATES 边落库，单测绿
  - 负责人/冲突说明：新增文件，与 T1 无文件冲突，可并行但依赖 T1 的 model 基础

- [x] 任务 3：新建独立链接阶段 FrontendBackendLinker，静态 URL 匹配构建 INVOKES_API 边
  - 对应需求/场景：frontend-backend-linkage「跨层 INVOKES_API 边」「路径参数归一化后匹配」
  - 前置依赖：T1、T2（前端 ApiClient 节点就绪）
  - 目标文件/符号：新增 `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/link/FrontendBackendLinker.java`（同构 CrossServiceLinker）、`link/UrlNormalizer.java`
  - 允许修改：新增链接器 + 归一化工具
  - 禁止修改：CrossServiceLinker 本体
  - 实施步骤：1) 实现 UrlNormalizer（`${var}`/`{var}`→`:param`）；2) 实现 FrontendBackendLinker（前端 ApiClient.url ↔ 后端 EntryPoint.entryKey 匹配）；3) 生成 INVOKES_API 边；4) 支持后端重建后重跑
  - 失败测试或已批准替代验证：先写单测断言「`/api/projects/${id}` 与 `GET /api/projects/{id}` 归一化后匹配」
  - 验证命令/动作：`mvn -pl hisi-dev-tool test -Dtest='FrontendBackendLinkerTest'`
  - 预期结果：单测通过，归一化匹配正确，INVOKES_API 边落库
  - 迁移/回滚：回滚 = 移除链接器调用 + 删除 INVOKES_API 边
  - 完成定义：跨层边落库，单测绿
  - 负责人/冲突说明：新增文件，无冲突

- [x] 任务 4：建图入口自动发现前端目录，触发前端实体化
  - 对应需求/场景：frontend-code-entities「前端目录自动发现」
  - 前置依赖：T1、T2
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java`（startTask）、新增 `service/FrontendProjectDiscoverer.java`
  - 允许修改：startTask 加前端目录探测、新增发现器
  - 禁止修改：任务队列核心逻辑
  - 实施步骤：1) 实现 FrontendProjectDiscoverer（扫同级 *-frontend 或显式前端路径，找 package.json）；2) startTask 发现前端目录后作为独立 projectPath 入队前端建图
  - 失败测试或已批准替代验证：先写单测断言「发现 package.json 返回前端目录，无则返回空」
  - 验证命令/动作：`mvn -pl hisi-dev-tool test -Dtest='FrontendProjectDiscovererTest'`
  - 预期结果：单测通过，自动发现正确，无 package.json 不报错
  - 迁移/回滚：回滚 = 移除探测调用
  - 完成定义：前端目录自动发现并入队，单测绿
  - 负责人/冲突说明：改动 KnowledgeGraphController.startTask，独占

- [x] 任务 5：MCP 层 language 放开 typescript + 新增跨层查询工具
  - 对应需求/场景：frontend-backend-linkage「MCP 层 language 放开 typescript」「跨层查询工具」
  - 前置依赖：T3（跨层边就绪）
  - 目标文件/符号：`hisi-mcp-server/src/tools/knowledgeGraphTools.ts`（language 枚举）、新增跨层查询工具定义
  - 允许修改：knowledgeGraphTools.ts language 枚举 + 新工具 schema
  - 禁止修改：MCP 传输层协议
  - 实施步骤：1) language 枚举加 typescript；2) 新增 kg_api_consumers 工具（查某后端接口的前端调用方）；3) 新增 kg_frontend_deps 工具（查某前端组件的后端依赖）
  - 失败测试或已批准替代验证：schema 校验 + 手测工具返回结构
  - 验证命令/动作：`cd hisi-mcp-server && npx tsc --noEmit`
  - 预期结果：编译通过，工具 schema 合法
  - 迁移/回滚：回滚 = 移除新工具 + 枚举回退
  - 完成定义：language=typescript 可检索前端实体，跨层查询工具可用
  - 负责人/冲突说明：独占 knowledgeGraphTools.ts

- [x] 任务 6：前端新增「前后端跨层」Tab + 跨层关系可视化
  - 对应需求/场景：frontend-backend-linkage（跨层可视化）
  - 前置依赖：T3、T5（后端跨层边 + MCP 工具就绪）
  - 目标文件/符号：新增 `hisi-dev-tool-frontend/src/views/knowledge-graph/components/FrontendBackendTab.vue`、`src/api/frontendBackend.ts`；改 `KnowledgeGraphView.vue`（加 Tab）
  - 允许修改：新增 Tab 组件 + API 模块 + KnowledgeGraphView 加 Tab
  - 禁止修改：现有 Tab 组件
  - 实施步骤：1) 新增 frontendBackend.ts API 模块；2) 新增 FrontendBackendTab.vue（跨层链路图，点击组件高亮后端接口/反向）；3) KnowledgeGraphView 加 Tab
  - 失败测试或已批准替代验证：前端构建 + 手测交互
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npm run build`
  - 预期结果：构建通过，跨层 Tab 可渲染链路
  - 迁移/回滚：回滚 = 移除 Tab + API 模块
  - 完成定义：跨层 Tab 可展示 Component→ApiClient→EntryPoint 链路
  - 负责人/冲突说明：独占前端跨层 Tab，与 T5 无文件冲突

## 集成顺序
T1 → T2 → T3 → T4 → T5 → T6（T2/T4 依赖 T1；T3 依赖 T1+T2；T5/T6 依赖 T3）

## 最终验证
| 命令/动作 | 覆盖范围 | 预期结果 |
|---|---|---|
| `mvn -pl hisi-dev-tool test` | 后端全量单测 | 全绿，无回归 |
| `cd hisi-mcp-server && npx tsc --noEmit` | MCP TS 编译 | 通过 |
| `cd hisi-dev-tool-frontend && npm run build` | 前端构建 | 通过 |
| `openspec validate kg-frontend-backend-graph` | change 结构 | valid |
