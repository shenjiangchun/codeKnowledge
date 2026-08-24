# 设计：包级 + 类级双粒度架构分析

## Context

架构坏味道判定应从「LLM 领域 / pom 模块单一数据」切换到**双粒度**：包级（循环依赖分级）+ 类级（分层违规）。现状数据：

- `ModuleNode(level=package)` + `DEPENDS_ON`：包级依赖（已存在，由 `Method CALLS` 按 packageName 聚合，保向）
- `ModuleNode(level=build-module)` + pom 依赖：module 级依赖（change A 已建，多模块场景用）
- `Method -[:CALLS]-> Method`：方法级调用（类级/包级调用依赖的单一事实源，通过 HAS_METHOD 间接聚合）
- `ClassNode`：类节点（已有，但缺类级职责字段）
- `ModuleNode.layerRole`：包级分层（已存在，仅作回退提示）

之前两个 change 方向错误：change A 把主数据切到 pom 模块级（单模块空图）+ Johnson 穷举环（过度）；change B 延续。本 change 纠正为双粒度。

## Goals / Non-Goals

**Goals:**
- 循环依赖按粒度分级：module 级定性 + 包级跨层分级 + 类级排除
- 分层违规在类级：注解→类名→包名三级回退识别类职责
- 类级依赖图 + 包级依赖图共存，双向定位
- LLM 领域降级参考卡片

**Non-Goals:**
- 不枚举环路径（SCC 只报「哪些在环里」）
- LLM 领域 vs 真实架构的差异**可视化展示**（Sankey/Heatmap 冲积图），但**不据此判定坏味道**（差异图仅作参考，坏味道仍由包级环 + 类级分层违规判定）
- 类级环不判为坏味道（Spring 支持循环注入）

## Decisions

### D1：循环依赖分级判定（module 定性 / 包级跨层分级 / 类级排除）

**选择**：循环依赖坏味道分三级粒度判定：
1. **module（pom）级**：架构坏味道定性标准，环必报。拓扑序破坏是纯图论事实，无策略假设，噪音为零。
2. **包级**：分级兜底。**跨层环**（controller↔service）必报；**同层环**（工具类互引）降级提示。
3. **类级**：排除，不判（Spring 三级缓存支持循环注入）。

**理由**：
- module 环破坏构建/部署，是唯一无需策略假设的硬坏味道。
- 包级环"应为零"只在强制单向分层策略下成立（DDD 的 domain↔application、六边形的 adapter↔port 是合法双向），所以必须分级：跨层报、同层降级。
- 类级环是特性（Spring 支持），不是坏味道。

**备选（否决）**：包级一刀切 SCC——会误报 DDD/六边形的合法双向，否决。

### D2：分层违规在类级，职责三级回退 + LLM 补全，标疑似不硬判

**选择**：分层违规（controller/service/repository/model 反向依赖）在**类级**检测。类职责三级回退：
1. **注解优先**：解析 `@RestController`/`@Service`/`@Repository`/`@Component`（Spring 权威分层信号），落 `ClassNode.classRole`。
2. **类名后缀**：`XxxController`/`XxxService`/`XxxRepository`（查询时现场推断）。
3. **包名后缀**：`com.foo.controller` 等（兜底）。

三级回退仍识别不了的类，在**架构现状阶段**调用 LLM **批量分批**补全层级（每批 N 个游离类 + 各自类级依赖结构，一次 prompt 判一批），结果落库，并**带来源标记**（ANNOTATION/NAME/PACKAGE/LLM/UNKNOWN）。**违规语义为「标疑似不硬判」**：系统自动标出反向依赖（下层依赖上层）为「疑似分层违规」+ 箭头可视化，架构师看图复核终审，算法不输出"违规"结论。

