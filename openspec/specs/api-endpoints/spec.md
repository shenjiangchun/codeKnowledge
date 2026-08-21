# api-endpoints Specification

## Purpose
TBD - created by archiving change multi-perspective-platform. Update Purpose after archive.
## Requirements
### Requirement: 架构仪表盘 API
系统 MUST 提供 GET /api/v2/knowledge-graph/dashboard 端点，返回项目的**领域列表**（基于技术耦合 + LLM 业务语义检测的 DomainNode）、领域间依赖、KPI 摘要和风险列表。领域来自纯依赖图 Louvain（无包名种子）+ LLM 业务名词提取的两条正交信号融合，**包结构仅作命名提示，不参与聚类**。

#### Scenario: 返回已聚合项目的领域仪表盘数据
- 前提：项目 hisi-dev-tool 已完成全量构建 + 聚合，DomainNode 已写入
- 当：GET /api/v2/knowledge-graph/dashboard?projectPaths=hisi-dev-tool&language=java
- 则：响应含 domains（数组，每项含 domainId/domainName/methodCount/classCount/confidence）
- 并且：interactions（数组，领域间 INTERACTS_WITH 关系，source→target+weight）
- 并且：kpis（totalMethods/totalDomains/cyclicDependencies/avgCoupling）
- 并且：risks（数组，循环依赖领域对，severity+source+target+message）
- 并且：hotspots（数组，文件级热点 Top N，含 filePath/commitCount90d/complexity/layerRole）

#### Scenario: 失败处理
- 当：项目未构建过 KG，或已构建但未运行聚合（无 DomainNode）
- 则：返回 HTTP 200，domains=[], kpis 中 totalDomains=0, risks=[]

### Requirement: 热点分析 API（文件级 + Git 变更频率）
系统 MUST 提供 GET /api/v2/knowledge-graph/hotspots 端点，返回**文件级**热点列表，每个文件一个节点，热度由 Git 变更频率（ChurnNode.commitCount90d）+ 圈复杂度 + 入度综合评分，并标注架构层级（controller/service/repository）。

#### Scenario: 返回文件级热点，按 Git 变更频率 + 复杂度排序
- 前提：项目已聚合，ChurnNode 已写入（307 个文件的 git log）
- 当：GET /api/v2/knowledge-graph/hotspots?projectPaths=hisi-dev-tool&limit=50
- 则：响应含 hotspots（数组，每项含 filePath/className/commitCount90d/complexity/inDegree/layerRole/riskScore）
- 并且：按 riskScore 降序（riskScore = 复杂度×0.35 + 变更频率×0.35 + 入度×0.20 + 循环×0.10）
- 并且：layerRole 标注每个文件所属架构层（CONTROLLER/SERVICE/REPOSITORY/...）

#### Scenario: 失败处理
- 当：ChurnNode 未写入（git log 失败）
- 则：commitCount90d 降级为 0，riskScore 仅由复杂度+入度计算，响应仍返回 200

### Requirement: 领域划分 API（技术耦合 + LLM 业务语义）
系统 MUST 提供 GET /api/v2/knowledge-graph/domains 端点，返回领域检测结果（DomainNode），每个领域由两条正交信号融合：纯依赖图 Louvain（技术耦合，无包名种子）+ LLM 业务名词提取（业务语义）。

#### Scenario: 返回自动检测的领域
- 前提：聚合管道的 Community + DomainName Stage 已运行，DomainNode 已写入
- 当：GET /api/v2/knowledge-graph/domains?projectPaths=hisi-dev-tool
- 则：响应含 domains（数组，每项含 domainId/domainName/confidence/methodCount/classCount）
- 并且：domainName 为业务名词（如"订单域""支付域"），由 LLM 从类名/注解/注释提取，非包名
- 并且：confidence 为业务语义与技术耦合的一致程度（非包名同质度）

#### Scenario: 失败处理
- 当：领域检测未运行，或 LLM 业务名词提取失败降级为纯依赖图
- 则：返回 HTTP 200，domains=[] 或 domains 含"语义降级"标记的领域

