# 架构仪表盘：包级 + 类级双粒度架构分析

## Why

当前架构仪表盘的坏味道判定存在两个方向性错误：① 主数据源错位——之前把判定切到「构建模块级（pom 依赖）」，但**单模块 Spring 项目**（如 `com.huawei.hisi:devTools`）的 pom 里只有第三方依赖、没有内部模块间依赖，导致构建模块图为空，而单项目**其实有架构**（靠包名分层 + 类间依赖）；② 环检测算法过度——用了 Johnson 穷举所有简单环，复杂度随环数爆炸。

架构坏味道的正确判定是**双粒度**：循环依赖按粒度分级（module 级定性 + 包级跨层分级 + 类级排除），分层违规在类级（注解→类名→包名三级回退）。数据已基本齐全（包级 `DEPENDS_ON`、`Method CALLS`、`ClassNode`），缺的是类级职责字段（注解解析）和正确的算法选型。

## What Changes

- **循环依赖分级判定**：module（pom）级环 = 架构坏味道定性标准（必报）；包级环按跨层/同层分级（跨层报、同层降级提示）；类级环排除（Spring 支持循环注入）。
- **分层违规切到类级**：从包级 layerRole 切到**类级**检测，类职责三级回退（注解 `@Service` 等 → 类名后缀 → 包名后缀），抓包级聚合平均掉的低频反向依赖。
- **类级依赖图 + 包级依赖图共存**：两张图双向定位（包级下钻类级、类级聚合回包级），类级图查询时现算不落库。
- **类级下钻改为 ego-net**：点击包/边返回中心类 + 一跳邻居（按包分组框包裹），非「包内类互连」。
- **环检测弃 Johnson 改 Tarjan SCC**：module 级和包级跨层环用 Tarjan SCC，弃 Johnson 穷举。
- **LLM 领域降级参考**：不参与坏味道判定，降级为业务语义参考卡片。
- **LLM 领域 × 技术分层差异图**：Sankey + Heatmap 切换，仅参考展示，不判定坏味道。
- **层枚举统一为 MODEL**：消除 DATA/MAPPER 分叉。
- **游离层 LLM 补全后端切 anthropic 中转（deepseek）**：避开智谱限流。
- **pom 模块级重新定位**：从"单项目主数据"改为"多模块项目的架构坏味道定性层"。

## Capabilities

### New Capabilities

- `package-layer-architecture-dashboard`: 双粒度架构分析——循环依赖按粒度分级（module 定性/包级跨层/类级排除），分层违规在类级（注解三级回退），类级+包级依赖图双向定位，作为架构仪表盘的主坏味道判定依据。

### Modified Capabilities

- `build-module-dependency-analysis`: 重新定位——pom 构建模块级从「架构仪表盘主数据」改为「多模块项目的架构坏味道定性层」，Johnson 环检测废弃（改 Tarjan SCC）。
- `architecture-dashboard-real-layering`: 修正——之前把主数据切到构建模块级是方向错误，本 change 纠正为双粒度（包级+类级）。

## Impact

- **后端**：
  - 新增 `PackageCycleDetector`（包级跨层环分级判定）与 module 级 Tarjan SCC。
  - 扫描器解析类注解，`ClassNode` 新增 `classRole` 字段。
  - 新增类级分层违规检测（类级调用依赖通过 HAS_METHOD+CALLS 间接查，import 类级落边，三级回退 + LLM 补全识别类职责，标疑似不硬判）。
  - `LayerRoleLlmService` LLM 后端切 `extractionChatClient`（anthropic 中转 deepseek）。
  - 层枚举统一 MODEL（`ModuleStatsAggregator`/`LayeredRuleEngine`/热点评估）。
  - 废弃 `BuildModuleCycleDetector`（Johnson）。
  - `KnowledgeGraphController`/V2 新增包级环 + module 级环 + 类级分层违规 + 类级依赖 + class-ego-net + layer-domain-matrix 端点。
- **前端**：
  - `DashboardPanel.vue`：循环依赖分级展示（module 级 + 包级跨层），分层违规类级，包级图分层框+名称+违规连线标红，包级→类级 ego-net 下钻（按包分组），LLM 领域参考。
  - `LayerDomainDiff.vue`：新增差异图（Sankey + Heatmap 切换 + 下钻类清单）。
- **依赖**：包级数据已存在，类级现算；需新增 `ClassNode.classRole` 字段 + 扫描器解析注解。

