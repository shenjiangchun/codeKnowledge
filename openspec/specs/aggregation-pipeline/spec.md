# aggregation-pipeline Specification

## Purpose
TBD - created by archiving change multi-perspective-platform. Update Purpose after archive.
## Requirements
### Requirement: 聚合管道受队列编排与 generateArchitecture 门控
系统 MUST 通过 KgGenerationQueue 消费循环在向量生成完成后串行编排聚合管道，且仅当任务勾选 generateArchitecture（架构现状）时才运行聚合（generateArchitecture=false 时不运行）。聚合触发点已从 buildKnowledgeGraph 内部移到队列消费循环。

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

### Requirement: 聚合管道 LLM 结构化输出跳过 thinking 块解析
系统 MUST 在聚合管道的领域归纳（`MultiDimensionCommunityDetector.callLlm`）与游离节点层级补全（`LayerRoleLlmService.resolveRoles`）中，通过 `.chatResponse()` 获取完整响应并经 `RobustJsonExtractor.extract` 遍历 generations 跳过 thinking 块后反序列化，而非依赖 `.entity()`（其只取第一个 Generation，拿到的是 thinking 散文）。当 LLM 输出含 thinking 块时，仍 MUST 正确产出领域 / 层级结果。

#### Scenario: 领域归纳含 thinking 块仍产出领域
- **WHEN** `MultiDimensionCommunityDetector.callLlm` 收到含 thinking 块 + text JSON 块的响应
- **THEN** 系统跳过 thinking 块提取出 `DomainGrouping`，`domains` 覆盖输入类，Community Stage 产出领域而非降级

#### Scenario: 层级补全含 thinking 块仍产出层级
- **WHEN** `LayerRoleLlmService.resolveRoles` 收到含 thinking 块 + text JSON 块的响应
- **THEN** 系统跳过 thinking 块提取出 `RoleGrouping`，`items` 覆盖输入节点，`resolved` 计数大于 0

#### Scenario: 提取失败仍走既有降级
- **WHEN** 鲁棒提取失败（返回 null）
- **THEN** 系统保持既有降级行为：领域归纳降级标记 `semantic-degraded;domains=0`，层级补全记录 `批量补全失败` 并继续下一批