**理由**：
- 注解是 Spring 权威信号，比命名/包名可靠，回应质疑者"命名不可靠"。
- 三级回退覆盖 95%+ 类，LLM 只处理少量游离类（token 可控），且 LLM 用「结构位置」推层级（和相对层级约束同思路）。
- LLM 批量分批调避免逐个调太慢（几十次串行 → 几次批量）。
- classRole 来源标记让架构师能区分「注解/命名确定的层级（高置信）」与「LLM 推测（低置信）」，判断疑似可信度。
- 反向依赖"可能识别不出来"（用户明确），所以标疑似 + 人终审，机器不硬判。
- 识别不了的类画游离节点，不硬归层，避免误判。

### D3：依赖关系按类型区分存储 —— 调用间接查，import 类级落边

**选择**：依赖关系按类型区分：
1. **调用关系（CALLS）**：不建类级/包级冗余调用边。类级调用依赖通过 `Class -HAS_METHOD-> Method -CALLS-> Method <-HAS_METHOD- Class` 间接查询；包级调用依赖同理上卷。单一事实源 = 方法级 CALLS 边。
2. **import 关系（IMPORTS）**：在类级单独落边 `ClassNode -[:IMPORTS]-> ClassNode`（编译期类型引用，和运行时调用是不同语义，不能从 CALLS 推出）。

「类级」= 类型级（className 维度，含 class/interface/enum），方法按 `className` 字段聚合，接口 default 方法归接口名，接口无需单独落节点。不新增文件级（FileNode）。

**理由**：
- 调用依赖是方法调用的聚合，重复落类级/包级调用边会漂移；间接查保证单一事实源。
- import 是编译期类型引用，import 了不一定调用、调用了也可能不 import（同包/反射），必须单独落边。
- 类级调用边之前设想为"现算聚合"，现在明确：不建边、走 HAS_METHOD+CALLS 间接查。

### D4：类级依赖图与包级依赖图共存，双向定位

**选择**：前端同时提供包级依赖图（骨架）+ 类级依赖图（细查），两张图可**双向定位**：包级图下钻到包内类级图，类级图聚合回包级关系。类级图按需局部渲染（不全局渲染上千节点）。

**理由**：
- 用户明确：类级图与包级图共存、可互相定位，不冲突。
- 包级图回答"哪些包耦合成环"，类级图回答"哪些类违反了分层"，粒度不同、互补。

### D5：聚合保向，不裁剪低频边

**选择**：包级 `DEPENDS_ON` 聚合保留方向（不无向化），不设频次阈值裁剪低频反向边。

**理由**：无向化或频次裁剪会丢失「service→controller 仅 1 条反向 import」这类真坏味道。

### D6：ClassNode 前置到图谱生成，全量 AST 建，含幽灵节点清理

**选择**：ClassNode 构建从「社区检测阶段」前置到「图谱生成阶段」（`KnowledgeGraphBuilder` 扫 `ClassOrInterfaceDeclaration`/`EnumDeclaration` 时全量 MERGE）。关联影响如下：
1. **全量落库 + 注解解析**：ClassNode 由真实语法树（JavaParser AST）全量建，顺带解析类注解（`@RestController`/`@Service`/`@Repository`/`@Component`）落 `classRole`，覆盖全量类（不只在领域内）。
2. **社区检测只建边**：`MultiDimensionCommunityDetector` 删掉 ClassNode 结构 MERGE，只保留 `BELONGS_TO`/`HAS_METHOD` 边（ClassNode 结构由图谱生成统一建，单一事实源）。
3. **增量删除**：ClassNode 新增 `filePath` 字段，增量构建按文件删除变更类的节点（照搬 MethodNode 的 deleteByFilePath 机制），避免幽灵类节点。
4. **类描述/向量时序修复**：现状 `VectorGenerationService` 生成类描述时读到的 ClassNode 是「上次社区检测的旧数据」（社区检测在向量生成之后才跑），前置后 ClassNode 在 buildKnowledgeGraph 就建好，修复了这个时序 bug。类描述/向量全量生成（所有类）。
5. **幽灵节点清理**：`cleanProjectData` 当前漏删 ClassNode（全量重建后旧类节点残留），需补 `classNodeRepository.deleteByProjectPath`。排查确认：`Service` 节点是死代码（无调用）、`GenerationCheckpoint`/`LogChunk` 是跨重建元数据，均不改动。

