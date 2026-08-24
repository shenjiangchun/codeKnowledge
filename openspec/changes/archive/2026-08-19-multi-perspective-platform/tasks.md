# 多切面分析平台（架构 Copilot）：实施任务清单

## 执行规则
- 权威状态源：`openspec/changes/multi-perspective-platform/`
- 风险/闸门：High — 每阶段完成后必须 `mvn test` 全量绿灯 + 人工审查 diff
- 禁止范围：禁止修改 `AnthropicHttpClient.java`、`ZhipuService.java`、已有 MCP 工具的接口签名
- 必须执行的最终验证：`mvn test`（后端 ~315 KG 测试 + ~111 Neo4j 测试）+ 前端 `npx vitest run`
- **⚠️ 路线修正说明**：Phase 1 的 task 1.3（HotspotScorer）、1.5（CommunityDetector）、1.6（DomainNameGenerator）的初版实现含两个已被审查否决的做法——①Louvain 包名种子（×1.3）、②方法级 riskScore。Phase 8（领域检测路线重做 + 文件级热点修正）对这些实现做了路线级修正。**执行时以 Phase 8 为准**，Phase 1 的 1.3/1.5/1.6 仅保留其"新建文件/类"的骨架价值，其"包名种子""方法级 riskScore""包名置信度"三个具体做法均已废弃。

## 任务

### Phase 0: 数据模型基础（所有后续阶段的前置依赖）

- [x] 任务 0.1：MethodNode 新增 5 个属性 + mergeAll SET 子句只加 packageName
  - 对应需求/场景：聚合管道 spec — MethodNode 新增属性与构建属性分离
  - 前置依赖：无
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/MethodNode.java`（加 @Property 字段）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java`（mergeAll SET 加 m.packageName = n.packageName）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/storage/Neo4jStorageService.java`（saveMethodNodes map.put）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/config/Neo4jInitializer.java`（加 packageName RANGE INDEX）
  - 允许修改：上述 4 个文件 + 对应测试
  - 禁止修改：已有 40+ Cypher 查询的 RETURN 子句（不需要加新字段）
  - 实施步骤：
    1. MethodNode.java 在 codeHash 后加 `@Property("packageName") private String packageName;` + 其他 4 个 @Property
    2. Neo4jMethodNodeRepository.mergeAll 的 SET 字符串加 `m.packageName = n.packageName`
    3. Neo4jStorageService.saveMethodNodes 的 map.put 加 `map.put("packageName", node.getPackageName());`
    4. Neo4jInitializer.RANGE_INDEXES 加 `"CREATE INDEX method_packageName_index IF NOT EXISTS FOR (m:Method) ON (m.packageName)"`
    5. 所有 MethodNode 构建点（KnowledgeGraphBuilder.java lines 854/878/908/1004, CodegraphToNeo4jTransformer.java line 204, PythonKnowledgeGraphBuilder.java lines 541/561/583）加 `.packageName(...)` —— 包名从 className 提取（lastIndexOf('.') 之前的部分）
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="NodeFieldsTest,Neo4jInitializerIndexTest,Neo4jStorageServiceDefaultsTest" -DfailIfNoTests=false`
  - 预期结果：所有测试通过，新属性在 Serialization/Index/Save 测试中验证
  - 完成定义：4 个文件修改 + 测试通过 + packageName 在 mergeAll SET 中

- [x] 任务 0.2：新增 Neo4j 节点/关系类型（4 节点 + 5 关系）
  - 对应需求/场景：聚合管道 spec — 全部需求
  - 前置依赖：任务 0.1
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/ChurnNode.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/ModuleNode.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/DomainNode.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/AggregationCheckpoint.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/config/Neo4jInitializer.java`（加约束/索引）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/storage/Neo4jStorageService.java`（cleanProjectData 扩展）
  - 允许修改：上述 6 个文件 + 对应新建测试
  - 禁止修改：已有节点类型的 SDN 注解
  - 实施步骤：
    1. 创建 ChurnNode（@Node("ChurnNode")：filePath+commitCount90d+linesChanged90d+lastCommitAt+authorCount90d+projectPath）
    2. 创建 ModuleNode（@Node("ModuleNode")：moduleId+moduleName+level+methodCount+classCount+entryPointCount+avgComplexity+inDegree+outDegree+instability+layerRole+projectPath+language）
    3. 创建 DomainNode（@Node("DomainNode")：domainId+domainName+confidence+packageRoots+methodCount+classCount+entryPoints+projectPath）
    4. 创建 AggregationCheckpoint（@Node("AggregationCheckpoint")：checkpointId+projectPath+stageName+status+lastSuccessAt+errorMessage+dataHash）
    5. Neo4jInitializer 加 ChurnNode/ModuleNode/DomainNode/AggregationCheckpoint 的约束和索引
    6. Neo4jStorageService.cleanProjectData 加 4 种新节点的 DETACH DELETE（按 projectPath）
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="ChurnNodeTest,ModuleNodeTest,DomainNodeTest,AggregationCheckpointTest,Neo4jInitializerIndexTest" -DfailIfNoTests=false`
  - 预期结果：所有 POJO 测试通过 + 索引创建成功
  - 完成定义：4 个新 Node 类型可持久化到 Neo4j + cleanProjectData 可清理

