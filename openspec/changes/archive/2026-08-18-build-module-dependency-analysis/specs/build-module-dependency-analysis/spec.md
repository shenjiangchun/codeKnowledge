# Spec: build-module-dependency-analysis

## ADDED Requirements

### Requirement: 解析 Maven pom.xml 提取构建模块

系统 SHALL 解析目标项目目录下的 `pom.xml`，提取每个构建模块的 Maven 坐标（`groupId`、`artifactId`、`version`）。解析范围包括项目目录内可定位到的所有 `pom.xml`，但 SHALL 排除非源码目录：`.git/`、`.claude/`（含 worktrees）、`.worktrees/`、`target/`、`node_modules/`、`src/test/`。对于 `<parent>` 继承与单 pom 内 `<properties>` 占位符，SHALL 正确插值；对于无法插值的跨 pom 占位符，SHALL 保留原始占位符字符串而不报错中断。

#### Scenario: 单模块项目解析出唯一构建模块
- **WHEN** 目标项目含单个 `pom.xml`（如 `com.huawei.hisi:devTools:1.0.0`）
- **THEN** 系统提取出一个构建模块，其 `groupId`、`artifactId`、`version` 与该 pom 声明一致

#### Scenario: 多模块项目解析出多个构建模块
- **WHEN** 目标项目目录下存在多个独立 `pom.xml`（如 `hisi-capture-spring-boot-starter`、`hisi-otel-extension`）
- **THEN** 系统为每个 pom 提取一个构建模块，坐标互不重复

#### Scenario: parent 继承正确插值
- **WHEN** pom 的 `version` 或 `groupId` 继承自 `<parent>` 或引用 `<properties>` 占位符
- **THEN** 系统解析出父 pom / properties 声明的实际值；无法解析时保留原始占位符字符串

#### Scenario: 排除非源码目录
- **WHEN** 项目目录下存在 `.claude/worktrees/xxx/pom.xml`、`.worktrees/xxx/pom.xml` 或 `src/test/resources/fixtures/xxx/pom.xml`
- **THEN** 系统不解析这些 pom，不将其作为构建模块

### Requirement: 存储构建模块节点与依赖坐标属性

系统 SHALL 将构建模块写入 Neo4j 的 `ModuleNode` 节点（`level='build-module'`）。`moduleName = groupId:artifactId`（匹配键，不带 version），`moduleId = groupId:artifactId:version`（唯一键，带 version）。节点属性含 `groupId`、`artifactId`、`version`、`projectPath`，以及 `dependencyCoordinates`（`List<String>`，元素为 `groupId:artifactId:version` 的直接依赖坐标）。**依赖关系不以「边」形式落库**。写入操作 SHALL 幂等（MERGE），可重复执行不产生重复节点。

#### Scenario: 构建模块节点带依赖坐标属性
- **WHEN** 模块 A（`com.a:app:1.0`）的 pom 声明依赖 `com.b:common:2.0`
- **THEN** 系统生成 `ModuleNode(level=build-module)`，其 `dependencyCoordinates` 含 `com.b:common:2.0`

#### Scenario: 同 ga 不同 version 是不同的节点
- **WHEN** 项目先后落库 `com.foo:B:1.0` 与 `com.foo:B:2.0`
- **THEN** 系统生成两个 `ModuleNode`（`moduleId` 分别为 `com.foo:B:1.0` 与 `com.foo:B:2.0`），`moduleName` 均为 `com.foo:B`

#### Scenario: 依赖关系不落库为边
- **WHEN** 模块 A 依赖模块 B
- **THEN** 系统不在 Neo4j 里创建 A 指向 B 的依赖边，仅在 A 的 `dependencyCoordinates` 属性里记录 B 的坐标

### Requirement: 查询时内存拼边构建依赖图

系统 SHALL 在查询时于 Java 内存中动态拼接构建模块依赖图：读勾选项目的所有 `ModuleNode(level=build-module)`，对每个 module 的 `dependencyCoordinates` 坐标**剥离 version 得 `groupId:artifactId`**，与其它 module 的 `moduleName` 精确匹配，匹配上则拼边。匹配不到（第三方库、未落库项目）不拼边。

