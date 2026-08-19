# 架构仪表盘切换到真实分层坏味道判定

## Why

当前架构仪表盘的「循环依赖」「分层违规」坏味道判定，数据源是 **LLM 推断的虚拟领域**（`DomainNode` + 社区检测 + LLM 命名）和**包级 `LayeredRuleEngine`**，而非真实代码架构。LLM 领域划分是"推测的架构"，用它判定坏味道会产生误报（如"通用模块"这种宽泛命名批量制造假环）。架构坏味道应当基于**真实代码架构分层**（构建模块级 + module 级职责）判定；LLM 领域只应作为"参考对比视图"，展示其与真实架构的差异、划分优劣与优化收益。

## What Changes

- **循环依赖判定切换到构建模块级**：架构仪表盘的循环依赖数据源从 `DomainNode + INTERACTS_WITH`（领域级）切换到 `ModuleNode(level=build-module)` 构建模块级（复用 change A 的 `/build-module-cycles` 端点，Johnson 穷举环路径）。
- **分层违规判定切换到 module 级**：架构仪表盘的分层违规数据源从包级 `LayeredRuleEngine`（controller→service→repository）切换到 module 级 `ModuleLayerRuleEngine`（model<client<service<api<gw，复用 change A 的 `/build-module-layer-violations` 端点）。
- **下钻图切换到构建模块级**：卡片点击下钻展示构建模块依赖图，而非旧的领域级依赖图。
- **LLM 领域降级为参考视图**：LLM 推断领域（`getDashboard` 的 domains）不再参与坏味道判定，降级为「领域参考」卡片，展示 LLM 划分 vs 真实构建模块的现状差异，标注"仅供参考，不作坏味道判定依据"。
- **依赖 change A 的能力**：本 change 复用 change A（`build-module-dependency-analysis`）已交付的 build-module 节点、拼边、环检测、module 分层规则三个查询端点，本 change 主要是**前端展示层切换** + 后端 `getDashboard` 的坏味道字段语义调整。

## Capabilities

### New Capabilities

- `architecture-dashboard-real-layering`: 架构仪表盘坏味道判定切换到真实分层——循环依赖基于构建模块级、分层违规基于 module 级职责、LLM 领域降级为参考对比视图。

### Modified Capabilities

（无。本 change 是前端展示层 + `getDashboard` 展示语义调整，不改动 change A 已交付的 `build-module-dependency-analysis`、`module-layer-rule` 能力 spec；LLM 领域检测能力本身也不变，只是它在仪表盘里的"角色"从判定依据变为参考。）

## Impact

- **前端**：
  - `DashboardPanel.vue`：循环依赖/分层违规卡片改用 `getBuildModuleCycles`/`getBuildModuleLayerViolations`，下钻图改构建模块级；新增「LLM 领域参考」卡片（读 `getDashboard` 的 domains，标注仅供参考）。
- **后端**：
  - `KnowledgeGraphController.getDashboard`：`risks` 字段语义调整——不再把领域级 cyclic + 包级 layered 混装为坏味道（或标记为 `reference` 类型），真实坏味道改由 change A 的三个构建模块端点提供。
- **依赖**：change A（`build-module-dependency-analysis`）的 build-module 节点、拼边、环检测、module 分层规则能力，本 change 直接消费。