- [ ] 任务 0.3：分层迁移 API — /api/admin/migrate-to-v5（**已决定不做**：历史项目通过全量重建补齐聚合数据，不单独提供迁移 API）
  - 对应需求/场景：聚合管道 spec — 历史数据迁移
  - 前置依赖：任务 0.1, 0.2
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java`（新增 POST /api/knowledge-graph/migrate）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/migration/MigrationService.java`（新建）
  - 允许修改：上述 2 个文件 + 对应新建测试
  - 禁止修改：已有 30+ 端点的接口签名
  - 实施步骤：
    1. 创建 MigrationService：Layer 1 — 纯 Cypher packageName 回填 + inDegree/outDegree 计算 + ModuleNode GROUP BY 聚合
    2. Layer 2 — Louvain communityId + LLM domainName + DSM 矩阵
    3. Layer 3 — git log ChurnNode + riskScore
    4. Controller 新增 POST /api/knowledge-graph/migrate?projectPaths=a,b，返回 {layers:{1:status,2:status,3:status}}
    5. 前端 KnowledgeGraphView.vue 空状态页加 [一键迁移] 按钮
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="MigrationServiceTest" -DfailIfNoTests=false`
  - 预期结果：已构建的 KG 项目经过 migrate 后，packageName/inDegree/outDegree 全部非 null
  - 完成定义：迁移 API 3 层全部可执行 + 测试通过

### Phase 1: 聚合管道（数据计算核心）

- [x] 任务 1.1：AggregationCheckpointManager — 聚合 Stage 的 checkpoint 读写
  - 对应需求/场景：聚合管道 spec — 聚合失败降级
  - 前置依赖：任务 0.2
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/AggregationCheckpointManager.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/AggregationCheckpointRepository.java`（新建）
  - 允许修改：上述新建文件 + 对应测试
  - 禁止修改：无
  - 实施步骤：
    1. AggregationCheckpointRepository：Cypher MERGE AggregationCheckpoint，按 projectPath+stageName 查询
    2. AggregationCheckpointManager：save(projectPath, stageName, status)、getLastSuccess(projectPath, stageName)、markFailed(projectPath, stageName, errorMessage)
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="AggregationCheckpointManagerTest,AggregationCheckpointRepositoryTest" -DfailIfNoTests=false`
  - 预期结果：checkpoint 可写入 Neo4j、读取、查询最近成功状态
  - 完成定义：CRUD 操作全部可测试

- [x] 任务 1.2：ModuleStatsAggregator（Stage 1）+ DsmMatrixBuilder（Stage 2）
  - 对应需求/场景：聚合管道 spec — 全量/增量聚合；API spec — dashboard + dsm 端点
  - 前置依赖：任务 0.1, 1.1
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/ModuleStatsAggregator.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/DsmMatrixBuilder.java`（新建）
    - Neo4jMethodNodeRepository 新增批量更新 inDegree/outDegree 的 Cypher
  - 允许修改：上述新建文件 + Neo4jMethodNodeRepository（追加查询）+ 对应测试
  - 禁止修改：已有查询的 RETURN 格式
  - 实施步骤：
    1. ModuleStatsAggregator：Cypher GROUP BY packageName → 统计 methodCount/classCount/entryPointCount/avgComplexity/inDegree/outDegree → MERGE ModuleNode + SET instability = outDegree/(inDegree+outDegree)
    2. 批量更新 MethodNode.inDegree/outDegree（UNWIND batch）
    3. DsmMatrixBuilder：读取 ModuleNode 集合 → 计算 N×N 矩阵 → 写入 DEPENDS_ON {weight, bridgeTypes}
    4. 增量模式：从 rebuiltNodeIds 推导脏模块集合 → 只重算脏模块 + 脏行/列
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="ModuleStatsAggregatorTest,DsmMatrixBuilderTest" -DfailIfNoTests=false`
  - 预期结果：8000 方法项目 → ModuleNode 数量 = 实际包数，DEPENDS_ON 边正确
  - 完成定义：全量和增量模式均通过测试

- [x] 任务 1.3：HotspotScorer（Stage 3）— 风险评分引擎
  - 对应需求/场景：聚合管道 spec — 失败行为（3次重试）
  - 前置依赖：任务 1.2（需要 inDegree/outDegree）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/HotspotScorer.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/RiskScoreCalculator.java`（新建 — 分段映射归一化）
  - 允许修改：上述新建文件 + 对应测试
  - 禁止修改：无
  - 实施步骤：
    1. RiskScoreCalculator：实现分段映射函数（圈复杂度：0-10→0-0.25, 11-20→0.25-0.5, 21-50→0.5-0.85, 50+→0.85-1.0；churn+入度+出度：百分位排名）
    2. 综合公式：riskScore = complexityNorm×0.35 + churnNorm×0.35 + inDegreeNorm×0.20 + cyclePenalty×0.10
    3. HotspotScorer：读取所有 MethodNode 的 complexity + ChurnNode 的 commitCount90d → 计算归一化 → 批量更新 riskScore
    4. 重试逻辑：try-catch 包裹，5s/15s/60s 指数退避 × 3 次 → 最终失败写 AggregationCheckpoint FAILED
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="RiskScoreCalculatorTest,HotspotScorerTest" -DfailIfNoTests=false`
  - 预期结果：riskScore 计算正确（0.0-1.0），分段映射边界正确
  - 完成定义：风险分公式测试通过 + 重试机制测试通过

- [x] 任务 1.4：ChurnCollector（Stage 4）— Git 变更频率
  - 对应需求/场景：聚合管道 spec — 全量/增量聚合
  - 前置依赖：任务 0.2（需要 ChurnNode）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/ChurnCollector.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/GitStatusServiceJgit.java`（扩展：加 getCommitHistory 方法）
  - 允许修改：上述文件 + 对应测试
  - 禁止修改：GitStatusServiceJgit 已有方法的签名
  - 实施步骤：
    1. GitStatusServiceJgit 新增 `getCommitHistory(filePath, sinceDays)`：JGIT `git.log().addPath(filePath)` → 统计 commitCount/sum(linesAdded+linesDeleted)/distinctAuthors/lastCommitAt
    2. ChurnCollector：遍历项目所有文件 → 调用 getCommitHistory → MERGE ChurnNode
    3. 增量模式：只重算 changedFiles 中的文件
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="ChurnCollectorTest,GitStatusServiceJgitTest" -DfailIfNoTests=false`
  - 预期结果：ChurnNode.commitCount90d > 0 for actively changed files
  - 完成定义：Git 变更频率可正确解析并写入 Neo4j

- [x] 任务 1.5：CommunityDetector（Stage 5）— Louvain 社区检测
  - 对应需求/场景：聚合管道 spec — 领域检测
  - 前置依赖：任务 1.2（需要 ModuleNode 数据）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/CommunityDetector.java`（新建）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/LouvainClusterer.java`（新建 — 算法核心）
    - Neo4jMethodNodeRepository 新增 `batchUpdateCommunityId` Cypher
  - 允许修改：上述新建文件 + Neo4jMethodNodeRepository（追加查询）+ 对应测试
  - 禁止修改：无
  - 实施步骤：
    1. LouvainClusterer：使用 JGraphT 的 SimpleWeightedGraph 构建邻接表 → 实现 Louvain 两阶段迭代（Local Moving + Network Aggregation）
    2. 包层次种子：同一 packageName 前缀的节点间边权重 ×1.3（粘性惩罚）
    3. CommunityDetector：读取 CALLS 边 → 运行 Louvain → 批量写入 communityId → 计算新旧社区分配的重叠率 → 若 >15% 漂移则写标记
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="LouvainClustererTest,CommunityDetectorTest" -DfailIfNoTests=false`
  - 预期结果：2 个完全断开的子图 → 2 个社区；已知全连接图 → 1 个社区
  - 完成定义：Louvain 算法正确分区 + communityId 已批量写入

