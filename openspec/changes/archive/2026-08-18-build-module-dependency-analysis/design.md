# 设计：构建模块级依赖分析

## Context

当前知识图谱以「源码方法调用」为核心：`Method -[:CALLS]-> Method` 承载方法级调用关系，`ModuleNode -[:DEPENDS_ON]-> ModuleNode` 承载**包级**依赖（由方法 CALLS 按 packageName 聚合而来）。`ModuleNode.level` 字段声明了 `"build-module" | "package"` 两种取值，但代码中 `SET mod.level = 'package'` 硬编码，**build-module 级从未实现**。

**核心架构事实（与源码 CALLS 图的根本区别）**：构建模块间的依赖关系**不在 Neo4j 里以「边」的形式落库**。构建模块依赖图是**查询时在 Java 内存里实时拼接**出来的——落库只存「模块节点 + 每个模块一跳依赖的坐标列表」，依赖边由查询时做坐标匹配动态生成。

由此带来的能力缺口：微服务/模块间通过 **jar 包注入**形成的依赖（A 的 pom 声明依赖 B 的 jar，B 又依赖 A 的 jar）在图谱中不可见——因为 B 的源码往往不在当前仓库，方法级 `CALLS` 边无从建立。这类「构建级循环依赖」只能通过解析构建文件（pom.xml）识别，是架构坏味道三层模型（构建模块级 / 包级 / 微服务级）中缺失最严重的一层。

目标项目现状：`hisi-dev-tool`（v4.0 与 v5.0）均为 **Maven 单模块**项目（`com.huawei.hisi:devTools`，`<packaging>jar</packaging>`，无 `<modules>`），v5.0 另含 `hisi-capture-spring-boot-starter`、`hisi-otel-extension` 等独立 Maven 模块。项目内**无 Gradle 构建**。

## Goals / Non-Goals

**Goals:**
- 解析目标项目的 `pom.xml`，提取 `<groupId>:<artifactId>:<version>` 与直接 `<dependencies>` 声明。
- 图谱生成时，为项目生成 `ModuleNode(level=build-module)` 节点：`moduleName = groupId:artifactId`（匹配键），`moduleId = groupId:artifactId:version`（唯一键），一跳依赖坐标列表存为节点属性 `dependencyCoordinates`。
- 图谱探索时，查询勾选项目的模块节点 + 它们的 `dependencyCoordinates`，在 Java 内存里做坐标匹配动态拼边。
- 在拼出的图上做 Johnson 穷举所有简单环，输出完整环路径（非仅计数）。
- 识别 module 职责（artifactId 命名约定），检测分层违规（反向/跨层）与相对层级约束矛盾。
- 提供查询 API 与前端展示入口。

**Non-Goals:**
- 不解析 **Gradle**（`build.gradle`）——当前项目均为 Maven，Gradle 支持留待后续。
- 不解析**传递依赖**（transitive dependencies）——只记录 pom 声明的直接依赖（一跳），不递归展开。
- 不处理 Python 的 `requirements.txt` / `pyproject.toml` 依赖——本 change 聚焦 Java/JVM 构建。
- 不在本 change 内做「坏味道判定切换到真实分层」（B）——那是后续 change，本 change 只交付构建模块级这一层**数据与环检测**地基。

## Decisions

### D1：pom.xml 解析方式 —— 引入 `maven-model`，不做命令行走读

**选择**：引入 `org.apache.maven:maven-model`（Maven 官方模型库），仅解析单个 pom.xml 的 `<groupId>/<artifactId>/<version>/<dependencies>` 与 parent 继承。

**理由**：
- 直接用 DOM/SAX 手写 XML 解析，需要自己处理 `parent` 继承、`properties` 占位符（如 `${spring-ai.version}`）、`dependencyManagement` 版本继承——这些在真实 pom 里大量存在，手写极易漏。
- `maven-model` 是 Maven 官方模型，能正确解析 parent 继承与 properties 插值，且**只做模型解析、不触发构建**，轻量，无 Maven 环境依赖。
- `mvn dependency:tree`（备选）最准确但依赖本地 Maven 环境、需网络拉依赖、慢，且我们只需要直接依赖声明。

**备选方案（否决）**：
- 手写 SAX/DOM：处理 parent/占位符易错，否决。
- `mvn dependency:tree`：依赖外部 Maven + 慢，否决。

### D2：build-module 标识 —— 匹配键不带 version，唯一键带 version

**选择**：复用现有 `ModuleNode` 实体，新增 `level='build-module'` 级，标识拆成两个字段：
- `moduleName = groupId:artifactId`（**匹配键**，不带 version）——用于查询时动态拼边的坐标对齐。
- `moduleId = groupId:artifactId:version`（**唯一键**，带 version）——同一 ga 的不同版本是不同节点，都能落库。

`groupId`/`artifactId`/`version` 落为节点属性；`projectPath` 落为属性（记录该模块归属哪个项目）。

