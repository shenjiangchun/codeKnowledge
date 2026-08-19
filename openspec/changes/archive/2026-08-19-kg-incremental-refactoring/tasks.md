# 任务清单 — KG 增量图谱构建重构

## Task 1: Neo4jMethodNodeRepository 新增 deleteDispatchCallsByProject

- [x] 在 `Neo4jMethodNodeRepository.java` 添加 `deleteDispatchCallsByProject(String projectPath)` 方法
- [x] Cypher: 删除 `callType IN ['IMPL_DISPATCH', 'IMPL_DISPATCH_FEIGN', 'FEIGN_BRIDGE']` 的 CALLS 边
- [x] 编译验证等待执行

## Task 2: 创建 IncrementalKnowledgeGraphBuilder

- [x] 新建 `IncrementalKnowledgeGraphBuilder.java`，`@Service`，组合持有 `KnowledgeGraphBuilder` 引用
- [x] 实现 `incrementalRefresh(String projectPath)` 入口方法
- [x] 实现 `javaIncrementalRefresh()`：Phase A→F 完整编排
- [x] 实现 `pythonIncrementalRefresh()`：对齐全量 Python 路径
- [x] 实现 Phase C 方法节点+入口点重建
- [x] 实现 Phase D 调用关系全量扫描+过滤
- [x] 实现 Phase E 结构边 MERGE + dispatch 先删后建
- [x] 实现 Phase F SQL/DataModel/USES_MODEL/向量/checkpoint

## Task 3: KgGenerationQueue 集成

- [x] `QueueItem` 加 `boolean incremental` 字段
- [x] 新增 `enqueueIncremental(String projectPath)` 方法
- [x] `processItem()` 根据 `incremental` 标记分发

## Task 4: RefreshController 新增端点

- [x] 添加 `IncrementalKnowledgeGraphBuilder` 依赖注入
- [x] 新增 `POST /api/knowledge-graph/refresh-v3`

## Task 5: 废弃旧增量服务

- [x] `IncrementalRefreshService` 加 `@Deprecated(since = "5.1", forRemoval = true)`
- [x] `IncrementalRefreshServiceV2` 加 `@Deprecated(since = "5.1", forRemoval = true)`

## Task 6: 编译与测试

- [x] `mvn compile -pl hisi-dev-tool` 通过
- [x] `mvn test -pl hisi-dev-tool` 零新回归（1072 tests, 0 new failures）