- [x] 任务 1.6：DomainNameGenerator（Stage 6）— LLM 领域命名
  - 对应需求/场景：聚合管道 spec — 领域命名
  - 前置依赖：任务 1.5（需要 communityId）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/DomainNameGenerator.java`（新建）
  - 允许修改：上述新建文件 + 对应测试
  - 禁止修改：无
  - 实施步骤：
    1. DomainNameGenerator：按 communityId 分组 → 收集每组的 classNames + 已有 description → 构建 Claude prompt："基于以下类列表，为这个业务领域生成 2-4 字中文名称"
    2. 复用已有 agentChatClient（Spring AI ChatClient），每个社区 1 次调用
    3. 置信度计算：包层次种子与 Louvain 结果的一致性（一致节点数 / 总节点数）
    4. MERGE DomainNode + BELONGS_TO 边
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="DomainNameGeneratorTest" -DfailIfNoTests=false`
  - 预期结果：mock LLM 返回 "订单域" → DomainNode.name="订单域"
  - 完成定义：DomainNode 创建 + BELONGS_TO 边写入

- [x] 任务 1.7：AggregationPipeline 编排器 + 构建管道注入
  - 对应需求/场景：聚合管道 spec — 聚合管道在构建完成后自动运行
  - 前置依赖：任务 1.1-1.6 全部完成
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/AggregationPipeline.java`（新建 — 编排 6 Stage）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`（5 个注入点各 1 行）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/IncrementalKnowledgeGraphBuilder.java`（2 个注入点各 1 行）
  - 允许修改：上述 3 个文件 + 对应测试
  - 禁止修改：KnowledgeGraphBuilder 的构建逻辑（注入点是唯一的改动）
  - 实施步骤：
    1. AggregationPipeline：run(projectPath, mode=FULL|INCREMENTAL, rebuiltNodeIds=null) → 按顺序执行 Stage 1-6，每个 Stage 自动 checkpoint + 重试，失败不阻断后续（Stage 3 失败 → Stage 4-5 跳过，Stage 5 失败 → Stage 6 跳过）
    2. KnowledgeGraphBuilder：buildJavaKnowledgeGraph() line 606 后加 `aggregationPipeline.run(projectPath, FULL)`；buildPythonKnowledgeGraph() line 258 后同理；buildCodegraphKnowledgeGraph() line 323 后同理
    3. IncrementalKnowledgeGraphBuilder：javaIncrementalRefresh() line 520 后加 `aggregationPipeline.run(projectPath, INCREMENTAL, rebuiltNodeIds)`；pythonIncrementalRefresh() line 718 后同理
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="AggregationPipelineTest,KnowledgeGraphBuilderCodegraphDispatchTest" -DfailIfNoTests=false`
  - 预期结果：全量构建完成后 ModuleNode/ChurnNode/DomainNode 全部存在于 Neo4j
  - 完成定义：聚合管道在构建后自动运行，全量和增量模式均验证

### Phase 2: REST API 端点

