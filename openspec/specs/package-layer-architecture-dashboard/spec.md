# package-layer-architecture-dashboard Specification

## Purpose
TBD - created by archiving change package-layer-architecture-dashboard. Update Purpose after archive.
## Requirements
### Requirement: 循环依赖按粒度分级判定

系统 SHALL 对循环依赖坏味道按粒度分级判定：**module（pom 构建模块）级环**是架构坏味道的定性标准（环必报）；**包级环**按跨层/同层分级（跨层环必报，同层环降级提示）；**类级环**不判为坏味道（Spring 支持循环依赖注入，类级环是特性而非坏味道）。

#### Scenario: module 级环必报
- **WHEN** 多模块项目的 pom 依赖构成环（A.jar → B.jar → A.jar，破坏构建拓扑序）
- **THEN** 系统判定为架构坏味道，报出环

#### Scenario: 单模块项目 module 无环是正确结论
- **WHEN** 单模块项目（无内部 module 边界）查询 module 级环
- **THEN** 系统输出「无环」且不视为缺陷（单项目无 module 边界即不存在 module 级坏味道）

#### Scenario: 包级跨层环必报
- **WHEN** 包级依赖构成跨层环（如 controller 包 ↔ service 包）
- **THEN** 系统判定为坏味道，报出环

#### Scenario: 包级同层环降级提示
- **WHEN** 包级依赖构成同层环（如工具类包之间互引）
- **THEN** 系统降级为提示（非坏味道报警），标注为技术债

#### Scenario: 类级环不判
- **WHEN** 类级依赖构成环（OrderService → OrderRepo → OrderService）
- **THEN** 系统不判为循环依赖坏味道（Spring 支持循环注入）

### Requirement: 分层违规在类级检测，职责三级回退 + LLM 补全，标疑似不硬判

系统 SHALL 在**类级**检测分层违规（controller/service/repository/model 反向依赖）。类级职责 SHALL 按三级回退识别：**注解优先**（`@RestController`/`@Service`/`@Repository`/`@Component` 等 Spring 注解）→ **类名后缀**（`XxxController`/`XxxService`/`XxxRepository`）→ **包名后缀**（`com.foo.controller` 等）。三级回退仍无法识别的类 SHALL 由 **LLM 在架构现状阶段批量分批补全层级**（每批 N 个游离类 + 各自类级依赖结构，一次 prompt 判一批，输入：类名 + 它依赖谁/被谁依赖），结果落库。

类职责 SHALL 带**来源标记**（`ANNOTATION`/`NAME`/`PACKAGE`/`LLM`/`UNKNOWN`），区分「注解/命名确定的层级」（高置信）与「LLM 推测的层级」（低置信），前端展示时 LLM 来源的层级以弱化样式（虚线/问号）标记，供架构师判断疑似可信度。

「类级」SHALL 定义为**类型级**（className 维度，含 class/interface/enum）：方法节点按 `className` 字段聚合到类型，接口 default 方法天然归到接口名，**接口无需单独落节点**。类级**调用**依赖 SHALL 通过 `ClassNode -[:HAS_METHOD]-> MethodNode -[:CALLS]-> MethodNode <-[:HAS_METHOD]- ClassNode` 间接查询，不建类级调用边；类级 **import** 依赖 SHALL 由 AST 解析 `clazz.getImports()` 单独落边（`ClassNode -[:IMPORTS]-> ClassNode`，Java 用 AST 而非 codegraph sidecar）。不新增文件级（FileNode）层级。

分层违规的语义 SHALL 为**宽松分层（Loose layering）+ 标疑似而非硬判**：系统仅把**反向依赖**（下层依赖上层，如 service 依赖 controller）标为「疑似分层违规」，由架构师看图复核终审。**跨层跳过**（上层直接依赖更下层，如 controller 直接依赖 repository）**不判违规**——与阿里手册 / Clean Architecture / Microsoft 指南的业界主流一致。算法不输出"违规"结论，只输出"疑似 + 反向依赖的箭头方向可视化"。

#### Scenario: 注解识别类职责优先
- **WHEN** 类上有 `@Service` 注解
- **THEN** 系统识别其职责为 SERVICE（无论类名/包名如何）

