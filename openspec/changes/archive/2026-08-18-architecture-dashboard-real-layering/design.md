# 设计：架构仪表盘切换到真实分层

## Context

架构仪表盘（`DashboardPanel.vue`）当前展示的「循环依赖」「分层违规」来自 `getDashboard` 端点的 `risks` 字段，其中混装了两种数据源：
- **循环依赖**：`DomainNode + INTERACTS_WITH` 双向检测（LLM 推断领域级）
- **分层违规**：包级 `LayeredRuleEngine`（Spring 技术分层 controller→service→repository）

这两者都不是"真实代码架构"。change A（`build-module-dependency-analysis`）已交付构建模块级的三个端点：`/build-modules`（依赖图）、`/build-module-cycles`（环路径）、`/build-module-layer-violations`（module 分层违规）。本 change 把架构仪表盘的坏味道判定切换到这三个端点，LLM 领域降级为参考。

## Goals / Non-Goals

**Goals:**
- 架构仪表盘循环依赖 = 构建模块级环路径（`/build-module-cycles`）
- 架构仪表盘分层违规 = module 级职责违规（`/build-module-layer-violations`）
- 架构仪表盘下钻图 = 构建模块依赖图（`/build-modules`）
- LLM 领域降级为「参考卡片」，标注仅供参考

**Non-Goals:**
- 不改 change A 已交付的 build-module 数据/拼边/环检测/分层规则能力 spec
- 不删除 LLM 领域检测能力（仍保留在 `getDashboard` domains，只是角色变为参考）
- 不在本 change 内做「LLM 领域 vs 真实架构差异的量化评分/优化收益计算」——那是对比分析的增强，留后续

## Decisions

### D1：前端展示层切换为主，后端 `getDashboard` 语义调整

**选择**：架构仪表盘坏味道判定的数据源切换，主要在**前端**完成——`DashboardPanel.vue` 直接消费 change A 的三个端点，不再用 `getDashboard.risks` 做坏味道判定。后端 `getDashboard` 保留（供 LLM 领域参考卡片读 domains），`risks` 字段不再作为坏味道展示依据。

**理由**：
- change A 的三个端点已经完整（拼边 + Johnson 环 + module 分层规则），前端切换即可，无需新后端逻辑。
- `getDashboard` 的 domains 仍要读（LLM 领域参考卡片），但 risks 字段的坏味道语义废弃。

### D2：LLM 领域参考卡片 —— 与坏味道判定并排，明确标注

**选择**：架构仪表盘布局 = 「构建模块循环依赖卡 + module 分层违规卡（真实坏味道）」+ 「LLM 领域参考卡（仅供参考）」。LLM 领域卡只展示领域名 + 方法数，不标红、不判环、不参与 KPI。

**理由**：
- 用户明确：LLM 领域可展示现状差异、作为优化参考，但不能基于虚拟架构判坏味道。
- 并排展示让架构师能对比"AI 划分 vs 真实构建模块"，但视觉上区分（参考卡用灰色/弱化样式）。

## Risks / Trade-offs

- **[R1] 单模块项目看不到构建模块环/违规** → devTools 是单模块，pom 依赖第三方库，无内部边。这是数据客观限制，前端展示空态 + 提示"单模块项目可能无内部依赖边"。
- **[R2] LLM 领域与真实构建模块并存可能让用户困惑** → 参考卡明确标注"仅供参考，不作坏味道判定依据"，用弱化样式区分。

## Migration Plan

- 无数据迁移：本 change 是展示层切换，数据源（build-module 节点）由 change A 的聚合 stage 生成，已存在。
- 回滚：恢复 `DashboardPanel.vue` 旧的领域级判定逻辑即可。
