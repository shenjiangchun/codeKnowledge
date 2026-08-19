# 构建模块级依赖分析（jar 注入循环依赖识别）

## Why

当前知识图谱只解析**源码方法调用**（`Method -[:CALLS]-> Method`），完全看不到**构建级依赖**（pom.xml 里声明的 jar 依赖关系）。由此无法识别的架构坏味道，本质是**模块分层职责错位 × 跨项目传递**：

- **分层职责错位**：Maven module 有明确的职责分层（`model` 最底层、`client` 依赖 model、`service` 依赖 client、`api` 最顶层、`gw` 网关入口）。但现实中 `model` 模块错误地依赖了 `client` 模块——`model` 是最底层叶子，本不该依赖任何上层。
- **跨项目传递形成环**：这种错位在多个微服务之间传递（`项目1.model → 项目1.client → 项目2.model → 项目2.client → 项目1.model`），形成跨项目的长环。微服务越多，环越容易产生。

这类坏味道在源码 CALLS 图里**不可见**——跨项目依赖的对方源码往往不在当前仓库，`CALLS` 边无从建立，只能通过解析构建文件（pom.xml）在**全局坐标图**上识别。这是「真实架构分层」三层模型中缺失最严重的一层。

## What Changes

- **新增 Maven pom.xml 解析**：解析目标项目的 `pom.xml`，提取 `<groupId>:<artifactId>:<version>` 与直接 `<dependencies>` 声明，得到构建模块（build-module）列表与依赖坐标。
- **新增 build-module 节点建模（复用 ModuleNode）**：复用现有 `ModuleNode`，扩展出 `level='build-module'` 级。`moduleName = groupId:artifactId`（匹配键），`moduleId = groupId:artifactId:version`（唯一键，带 version），一跳依赖坐标存为节点属性 `dependencyCoordinates`。
- **依赖关系不落库为边**：构建模块间的依赖**边**不在 Neo4j 落库，而是**查询时在 Java 内存里做坐标匹配动态拼接**（A 的依赖坐标剥离 version 后匹配 B 的 `moduleName` 即拼边）。
- **新增 module 职责识别**：按 artifactId 命名约定识别 module 职责（`xxx-model`/`xxx-client`/`xxx-service`/`xxx-api`/`xxx-gw` 等），职责未知的 module 不阻碍环检测。
- **新增构建级循环依赖检测**：查询时在内存拼出的图上做 Johnson 穷举所有简单环，识别跨项目长环（`A → B → C → D → A`），输出**完整环路径**（非仅计数）。
- **新增 module 级分层违规检测**：定义 module 职责分层偏序（`model < client < service < api < gw`），检测已知职责 module 的反向/跨层依赖（如 `model → client`）；对职责未知的 module，用**相对层级约束**兜底。
- **新增查询 API 与前端展示**：提供构建模块依赖图 + 循环依赖清单 + 分层违规清单的查询端点，供架构仪表盘展示。

## Capabilities

### New Capabilities

- `build-module-dependency-analysis`: 构建模块级依赖分析——解析 Maven pom.xml 提取 build-module 与依赖坐标，复用 `ModuleNode` 建立 build-module 级节点（`dependencyCoordinates` 属性存一跳依赖），查询时内存拼边 + Johnson 穷举循环依赖，输出环路径。
- `module-layer-rule`: module 级分层职责规则——按 artifactId 命名约定识别 module 职责，定义分层偏序（`model < client < service < api < gw`），检测分层违规（反向/跨层依赖）与相对层级约束矛盾，输出违规清单。

### Modified Capabilities

（无。本 change 新增构建模块级这一层数据与 module 级分层规则，不改动现有 `layered-rule-engine`、领域检测等能力的 spec 级需求。）

## Impact

- **后端**：
  - 新增 pom.xml 解析器（Maven 依赖解析，需引入 Maven model 依赖或轻量 XML 解析）。
  - 扩展 `ModuleNode` 支持 `level='build-module'` 级（`moduleName = groupId:artifactId`，`moduleId = groupId:artifactId:version`，`dependencyCoordinates` 属性存一跳依赖），复用已有 `ModuleNodeRepository`，Neo4j 索引/约束初始化（`Neo4jInitializer`）。
  - 新增 module 职责识别器（artifactId 命名约定 → 职责映射）。
  - 新增构建级依赖解析 Stage（挂入聚合 pipeline），生成 `ModuleNode(level=build-module)` 节点及 `dependencyCoordinates` 属性。
  - 新增查询时内存拼边 + Johnson 环检测逻辑。
  - 新增 module 级分层规则引擎（分层偏序 + 相对层级约束，检测分层违规）。
  - `KnowledgeGraphController` / `KnowledgeGraphV2Controller` 新增构建模块依赖图 + 循环依赖 + 分层违规查询端点。
- **前端**：
  - `api/knowledgeGraph.ts` 新增类型与 API 函数。
  - 架构仪表盘新增「构建级循环依赖」展示（后续与「真实分层坏味道」切换衔接）。
- **依赖**：pom.xml 解析需引入 Maven 模型库（如 `org.apache.maven:maven-model`）或改用轻量 XML 解析，设计阶段定夺。
