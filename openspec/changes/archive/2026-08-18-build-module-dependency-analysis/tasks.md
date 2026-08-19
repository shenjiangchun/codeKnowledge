# 任务：构建模块级依赖分析

## 1. 依赖与建模

- [x] 1.1 在 `hisi-dev-tool/pom.xml` 引入 `org.apache.maven:maven-model` 依赖
- [x] 1.2 扩展 `ModuleNode` 支持 `level='build-module'` 级：`moduleName = groupId:artifactId`（匹配键），`moduleId = groupId:artifactId:version`（唯一键带 version），新增 `dependencyCoordinates` 属性（`List<String>`，一跳依赖坐标）
- [x] 1.3 复用现有 `ModuleNodeRepository`，新增 build-module 级查询方法（按 `level` + `projectPath` 查询）
- [x] 1.4 在 `Neo4jInitializer` 新增 build-module 级 `moduleId` 唯一约束与 `moduleName` 索引

## 2. pom.xml 解析

- [x] 2.1 新增 `PomDependencyParser`：扫描项目目录下 `pom.xml`（排除 `.git/`、`.claude/`、`.worktrees/`、`target/`、`node_modules/`、`src/test/`），用 maven-model 解析出模块坐标列表与直接依赖声明
- [x] 2.2 处理 `parent` 继承与单 pom 内 `<properties>` 占位符插值（无法插值时保留原始字符串）
- [x] 2.3 单元测试：单模块解析、多模块解析、parent 继承、占位符回退、排除非源码目录

## 3. 模块节点落库

- [x] 3.1 新增 `BuildModuleDependencyAggregator` stage：解析项目 pom，生成/覆盖该项目的 `ModuleNode(level=build-module)` 节点及 `dependencyCoordinates` 属性（只记一跳，不建依赖边），幂等 MERGE
- [x] 3.2 单元测试：模块节点建节点、`dependencyCoordinates` 属性正确、同 ga 不同 version 是两个节点、幂等 MERGE

## 4. 依赖图拼接与环检测（查询时实时）

- [x] 4.1 新增 `BuildModuleGraphAssembler`：查询时读勾选项目 module 节点 + `dependencyCoordinates`，坐标剥离 version 后按 `moduleName` 匹配，内存拼边
- [x] 4.2 新增 `BuildModuleCycleDetector`：对内存拼出的图用 Johnson's algorithm 穷举所有简单环，输出完整环路径（有序 module 序列）
- [x] 4.3 单元测试：坐标匹配拼边、version 差异仍拼边、第三方库不拼边、直接双向环（A→B→A）、多节点环（A→B→C→A）、跨项目 jar 环、无环 DAG

## 5. 分层规则引擎（查询时实时）

- [x] 5.1 新增 `ModuleLayerRoleDetector`：按 artifactId 命名约定识别 module 职责（L1-L5 + UNKNOWN）
- [x] 5.2 新增 `ModuleLayerRuleEngine`：定义分层偏序，检测已知职责 module 的反向/跨层违规
- [x] 5.3 新增相对层级约束检测：职责未知 module 的层级区间推导（下界>上界 → 层级矛盾）
- [x] 5.4 单元测试：反向依赖（model→client）、正常分层、网关依赖下游、相对层级约束矛盾/一致

## 6. 查询 API

- [x] 6.1 在 `KnowledgeGraphController` 新增 `GET /build-modules`：查询时内存拼边，返回构建模块依赖图（节点 + 拼出的边）
- [x] 6.2 新增 `GET /build-module-cycles`：查询时实时拼边 + Johnson 返回构建级循环依赖清单（每项含环路径）
- [x] 6.3 新增 `GET /build-module-layer-violations`：查询时实时返回 module 级分层违规清单（含违规边 + 类型 + 职责）
- [x] 6.4 在 `KnowledgeGraphV2Controller` 新增对应委托方法（`projectPaths` 必填风格）

## 7. 前端

- [x] 7.1 `api/knowledgeGraph.ts` 新增类型（`BuildModule` 含 `projectPath`、`BuildModuleCycle`、`ModuleLayerViolation`）与 `getBuildModules`/`getBuildModuleCycles`/`getBuildModuleLayerViolations` API 函数
- [x] 7.2 新增「构建模块依赖图」页签：全局大图渲染（复用 ECharts graph），module 节点按 `projectPath` 映射项目名着色/分组
- [x] 7.3 新增「构建级循环依赖」展示：环路径高亮 + 标注跨项目数量，区分于现有包级分层违规卡片
- [x] 7.4 新增「module 级分层违规」展示：违规边 + 类型 + 职责标注
- [x] 7.5 多项目展示：全局大图（不拆多图），模块按归属项目着色，跨项目依赖边跨越分组

## 8. 验证

- [x] 8.1 `mvn test` 全量回归通过（含新增解析器、拼边、环检测、分层规则单测）
- [x] 8.2 前端 `vue-tsc -b` 无新增类型错误
- [x] 8.3 对 v4.0/v5.0 目标项目实测：构建模块解析 + 循环依赖清单 + 分层违规清单输出正确
