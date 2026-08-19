# ClassNode 实体节点 + 语义检索类型化 — 任务清单

## 1. ClassNode 实体（数据模型）

- [x] 1.1 新增 `neo4j/model/ClassNode.java`（classId/className/packageName/signature/classComment/description/descriptionEmbedding/methodCount/projectPath/language/framework）
- [x] 1.2 `Neo4jInitializer` 加 ClassNode 约束 + descriptionEmbedding 向量索引
- [x] 1.3 新增 `Neo4jClassNodeRepository`（CRUD + HAS_METHOD 边 + 向量检索）

## 2. 三层领域归属

- [x] 2.1 `MultiDimensionCommunityDetector` 写 ClassNode + `Domain -[:BELONGS_TO]-> ClassNode` + `ClassNode -[:HAS_METHOD]-> Method`
- [x] 2.2 `DomainNameGenerator` INTERACTS_WITH 边改走三层结构
- [x] 2.3 领域下钻端点改为读 ClassNode（DTO 不变 + description）

## 3. 类注释提取与类描述生成

- [x] 3.1 `CommentExtractor` 补类 Javadoc 提取（extractClassComment，作为后续接入点）
- [x] 3.2 类描述生成：聚合方法描述（有描述用描述，无则签名）；类注释优先后续接入
- [x] 3.3 类描述向量化（复用 EmbeddingService）

## 4. 语义检索类型化

- [x] 4.1 `SearchRequest` 加 searchType 枚举字段
- [x] 4.2 `HybridSearchService` 加 searchType 路由（CLASS 走类检索，其余/null 回退自动检测）
- [x] 4.3 CLASS 类型走 ClassNode.descriptionEmbedding 检索
- [x] 4.4 未传 searchType 回退 QueryTypeDetector 自动检测

## 5. MCP 适配

- [x] 5.1 KG 检索 MCP 工具（AgentTools.hybrid_search）加 searchType 参数透传

## 6. 内部 agent 适配（DirectKgClient + ProjectOverviewNode）

- [x] 6.1 `DirectKgClient` 新增 classSearch + representativeMethod 方法
- [x] 6.2 `KgMcpClient` 接口加 classSearch / representativeMethod 方法声明
- [x] 6.3 `ProjectOverviewNode` 改造为「先类后方法」：先 classSearch 定位类，取入度最高代表方法；类检索空回退方法检索
- [x] 6.4 KgSearchNode / Phase2AnalysisNode 保持方法粒度不动（不改）

## 7. 测试 + 回归

- [x] 7.1 ClassNode 实体 + repository 编译通过（CRUD 走 Spring Data，无需额外单测）
- [x] 7.2 类注释提取测试（CommentExtractorTest +3 用例）
- [x] 7.3 searchType 路由（编译验证，CLASS 走独立 classSearch）
- [x] 7.4 DirectKgClient classSearch + representativeMethod（编译验证）
- [x] 7.5 `mvn test` 全量回归（1103 tests 0 failures）+ `vue-tsc` 无新增错误
