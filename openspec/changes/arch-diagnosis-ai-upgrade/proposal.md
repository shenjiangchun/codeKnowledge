## Why

架构诊断当前存在两处"定位错位"，导致交付物不可信、价值不足：

1. **规则看板产出系统性误报**：确定性检测（Cypher 启发式）把测试类 `new Impl()` 判为"实现类外泄"（v4.0 项目实测 6 个 HIGH 假阳性）、把 REST Controller 的 `get*` 方法判为"贫血数据类"、把枚举/DTO/Spring 启动类判为"幽灵类"。这些误报变体无穷，靠加规则（`NOT =~ 'Test$'` 之类）无法穷尽，需要一个"结合真实代码语义"的验证者做灰度过滤。

2. **AI 深度分析价值错位**：当前 ReAct agent 只做"根因归纳"（对看板结论的 LLM 摘要），没有产出架构师真正需要的《架构现状分析报告书（4+1 视图）》。同时项目级"领域"依赖图社区检测推断，聚出的是"知识图谱/语义搜索"这类水平耦合社区，与真实业务领域（订单/风控，自上而下贯穿 controller→service→repo→entity 的类组合）根本不在一个维度。

## What Changes

- **AI 灰度过滤误报**：根因分析节点从"归纳根因"升级为"逐条查证 → 判定 confirmed / false_positive / downgraded + 理由"，证据包补充可查证定位信息（className/methodName/nodeId），利用已有 18 只读工具（method_detail/method_by_class/upstream/root_entries）验证候选问题真伪，而非加规则堵漏。
- **4+1 视图架构现状报告书（AI 生成）**：报告节点从"四段式现状总览"升级为"4+1 视图（逻辑/过程/开发/物理/场景）+ 灰度后问题分析"，由 AI 深度分析生成，非图谱规则拼装。
- **垂直领域识别**：项目级领域从图社区检测改为"命名领域词提取 + 分层角色贯穿聚合 + LLM 消歧命名"，从真实代码读出领域（Order 领域 = OrderController + OrderService + OrderRepository + OrderEntity）。诊断侧纯读已有 classRole/className 重算，不碰生成侧。
- **多项目四层分组**：项目级跨项目聚合分析；模块/类/方法级按 projectPath 分组，前端子面板筛选项目→模块。
- **精简 Markdown 导出**：诊断报告导出为更精简直观的 Markdown（可评审/归档）。

## Capabilities

### New Capabilities

- `vertical-domain-identification`: 垂直领域识别——命名领域词提取 + 分层角色贯穿聚合 + LLM 消歧命名，从真实代码读出业务领域（替代图社区检测的领域推断）。

### Modified Capabilities

- `architecture-diagnosis`: 多项目下四层分析分组语义（项目级聚合 + 模块/类/方法级按项目分组）；项目级领域来源从社区检测 DomainNode 改为垂直领域识别；诊断报告 Markdown 导出精简化。
- `arch-deep-analysis`: 根因分析节点升级为灰度验证过滤（输出 confirmed/false_positive/downgraded + 理由）；报告节点升级为 4+1 视图架构现状报告书；证据包补充可查证定位信息。

## Impact

- **后端修改**：`ArchReActAgentNode`（灰度过滤 prompt + 输出契约）、`ArchEvidenceCollectNode`（证据包补定位信息）、`schemas/arch/react-step.json`（final_answer 加 verdict + 4+1 视图 schema）、`ArchDiagnosisReportService`（多项目分组 + 领域来源切换）、`ArchReportMarkdownExporter`（精简 MD）。
- **后端新增**：垂直领域识别组件（领域词提取 + 分层聚合 + LLM 命名，诊断侧，纯读 classRole/className）。
- **前端修改**：`ArchitectureDiagnosisView.vue`（多项目分组 + 子面板筛选）、`AiDeepAnalysisPanel.vue`（4+1 视图报告渲染）。
- **依赖**：复用已有 `ClassLayerRoleDetector` 产出的 classRole、`DeepseekJsonClient`、18 只读工具，不新增外部依赖。
- **约束**：不修改生成侧（aggregation pipeline / `MultiDimensionCommunityDetector` / `DomainNameGenerator` 等），领域识别在诊断侧纯读重算。