- [x] 任务 2.1：6 个新聚合端点 + Controller
  - 对应需求/场景：API 端点 spec — 全部需求
  - 前置依赖：任务 1.7（需要聚合数据已写入 Neo4j）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java`（追加 6 个 GET 方法）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphV2Controller.java`（追加 6 个委托方法）
  - 允许修改：上述 2 个文件 + 对应测试
  - 禁止修改：已有 35+ 端点的接口签名
  - 实施步骤：
    1. KnowledgeGraphController 加 6 个方法：getDashboard/getDsm/getHotspots/getDomains/getServiceTopology/getBlastRadius（每个遵循已有模式：@GetMapping + ProjectPathResolver + ApiResponse）
    2. blast-radius 方法内部调用已有的 findCalleesUpToDepth + findCallersUpToDepth + findBridgesByType → 聚合为一个 BlastRadiusData
    3. KnowledgeGraphV2Controller 加 6 个委托方法（复用已有 projectPaths List 模式）
    4. 3 个已有方法补字段：getMethodDetail/getMethodsByClass/toMethodSummary 的响应 Map 加 serviceName/language/framework
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="AggregationDashboardControllerTest,DsmControllerTest,HotspotControllerTest,DomainsControllerTest,ServiceTopologyControllerTest,BlastRadiusControllerTest" -DfailIfNoTests=false`
  - 预期结果：6 个新端点 + 3 个修正端点全部返回正确 JSON
  - 完成定义：所有端点通过 Controller 测试

### Phase 3: MCP 工具

- [x] 任务 3.1：6 个新 kg_* MCP 工具
  - 对应需求/场景：MCP 工具 spec — 全部需求
  - 前置依赖：任务 2.1（需要后端端点可用）
  - 目标文件/符号：
    - `hisi-mcp-server/src/tools/knowledgeGraphTools.ts`（追加 6 个 tool 定义 + handler 分支）
    - `hisi-mcp-server/src/tools/index.ts`（KG_TOOLS 数组从 15 扩展到 21 + 导出新参数类型）
  - 允许修改：上述 2 个文件 + 对应测试
  - 禁止修改：已有 15 个工具的定义和 handler
  - 实施步骤：
    1. knowledgeGraphTools.ts：在 knowledgeGraphToolDefinitions 数组中加 6 个新定义（kg_dashboard/kg_dsm/kg_hotspots/kg_domains/kg_service_topology/kg_blast_radius），每个遵循已有参数模式
    2. KnowledgeGraphTools 类加 6 个方法，每个调用对应的 REST 端点
    3. handleKnowledgeGraphToolCall switch 加 6 个 case
    4. index.ts KG_TOOLS 数组更新 + 导出新参数类型接口
  - 验证命令/动作：`cd hisi-mcp-server && npx tsx src/utils/pathUtils.test.ts`（若有 MCP 测试则运行）
  - 预期结果：MCP Server 的 ListTools 响应包含 21 个工具
  - 完成定义：6 个新 MCP 工具可被调用并正确代理到后端

### Phase 4: 前端基础设施

- [x] 任务 4.1：G6 v5 集成 + 前端 API 层扩展
  - 对应需求/场景：前端 spec — G6 v5 渲染；API 端点 spec
  - 前置依赖：任务 3.1（需要 MCP 和后端可用 — 前端可用 mock 开发）
  - 目标文件/符号：
    - `hisi-dev-tool-frontend/package.json`（加 @antv/g6 + @antv/g6-vue；移除未使用的 cytoscape/cytoscape-fcose/@vue-flow）
    - `hisi-dev-tool-frontend/src/api/knowledgeGraph.ts`（MethodNode 接口加 5 个字段 + 6 个新 API 函数 + 6 个新 TypeScript 类型）
  - 允许修改：上述 2 个文件
  - 禁止修改：已有 API 函数的签名
  - 实施步骤：
    1. npm install @antv/g6 @antv/g6-vue；npm uninstall cytoscape cytoscape-fcose @vue-flow/core @vue-flow/background @vue-flow/controls
    2. knowledgeGraph.ts：MethodNode 接口加 packageName?/inDegree?/outDegree?/communityId?/riskScore?: number
    3. 加 6 个新 API 函数：getDashboard/getDsm/getHotspots/getDomains/getServiceTopology/getBlastRadius
    4. 加 6 个新类型：DashboardData/DsmMatrix/DsmCell/HotspotItem/DomainCluster/ServiceTopologyGraph/ServiceTopologyNode/ServiceTopologyEdge/BlastRadiusData
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx tsc --noEmit`（类型检查通过）
  - 预期结果：TypeScript 编译无错误，新类型可被其他组件 import
  - 完成定义：G6 v5 可被 import + 前端 API 层就绪

- [x] 任务 4.2：多切面 Tab 布局框架 + 空状态引导
  - 对应需求/场景：前端 spec — 首页 5 秒回答；空状态引导；路由模式
  - 前置依赖：任务 4.1
  - 目标文件/符号：
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/KnowledgeGraphView.vue`（扩展 el-tabs：加 6 个新 Tab）
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/DashboardPanel.vue`（新建 — 架构仪表盘首页）
    - `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`（加新菜单项）
  - 允许修改：上述 3 个文件
  - 禁止修改：已有 FlowDag/ChainChart 的渲染逻辑
  - 实施步骤：
    1. KnowledgeGraphView.vue：el-tabs 加 [🏗️仪表盘] [📊DSM] [🎯热点] [🧩领域] [🌐跨服务] [⚡生成] 6 个 Tab
    2. 空状态组件：projects=[] → 引导卡片"尚未构建知识图谱" + 项目选择器 + [开始生成] 按钮 + 预计耗时
    3. 构建进度条：通过已有 polling（每 2s GET /tasks/status）驱动进度展示
    4. 上次结果降级展示：构建中同时展示上次成功数据 + 黄色提示条"数据更新中，当前展示上次构建结果"
    5. AppSidebar.vue 菜单项更新
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx vitest run --reporter=verbose 2>&1 | head -30`
  - 预期结果：Tab 切换正常，空状态引导正确展示
  - 完成定义：6 个 Tab 可切换 + 空状态可展示

### Phase 5: 前端切面视图

- [x] 任务 5.1：架构仪表盘首页 + DSM 矩阵
  - 对应需求/场景：前端 spec — 首页需求 + DSM 矩阵需求
  - 前置依赖：任务 4.2
  - 目标文件/符号：
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/DashboardPanel.vue`（架构评分 + KPI 卡片 + Top 风险 + 模块架构G6 图）
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/DsmMatrix.vue`（新建 — ECharts heatmap）
  - 允许修改：上述文件
  - 禁止修改：无
  - 实施步骤：
    1. DashboardPanel：顶部一句话状态（架构评分 78/100 ↗ +3）+ 4 个 el-statistic 卡片 + "立即关注"列表（Top 3 风险，每条含类名+问题+动作按钮）+ 底部 G6 模块架构图（dagre 布局，节点=模块，边=DEPENDS_ON）
    2. DsmMatrix：ECharts heatmap，N×N 矩阵，颜色=依赖强度，红色边框=循环依赖，红色背景=分层违规，点击单元格 → 弹出"谁依赖了谁"列表
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx vitest run --reporter=verbose 2>&1 | grep -E "(PASS|FAIL|Tests)"`
  - 预期结果：仪表盘渲染架构评分 + KPI + 风险列表；DSM 渲染 N×N 热力图
  - 完成定义：仪表盘和 DSM 矩阵可用

