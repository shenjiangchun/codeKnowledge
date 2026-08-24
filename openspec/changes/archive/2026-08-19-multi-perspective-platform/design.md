# 多切面分析平台（架构 Copilot）：技术实施计划

## 已批准目标与约束
- 目标：将 Hisi Dev Tool 升级为多切面架构分析平台，支持诊断→影响分析→建议闭环
- 非目标：CI/CD 集成、完整代码生成、实时文件监控、多用户协作
- 风险/闸门：High（命中 migration + public contract + 跨模块 + 核心数据模型）；分 3 阶段交付，每阶段 mvn test 全量回归

## 已刷新代码事实

| 结论 | 证据 | 新鲜度 |
|------|------|--------|
| MethodNode.java 1 class, 20 fields, Lombok @Data+@Builder | `neo4j/model/MethodNode.java` | 2026-08-11 ✅ |
| KnowledgeGraphBuilder 全量构建入口，generationSemaphore(1,true) | `knowledgegraph/service/KnowledgeGraphBuilder.java` | 2026-08-11 ✅ |
| Neo4jInitializer 用 Java 代码创建索引（非 Cypher 文件） | `neo4j/config/Neo4jInitializer.java` lines 61-69 | 2026-08-11 ✅ |
| mergeAll Cypher 显式 SET 子句，含 15 个属性 | `Neo4jMethodNodeRepository.java` lines 2210-2229 | 2026-08-11 ✅ |
| saveMethodNodes 用 map.put 转换 MethodNode→Map | `Neo4jStorageService.java` lines 61-82 | 2026-08-11 ✅ |
| AgentConfig.agentChatClient 已配置，Claude Sonnet 4 | `config/AgentConfig.java` lines 17-30 | 2026-08-11 ✅ |
| 前端 knowledgeGraph.ts 793 行，含 MethodNode 接口 (lines 125-139) | `frontend/src/api/knowledgeGraph.ts` | 2026-08-11 ✅ |
| MCP knowledgeGraphTools.ts 637 行，15 个工具 | `hisi-mcp-server/src/tools/knowledgeGraphTools.ts` | 2026-08-11 ✅ |
| 增量构建有 rebuiltNodeIds 集合可用 | `IncrementalKnowledgeGraphBuilder.java` lines 353-360 | 2026-08-11 ✅ |
| cleanProjectData 删 MethodNode + EntryPoint + relations | `Neo4jStorageService.java` lines 398-426 | 2026-08-11 ✅ |

## 技术决策清单

| ID | 待决事项 | 决策归属 | 实质影响 | 选项与建议 | 状态 | 最终结论 |
|----|---------|---------|---------|-----------|------|---------|
| D1 | 聚合字段（inDegree/outDegree/communityId/riskScore）存储策略 | Agent | 全量重建时是否丢失聚合数据 | A: mergeAll SET 子句包含（会被重置） / B: 独立 batchUpdate（不丢） | decided | **B**：独立 Cypher batchUpdate，mergeAll 只加 packageName |
| D2 | Louvain 实现方式 | Agent | 是否依赖 Neo4j GDS 插件 | A: GDS gds.louvain.write / B: Java 自实现（JGraphT 或手写） | decided | **B**：Java 自实现，使用 JGraphT 提供的基础数据结构，手写 Louvain 迭代逻辑 |
| D3 | ChurnNode 数据源 | Agent | Git 数据获取方式 | A: JGIT API / B: ProcessBuilder + git log shell | decided | **A**：复用已有 GitStatusServiceJgit，扩展支持 git log --stat |
| D4 | 风险评分的归一化函数 | Agent | 算法正确性 | A: Min-Max / B: 分段映射 | decided | **B**：圈复杂度用分段映射（见规格 0-10→0-0.25, 11-20→0.25-0.5, 21-50→0.5-0.85, 50+→0.85-1.0）；churn/入度用百分位排名 |
| D5 | MethodNode 新增属性的 Neo4j 索引 | Agent | 查询性能 | 需要索引：packageName / riskScore / communityId | decided | **packageName 建 RANGE INDEX**；riskScore/communityId 在聚合 Stage 中批量查询的场景也建索引 |

## 方案比较

本方案无重大技术路线分歧需要方案对比——整个设计已在 17 轮 grill 中充分收敛。以下仅记录关键集成决策。