**理由**：
- 类节点是「结构事实」，应由 AST 扫描（图谱生成）统一建，社区检测只做「领域归属」聚合。
- 前置修复现状时序 bug（类描述读到旧 ClassNode）与幽灵节点 bug（ClassNode 不删残留）。
- 全量 AST 建保证类级分层违规覆盖全量类，不限于领域内类。

### D7：废弃边界 —— 只删 Johnson，保留拼边/端点/领域下钻

**选择**：仅废弃 `BuildModuleCycleDetector`（Johnson 穷举环，被 Tarjan SCC 替代且无他用）。保留：
- `BuildModuleGraphAssembler`（拼边，module 级环定性用）
- `/build-module-cycles` 端点（内部实现从 Johnson 换 Tarjan，端点保留）
- `getDomainDependencyGraph`（LLM 领域参考卡的领域下钻还用）

**理由**：这些保留项在多模块场景或 LLM 领域参考功能里仍被消费，删掉会破坏其他功能。Johnson 是被 Tarjan 完全替代且无他用的唯一死代码。

### D8：游离层 LLM 补全后端切 anthropic 中转（deepseek），避开智谱限流

**选择**：`LayerRoleLlmService` 的 LLM 后端从 `UnifiedTextService`（智谱 GLM-4.7-Flash）切换到 `extractionChatClient`（Spring AI `AnthropicChatModel` → anthropic 中转 deepseek），与领域归纳（`MultiDimensionCommunityDetector`）同一条链路。

**理由**：实测智谱 GLM-4.7-Flash 被全局 429（`code 1305 该模型当前访问量过大`），批量补全游离层全失败。领域归纳用的 anthropic 中转 deepseek 可用，复用同链路即可。**关键坑**：`@Qualifier` 必须放显式构造器参数上（不能配合 `@RequiredArgsConstructor` 放字段，Lombok 不复制 qualifier，会注入到带 memory advisor 的 `@Primary agentChatClient`，报 `conversationId cannot be null`）。

### D9：层枚举统一为 MODEL，消除 DATA/MAPPER 分叉

**选择**：层枚举单一事实源统一为 `CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN`。包级 `ModuleStatsAggregator.updateLayerRole` 的 `DATA`→`MODEL`、`MAPPER`→`REPOSITORY`；`LayeredRuleEngine.LAYER_ORDER` 的 `DATA`→`MODEL`；`KnowledgeGraphController` 热点/风险评估的 `DATA`→`MODEL`。类级 `ClassLayerRoleDetector` 本就用 MODEL，无需改。

**理由**：此前类级用 MODEL、包级用 DATA、规则引擎用 DATA，前端又写死 MODEL（漏 DATA/MAPPER），导致 8 个 DATA 包被前端当 UNKNOWN 丢灰色层。统一后前后端层名一致。

### D10：类级下钻改为 ego-net（中心类 + 一跳邻居，按包分组）

**选择**：下钻不再是「包内类之间的调用图」（`class-dependencies` 端点只查 `packages` 内类互连，单包内部边近零，图恒空），而是新增 `/class-ego-net` 端点：中心包全部类 + 一跳依赖/被依赖的邻居类（带 packageName/classRole）+ 涉及边。前端按包分组渲染，包框包裹（中心包蓝框高亮、邻居包灰框），类节点横排在框内。

**理由**：用户明确要求「展示当前包内所有类 + 依赖/被依赖的一跳类 + 各自所在包，类节点包裹在包框内」。原 `class-dependencies` 语义（包内互连）不满足需求且单包场景图为空。

### D11：新增 LLM 领域 × 技术分层差异图（Sankey + Heatmap 切换）

