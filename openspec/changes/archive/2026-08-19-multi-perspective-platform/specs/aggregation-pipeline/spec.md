# 聚合管道与数据模型扩展：需求规格

## 目的
将架构聚合计算嵌入 KG 构建管道，在全量/增量构建完成后自动运行，预计算模块统计、DSM 矩阵、热点风险分、Git 变更频率、社区检测和领域命名，结果写入 Neo4j 新节点/属性供前端切面直接读取。

## 范围
### 本次范围
- MethodNode 新增 5 个属性：`packageName`, `inDegree`, `outDegree`, `communityId`, `riskScore`
- 新增 4 种 Neo4j 节点类型：ChurnNode, ModuleNode, DomainNode, AggregationCheckpoint
- 新增 5 种 Neo4j 关系类型：DEPENDS_ON, INTERACTS_WITH, CHURNS_AT, CONTAINS, BELONGS_TO
- 聚合管道 6 Stage：ModuleStats → DSM → Hotspot → Churn → Community → DomainName
- 全量和增量构建完成后自动触发聚合
- 增量构建时局部重算（社区检测全量重跑）
- 聚合失败不阻断构建，降级展示上次成功数据（B+C 容错策略）

### 非目标
- GDS 插件依赖的社区检测（选自实现 Louvain）
- 方法级 Git 变更精度（使用文件级 ChurnNode）
- 测试覆盖率数据收集（项目无覆盖率基础设施）

## ADDED Requirements
### Requirement: 聚合管道在构建完成后自动运行
系统 MUST在 KnowledgeGraphBuilder.buildKnowledgeGraph() 和 IncrementalKnowledgeGraphBuilder.incrementalRefresh() 完成后，自动运行聚合管道 6 个 Stage。

#### Scenario: Java 全量构建后触发全量聚合
- 前提：用户发起 POST /api/knowledge-graph/tasks/generate
- 当：构建完成 MethodNode/CALLS 写入 Neo4j，saveGenerationLog 之前
- 则：聚合管道运行全部 6 Stage（全量模式），所有 ModuleNode/ChurnNode/DomainNode 写入 Neo4j
- 并且：saveGenerationLog 在聚合完成后才保存 Checkpoint

#### Scenario: Java 增量构建后触发局部聚合
- 前提：用户发起 POST /api/knowledge-graph/refresh，changedFiles 含 3 个 Java 文件
- 当：增量构建只重建了变更文件的 MethodNode，rebuiltNodeIds 已确定
- 则：ModuleStats/DSM/Hotspot/Churn 只重算受影响模块（包含 rebuiltNodeIds 的 package），Community 全量重跑
- 并且：DomainName 仅在 >15% 节点换了社区时才重新 LLM 命名

#### Scenario: 失败处理
- 当：HotspotScorer 因 git log 超时失败
- 则：Stage 3 自动重试最多 3 次（间隔 5s/15s/60s 指数退避），3 次仍失败则标记为 FAILED
- 并且：Stage 1-2 的 ModuleStats 和 DSM 数据仍然可用（独立 checkpoint，B+C 容错策略）
- 并且：前端热点面板降级显示"暂时不可用，上次更新：N天前"
- 并且：其余 Stage（Churn/Community/DomainName）跳过（Churn 数据依赖 Git log）
- 并且：下次增量构建时自动重试所有 FAILED 状态的 Stage

### Requirement: MethodNode 新增属性与构建属性分离
系统 MUST在 MethodNode 上新增 `packageName`（构建时写入，走 mergeAll），`inDegree/outDegree/communityId/riskScore`（聚合时写入，走独立 batch update 查询），全量重建时不覆盖聚合字段。

#### Scenario: 全量构建后聚合字段保持不变
- 前提：已有 MethodNode 的 riskScore=0.87, communityId=3
- 当：全量重建运行（DETACH DELETE + MERGE 重建同一 nodeId）
- 则：riskScore 和 communityId 保持 0.87 和 3（未在 mergeAll SET 子句中）
- 并且：packageName 被 mergeAll 更新（在 SET 子句中）

### Requirement: Git 变更频率收集（Churn Stage）
系统 MUST在聚合管道中运行 Churn Stage，用 JGIT 解析每个文件的 git log，统计近 90 天的提交次数、变更行数、作者数，写入 ChurnNode（文件级，一个文件一个 ChurnNode）。

#### Scenario: 收集文件级 git 变更频率
- 前提：项目是 Git 仓库，全量构建完成
- 当：ChurnCollector 遍历所有 MethodNode 的 filePath
- 则：每个文件生成一个 ChurnNode（commitCount90d/linesChanged90d/authorCount90d/lastCommitAt）
- 并且：ChurnNode 通过 CHURNS_AT 关系关联到对应 MethodNode

#### Scenario: 失败处理
- 当：非 Git 仓库
- 则：跳过 Churn Stage，checkpoint 标记 "no-git-repo"，热点降级为纯复杂度评分

### Requirement: 热点风险评分（Hotspot Stage，文件级）
系统 MUST在聚合管道中运行 Hotspot Stage，按**文件级**聚合评分：每个文件的风险分 = 该文件内方法的最大圈复杂度×0.35 + 该文件的 Git 变更频率（ChurnNode.commitCount90d 归一化）×0.35 + 该文件方法的入度×0.20 + 循环依赖惩罚×0.10。