#### Scenario: 类名后缀回退
- **WHEN** 类无注解，但类名为 `OrderController`
- **THEN** 系统按类名后缀识别为 CONTROLLER

#### Scenario: 包名后缀兜底
- **WHEN** 类无注解、类名非标准，但包名为 `com.foo.service`
- **THEN** 系统按包名后缀识别为 SERVICE

#### Scenario: 反向依赖标疑似不硬判
- **WHEN** 类级依赖中 `XxxService` 依赖 `XxxController`（逆偏序）
- **THEN** 系统标为「疑似分层违规」并可视化反向依赖箭头，由架构师复核确认，不输出"违规"结论

#### Scenario: 跨层跳过不判违规（宽松分层）
- **WHEN** 类级依赖中 `XxxController` 直接依赖 `XxxRepository`（跳过 service 层）
- **THEN** 系统不判为分层违规（业界主流允许上层依赖任意下层）

#### Scenario: LLM 批量分批补全游离类层级
- **WHEN** 三级回退仍无法识别的类（无注解、类名非标准、包名非标准）
- **THEN** 系统在架构现状阶段批量分批调用 LLM，每批 N 个游离类 + 各自类级依赖结构，一次 prompt 判一批，LLM 判断层级并落库（带 LLM 来源标记）

#### Scenario: LLM 补全后端走 anthropic 中转（deepseek），非智谱
- **WHEN** 游离层 LLM 补全调用 LLM
- **THEN** 系统通过 `extractionChatClient`（Spring AI AnthropicChatModel → anthropic 中转 deepseek）调用，与领域归纳同链路，避开智谱 GLM-4.7-Flash 的全局限流（`code 1305`）

#### Scenario: classRole 来源标记区分置信度
- **WHEN** 类职责由 LLM 补全（非注解/命名确定）
- **THEN** 系统标记来源为 LLM，前端弱化展示，架构师可知该层级为推测、疑似可信度较低

#### Scenario: 识别不了的类按游离节点画
- **WHEN** 类无法识别层级（三级回退 + LLM 均未识别）
- **THEN** 系统将其画为游离节点，不硬归入任何层级

### Requirement: 类级依赖图与包级依赖图共存且双向定位

系统 SHALL 同时提供**包级依赖图**与**类级依赖图**，两张图 SHALL 可**双向定位**（包级下钻到类级、类级聚合回包级）。默认视图 SHALL 为**包级全量**（节点少可读），类级图 SHALL **按需局部渲染**（点击包/边时仅查该包相关类级依赖，不做全局类级骨架）。

依赖关系的存储策略 SHALL 按类型区分：**调用关系**不建类级/包级冗余边，通过方法级 `CALLS` 边 + `HAS_METHOD` 间接查询；**import 关系**在类级单独落边（`ClassNode -[:IMPORTS]-> ClassNode`）。

#### Scenario: 调用关系间接查
- **WHEN** 查询类级/包级调用依赖
- **THEN** 系统通过 `HAS_METHOD → CALLS → HAS_METHOD` 间接聚合，不建类级/包级调用边

#### Scenario: import 关系类级落边
- **WHEN** 类 A import 了类 B（编译期类型引用）
- **THEN** 系统落 `ClassNode A -[:IMPORTS]-> ClassNode B` 边

#### Scenario: 包级下钻到类级 ego-net（中心类 + 一跳邻居）
- **WHEN** 用户点击包级依赖图的某个包或某条边
- **THEN** 系统通过 `/class-ego-net` 返回该包全部类（中心类）+ 依赖/被依赖的一跳邻居类（带各自 packageName/classRole）+ 涉及边，前端按包分组渲染，类节点包裹在包框内（中心包高亮、邻居包灰框）

#### Scenario: 类级聚合回包级
- **WHEN** 用户查看类级依赖图中的某个类
- **THEN** 系统展示该类所属包及包级依赖关系

### Requirement: 包级依赖图按层级分框 + 违规连线标红