**选择**：新增 `/layer-domain-matrix` 端点（类 → classRole + domainName），前端 `LayerDomainDiff.vue` 用 Sankey（左列 6 分层 → 右列 30 领域，连线宽度=类数）+ Heatmap（6×30 矩阵）切换展示，点连线/格子下钻类清单。全显 30 领域（不折叠），靠 opacity + gradient + hover adjacency 降噪。

**理由**：暴露「LLM 领域 vs 真实技术分层」的错位（领域内聚性、分层被拆散）。经三路调研（ECharts 选型/业界最佳实践/落地实现）确认 Sankey（冲积图 alluvial）是「两套分类归属」的标准形态，Heatmap 是精确兜底。**注意**：差异图中的「未归属」类（约 286 个）由测试类（125，`isTestClass` 故意排除）+ 无方法 DTO/枚举（161，无 Method 节点，`loadClassMethods` 从 Method 派生故未喂给 LLM）构成。经调研确认：**DTO 是边界对象，本就不需要业务领域**（阿里手册/DDD/整洁架构一致结论），故「DTO 无领域」是正确语义而非缺陷；「未归属」巨柱是测试类 + 边界对象，无需另立 change 修复。

## Risks / Trade-offs

- **[R1] 注解解析需改扫描器 + ClassNode 加 classRole 字段** → 一次性数据生产改动，回退到类名/包名后缀兜底。
- **[R2] 类级图上千节点不可读** → 按需局部渲染 + 双向定位，不做全局类级骨架。
- **[R3] 包级同层环误报（DDD/六边形合法双向）** → 分级：跨层报、同层降级提示，而非一刀切。
- **[R4] 单项目 module 级环恒空** → 正确结论（无 module 边界即无 module 级坏味道），由包级跨层环兜底。
- **[R5] 层枚举 DATA→MODEL 存量数据迁移** → 存量 Neo4j 有 `layerRole=DATA` 节点，须一次全量重新生成图谱才刷成 MODEL；期间前端动态渲染兜底显示 DATA（不丢 UNKNOWN），但会短暂出现 DATA/MODEL 并存。
- **[R6] 游离层补全 + 差异图依赖 anthropic 中转 deepseek** → 中转不可用时游离层保持 UNKNOWN（有 catch 降级不阻塞）。差异图「未归属」类 = 测试类（故意排除）+ 无方法 DTO/枚举（边界对象，业界确认无需业务领域），非数据质量缺陷。
- **[R7] ego-net 一跳邻居爆炸 + 单行标签重叠** → 中心包类多 × 邻居多时边数大、标签重叠（已关 roam）；需后续框内多行排布。
- **[R8] 差异图全显 30 领域 180 条连线糊** → opacity + gradient + hover adjacency + Heatmap 兜底；最坏情况可读性仍受限。

## Migration Plan

- **ClassNode 前置改造**：ClassNode 从社区检测阶段移到图谱生成阶段（`KnowledgeGraphBuilder` 全量 AST 建），含 `classRole`（注解解析）+ `filePath`（增量删除用）。
- **社区检测改造**：`MultiDimensionCommunityDetector` 删 ClassNode 结构 MERGE，只留 `BELONGS_TO`/`HAS_METHOD` 边。
- **幽灵节点清理**：`cleanProjectData` 补 `classNodeRepository.deleteByProjectPath`。
- 废弃 `BuildModuleCycleDetector`（Johnson），module 级环改用 Tarjan SCC。
- 包级环检测改用「分级判定」（跨层 SCC 报、同层降级），弃一刀切。
- **数据迁移**：现有 ClassNode（社区检测建的，无 classRole/filePath）需在下次全量构建时重建；旧 ClassNode 在全量构建的 cleanProjectData 中删除。
- 无破坏：包级数据已存在，类级依赖通过 HAS_METHOD+CALLS 间接查 + import 类级落边。