#### Scenario: 文件级热点评分
- 前提：ChurnNode 已写入（Churn Stage 完成），MethodNode 有 complexity/inDegree
- 当：HotspotScorer 按 filePath 分组聚合方法
- 则：每个文件得到一个 riskScore（0-1），标注 layerRole（按包名最后一段推断）
- 并且：riskScore 写入 ChurnNode（文件级唯一落点），不写回 MethodNode

#### Scenario: 失败处理
- 当：ChurnNode 缺失（Churn Stage 失败）
- 则：变更频率项记 0，riskScore 仅由复杂度+入度计算

### Requirement: 领域检测自动识别代码边界（技术耦合 + LLM 业务语义）

系统 MUST基于两条正交信号融合划分领域：①**技术耦合**——纯 CALLS 边 Louvain 社区检测（**不加包名种子**），回答"哪些代码在技术上是耦合的"；②**业务语义**——LLM 从类名/注解/注释提取业务名词（如 OrderService→"订单"），相同业务名词的类聚合成领域。两条信号融合后生成最终领域，包结构仅作领域命名提示，不参与聚类。

#### Scenario: 纯依赖图 + 业务语义融合划分领域
- 前提：项目有 3425 个 MethodNode，CALLS 边形成调用图，每个类有 LLM 生成的描述（descriptionEmbedding 或类名/注解/注释）
- 当：Louvain 对纯 CALLS 边做社区检测（无包名种子），得到技术耦合社区
- 并且：LLM 对每个类提取业务名词（如 OrderService→订单、PaymentService→支付、UserService→用户）
- 则：相同业务名词的类被归为同一领域
- 并且：技术耦合社区与业务名词聚类融合，最终领域数控制在合理范围（8-30 个）
- 并且：每个领域规模 50-400 方法

#### Scenario: 业务名词驱动领域命名
- 前提：LLM 提取的业务名词已归类
- 则：领域名直接使用业务名词（"订单域""支付域""用户域"），不是包名拼接
- 并且：一个领域对应多个技术社区但同一业务名词时，合并为一个领域

#### Scenario: 失败处理
- 当：LLM 业务名词提取失败（如 LLM 欠费/超时）
- 则：降级为纯依赖图 Louvain 社区（无包名种子），领域名用社区内出现频率最高的类名后缀
- 并且：log.warn "[Aggregation] Stage=Community 降级: 业务语义提取失败，使用纯依赖图"
- 并且：前端领域切面标注"语义降级"提示

#### Scenario: 业务语义数据缺失（无 LLM 描述）
- 前提：项目尚未生成 descriptionEmbedding，且类名/注解/注释不足以提取业务名词
- 则：跳过领域检测，log.warn "[Aggregation] Stage=Community 跳过: 缺少业务语义数据"
- 并且：前端领域切面展示空状态提示

## 兼容性与外部契约
- 现有 40+ 个 Cypher 查询：RETURN m 的自动兼容新属性，RETURN 显式列的查询新属性为 null（不修正——调用链/搜索查询不需要聚合字段）
- 现有 REST 端点：MethodNode JSON 自动多 5 个字段（向后兼容）
- mergeAll SET 子句：只加 packageName，不加聚合字段（设计决策：属性分离）

## 验收矩阵
| 需求/场景 | 验证方法 | 可证伪的失败表现 |
|-----------|---------|----------------|
| 全量构建后聚合运行 | 集成测试：mock build → 断言 ModuleNode 存在 | ModuleNode 计数 = 0 |
| 增量构建后局部重算 | 集成测试：改 1 文件 → 增量 → 断言只有受影响模块的 ModuleNode.counter 被更新 | 未变模块的 counter 也被更新 |
| 聚合失败降级 | 单元测试：Stage 3 抛异常 → 断言 Stage 1-2 的 AggregationCheckpoint.status=SUCCESS | AggregationCheckpoint 全部标记 FAILED |
| packageName 从 className 提取 | 单元测试：className="com.example.Foo" → packageName="com.example" | packageName ≠ "com.example" |
| 聚合属性不被 mergeAll 覆盖 | 集成测试：set riskScore=0.5 → 全量重建 → 断言 riskScore=0.5 | riskScore 变成 null |
| 领域命名 | 单元测试：mock LLM 返回 "订单域" → DomainNode.name="订单域" | DomainNode.name 为空或"domain-1" |

## 已确认决策
| 决策项 | 选择 | 批准人/日期 | 影响的需求 |
|--------|------|------------|-----------|
| 聚合管道嵌入方式 | B：独立后置回调 | 用户 / 2026-08-11 | 构建后自动运行 |
| 容错策略 | B+C：独立 checkpoint + 降级 + 重试 | 用户 / 2026-08-11 | 聚合失败降级 |
| 构建属性与聚合属性分离 | packageName 走 mergeAll，其他走独立写入 | Agent 设计决策 | mergeAll SET 子句 |
| 社区检测算法 | Java 自实现 Louvain（不依赖 GDS） | 用户 / 2026-08-11 | 领域检测 |
| 领域命名 | LLM 自动命名（Claude Sonnet 4） | 用户 / 2026-08-11 | 领域检测 |

## 显式未知项
- Louvain 在 10000+ 节点时的实际运行时间（理论 <5s，需实测调整参数）