系统 SHALL 在包级依赖图中，将节点按 `layerRole` 分层排布，每层画一个**包框 + 层级名称**（层级名称与层色一致，层列表从后端返回的 layerRole 动态派生，不硬编码枚举）。**反向依赖**（`layered` 类型）与**跨层循环依赖环内的反向边**（下层依赖上层，如 service → controller）SHALL 标红加粗，区别于普通依赖边；跨层环内的**正向边**（上层依赖下层，如 controller → service）与**同层循环依赖环**（`SAME_LAYER`，技术债）不标红。

#### Scenario: 层级框与名称动态派生
- **WHEN** 后端返回的 layerRole 集合含未知层名（如历史 `DATA`）
- **THEN** 系统自动为该层画框 + 名称，不将其误丢到 UNKNOWN 层

#### Scenario: 违规连线标红
- **WHEN** 包级依赖图中存在反向依赖边，或跨层循环依赖环内存在反向边（下层→上层）
- **THEN** 系统将该边标红加粗，hover 提示「违规依赖」，点击可下钻类级

#### Scenario: 跨层环正向边不标红
- **WHEN** 跨层循环依赖环内存在正向边（上层依赖下层，如 controller → service）
- **THEN** 系统不将其标红（正向依赖不算违规）

#### Scenario: 同层环不标红
- **WHEN** 包级依赖图存在同层循环依赖环（技术债）
- **THEN** 系统不将其标红，仅作降级提示

### Requirement: DSM 矩阵并入架构仪表盘（视图切换）

系统 SHALL 将 DSM 依赖矩阵（N×N 包/类依赖矩阵）作为架构仪表盘的**一个视图切换**（「架构图 / DSM 矩阵」按钮），而非独立 tab。DSM 矩阵与包级依赖图**同数据源**（`DEPENDS_ON`），只是「分层图 vs 矩阵」两种视角，避免职责重复。

#### Scenario: DSM 视图切换
- **WHEN** 用户在架构仪表盘点击「DSM 矩阵」切换
- **THEN** 系统在同一 tab 内切换到 N×N 依赖矩阵视图（含 Top N 截断、勾选模块下钻类粒度）

#### Scenario: DSM 不占独立 tab
- **WHEN** 用户浏览知识图谱页的架构切面 tab 列表
- **THEN** 系统不显示独立的「DSM 矩阵」tab（已并入架构仪表盘）

### Requirement: LLM 领域 × 技术分层差异图（参考，不判定坏味道）

系统 SHALL 提供「LLM 推断领域 × 技术分层（classRole）」的差异可视化：Sankey 冲积图（左列技术分层 → 右列 LLM 领域，连线宽度=类数量）与 Heatmap（分层×领域计数矩阵）可切换。该图 SHALL 仅作参考展示，不参与坏味道判定。数据 SHALL 由 `/layer-domain-matrix` 端点返回「类 → (classRole, domainName)」，前端聚合。

#### Scenario: 差异图展示错位
- **WHEN** 用户打开差异图
- **THEN** 系统展示每个「技术分层 × LLM 领域」组合的类数量，暴露领域内聚性差或分层被拆散的错位

#### Scenario: 差异图下钻类清单
- **WHEN** 用户点击 Sankey 连线或 Heatmap 格子
- **THEN** 系统展示该（分层,领域）组合下的类清单

#### Scenario: 差异图不判定坏味道
- **WHEN** 差异图显示某领域混入多个分层
- **THEN** 系统仅作参考展示，坏味道仍由包级环 + 类级分层违规判定

### Requirement: 架构仪表盘主数据为包级 + 类级

系统 SHALL 使架构仪表盘的坏味道判定基于**包级（循环依赖分级）+ 类级（分层违规）**，而非构建模块级（pom）单一数据或 LLM 领域。LLM 推断领域 SHALL 降级为「业务语义参考卡片」，不参与坏味道判定。

#### Scenario: 单项目架构仪表盘非空
- **WHEN** 用户打开单模块 Spring 项目的架构仪表盘
- **THEN** 系统基于包级（跨层环）+ 类级（分层违规）展示真实坏味道，而非因 pom 无内部模块边而空图

#### Scenario: LLM 领域仅作参考
- **WHEN** 用户打开架构仪表盘
- **THEN** 系统以参考卡片展示 LLM 领域，标注仅供参考，不据此判定坏味道