- [x] 任务 5.2：热点分析 + 领域边界 + 跨服务拓扑
  - 对应需求/场景：前端 spec — 热点 Treemap + 领域力导向图 + 跨服务拓扑需求
  - 前置依赖：任务 5.1
  - 目标文件/符号：
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/HotspotTreemap.vue`（新建）
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/DomainBoundaryView.vue`（新建）
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/CrossServiceTopology.vue`（新建 — 增强已有 CrossServiceBridgeTab）
  - 允许修改：上述新建文件
  - 禁止修改：已有 CrossServiceBridgeTab（可被新组件替代，但保留）
  - 实施步骤：
    1. HotspotTreemap：ECharts treemap，面积=代码行数，颜色=风险分（红→绿），点击方块 → 详情面板，右上角视图切换
    2. DomainBoundaryView：G6 v5 force layout，5 种颜色分组，组间连线粗细=调用强度，置信度边框（>0.8 实线/<0.8 虚线）
    3. CrossServiceTopology：G6 v5 force layout，服务节点 + bridgeType 着色边（Feign=红/MQ=橙/HTTP=黄），节点大小=方法数
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx vitest run --reporter=verbose 2>&1 | grep -E "(PASS|FAIL|Tests)"`
  - 预期结果：3 个视图各自渲染正确
  - 完成定义：热点/领域/跨服务视图可用

### Phase 6: 爆炸半径 + 生成中心

- [x] 任务 6.1：爆炸半径前端展示
  - 对应需求/场景：前端 spec — 爆炸半径在调用链切面 + 独立弹窗
  - 前置依赖：任务 5.1（需要仪表盘基础）
  - 目标文件/符号：
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/BlastRadiusPanel.vue`（新建）
    - 调用链切面中集成"查看爆炸半径"按钮
  - 允许修改：上述文件
  - 禁止修改：FlowDag.vue 核心渲染
  - 实施步骤：
    1. BlastRadiusPanel：独立弹窗/侧边栏，展示上游入口点列表 + 下游调用链（复用 FlowDag 渲染） + 跨服务波及服务列表 + riskSummary + suggestedTests
    2. 调用链切面：方法节点右键菜单加"查看爆炸半径"→ 弹出 BlastRadiusPanel
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx vitest run --reporter=verbose 2>&1 | grep -E "(PASS|FAIL|Tests)"`
  - 预期结果：爆炸半径面板展示完整影响面数据
  - 完成定义：爆炸半径可从前端触发并展示

- [x] 任务 6.2：生成中心后端 — Agent Type + @Tool 扩展
  - 对应需求/场景：生成中心 spec — kg_test_suggestions + kg_refactor_suggestions
  - 前置依赖：任务 2.1（需要后端端点可用）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/tools/AgentTools.java`（加 4 个 @Tool）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/config/AgentTypeRegistry.java`（注册 "test-gen"）
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/GenerationController.java`（新建 — kg_test_suggestions/kg_refactor_suggestions 端点）
  - 允许修改：上述 3 个文件 + 对应测试
  - 禁止修改：已有 AgentTools 的 10 个 @Tool 方法
  - 实施步骤：
    1. AgentTools 加 4 个 @Tool：getBlastRadius(nodeId, projectPath)、getHotspotSummary(projectPath)、getDsmViolations(projectPath)、getDomainBoundaries(projectPath)
    2. AgentTypeRegistry：注册 "test-gen" → systemPrompt="你是一个架构分析专家，可以查询代码图谱来生成测试建议和重构建议" + provider="anthropic" + tools=AgentTools.all()
    3. GenerationController：POST /api/knowledge-graph/test-suggestions（输入 nodeId+projectPaths → 计算爆炸半径 → 构建 Claude prompt → 返回结构化 testCases JSON）；POST /api/knowledge-graph/refactor-suggestions（输入 moduleName+projectPaths → 查询 DSM/Hotspot → 构建 Claude prompt → 返回 refactoringPriority JSON）
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="AgentToolsTest,GenerationControllerTest" -DfailIfNoTests=false`
  - 预期结果：@Tool 可被 Spring AI 发现，test-gen Agent 可被创建，后端端点返回 Claude 生成的测试建议
  - 完成定义：生成中心后端就绪 + 2 个 MCP 工具可用

