# Spec: architecture-dashboard-real-layering

## ADDED Requirements

### Requirement: 循环依赖判定基于构建模块级

系统 SHALL 在架构仪表盘中，将「循环依赖」的判定数据源从 LLM 推断领域切换到**构建模块级**：读取勾选项目的 `ModuleNode(level=build-module)` 依赖关系（复用 change A 的 `/build-module-cycles` 端点），展示构建模块间的循环依赖环路径。

#### Scenario: 展示构建模块级循环依赖
- **WHEN** 用户打开架构仪表盘查看循环依赖
- **THEN** 系统展示构建模块级循环依赖环路径（如 `[com.a:app, com.b:client, com.a:app]`），而非 LLM 领域级双向依赖

#### Scenario: 无构建模块环时不误报
- **WHEN** 构建模块依赖图是无环 DAG
- **THEN** 系统显示"未检测到循环依赖"，不展示 LLM 领域级的假环

### Requirement: 分层违规判定基于 module 级职责

系统 SHALL 在架构仪表盘中，将「分层违规」的判定数据源从包级 `LayeredRuleEngine` 切换到 **module 级 `ModuleLayerRuleEngine`**（复用 change A 的 `/build-module-layer-violations` 端点），展示 module 职责分层违规（反向依赖/跨层/层级矛盾）。

#### Scenario: 展示 module 级分层违规
- **WHEN** 用户打开架构仪表盘查看分层违规
- **THEN** 系统展示 module 级分层违规（如 `model → client` 反向依赖），而非包级 Spring 技术分层违规

### Requirement: 下钻图展示构建模块依赖

系统 SHALL 使架构仪表盘的分层违规/循环依赖卡片点击下钻时，展示**构建模块依赖图**（节点 + 拼边），而非旧的领域级依赖图。

#### Scenario: 下钻展示构建模块图
- **WHEN** 用户点击架构仪表盘中的循环依赖或分层违规条目
- **THEN** 系统展示构建模块依赖图，节点按归属项目着色，环路径高亮

### Requirement: LLM 领域降级为参考视图

系统 SHALL 将 LLM 推断领域从「坏味道判定依据」降级为「参考对比视图」。架构仪表盘 SHALL 以独立卡片展示 LLM 推断领域（领域名 + 方法数），并明确标注"仅供参考，不作坏味道判定依据"。LLM 领域 SHALL 不参与循环依赖与分层违规的判定。

#### Scenario: LLM 领域仅作参考展示
- **WHEN** 用户打开架构仪表盘
- **THEN** 系统以独立卡片展示 LLM 推断领域列表，标注"仅供参考"，且不据此判定坏味道

#### Scenario: 坏味道判定不依赖 LLM 领域
- **WHEN** LLM 领域划分与真实构建模块划分存在差异
- **THEN** 系统的循环依赖/分层违规判定仅基于构建模块级数据，不受 LLM 领域划分影响
