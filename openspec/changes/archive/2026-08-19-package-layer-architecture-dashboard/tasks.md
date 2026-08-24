# 任务：包级 + 类级双粒度架构分析

## 1. 循环依赖分级判定

- [x] 1.1 module 级环：`BuildModuleCycleDetector` 弃 Johnson，改用 Tarjan SCC（多模块项目）
- [x] 1.2 包级跨层环检测：包级 DEPENDS_ON 按「跨层/同层」分级，跨层环必报、同层环降级提示
- [x] 1.3 类级环排除：不判类级循环依赖
- [x] 1.4 单元测试：module 级环、包级跨层环、包级同层环降级、类级环排除、无环 DAG

## 2. 类级分层违规（职责三级回退 + LLM 补全 + ClassNode 前置）

- [x] 2.1 ClassNode 前置到图谱生成：`KnowledgeGraphBuilder` 扫 `ClassOrInterfaceDeclaration`/`EnumDeclaration` 时全量 MERGE ClassNode（classId/className/packageName/filePath/classRole），注解解析当场落 classRole
- [x] 2.2 ClassNode 新增 `filePath` 字段 + Repository 加 `deleteByFilePathAndProjectPath`（增量删除用）
- [x] 2.3 `MultiDimensionCommunityDetector` 删 ClassNode 结构 MERGE，只保留 `BELONGS_TO`/`HAS_METHOD` 边
- [x] 2.4 `cleanProjectData` 补 `classNodeRepository.deleteByProjectPath`（幽灵节点清理）
- [x] 2.5 类级职责三级回退：注解 → 类名后缀 → 包名后缀
- [x] 2.6 LLM 批量分批补全游离类层级（架构现状阶段，每批 N 个游离类 + 类级依赖结构，一次 prompt 判一批，落 classRole）【后端切 anthropic 中转 deepseek，避开智谱 429】
- [x] 2.7 classRole 带来源标记（ANNOTATION/NAME/PACKAGE/LLM/UNKNOWN），LLM 来源前端弱化展示
- [x] 2.8 类级分层违规检测：类级调用依赖通过 HAS_METHOD+CALLS 间接查，标「疑似」反向依赖（不硬判）
- [x] 2.9 单元测试：注解识别、类名回退、包名兜底、LLM 补全、来源标记、反向依赖标疑似、游离节点
- [x] 2.10 分层违规改为宽松分层：只判反向依赖，跨层跳过不判违规（`LayeredRuleEngine` 删 `srcOrder+1 < tgtOrder` 分支）

## 3. 类级依赖图 + 包级依赖图双向定位

- [x] 3.1 类级 import 依赖落边：AST 解析 `cu.getImports()` 落 `ClassNode -[:IMPORTS]-> ClassNode`（Java 用 AST，非 codegraph）
- [x] 3.2 类级调用依赖查询端点：通过 `HAS_METHOD → CALLS → HAS_METHOD` 间接聚合（不建类级调用边）
- [x] 3.3 包级默认全量、类级按需下钻的双向定位（点包/边才局部查类级，类级图有返回包级按钮）
- [x] 3.4 类级下钻改 ego-net：新增 `/class-ego-net`（中心类 + 一跳邻居 + 边），前端按包分组框包裹

## 4. 查询 API

- [x] 4.1 `GET /package-cycles`：包级循环依赖（分级：跨层/同层）
- [x] 4.2 `GET /module-cycles`：module 级循环依赖（Tarjan SCC，多模块）
- [x] 4.3 `GET /class-layer-violations`：类级分层违规
- [x] 4.4 `GET /class-dependencies`：类级依赖图（调用间接查 + import 落边）
- [x] 4.5 V2 委托方法
- [x] 4.6 `GET /class-ego-net`：类级 ego-net（中心类 + 一跳邻居 + 边）
- [x] 4.7 `GET /layer-domain-matrix`：类 → (classRole, domainName)，差异图数据源

## 5. 前端 DashboardPanel 双粒度

- [x] 5.1 循环依赖卡片：module 级环 + 包级跨层环（分级展示）
- [x] 5.2 分层违规卡片：类级分层违规
- [x] 5.3 包级依赖图 + 类级依赖图双向定位
- [x] 5.4 LLM 领域参考卡片保留
- [x] 5.5 包级图分层框 + 层级名称（layerRole 动态派生）
- [x] 5.6 违规连线标红（反向依赖 + 跨层环内边；同层环不标红）
- [x] 5.7 包级→类级 ego-net 下钻（按包分组框包裹）
- [x] 5.8 `LayerDomainDiff.vue` 差异图（Sankey + Heatmap 切换 + 下钻类清单）
- [x] 5.9 DSM 矩阵并入架构仪表盘（图/矩阵切换），删独立 tab

## 6. 清理

- [ ] 6.1 废弃 `BuildModuleCycleDetector`（Johnson，被 Tarjan 替代），保留 `BuildModuleGraphAssembler`（拼边）、`/build-module-cycles` 端点（换 Tarjan 实现）、`getDomainDependencyGraph`（领域参考卡下钻用）

## 7. 验证

- [ ] 7.1 `mvn test` 全量回归通过
- [ ] 7.2 前端 `vue-tsc -b` 无新增类型错误
- [ ] 7.3 实测：单模块项目展示包级跨层环 + 类级分层违规（非空图）；多模块项目展示 module 级环