- [x] 任务 6.3：生成中心前端面板
  - 对应需求/场景：前端 spec — 生成中心面板上下文感知
  - 前置依赖：任务 6.2
  - 目标文件/符号：
    - `hisi-dev-tool-frontend/src/views/knowledge-graph/components/GenerationPanel.vue`（新建）
  - 允许修改：上述文件
  - 禁止修改：无
  - 实施步骤：
    1. GenerationPanel：上下文感知（监听全局选中的方法/模块）→ 自动切换内容：选中方法 → 爆炸半径概要 + 测试建议列表 + [复制为 prompt] [导出]；选中模块 → 重构优先级建议 + [采纳/忽略]
    2. 测试建议列表：每条 = 场景描述 + 类型（NORMAL/EXCEPTION/BOUNDARY/INTEGRATION）+ 优先级标签（红/黄/蓝）
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx vitest run --reporter=verbose 2>&1 | grep -E "(PASS|FAIL|Tests)"`
  - 预期结果：面板根据上下文自动切换内容
  - 完成定义：生成中心面板可用

### Phase 7: 最终集成与回归

- [ ] 任务 7.1：全量回归测试 + 集成验证
  - 对应需求/场景：全部 spec
  - 前置依赖：所有 Phase 0-6 任务完成
  - 目标文件/符号：全部修改文件
  - 允许修改：测试文件
  - 禁止修改：无（只运行测试）
  - 实施步骤：
    1. `cd hisi-dev-tool && mvn test` — 后端全量回归（已有 ~315 KG 测试 + ~111 Neo4j 测试 + 新增聚合测试）
    2. `cd hisi-dev-tool-frontend && npx vitest run` — 前端全量回归
    3. `cd hisi-mcp-server && npx tsx src/utils/pathUtils.test.ts` — MCP 工具测试
    4. 手动验证：启动后端 → 构建 KG → 打开前端仪表盘 → 检查 6 个切面 → 测试爆炸半径 → 测试生成中心
  - 验证命令/动作：`cd hisi-dev-tool && mvn test && cd ../hisi-dev-tool-frontend && npx vitest run`
  - 预期结果：所有已有测试通过 + 所有新增测试通过
  - 完成定义：全量回归绿灯 + 手动冒烟通过

### Phase 8: 领域检测路线重做 + 文件级热点修正

> 背景：审查发现 Phase 2 的实现有两处根本偏差：①领域检测的"4维共识"实际仍是包名/类名驱动（维度1=包名前缀、维度2=被包名种子污染的Louvain、维度3=类名加工），未引入真实业务语义；②热点是方法级而非文件级，且 inCycle/变更行数等字段有 bug。本 Phase 彻底修正，符合审查后重写的 spec。

- [x] 任务 8.0：去掉 CommunityDetector 的包名种子（×1.3 sticky weight）
  - 对应需求/场景：aggregation-pipeline spec — 领域检测（纯依赖图，无包名种子）
  - 前置依赖：无
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/CommunityDetector.java`
  - 允许修改：仅 loadGraph 方法（移除 PACKAGE_STICKY_WEIGHT 逻辑）
  - 禁止修改：Louvain 算法本身、其他 Stage
  - 实施步骤：
    1. 移除 `PACKAGE_STICKY_WEIGHT = 1.3` 常量
    2. 移除 loadGraph 中"同包边 ×1.3"的污染逻辑（约 68-70 行），边权重统一为 count(r)
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="CommunityDetectorTest" -DfailIfNoTests=false`
  - 预期结果：Louvain 结果只反映真实调用关系，不受包名影响
  - 完成定义：无包名种子，纯依赖图社区检测

- [x] 任务 8.1：重写领域检测——纯依赖图 Louvain + LLM 业务名词提取
  - 对应需求/场景：aggregation-pipeline spec — 领域检测（技术耦合 + LLM 业务语义）
  - 前置依赖：任务 8.0（去包名种子）
  - 目标文件/符号：
    - `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/MultiDimensionCommunityDetector.java`（重写为 BusinessSemanticDomainDetector，或重写其 detect 逻辑）
  - 允许修改：该文件（可整体重写）
  - 禁止修改：CommunityDetector（已去种子）、ChurnCollector
  - 实施步骤：
    1. 技术耦合信号：复用去种子的 CommunityDetector 得到 communityId
    2. 业务语义信号：对每个类（按 className 去重）调用 LLM，从类名+注解+注释提取业务名词（如 OrderService→"订单"），写入内存映射 className→businessNoun
    3. 融合：相同 businessNoun 的类归为同一领域；若一个业务名词跨多个技术社区，合并为一个领域
    4. 最终 communityId 覆盖写入 MethodNode（领域粒度）
    5. 清理旧数据：重写后，需删除旧包名驱动的 DomainNode（`MATCH (d:DomainNode) WHERE d.projectPath IN $paths DETACH DELETE d`）并清空旧 communityId，否则重跑聚合后新旧领域混淆
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="MultiDimensionCommunityDetectorTest" -DfailIfNoTests=false`
  - 预期结果：领域由业务名词驱动（订单域/支付域），不是包名
  - 完成定义：领域 = 纯依赖图 + LLM 业务名词融合，旧包名 DomainNode 已清理

- [x] 任务 8.2：重写 DomainNameGenerator——LLM 提取业务名词命名领域
  - 对应需求/场景：aggregation-pipeline spec — 业务名词驱动领域命名
  - 前置依赖：任务 8.1（业务语义提取已完成）
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/DomainNameGenerator.java`
  - 允许修改：该文件（可整体重写）
  - 禁止修改：其他 Stage
  - 实施步骤：
    1. domainName 直接用任务 8.1 提取的业务名词（如"订单""支付"），加"域"后缀
    2. 移除 packageBasedName 回退（社区>30 跳 LLM 的逻辑）
    3. confidence 改为"业务语义与技术耦合的一致程度"（同一业务名词的类是否在同一技术社区），非包名同质度
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="DomainNameGeneratorTest" -DfailIfNoTests=false`
  - 预期结果：DomainNode.name 为业务名词（订单域/支付域），非包名拼接
  - 完成定义：领域名由业务名词驱动，confidence 反映语义-耦合一致性

