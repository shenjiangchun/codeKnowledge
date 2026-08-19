# 架构现状分析 — 任务清单

## 1. 领域划分：LLM 全局归纳（后端核心）

- [x] 1.1 重写 `MultiDimensionCommunityDetector`：删除「按社区命名」逻辑，改为 LLM 全局归纳（输入类名+方法描述/签名，输出「领域→类列表」）
- [x] 1.2 定义 LLM 结构化输出 record（`DomainGrouping`：领域名 + 类名列表），加 `@JsonClassDescription`
- [x] 1.3 实现「逐方法 COALESCE」输入组装：`description` 非空用描述，否则用方法签名（本次不含类注释）
- [x] 1.4 编写单元测试：LLM 全局归纳成功/失败降级/逐方法降级
- [x] 1.5 分块归纳 + 跨块领域传递（大项目不截断、领域不分裂）

## 2. 领域归属：BELONGS_TO 边（数据模型改造）

- [x] 2.1 `DomainNameGenerator` 改为领域交互边构建；BELONGS_TO 边由 `MultiDimensionCommunityDetector` 写入（`DomainNode -[:BELONGS_TO]-> MethodNode`），删除 `businessNoun` 写库逻辑
- [x] 2.2 删除 `MethodNode.businessNoun` 字段
- [x] 2.3 领域间 `INTERACTS_WITH` 边计算改为走 `BELONGS_TO` 图遍历（不再靠 `businessNoun` 属性撞名）
- [x] 2.4 Neo4jInitializer 索引（MERGE 自动建，无需显式索引）
- [x] 2.5 编写单元测试：BELONGS_TO 边写入、领域交互边

## 3. 领域下钻：虚拟类节点（后端 + 前端）

- [x] 3.1 `KnowledgeGraphController` 新增领域下钻端点：聚合 `className` 生成虚拟类节点
- [x] 3.2 定义统一 `DomainClass` DTO（className/methodCount）
- [x] 3.3 更新 `/dashboard`、`/domains` 端点：领域归属改走 `BELONGS_TO` 边
- [x] 3.4 前端 `DomainBoundaryView.vue`：领域→类→方法三层下钻交互
- [x] 3.5 下钻端点已端到端验证（真实 235 类 → 20 领域 + BELONGS_TO 边）

## 4. 架构现状编排（后端 + 前端）

- [x] 4.1 `KnowledgeGraphTaskServiceImpl` 新增编排入口，支持四种组合（都不选/只语义/只架构/都选串行）
- [x] 4.2 架构现状分析抽为独立可调用端点（`/architecture-analysis`，供独立触发按钮复用）
- [x] 4.3 前端 `ProjectList.vue`：图谱生成弹窗（勾选语义&向量、架构现状）+「架构现状」按钮
- [x] 4.4 四组合编排参数贯穿全链路（startTask→enqueue→buildKnowledgeGraph 3 分支），编译+回归验证

## 5. DSM 展示增强（前端）

- [x] 5.1 `DsmMatrix.vue`：Top N 从硬编码 20 改为可配置（20/50/全部）
- [x] 5.2 `DsmMatrix.vue`：勾选模块 → 下钻模块内类依赖（聚焦交互）
- [x] 5.3 vue-tsc 类型检查通过（无新增错误）

## 6. ModuleNode CONTAINS 边（后端）

- [x] 6.1 `ModuleStatsAggregator` 新增 `ModuleNode -[:CONTAINS]-> MethodNode` 边写入
- [x] 6.2 DSM 下钻端点 `/dsm/drill-down`：按模块下钻到包内类依赖（类粒度矩阵）
- [x] 6.3 编写单元测试：CONTAINS 边写入（ModuleStatsAggregatorTest）

## 7. 定时任务增强（后端）

- [x] 7.1 `KgSchedule` 新增 `gitPullEnabled`、`branch`、`refreshDescription`、`refreshArchitecture` 字段
- [x] 7.2 `KgSchedulerService` 增量执行前 git pull（branch 可选，异常跳过不中断）
- [x] 7.3 `IncrementalKnowledgeGraphBuilder` 增量支持「是否刷新语义&向量」「是否刷新架构现状」
- [x] 7.4 定时任务配置的持久化（`KgScheduleRepository` 表结构/SQLite 迁移）
- [x] 7.5 git pull 冲突跳过逻辑（异常 catch 不中断）

## 8. 全量回归

- [x] 8.1 `mvn test` 后端全量回归通过（1103 tests, 0 failures）
- [x] 8.2 前端 `vue-tsc` 类型检查通过（无新增错误）
- [x] 8.3 端到端验证：真实 v4.4 项目 235 类 → 20 业务域 + BELONGS_TO 边 + 无 JSON 截断
