# Tasks: 架构诊断 AI 升级

## 1. 垂直领域识别（后端新增，诊断侧纯读）

- [x] 1.1 实现领域词提取组件 `VerticalDomainWordExtractor`：类名剥离技术后缀（Controller/Service/ServiceImpl/Impl/Repository/Dao/Mapper/Entity/PO/DO/DTO/VO/QO/BO/Model/Enum/Util/Utils/Config/Configuration/Constant/Constants/Helper/Factory/Builder/Handler/Listener/Filter/Interceptor/Aspect/Exception/Request/Response 等）得核心领域词；单测覆盖复合词/缩写/无后缀回退
- [x] 1.2 实现垂直领域聚合组件 `VerticalDomainAggregator`：读 Neo4j 已有 ClassNode 的 `classRole` + `className`，同领域词在 ≥2 分层角色出现聚合为一个领域；单测覆盖"Order 域跨 controller/service/repo/model 四层"与"跨包边界"
- [x] 1.3 实现领域骨架判定：仅含 SERVICE/CONTROLLER/REPOSITORY 至少一业务角色的领域词判为完整领域，纯 MODEL 孤立词归"孤立数据对象"；单测覆盖两分支
- [x] 1.4 实现 LLM 消歧命名服务 `VerticalDomainNamer`（复用 `DeepseekJsonClient`）：复合词拆分/缩写还原/中文名，仅命名不改边界，失败回退领域词原文；命名结果缓存复用（SQLite 存领域名），点"实时生成/刷新"时清缓存重算；单测覆盖回退与缓存
- [x] 1.5 接入 `ArchDiagnosisReportService` 项目级章节：领域数据源从社区检测 DomainNode 切换为垂直领域识别结果，不写回、不碰生成侧；验证单项目诊断项目级章节仍正常

## 2. AI 灰度过滤误报（workflow）

- [x] 2.1 `ArchEvidenceCollectNode` 证据包 issue 条目补可查证定位信息（className/methodName/nodeId）
- [x] 2.2 `schemas/arch/react-step.json`：`final_answer.issues[]` 每项加 `verdict ∈ {confirmed,false_positive,downgraded}` + `reason` 字段，schema 校验规则同步
- [x] 2.3 `ArchReActAgentNode` system prompt 增加"灰度验证"职责（逐条用只读工具查证候选问题真伪），输出解析支持 verdict
- [x] 2.4 集成测试：测试类触发的"实现类外泄"被标 false_positive；真实问题标 confirmed；严重度虚高标 downgraded；schema 校验失败降级保留证据包不过滤；灰度结果只进 AI 报告不回写看板

## 3. 4+1 视图架构现状报告书（AI 生成）

- [x] 3.1 `schemas/arch/react-step.json`：报告 schema 从"四段式"升级为"4+1 视图（逻辑/过程/开发/物理/场景）+ 问题分析"
- [x] 3.2 `ArchReActAgentNode.assembleMarkdown` 升级为 4+1 视图报告书（逻辑/过程/开发/物理/场景五视图 + 灰度后问题分析 + 证据附录），降级模板同步
- [x] 3.3 前端 `AiDeepAnalysisPanel.vue` 渲染 4+1 视图报告书（复用现有 markdown 渲染，无新可视化依赖）
- [x] 3.4 单测：4+1 视图 schema 校验通过；降级时报告书回退模板且标注降级

## 4. 实时报告单项目 + AI 深度分析跨项目聚合

- [x] 4.1 前端 `ArchitectureDiagnosisView.vue` 实时报告项目选择器改单选（实时报告针对单项目生成，垂直领域在本项目内识别）
- [x] 4.2 前端 AI 深度分析入口支持多选项目（跨项目聚合报告入口）
- [x] 4.3 `ArchEvidenceCollectNode` / `ArchReActAgentNode` 支持多项目证据收集与跨项目聚合报告（4+1 视图 + 跨项目问题对比）
- [x] 4.4 回归测试：单项目诊断行为与现状一致（后端 `projectPaths` 多值向后兼容，快照仍按项目隔离）；跨项目聚合仅 AI 深度分析路径生效

## 5. 精简 Markdown 导出

- [x] 5.1 `ArchReportMarkdownExporter` 精简：折叠重复行、同源计数聚合、去冗余空表，保持"总分 + 等级 + 问题清单 + 四章核心明细 + 扣分汇总"；单测覆盖精简输出结构

## 6. 回归

- [x] 6.1 后端 `mvn test` 全绿（含新增单测/集成测试）
- [x] 6.2 前端 `npm run build` 通过
- [ ] 6.3 手工验证 v4.0 项目诊断：6 个"实现类外泄"假阳性在 AI 深度分析中被灰度过滤，4+1 视图报告书正常生成
