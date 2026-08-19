# 架构现状分析 — 设计文档

## Context

领域划分当前是「Louvain 社区逐社区命名」，在稀疏依赖图上产生碎片化领域（235 类→189 领域，加 resolution 后仍 23 个），且 `DomainNode` 与 `MethodNode` 之间无任何关系边（`BELONGS_TO` 只在注释里声明，从未创建），领域归属靠 `MethodNode.businessNoun` 属性与 `DomainNode.domainName` 字符串撞名隐式关联。

现状约束：
- 底层唯一实体节点是 `MethodNode`（方法粒度，类只是其 `className` 属性）。
- 语义检索走 `MethodNode.descriptionEmbedding` 向量；调用链走 `CALLS` 边。二者均不依赖 `DomainNode`。
- 项目（release_v3 / v4.4）是单 Maven 模块，包结构为纯技术分层（service/model/controller/repository 平铺），无业务模块维度。

## Goals / Non-Goals

**Goals:**
- 领域划分从「Louvain 碎片」改为「LLM 全局归纳」，领域数可控且名字是业务语义（忽略技术分层）。
- 领域归属以 `BELONGS_TO` 边为唯一真相，支持领域→类→方法下钻。
- 领域节点绝不进入语义检索/调用链查询路径（虚拟边，仅承载抽象属性）。
- 图谱生成、语义&向量、架构现状三者可编排 + 独立触发 + 定时自动刷新。

**Non-Goals:**
- 本次不建实体 ClassNode（留给后续 spec，连语义检索类型化一起做）。
- 本次不改语义检索（不加 `searchType` 字段，不动 MCP 适配）。
- 本次不处理 ChurnNode/ServiceNode 的边（ChurnNode 保持 filePath 关联，ServiceNode 忽略）。

## Decisions

### D1: 领域划分 = LLM 全局归纳（取代 Louvain 逐社区命名）

**选择**：一次性把全部类（含包名、类注释、方法自然语言描述）输入 LLM，输出「领域→类列表」。
**替代方案**：Louvain 社区 + LLM 逐社区命名（已证实碎片化）；Louvain + LLM 二次归并（LLM 仍受碎片社区掣肘）。
**理由**：领域是自顶向下的宏观业务概念，Louvain 是自底向上的技术耦合聚类，二者目标不同。业务域必须由 LLM 全局视角归纳。

### D2: 领域归属 = BELONGS_TO 边（删除 businessNoun）

**选择**：`DomainNode -[:BELONGS_TO]-> MethodNode`，删除 `MethodNode.businessNoun` 属性。
**理由**：单一真相源，避免边/属性漂移。领域下钻、领域间 INTERACTS_WITH 计算都改走边，不再靠属性撞名。
**影响**：`DomainNameGenerator` 分组逻辑、dashboard/domains 端点查询全部改为 Cypher 图遍历。

### D3: 领域下钻用「虚拟类节点」

**选择**：查询时 `MATCH (d:DomainNode)-[:BELONGS_TO]->(m:Method)` 后按 `m.className` 聚合，生成虚拟类节点返回，不落库。
**理由**：类节点本次不实体化（方案 1），但下钻需要「类」这一层。虚拟节点用 `className` 作稳定标识，未来实体 ClassNode 落地时零迁移（`classId` 也基于 `projectPath + className`）。
**扩展性预留**：下钻接口返回统一 `ClassNode` DTO（className/methodCount/description 占位），未来实体化只换后端数据源，DTO 不变。

### D4: 领域隔离原则

**选择**：`BELONGS_TO` 边仅用于领域视图下钻；语义检索、调用链上游查询的 Cypher 全程只走 `Method`/`CALLS` 边。
**理由**：领域是"虚拟边+节点，仅承载抽象属性"，不得污染检索/调用链的真实语义。

### D5: 架构现状输入分层降级（逐方法 COALESCE）

**选择**：优先「类名 + 方法自然语言描述」；逐方法判断，`description` 为空时降级用方法签名。本次**不含类注释**（类注释当前未提取，留待未来 ClassNode spec 加入）。
**理由**：增量生成时多数方法已有描述，只有新建方法无描述。逐方法 COALESCE 比"整体一刀切用签名"质量高。
**LLM 输入组成**：`类名 + [方法签名 | 方法自然语言描述]` 列表。

### D6: 编排顺序 = 图谱 → 语义 → 架构现状

**选择**：都勾选时三者串行，架构现状排在语义之后，吃到刚生成的描述。
**理由**：架构现状的最优输入是方法自然语言描述，语义正好生成在前，形成自然流水线依赖。

### D7: 领域数 = LLM 自定（E2）

**选择**：LLM 按业务语义归纳，忽略技术分层（controller/service/repository 不是业务域），领域数不跟类数硬挂钩。
**理由**：Spring 分层下一个领域横跨多层，类数≠领域数。硬上限会误伤大项目。

### D8: 增量时领域全量重归域（F1）

**选择**：增量图谱生成后，领域划分仍对全库所有方法重新 LLM 归域。
**理由**：领域是全局语义概念，增量局部归域会破坏一致性（新增类可能归出与旧域冲突的名字）。全量归域成本仅一次 LLM 调用（139 类 ≈ 4k token），可接受。

### D9: DSM 展示 = 可配置 Top N + 分层钻取

**选择**：Top N 从写死 20 改为可配置；勾选模块（A1 筛选）→ 下钻展示模块内类的依赖（B1 聚焦）。
**理由**：硬截断丢数据。分层钻取（module→class）让用户"聚焦少数→看内部依赖"，永不截断。

### D10: ModuleNode 补 CONTAINS 边

**选择**：`ModuleNode -[:CONTAINS]-> MethodNode`，支撑 DSM 下钻。
**理由**：DSM 从"静态数字墙"变成"可下钻交互图"，能定位循环依赖/分层违规的具体类。

### D11: 定时任务增强

**选择**：`KgSchedule` 增加 `gitPullEnabled`、`branch`、`refreshDescription`、`refreshArchitecture` 字段；`KgSchedulerService` 执行增量前先 git pull（`branch` 非空用 `git pull origin <branch>`，否则跟随当前分支）。
**理由**：现状定时任务只做增量，不拉代码、不刷新语义/架构。用户要求自动拉最新分支 + 可配置刷新范围；branch 可选字段避免拉错分支（用户刚踩过选错目录/分支的坑）。

## Risks / Trade-offs

- **[LLM 全局归纳一次性输入 token 爆炸]** → 类数多（如 v4.4 的 395 类）时按「类名+注释+方法描述」输入可能超长。缓解：分块归纳 + 领域词表合并，或对超大项目截断每类方法数。
- **[删除 businessNoun 的破坏性迁移]** → 旧图谱数据有 `businessNoun` 属性、无 `BELONGS_TO` 边。缓解：重跑聚合即可重建（领域是纯派生数据），无需手工迁移脚本。
- **[领域下钻虚拟类节点性能]** → 聚合 `className` 需遍历领域下所有方法。缓解：单领域方法数有限（几十~几百），Neo4j 聚合查询足够快。
- **[LLM 归纳仍可能出技术名]** → prompt 需明确"忽略技术分层"；若仍出"控制器域"，需在 prompt 强化负例。缓解：prompt 里显式列出技术分层词作负例。
- **[定时 git pull 冲突风险]** → 本地有未提交改动时 git pull 会失败。缓解：git pull 前检查工作区，dirty 则跳过并告警（沿用现有 GitStatusService）。

## Open Questions

- 无。所有关键决策已在 grill 阶段与用户收敛确认。