**理由**：
- **id 带 version**：同一 jar 的多个版本（`com.foo:B:1.0`、`com.foo:B:2.0`）是不同构件，应当各落一个节点，否则会互相覆盖。
- **匹配键不带 version**：动态拼边时，A 依赖 `com.foo:B:1.0`、B 落库的是 `com.foo:B:2.0`，若用带 version 的坐标匹配则匹配不上、边拼不出、环断开。剥离 version 用 `ga` 匹配，保证「A 依赖 B、B 依赖 A」的环能闭合，version 差异不影响环判定。
- 复用 `ModuleNode` 而非新建 `BuildModuleNode` 实体：`level` 字段早已预留 `"build-module"` 取值，无需新实体/新 Repository。

### D3：依赖关系存储 —— 存为节点属性，不建依赖边

**选择**：build-module 级依赖关系**不落库为边**。图谱生成时，把该模块的直接依赖坐标列表存为 `ModuleNode(level=build-module)` 节点的属性 `dependencyCoordinates`（`List<String>`，元素为 `groupId:artifactId:version`）。**只记一跳，不递归展开。**

**理由**：
- **依赖边是查询时动态拼接的产物，不是持久化数据**。落库只存「模块 + 它依赖了哪些坐标」这个事实，边的生成交给查询时的坐标匹配。
- 不建边避免了「pom 删依赖后残留旧边造成假环」的一致性问题——pom 变了，重新生成图谱时 `dependencyCoordinates` 属性整体覆盖，下次查询自然匹配出正确边。
- 与现有 `DEPENDS_ON`（包级边，源码 CALLS 聚合而来）**完全隔离**，不污染 LayeredRuleEngine 的输入。

### D4：依赖图拼接与环检测 —— 查询时内存实时拼边 + Johnson 穷举

**选择**：查询时在 Java 内存里完成拼边和环检测：
1. 读勾选项目的所有 `ModuleNode(level=build-module)`，按 `moduleName` 建立索引。
2. 对每个 module A，遍历其 `dependencyCoordinates`，对每个坐标**剥离 version 得 `groupId:artifactId`**，在索引里找 `moduleName` 相等的 module B，拼边 `A → B`。
3. 在拼出的有向图上用 **Johnson's algorithm** 穷举所有**简单环**（不重复节点的环），输出完整环路径（有序 module 序列，如 `[A, B, C, A]`）。

**理由**：
- 环检测必须实时计算，结果反映「当前落库项目」的最新依赖事实，不存在构建先后导致方向不完整的问题。
- Johnson 复杂度 `O((V+E)(C+1))`（C=环数），build-module 图节点数 = 模块数（几十~几百），稀疏，穷举完全可行。
- 第三方库（spring、mybatis 等）**没有落库节点**（从未扫描过它们的源码），查询时坐标匹配不到，天然不参与环——无需任何额外过滤字段。

### D5：落库与查询分离 —— 生成时解析落库，探索时内存拼图

**选择**：职责拆分为两层时序——
1. **落库（图谱生成，离线）**：新增 `BuildModuleDependencyAggregator` stage，挂入 `AggregationPipeline`。解析项目 pom，生成/覆盖该项目的 `ModuleNode(level=build-module)` 节点及 `dependencyCoordinates` 属性。
2. **查询（图谱探索，实时）**：查询端点读勾选项目的模块节点 + 依赖坐标，内存拼边、Johnson 环检测、分层规则，返回 nodes/edges/环。

**理由**：
- **落库必须离线**：pom.xml 解析要读文件系统、处理 parent/占位符，成本高，应和「图谱刷新」节奏一致，不能每次查询重读 pom。
- **拼边/检测必须实时**：拼边和环检测是纯内存计算，读已落库的 `dependencyCoordinates` 属性即可，微秒级；这样 pom 改动后重新生成图谱，下次查询自然得到新结果。
- 不改 pipeline 签名：现有 `AggregationPipeline.run(单 projectPath)`，每个项目串行落自己的模块节点即可，跨项目关联靠查询时的坐标匹配完成。

### D6：module 职责识别与分层规则 —— 命名约定 + 相对层级约束兜底

**选择**：分两部分。
1. **职责识别**：按 artifactId 命名后缀约定映射到职责层级（见下表），职责未知记为 `UNKNOWN`。职责未知不阻碍环检测（环检测只用依赖边，不用职责）。
2. **分层规则引擎**：定义职责偏序 `L1 model/common/util < L2 client < L3 service < L4 api/controller < L5 gw`。检测两类违规：
   - **已知职责违规**：`src → tgt` 若 `tgt 层级 > src 层级`（依赖了上层）→ 反向依赖违规（如 `model → client`）；跨越多层 → 跨层违规。
   - **相对层级约束（职责未知兜底）**：对职责未知的 module X，用已知邻居推导层级区间 `[下界, 上界]`——下界 = X 依赖的所有已知 module 的最大层级，上界 = 依赖 X 的所有已知 module 的最小层级。若 **下界 > 上界** → 层级矛盾，判定违规。

