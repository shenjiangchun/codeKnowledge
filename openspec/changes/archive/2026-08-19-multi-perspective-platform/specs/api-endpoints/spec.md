# API 端点扩展：需求规格

## 目的
新增加 6 个聚合查询 REST 端点，补全 3 个已有端点的响应字段，支持前端的 6 个切面和 MCP 工具的数据消费。

## 范围
### 本次范围
- 6 个新 GET 端点：`/dashboard`, `/dsm`, `/hotspots`, `/domains`, `/service-topology`, `/blast-radius/{nodeId}`
- 3 个已有端点响应补全：`/method/detail`, `/method/by-class`, `/method/search`（加 serviceName/language/framework 字段）
- V2 委托层的 6 个新方法（复用已有 projectPaths 模式）
- 爆炸半径的一键查询端点（聚合 upstream/downstream/bridge 数据）

### 非目标
- 写入端点（聚合数据由构建管道写入 Neo4j，不由 API 写入）
- 实时推送（无 WebSocket/SSE）

## ADDED Requirements
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

## 兼容性与外部契约
- 所有新端点遵循已有 ApiResponse<T> 响应格式
- V2 委托层遵循已有 KnowledgeGraphV2Controller 模式（@RequestParam List<String> projectPaths）
- 已有端点响应补全是纯增量（加字段，不改已有字段名）

## 验收矩阵
| 需求/场景 | 验证方法 | 可证伪的失败表现 |
|-----------|---------|----------------|
| dashboard 返回领域数据 | Controller 测试：mock DomainNode → 断言 JSON 含 domains[] | domains 为空数组但 Neo4j 有数据 |
| dsm 返回 N×N 矩阵 | Controller 测试：3 个模块 → 断言 matrix 为 3×3 | matrix 维度 ≠ N |
| blast-radius 返回影响面 | Controller 测试：mock CALLS 数据 → 断言 downstream.totalAffectedMethods > 0 | totalAffectedMethods = 0 |
| 已有 /method/detail 加字段 | Controller 测试：mock MethodNode → 断言 JSON 含 serviceName | serviceName 字段缺失 |
| V2 委托正确代理 | Controller 测试：调用 V2 端点 → 断言 V1 方法被调用 | 返回 404 |

## 已确认决策
| 决策项 | 选择 | 批准人/日期 | 影响的需求 |
|--------|------|------------|-----------|
| 爆炸半径为一键查询 | 单一端点聚合 upstream+downstream+bridge | 用户 / 2026-08-11 | 爆炸半径 API |
| 已有端点补全字段 | serviceName/language/framework 加到 3 个端点 | Agent 代码审计发现 | method/detail, method/by-class, method/search |