## 最终决策
- 选定方案：预计算缓存模型（聚合管道在构建后自动运行，结果写入 Neo4j）
- 选择理由：构建-查询分离，前端秒开；不需要实时计算，降低 Neo4j 查询负载
- 未选方案：前端实时计算（Cypher 聚合查询每次请求时执行）— 被放弃：8000+ 方法的 GROUP BY 聚合 >2s
- 决策来源：用户确认决策 #2 + #5 + #13

## 集成方式与数据流/控制流

### 构建→聚合管道集成

```
KnowledgeGraphBuilder.buildJavaKnowledgeGraph()
  ├─ ...
  ├─ startVectorGeneration()          // line 606
  ├─ ✨ NEW: aggregationPipeline.run(projectPath, FULL)  // line 607
  ├─ saveGenerationLog()              // line 609
  └─ return result

IncrementalKnowledgeGraphBuilder.javaIncrementalRefresh()
  ├─ ...
  ├─ startVectorGeneration()          // line 520
  ├─ ✨ NEW: aggregationPipeline.run(projectPath, INCREMENTAL, rebuiltNodeIds)  // line 521
  ├─ saveGenerationLog()              // line 524
  └─ return result
```

### 聚合管道 6 Stage 数据流

```
Stage 1: ModuleStatsAggregator
  Input: MethodNode.packageName + CALLS 边
  Cypher: MATCH (m:Method) WITH m.packageName AS pkg, count(m) AS cnt, ...
  Output: ModuleNode (merged), DEPENDS_ON edges, MethodNode.inDegree/outDegree (batch updated)

Stage 2: DsmMatrixBuilder
  Input: ModuleNode 集合
  Output: DEPENDS_ON {weight, bridgeTypes} 属性

Stage 3: HotspotScorer
  Input: MethodNode.complexity + ChurnNode.commitCount90d + inDegree + outDegree
  Output: MethodNode.riskScore (batch updated, 分段映射归一化)

Stage 4: ChurnCollector
  Input: GitStatusServiceJgit.getCommitHistory(filePath, since=90d)
  Output: ChurnNode (merged)

Stage 5: CommunityDetector
  Input: CALLS 边 (findAllCallRelationsByProjectPaths → 邻接表)
  Algorithm: Louvain (JGraphT + 自实现迭代)
  Output: MethodNode.communityId (batch updated)

Stage 6: DomainNameGenerator
  Input: communityId 分组 + 各组的 ClassName + 已有 description
  LLM: Spring AI ChatClient (Claude Sonnet 4), 每个社区 1 次调用
  Output: DomainNode (merged), BELONGS_TO edges
```

### 聚合触发表

| 构建类型 | ModuleStats | DSM | Hotspot | Churn | Community | DomainName |
|---------|------------|-----|---------|-------|-----------|------------|
| 全量 FULL | 全量 | 全量 | 全量 | 全量 | 全量 | 全量 |
| 增量 INCREMENTAL | 局部(脏模块) | 局部(脏行/列) | 局部(脏文件) | 局部(脏文件) | **全量** | 仅 >15% 漂移 |

## 接口与状态模型

### 新增 REST 端点设计

全部在 `/api/v2/knowledge-graph/` 下，遵循已有 `ApiResponse<T>` 包装：

```
GET  /dashboard         → ApiResponse<DashboardData>
GET  /dsm               → ApiResponse<DsmMatrixData>
GET  /hotspots          → ApiResponse<HotspotListData>
GET  /domains           → ApiResponse<DomainListData>
GET  /service-topology  → ApiResponse<ServiceTopologyData>
GET  /blast-radius/{id} → ApiResponse<BlastRadiusData>
```

### AggregationCheckpoint 状态机

```
PENDING → RUNNING → SUCCESS
                  → FAILED → (retry 3x) → SUCCESS
                                         → FAILED (最终，降级展示旧数据)
```

### 分层迁移 API 状态

```
POST /api/admin/migrate-to-v5?projectPaths=a,b
返回: { layers: { 1: PENDING|RUNNING|DONE|FAILED, 2: ..., 3: ... } }

Layer 1 (Cypher only): packageName 回填 + inDegree/outDegree 计算 + ModuleNode 创建
Layer 2 (Algorithm): Louvain + LLM 领域命名 + DSM 矩阵
Layer 3 (Git): git log → ChurnNode + riskScore
```

## 失败处理与可观测性