**理由**：
- 职责识别用命名约定零配置、可解释；`gw` 是独立最顶层入口，单列一层。
- 相对层级约束解决「命名识别不了的 module 也要能判定层级问题」——即使职责未知，只要依赖关系里出现「依赖上层 + 被下层依赖」的矛盾，就必然违规。
- 分层偏序与现有包级 `LayeredRuleEngine`（controller→service→repository→model→util）**是两套独立体系**，互不替代、并存。

**module 职责 → 层级映射（artifactId 后缀）**：

| 层级 | 职责 | artifactId 后缀 |
|------|------|----------------|
| L1 | 数据/工具（叶子） | `model` `dto` `po` `entity` `common` `util` |
| L2 | 客户端 | `client` `rpc` `sdk` `feign` |
| L3 | 业务 | `service` `core` `biz` `domain` |
| L4 | 接口 | `api` `controller` `web` `facade` `app` |
| L5 | 网关 | `gw` `gateway` `edge` `portal` |

### D7：多项目展示 —— 全局大图 + 项目着色

**选择**：前端构建模块依赖图采用**全局大图 + 项目着色**。
1. **全局大图**：build-module 依赖边是查询时按坐标匹配拼出来的，跨项目模块只要坐标匹配上就成边，本应是一张全局图，不做按项目拆分的多图。每个 module 节点用 `projectPath` 映射成项目名（路径末段），以颜色/分组标注归属。
2. **环高亮**：循环依赖环路径在图上高亮边，并标注该环跨越的项目数量。
3. **查询范围**：查询勾选项目的所有 module + 它们的依赖坐标，坐标匹配到其他勾选项目的 module 就拼边；匹配不到（第三方库或未落库项目）就不成边。

**理由**：
- 现有架构页签是「多项目结果并集合并」，但构建模块图的本质是坐标匹配出的全局图，天然跨项目。
- 项目着色解决「不知道每个 module 属于哪个项目」，回应本次功能反复出现的可读性问题。

## Risks / Trade-offs

- **[R1] maven-model 无法解析父 pom 在多模块 reactor 之外的场景** → 本 change 仅解析目标项目目录下能定位到的 pom.xml，远端 parent（如 `spring-boot-starter-parent`）不递归解析——构建级环只关心业务模块间依赖，远端 parent 不构成 jar 注入环。
- **[R2] properties 占位符未解析导致版本号为空** → `maven-model` 对 `<properties>` 占位符可在单 pom 内插值；跨 pom 若未解析，version 记为原始占位符字符串。version 仅进 moduleId，不参与匹配（匹配用 ga），不影响拼边和环检测。
- **[R3] 扫描范围误扫非源码目录（worktree/测试 fixture）** → 解析时排除 `.git/`、`.claude/`（含 worktrees）、`.worktrees/`、`target/`、`node_modules/`、`src/test/` 目录，避免把 git worktree 或测试 fixture 的 pom 误当真实模块。
- **[R4] 命名约定识别职责可能误判**（如 `xxx-api` 既可能是「对外接口」也可能是「数据模型 DTO」）→ 命名约定作为启发式，职责未知/歧义记为 `UNKNOWN`，由相对层级约束兜底判定；不因职责误判而误报环（环检测不依赖职责）。
- **[R5] 相对层级约束在职责未知节点占多数时失效** → 当 `UNKNOWN` 节点过多、已知邻居稀疏时，层级区间无法推导，约束退化为「仅环检测」。可接受的降级：环检测始终可用。
- **[R6] 全局大图节点过多导致视觉过密** → 通过「按项目分组布局 + 图可缩放/拖拽 + 邻接高亮」缓解，必要时提供「单项目聚焦 / 全局视角」切换。

## Migration Plan

- 无数据迁移：`ModuleNode(level=build-module)` 为**新增**节点（含 `dependencyCoordinates` 属性），由新增 Stage 在聚合时生成，不影响现有 `Method`/`ModuleNode(level=package)`/`DomainNode` 数据。
- 索引/约束：在 `Neo4jInitializer` 新增 `ModuleNode.moduleId` 唯一约束（对 build-module 级）与 `ModuleNode.moduleName` 索引，启动时自动创建。
- 回滚：移除新增 Stage 挂载 + 删除 `ModuleNode(level=build-module)` 节点即可，无侵入既有数据。

## Open Questions

- 是否需要支持 Gradle（`build.gradle`）作为第二阶段？（当前 non-goal，待用户确认。）
- 构建级循环依赖的 severity 分级：仅 `HIGH`，还是按环长度/是否含 optional 依赖分级？（暂定全 `HIGH`，规格中可再细化。）