- [x] 任务 8.3：修复 HotspotScorer 的 inCycle 硬编码 false + 文件级 riskScore
  - 对应需求/场景：aggregation-pipeline spec — 热点风险评分（文件级）
  - 前置依赖：无
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/HotspotScorer.java`
  - 允许修改：该文件
  - 禁止修改：RiskScoreCalculator（公式正确）
  - 实施步骤：
    1. 修复 inCycle：loadMethodRiskInputs 里从循环检测结果读真实 inCycle（而非硬编码 false）
    2. 文件级聚合：按 filePath 分组，riskScore 取文件内方法的加权（最大圈复杂度 + 文件级 churn + 平均入度）
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="HotspotScorerTest" -DfailIfNoTests=false`
  - 预期结果：riskScore 为文件级，循环依赖惩罚生效
  - 完成定义：inCycle 真实，riskScore 文件级

- [x] 任务 8.4：修复 ChurnCollector 的 linesChanged90d 恒 0
  - 对应需求/场景：aggregation-pipeline spec — Git 变更频率收集
  - 前置依赖：无
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/ChurnCollector.java`
  - 允许修改：该文件
  - 禁止修改：其他 Stage
  - 实施步骤：
    1. 累加 linesAdded/linesDeleted（当前声明后未累加，从 RevCommit 的 DiffEntry 统计）
    2. 或明确：若 JGIT 无法获取行数，改用 commitCount90d 作为唯一变更指标，删除 linesChanged90d 死字段
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="ChurnCollectorTest" -DfailIfNoTests=false`
  - 预期结果：linesChanged90d 正确统计，或明确废弃
  - 完成定义：无死字段

- [x] 任务 8.5：修复 layerRole 推断（包名最后一段，非 CONTAINS）
  - 对应需求/场景：api-endpoints spec — 热点 API 的 layerRole 标注
  - 前置依赖：无
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/aggregation/stage/ModuleStatsAggregator.java`（updateLayerRole 方法）
  - 允许修改：仅 updateLayerRole 方法
  - 禁止修改：其他方法
  - 实施步骤：
    1. 改为取包名最后一段判断：`split(pkg,'.')[-1]`，精确匹配 controller/service/repository/mapper/dto/model/util/config
    2. 移除 CONTAINS 模糊匹配（导致 service 包含 'service' 子串误判）
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="ModuleStatsAggregatorTest" -DfailIfNoTests=false`
  - 预期结果：layerRole 精确（com.hisi.service → SERVICE，com.hisi.service.impact → UNKNOWN 或按最后段 impact 判断）
  - 完成定义：layerRole 按包名最后一段精确匹配

- [x] 任务 8.6：dashboard 端点改为读 DomainNode，移除包名切片
  - 对应需求/场景：api-endpoints spec — 架构仪表盘 API（领域列表）
  - 前置依赖：任务 8.1 + 8.2（DomainNode 已正确写入）
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java`（getDashboard 方法）
  - 允许修改：仅 getDashboard 方法
  - 禁止修改：其他端点
  - 实施步骤：
    1. 读 DomainNode + INTERACTS_WITH，返回 domains + interactions
    2. kpis 用 totalDomains，循环依赖从 interactions 双向边检测
    3. 移除 `split(packageName,'.')[3]` 逻辑
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="AggregationEndpointsIntegrationTest" -DfailIfNoTests=false`
  - 预期结果：dashboard 返回业务领域，非包名列表
  - 完成定义：getDashboard 读 DomainNode，无包名切片

- [x] 任务 8.7：hotspots 端点改为文件级聚合 + ChurnNode + layerRole
  - 对应需求/场景：api-endpoints spec — 热点分析 API（文件级 + Git 变更频率）
  - 前置依赖：任务 8.3 + 8.4（riskScore 文件级 + ChurnNode 正确）
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java`（getHotspots 方法）
  - 允许修改：仅 getHotspots 方法
  - 禁止修改：其他端点
  - 实施步骤：
    1. 按 filePath 分组，读 ChurnNode.commitCount90d + 文件内最大复杂度 + layerRole + riskScore（文件级）
    2. 按 riskScore 降序返回
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -pl . -Dtest="AggregationEndpointsIntegrationTest" -DfailIfNoTests=false`
  - 预期结果：hotspots 返回文件级列表，含 commitCount90d + layerRole
  - 完成定义：文件级热点，含变更频率 + 层级