- 聚合 Stage 失败：try-catch 包裹每个 Stage，异常日志含 [Aggregation] projectPath + stageName
- 重试策略：5s / 15s / 60s 指数退避，最多 3 次
- 降级展示：AggregationCheckpoint.status=FAILED → 前端返回上次成功的 AggregationCheckpoint 数据
- 构建不阻断：聚合异常被 catch 后，构建任务仍标记 COMPLETED
- 日志前缀：`[Aggregation] [Stage=N] [projectPath]` 便于检索

## 兼容、迁移与回滚

- MethodNode 5 个新属性 → 已有 ~25 个 RETURN m Cypher 自动兼容（SDN 反序列化 null）
- 已有 ~8 个显式 RETURN 列查询 → 新字段 null（不修正，调用链/搜索不需要）
- 已有 REST 端点 → MethodNode JSON 多 5 个字段（向后兼容）
- 聚合字段独立于 mergeAll → 全量重建不会覆盖聚合计算结果
- 迁移 API → 历史项目只跑一次，不强制全量重建
- 回滚：删除聚合新增的节点/关系/属性即可（无破坏性数据变更）

## 安全与性能

- 所有 API 端点使用已有认证机制（与现有 30+ 端点一致）
- Claude API Key 通过已有 Spring AI 配置注入，不新增密钥存储
- 聚合管道预估耗时（8000 方法项目）：Stage 1-4 各 1-3s，Stage 5 2-5s，Stage 6（每社区 1 次 LLM，~2s × 3-10 社区 = 6-20s）— 总计 <30s
- 增量聚合（100 个脏文件）预估 <5s（Stage 5 社区检测全量重跑 2-5s + Stage 1-4 局部 <1s）

## 验证策略

- 单元测试：每个 Stage 独立单元测试（mock Neo4j），风险评分公式、归一化函数、Louvain 算法
- 集成测试：Neo4j Testcontainers 测试完整聚合管道
- Controller 测试：Spring MockMvc 测试 6 个新端点 + 3 个修正端点
- MCP 测试：TypeScript 测试 kg_* 新工具 handler
- 前端测试：Vue 组件测试（DSM/热点/领域视图渲染）
- E2E 回归：`mvn test` 全量（已有 ~315 个 KG 相关测试）

## 需求追溯

| 需求/场景 | 设计要素 | 任务 | 验证 |
|-----------|---------|------|------|
| 全量构建后聚合运行 | AggregationPipeline.run(projectPath, FULL) | T1.5 | 集成测试：ModuleNode 存在 |
| 增量构建后局部重算 | stageMode=INCREMENTAL, rebuiltNodeIds | T1.6 | 集成测试：未变模块 counter 不变 |
| 聚合失败降级 | try-catch + AggregationCheckpoint + 3x retry | T1.3, T1.7 | 单元测试：Stage 3 失败 → Stage 1-2 仍 SUCCESS |
| packageName 提取 | className.lastIndexOf('.') substring | T1.1 | 单元测试 |
| 聚合属性不被覆盖 | mergeAll 只加 packageName，其他独立 batchUpdate | T1.1, T1.2 | 集成测试 |
| 领域检测 | Louvain + communityId batch update | T1.4 | 单元测试：已知图 → 正确分区 |
| 领域命名 | LLM per community | T1.4 | 单元测试：mock LLM → DomainNode.name |
| 爆炸半径 API | 单一 Cypher 查询 upstream+downstream+bridge | T2.1 | Controller 测试 |
| 仪表盘 API | GET /dashboard → DashboardData | T2.2 | Controller 测试 |
| MCP kg_dashboard | 工具定义 + handler → 调用 REST 端点 | T3.1 | MCP 集成测试 |
| 前端首页 5 秒展示 | 预计算数据 + 3 KPI 卡片 + Top 3 风险 | T4.1 | E2E 截屏 |
| DSM 热力图 | ECharts heatmap + 循环/违规高亮 | T5.1 | 组件测试 |
| 热点 Treemap | ECharts treemap + 点击详情 | T5.2 | 组件测试 |
| 生成中心 | Claude Sonnet + AgentTools → 返回 JSON | T6.2 | 集成测试 |

## 已知风险与非目标
- Louvain 10000+ 节点实际性能待实测（理论 <5s）
- G6 v5 500+ 领域节点帧率待实测（理论 WebGL 60fps）
- 前端 E2E 测试基础设施需 Playwright 配置（已有依赖但无 KG 相关测试）
