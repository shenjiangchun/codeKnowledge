# 架构评审修复（P0）— 设计文档

## Context

四位资深架构师评审发现：数据管道质量高，但两个功能性 bug 让功能静默失效，分层规则引擎（架构师最需要的杀手级能力）被写死为 0，fixengine 缺人工闸门。这些都是「数据已就绪、只需兑现」的 P0 修复。

现状约束：
- `Neo4jInitializer` 的 UNIQUE_CONSTRAINTS 和 VECTOR_INDEXES 是启动时静态执行的字符串列表。
- `ModuleStatsAggregator.updateLayerRole` 已把 ModuleNode.layerRole 算好（CONTROLLER/SERVICE/REPOSITORY/DATA/UTILITY/UNKNOWN）。
- `KnowledgeGraphController.getDashboard` 的 `layeredViolations` 写死为 0。
- `FixFlowRunner` 在修复未通过测试时仍直接 commit。

## Goals / Non-Goals

**Goals:**
- 修两个功能性 bug（ServiceNode 约束字段、SqlNode 向量索引标签）。
- 分层规则引擎产出真违规清单（layeredViolations 从 0 到真值）。
- 非 Spring 项目门控，避免误报。
- fixengine commit 前 HITL 闸门。

**Non-Goals:**
- 不砍 ServiceNode（保持现状，仅修约束字段）。
- 不做规则可配置 DSL（硬编码通用 Spring 分层偏序）。
- 不做坏味道检测/技术债量化/演进趋势（那是后续方向，本次聚焦 P0）。

## Decisions

### D1: ServiceNode 约束修复 = 最小改动
**选择**：`REQUIRE s.name` → `REQUIRE s.serviceId`，不砍 ServiceNode。
**理由**：ServiceNode 虽是半成品，但砍掉是更大决策，本次只修约束字段使其正确，避免引入新决策面。

### D2: SqlNode 向量索引标签修正
**选择**：`(s:SQL)` → `(s:Sql)`，对齐 `@Node("Sql")`。
**理由**：Neo4j 标签大小写敏感，原索引建在空标签 `SQL` 上，SqlNode 语义检索静默失效。修正后 SqlNode.sqlEmbedding 真正可用。

### D3: 分层规则引擎 = 硬编码 Spring 分层偏序
**选择**：内置通用分层偏序 `controller → service → repository → model → util`，违规 = 依赖方向逆偏序或跨层跳过（如 controller 直接依赖 repository）。
**理由**：Java/Spring 标准技术分层，通用偏序已够产出「分层违规清单」这个杀手级能力；可配置 DSL 是后续方向。

### D4: 非 Spring 门控 = layerRole 覆盖率
**选择**：`layerRole` 非 UNKNOWN 的模块占比 <30% 时，跳过分层检测、前端显示「非分层架构，不适用」。
**理由**：Python/FastAPI、Go、Node 没有 Spring 的分层结构，硬套会产生海量 UNKNOWN 噪音。门控依据现成的 layerRole 覆盖率，能力针对架构类型、检测不到就诚实说「不适用」。

### D5: fixengine HITL 闸门
**选择**：修复未通过测试时，停在 worktree 不 commit，把 diff + 测试结果推给人，人确认后才提交。
**理由**：未验证的修复自动落盘是最危险的「演示味」，HITL 闸门把它从危险玩具变成可控工具。

## Risks / Trade-offs

- **[分层偏序过严/过宽误报]** → 偏序是通用默认，某些合理依赖（如 controller 直接调 util）会被误判；缓解：util 是叶子层（被所有人依赖，不依赖别人），偏序设计成 util 可被任意层依赖，只判「util 反向依赖业务层」为违规。
- **[非 Spring 门控阈值]** → 30% 是经验值，某些 Java 项目 layerRole 覆盖率可能刚好在边界；缓解：阈值可调，先按 30% 落地。
- **[HITL 闸门打断自动化]** → 会降低 fixengine 的自动化程度，但换来安全；缓解：只对「测试未通过」的修复强制闸门，测试通过的可自动提交（可选，见 Open Questions）。

## Open Questions

- HITL 闸门是「测试未通过才拦」还是「所有修复都拦」？默认「测试未通过才拦」（测试通过的可自动提交），实现时确认。