- [x] 任务 8.8：前端 DashboardPanel 改为领域模块图（数据源 domains）
  - 对应需求/场景：frontend-dashboard spec — 首页 + 领域边界
  - 前置依赖：任务 8.6（后端 dashboard 返回 domains）
  - 目标文件/符号：`hisi-dev-tool-frontend/src/views/knowledge-graph/components/DashboardPanel.vue` + `src/api/knowledgeGraph.ts`
  - 允许修改：上述 2 个文件
  - 禁止修改：其他前端组件
  - 实施步骤：
    1. DashboardData 类型：modules → domains，dependencies → interactions
    2. DashboardPanel 渲染领域图（dagre 分层），节点=业务领域名
    3. 洞察卡片：循环依赖领域对 + 热点领域
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx vue-tsc --noEmit`
  - 预期结果：渲染业务领域，非包名
  - 完成定义：领域名非包名

- [x] 任务 8.9：前端 HotspotTreemap 改为文件级 + 变更频率展示
  - 对应需求/场景：frontend-dashboard spec — 热点 Treemap 文件级
  - 前置依赖：任务 8.7（后端 hotspots 文件级）
  - 目标文件/符号：`hisi-dev-tool-frontend/src/views/knowledge-graph/components/HotspotTreemap.vue` + `src/api/knowledgeGraph.ts`
  - 允许修改：上述 2 个文件
  - 禁止修改：其他前端组件
  - 实施步骤：
    1. HotspotItem 类型加 commitCount90d + layerRole
    2. Treemap 展示文件级热点，tooltip 显示变更频率 + 架构层级
    3. 增加按架构层级筛选
  - 验证命令/动作：`cd hisi-dev-tool-frontend && npx vue-tsc --noEmit`
  - 预期结果：文件级热点 + 变更频率 + 层级
  - 完成定义：文件级热点展示

### Phase 9: 生成中心结构化输出改造

- [x] 任务 9.1：GenerationController 文本解析改为 .entity() 结构化输出
  - 对应需求/场景：generation-center spec — 生成中心 LLM 响应采用结构化输出（非文本解析）
  - 前置依赖：任务 6.2（生成中心后端已存在）
  - 目标文件/符号：`hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/GenerationController.java`
  - 允许修改：该文件 + 对应 smoke test
  - 禁止修改：其他端点、AgentTools、AgentTypeRegistry
  - 实施步骤：
    1. 定义 record：`TestSuggestion(scenario, type, priority)` 和 `RefactorSuggestion(issue, direction, impact, priority)`
    2. `generateTestSuggestions` 改用 `.entity(new ParameterizedTypeReference<List<TestSuggestion>>() {})` 替代 `.content()` + `parseJsonArray`
    3. `generateRefactorSuggestions` 改用 `.entity(new ParameterizedTypeReference<List<RefactorSuggestion>>() {})`
    4. 删除 `parseJsonArray` 方法（改为孤儿代码）
    5. 保留失败处理：catch 后返回 `ApiResponse.error`
  - 验证命令/动作：`cd hisi-dev-tool && mvn test -Dtest="GenerationStructuredOutputSmokeTest"`（已存在，验证两个场景真实业务输入输出）
  - 预期结果：`List<TestSuggestion>` / `List<RefactorSuggestion>` 字段精确映射，枚举值合法，无 markdown 围栏/解析异常
  - 完成定义：结构化输出 smoke test 通过 + 全量 `mvn test` 无回归

## 集成顺序

```
Phase 0 (数据模型) ─────────────────────────────────────────────┐
  ├─ T0.1 MethodNode + mergeAll (无前置)                        │
  ├─ T0.2 新节点类型 (依赖 T0.1)                                │
  └─ T0.3 迁移 API (依赖 T0.1, T0.2)                            │
       ↓                                                         │
Phase 1 (聚合管道) ─────────────────────────────────────────────┤
  ├─ T1.1 CheckpointManager (依赖 T0.2)                         │
  ├─ T1.2 ModuleStats + DSM (依赖 T0.1, T1.1)                   │
  ├─ T1.3 HotspotScorer (依赖 T1.2)                              │
  ├─ T1.4 ChurnCollector (依赖 T0.2)                             │
  ├─ T1.5 CommunityDetector (依赖 T1.2)                          │
  ├─ T1.6 DomainNameGenerator (依赖 T1.5)                        │
  └─ T1.7 AggregationPipeline + 注入 (依赖 T1.1-1.6 全部)        │
       ↓                                                         │
Phase 2 (API) ──────────────────────────────────────────────────┤
  └─ T2.1 6 端点 + Controller (依赖 T1.7)                        │
       ↓                                                         │
Phase 3 (MCP) + Phase 4 (前端基础) ─ 可并行                      │
  ├─ T3.1 MCP 工具 (依赖 T2.1)                                   │
  └─ T4.1 G6 + API 层 (可并行)                                   │
       ↓                                                         │
Phase 4 后继 + Phase 5 ──┐                                       │
  ├─ T4.2 Tab 布局 (依赖 T4.1)                                   │
  ├─ T5.1 仪表盘 + DSM (依赖 T4.2)                               │
  └─ T5.2 热点 + 领域 + 拓扑 (依赖 T5.1)                          │
       ↓                                                         │
Phase 6 (爆炸半径 + 生成中心) ───────────────────────────────────┤
  ├─ T6.1 爆炸半径前端 (依赖 T5.1)                                │
  ├─ T6.2 生成中心后端 (依赖 T2.1)                                │
  └─ T6.3 生成中心前端 (依赖 T6.2)                                │
       ↓                                                         │
Phase 7 ────────────────────────────────────────────────────────┘
  └─ T7.1 全量回归 (依赖所有)
```

## 最终验证

| 命令/动作 | 覆盖范围 | 预期结果 |
|-----------|---------|---------|
| `cd hisi-dev-tool && mvn test` | 后端 ~426 个测试（已有 + 新增） | 全部通过 |
| `cd hisi-dev-tool-frontend && npx vitest run` | 前端测试 | 全部通过 |
| 启动后端 → 构建 KG → 打开仪表盘 | 全链路冒烟 | 仪表盘首页展示架构评分 + Top 3 风险 |
| 点击 DSM Tab → 查看 12×12 矩阵 | DSM 切面 | 矩阵渲染 + 循环依赖红框 |
| 点击热点 Tab → 查看 Treemap | 热点切面 | OrderService 红色最大方块 |
| 右键方法 → 查看爆炸半径 | 爆炸半径 | 展示下游 47 方法 + 上游 12 API |
| 选中方法 → 打开生成中心 | 生成中心 | 展示 5+ 条测试建议 |