### Requirement: 跨服务拓扑 API
系统 MUST 提供 GET /api/v2/knowledge-graph/service-topology 端点，返回微服务间拓扑（服务节点 + 跨服务依赖边，按 Feign/MQ/HTTP 类型着色）。

#### Scenario: 返回服务拓扑
- 前提：项目已聚合，服务间调用边已识别
- 当：GET /api/v2/knowledge-graph/service-topology?projectPaths=hisi-dev-tool
- 则：响应含 services（数组，每项含 name/methodCount/language/framework）
- 并且：edges（数组，source→target+type+weight，type∈{FEIGN,MQ,HTTP}）

#### Scenario: 失败处理
- 当：无跨服务数据
- 则：返回 HTTP 200，services=[], edges=[]

### Requirement: DSM 依赖矩阵 API
系统 MUST 提供 GET /api/v2/knowledge-graph/dsm 端点，返回 N×N 模块依赖矩阵。

#### Scenario: 返回模块级 DSM 矩阵
- 前提：请求 ?projectPaths=a,b&level=package
- 当：后端计算模块间依赖关系
- 则：响应含 modules（N 个模块名的有序数组）
- 并且：matrix（N×N 二维数组，matrix[i][j] = 模块 i 依赖模块 j 的调用次数）
- 并且：cycles（循环依赖的 {sourceIdx, targetIdx} 对）
- 并且：violations（分层违规的 {sourceIdx, targetIdx, rule} 对）

### Requirement: 爆炸半径 API
系统 MUST 提供 GET /api/v2/knowledge-graph/blast-radius/{nodeId} 端点，返回指定方法的完整影响面分析。

#### Scenario: 查询 OrderService.placeOrder 的影响面
- 前提：nodeId="com.hisi.order.OrderService.placeOrder"
- 当：GET /api/v2/knowledge-graph/blast-radius/{nodeId}?projectPaths=hisi-dev-tool&maxDepth=5
- 则：响应含 downstream（totalAffectedMethods/maxDepth/bridgeCount/affectedServices/criticalPath）
- 并且：upstream（totalEntryPoints/entryTypes/affectedAPIs）
- 并且：riskSummary（overallRisk/reasons）
- 并且：suggestedTests（受影响入口点的测试建议列表）


### Requirement: 批量生成图谱支持后处理勾选
系统 MUST 在 POST /knowledge-graph/tasks/generate-batch 端点接收 generateVector（是否生成语义&向量）和 generateArchitecture（是否运行架构现状聚合）两个布尔参数，透传到入队逻辑。批量生成每个项目按勾选决定是否生成向量、是否运行架构现状，而非固定全做。

#### Scenario: 批量生成仅生成图谱
- 前提：POST generate-batch body 含 generateVector=false, generateArchitecture=false
- 则：每个项目只生成图谱，不等待向量生成、不运行架构现状聚合

#### Scenario: 批量生成含向量和架构现状
- 前提：POST generate-batch body 含 generateVector=true, generateArchitecture=true
- 则：每个项目生成图谱后继续生成向量，再运行架构现状聚合（串行）

### Requirement: 批量状态查询
系统 MUST 提供 GET /api/v2/knowledge-graph/status/batch 端点，一次性返回多个项目的图谱状态（status/methodNodeCount/callRelationCount/entryPointCount），用 IN + GROUP BY 批量查询替代逐项目单查，避免 N×多次 Neo4j 往返。

#### Scenario: 批量查询多项目状态
- 前提：请求 projectPaths=[a,b,c]
- 则：响应为每项目一条记录，含 status（task 状态小写 / generated / not_generated / unknown）和三个计数
- 并且：计数查询失败时 status 标记 unknown，不静默降级为 not_generated

#### Scenario: 卡死任务状态一致
- 前提：某项目存在 RUNNING 超过 24h 的任务
- 则：批量状态与单查 /tasks/latest 返回一致的 FAILED 状态（自动标记超时）