#### Scenario: 坐标匹配拼边
- **WHEN** 勾选项目 A 与 B，A 的 `dependencyCoordinates` 含 `com.foo:B:1.0`，B 落库的 `moduleName` 为 `com.foo:B`
- **THEN** 系统剥离 version 后匹配成功，拼边 `A → B`

#### Scenario: version 差异不影响拼边
- **WHEN** A 依赖 `com.foo:B:1.0`，B 落库的 `moduleId` 是 `com.foo:B:2.0`（version 不同）
- **THEN** 系统仍按 `moduleName = com.foo:B` 匹配成功，拼边 `A → B`

#### Scenario: 第三方库不拼边
- **WHEN** A 依赖 `org.springframework:spring-web`，但该坐标无对应 `ModuleNode` 落库（未扫描过其源码）
- **THEN** 系统匹配不到，不拼边，spring-web 不参与依赖图

### Requirement: 检测构建级循环依赖并输出环路径

系统 SHALL 在查询时对内存拼出的有向图执行 Johnson's algorithm，穷举所有**简单环**（不重复节点的环），输出完整环路径（有序 module 序列，如 `[A, B, C, A]`），而非仅计数。

#### Scenario: 检测到直接双向依赖环
- **WHEN** 模块 A 依赖 B 且 B 依赖 A（A → B → A）
- **THEN** 系统输出环路径 `[A, B, A]`

#### Scenario: 检测到多节点长环
- **WHEN** 模块 A → B → C → A 构成闭环
- **THEN** 系统输出环路径 `[A, B, C, A]`

#### Scenario: 跨项目 jar 注入环
- **WHEN** 勾选项目 A 与 B，A 的 module 依赖 B 的 module、B 的 module 依赖 A 的 module
- **THEN** 系统在拼出的图上检测并输出环路径

#### Scenario: 无环时不产生误报
- **WHEN** 构建模块依赖图是有向无环图（DAG）
- **THEN** 系统输出空循环依赖清单

### Requirement: 提供构建模块依赖查询 API

系统 SHALL 提供查询端点，返回构建模块依赖图与构建级循环依赖清单。依赖图与环检测 SHALL 在查询时实时计算（读落库节点属性，内存拼边 + Johnson 环检测）。循环依赖清单中每一项 SHALL 包含环路径（有序 module 序列）。构建模块依赖图 SHALL 返回节点列表（含 `groupId`/`artifactId`/`version`/`projectPath`）与边列表（内存拼边结果）。

#### Scenario: 查询构建模块依赖图（实时拼边）
- **WHEN** 前端请求构建模块依赖图端点（携带 `projectPaths`）
- **THEN** 系统读勾选项目的 module 节点与 `dependencyCoordinates`，内存拼边后返回节点列表与边列表

#### Scenario: 查询构建级循环依赖清单（实时计算）
- **WHEN** 前端请求构建级循环依赖端点（携带 `projectPaths`）
- **THEN** 系统实时拼边 + Johnson 环检测，返回循环依赖清单，每项含完整环路径

### Requirement: 多项目时构建模块依赖图可视化

系统 SHALL 在多项目场景下将构建模块依赖图渲染为**一张全局大图**（不按项目拆分为多图）。每个 module 节点 SHALL 用 `projectPath` 映射的项目名（路径末段）进行着色或分组标注归属。跨项目依赖边 SHALL 跨越项目分组显示。循环依赖环路径 SHALL 在图上高亮，并标注该环跨越的项目数量。

#### Scenario: 多项目全局大图着色
- **WHEN** 用户勾选项目 A 与项目 B 查看构建模块依赖图
- **THEN** 系统在一张全局图上渲染所有模块，模块按归属项目着色/分组，跨项目依赖边可见

#### Scenario: 环高亮标注跨项目
- **WHEN** 构建模块依赖图上存在跨项目环 `A.module → B.module → A.module`
- **THEN** 系统高亮环路径边，并标注该环跨越的项目数量
