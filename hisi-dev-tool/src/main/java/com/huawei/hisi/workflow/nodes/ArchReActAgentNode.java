package com.huawei.hisi.workflow.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.workflow.NodeEventSink;
import com.huawei.hisi.workflow.agent.ArchAgentTool;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 架构深度分析 ReAct 智能体节点的契约与报告组装基类：查证规则提示词、
 * 图生成、结构化 Markdown 报告与降级模板。
 *
 * <p>手动 ReAct 循环已由 pi 会话平替（见 {@link com.huawei.hisi.workflow.v2.PiReActAgentNode}），
 * 本类不再是 {@code DagNode}，也不注册为 bean，只保留
 * {@code {rootCauseResult, markdownReport, diagrams, degraded}} 输出契约供 pi 版节点复用。
 */
@Slf4j
public class ArchReActAgentNode {

    protected final ArchAgentToolRegistry registry;
    private final ArchDiagramGenerator diagramGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ArchReActAgentNode(ArchAgentToolRegistry registry, ArchDiagramGenerator diagramGenerator) {
        this.registry = registry;
        this.diagramGenerator = diagramGenerator;
    }

    /** 确定性图生成，失败降级为空列表，不阻断主流程。 */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> generateDiagrams(Map<?, ?> evidencePack, List<String> projectPaths) {
        try {
            List<Map<String, Object>> diagrams =
                    diagramGenerator.generate((Map<String, Object>) evidencePack, projectPaths);
            return diagrams == null ? List.of() : diagrams;
        } catch (Exception e) {
            log.warn("[ArchReActAgentNode] 图生成失败: {}", e.getMessage());
            return List.of();
        }
    }

    protected Map<String, Object> buildOutput(Map<?, ?> evidencePack,
                                            Map<String, Object> finalAnswer, NodeEventSink sink,
                                            List<Map<String, Object>> diagrams) {
        Object issuesObj = finalAnswer.get("issues");
        List<?> issues = issuesObj instanceof List<?> l ? l : List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = finalAnswer.get("report") instanceof Map<?, ?> r
                ? (Map<String, Object>) r : Map.of();
        Map<String, Object> rootCauseResult = new LinkedHashMap<>();
        rootCauseResult.put("issues", issues);
        rootCauseResult.put("report", report);
        rootCauseResult.put("degraded", false);
        sink.emit("分析完成，发现 " + issues.size() + " 个问题");

        String markdown = assembleMarkdown(report);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("projectPaths", evidencePack.get("projectPaths"));
        output.put("rootCauseResult", rootCauseResult);
        output.put("markdownReport", markdown);
        output.put("diagrams", diagrams);
        output.put("degraded", false);
        return output;
    }

    protected Map<String, Object> buildDegradedOutput(Map<?, ?> evidencePack, NodeEventSink sink,
                                                    List<Map<String, Object>> diagrams) {
        sink.emit("ReAct 未收敛，降级为模板报告");
        Map<String, Object> rootCauseResult = new LinkedHashMap<>();
        rootCauseResult.put("issues", List.of());
        rootCauseResult.put("degraded", true);
        String markdown = templateReport(evidencePack, Map.of("issues", List.of()));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("projectPaths", evidencePack.get("projectPaths"));
        output.put("rootCauseResult", rootCauseResult);
        output.put("markdownReport", markdown);
        output.put("diagrams", diagrams);
        output.put("degraded", true);
        return output;
    }

    /** 结构化报告 Markdown 组装：执行摘要 + 4+1 视图洞察 + 重构路线图 + 证据附录。 */
    private String assembleMarkdown(Map<String, Object> report) {
        StringBuilder md = new StringBuilder();
        md.append("# 架构深度分析报告\n\n");
        md.append("## 执行摘要\n").append(str(report.get("executive_summary"))).append("\n\n");

        Map<String, Object> views = asMap(report.get("views"));
        appendView(md, "逻辑视图", views.get("logical"));
        appendView(md, "过程视图", views.get("process"));
        appendView(md, "开发视图", views.get("development"));
        appendView(md, "物理视图", views.get("physical"));
        appendView(md, "场景视图", views.get("scenario"));

        appendRoadmap(md, report);

        md.append("## 证据附录\n").append(str(report.get("evidence_appendix"))).append("\n");
        return md.toString();
    }

    private void appendView(StringBuilder md, String title, Object viewObj) {
        md.append("## ").append(title).append("\n");
        Map<String, Object> view = asMap(viewObj);
        md.append(str(view.get("insight"))).append("\n");
        Object refs = view.get("diagramRefs");
        if (refs instanceof List<?> list && !list.isEmpty()) {
            md.append("\n引用架构图：").append(String.join("、", list.stream().map(String::valueOf).toList())).append("\n");
        }
        md.append("\n");
    }

    @SuppressWarnings("unchecked")
    private void appendRoadmap(StringBuilder md, Map<String, Object> report) {
        Map<String, Object> roadmap = asMap(report.get("roadmap"));
        md.append("## 重构路线图\n");
        appendRoadmapLevel(md, "P0（立即）", roadmap.get("p0"));
        appendRoadmapLevel(md, "P1（近期）", roadmap.get("p1"));
        appendRoadmapLevel(md, "P2（远期）", roadmap.get("p2"));
        md.append("\n");
    }

    private void appendRoadmapLevel(StringBuilder md, String title, Object itemsObj) {
        if (!(itemsObj instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        md.append("### ").append(title).append("\n");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            md.append("- **").append(str(m.get("action"))).append("**：").append(str(m.get("impact")));
            Object benefit = m.get("benefit");
            Object risk = m.get("risk");
            if (benefit != null && !String.valueOf(benefit).isBlank()) {
                md.append("（收益：").append(benefit).append("）");
            }
            if (risk != null && !String.valueOf(risk).isBlank()) {
                md.append("（风险：").append(risk).append("）");
            }
            md.append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /** 确定性模板兜底（4+1 视图标题始终齐全，标注降级）。 */
    @SuppressWarnings("unchecked")
    private String templateReport(Map<?, ?> evidencePack, Map<?, ?> rootCause) {
        StringBuilder md = new StringBuilder();
        md.append("> ⚠️ 本报告为降级模板（AI 深度分析未收敛），以下为确定性摘要。\n\n");

        md.append("## 逻辑视图\n（AI 分析降级，暂不可用）\n\n");
        md.append("## 过程视图\n（AI 分析降级，暂不可用）\n\n");
        md.append("## 开发视图\n（AI 分析降级，暂不可用）\n\n");
        md.append("## 物理视图\n（AI 分析降级，暂不可用）\n\n");
        md.append("## 场景视图\n（AI 分析降级，暂不可用）\n\n");

        md.append("## 问题分析\n");
        Object issues = rootCause.get("issues");
        if (issues instanceof List<?> list && !list.isEmpty()) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> issue) {
                    md.append("- [").append(issue.get("severity")).append("] ")
                            .append(issue.get("title")).append(" —— ")
                            .append(issue.get("rootCause")).append("\n");
                }
            }
        } else {
            md.append("健康分 ").append(evidencePack.get("healthScore"))
                    .append("（等级 ").append(evidencePack.get("grade")).append("），AI 分析降级，无法给出根因\n");
        }
        md.append("\n");

        md.append("## 证据附录\n");
        Object evidenceIssues = evidencePack.get("issues");
        if (evidenceIssues instanceof List<?> issueList && !issueList.isEmpty()) {
            md.append("诊断问题（").append(issueList.size()).append(" 项）：\n");
            for (Object i : issueList) {
                md.append("- ").append(i).append("\n");
            }
        } else {
            md.append("健康分 ").append(evidencePack.get("healthScore"))
                    .append("，等级 ").append(evidencePack.get("grade")).append("\n");
        }
        return md.toString();
    }

    protected String systemPrompt(List<Map<String, Object>> diagrams) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深软件架构诊断专家。基于给定的架构证据包，通过只读工具反复查询知识图谱，")
                .append("归纳问题根因并生成结构化深度分析报告。\n\n")
                .append("可用工具（每次只调一个）：\n");
        for (ArchAgentTool t : registry.tools()) {
            sb.append("- ").append(t.name()).append("：").append(t.description());
            if (!t.parameterSchema().isEmpty()) {
                sb.append(" 参数 ").append(t.parameterSchema());
            }
            sb.append("\n");
        }

        sb.append("\n本轮已由确定性图生成器产出的架构图（可在 views 各视图的 diagramRefs 中引用其 id）：\n");
        if (diagrams.isEmpty()) {
            sb.append("（无，可在 insight 中说明图缺失原因）\n");
        } else {
            for (Map<String, Object> d : diagrams) {
                sb.append("- ").append(d.get("id")).append("：").append(d.get("name")).append("\n");
            }
        }

        sb.append("\n每轮只输出一个 JSON 对象，二选一：\n")
                .append("1) 需要更多证据时：{\"thought\":\"推理\",\"action\":{\"tool\":\"工具名\",\"args\":{...}}}\n")
                .append("2) 证据充分时：{\"thought\":\"总结\",\"final_answer\":{\"issues\":[")
                .append("{\"ref\":\"编号\",\"title\":\"标题\",\"severity\":\"HIGH|MEDIUM|LOW\",")
                .append("\"rootCause\":\"根因\",\"evidenceRefs\":[\"证据引用\"],\"suggestion\":\"建议\",")
                .append("\"verdict\":\"confirmed|false_positive|downgraded\",\"reason\":\"判定理由\"}],")
                .append("\"report\":{\"executive_summary\":\"执行摘要（健康度+核心风险一句话）\",")
                .append("\"views\":{\"logical\":{\"insight\":\"洞察\",\"diagramRefs\":[\"图id\"]},")
                .append("\"process\":{\"insight\":\"洞察\",\"diagramRefs\":[\"图id\"]},")
                .append("\"development\":{\"insight\":\"洞察\",\"diagramRefs\":[\"图id\"]},")
                .append("\"physical\":{\"insight\":\"洞察\",\"diagramRefs\":[\"图id\"]},")
                .append("\"scenario\":{\"insight\":\"洞察\",\"diagramRefs\":[\"图id\"]}},")
                .append("\"roadmap\":{\"p0\":[{\"action\":\"动作\",\"impact\":\"影响\",\"benefit\":\"收益\",\"risk\":\"风险\"}],")
                .append("\"p1\":[...],\"p2\":[...]},")
                .append("\"evidence_appendix\":\"证据附录\"}}}\n\n")
                .append("要求：\n")
                .append("- severity 只能 HIGH/MEDIUM/LOW；issues 按严重度降序；evidenceRefs 引用可追溯的证据。\n")
                .append("- views 五个视图（logical/process/development/physical/scenario）缺一不可；")
                .append("每个视图的 insight 必须是「架构洞察」而非复述检测数字——回答为什么/影响面/优先级。\n")
                .append("- diagramRefs 每个元素必须【逐字符】等于上面「本轮已产出架构图」列出的 id，")
                .append("上面没列出的 id 一律不得出现，无对应图时传空数组 [];")
                .append("禁止改写/意译 id（例如把 cycle_rings 写成 loop_rings、把 deployment_topology 写成 service_topology）。\n")
                .append("- roadmap 分 p0/p1/p2 三档：p0 立即、p1 近期、p2 远期；")
                .append("每项至少给出 action 与 impact（改造动作与影响面），benefit 收益与 risk 风险尽量给出。\n")
                .append("- executive_summary 用 2-4 句话概括整体架构健康度、最关键的 1-2 个风险与优先行动方向。\n")
                .append("- 不要臆造图 id；不要输出 mermaid 代码，图已由生成器产出，你只负责引用与解读。\n\n")
                .append("灰度验证职责（必须执行）：对证据包 issues 中的每条候选问题，")
                .append("用只读工具查证其真伪（如用 method_by_class/upstream/root_entries/interfaces/implementations 等")
                .append("核对定位信息 className/methodName/nodeId 指向的真实代码），再给出 verdict。\n")
                .append("【抽样查证，不要逐条】同一类型的多条候选（例如多条 impl-leak/实现类外泄），")
                .append("先抽 1-2 条代表查证：若代表都判 false_positive（如 injection_scan 返回 total=0），")
                .append("且其余同类候选的定位信息相似，可整体判为该类型误报，不必再逐条调工具，")
                .append("直接进入 final_answer；仅当代表查证为 confirmed 时，才对需区分的其余同类逐条查证。\n")
                .append("凡涉及 impl-leak/实现类外泄/直接依赖实现类的候选问题，必须用 injection_scan 反向查询：")
                .append("调 injection_scan {\"injectedType\": \"<被依赖的实现类全限定名>\"}，按返回判定：\n")
                .append("- total=0（items 空）→ 无人直接注入该实现类 → false_positive（接口注入，非直接依赖实现类）；\n")
                .append("- kind=interface → false_positive（声明为接口，Spring 接口注入）；\n")
                .append("- kind=class → confirmed（直接依赖实现类属实）。\n")
                .append("若 injection_scan 返回空或拿不准，用 grep_source {\"pattern\": \"<实现类简单名>\"} 直接查源码亲眼核对：")
                .append("命中行只出现在实现类自身定义（impl 包）→ 无人直接注入 → false_positive；")
                .append("命中其他类的 @Autowired/@Resource/@Inject 字段或构造器参数 → 直接依赖实现类 → confirmed。\n")
                .append("注意：任何工具返回 scannedFileCount=0 都说明源码扫描未命中文件（项目路径可能不对），")
                .append("此时不得判 false_positive，改在 reason 注明「无法核实」并保留原严重度。\n")
                .append("- confirmed：查证属实，保留原严重度；\n")
                .append("- false_positive：误报（如调用方为测试类触发的实现类外泄），不予采纳；\n")
                .append("- downgraded：问题属实但严重度虚高，severity 给出下调后的等级，reason 说明为何虚高。\n")
                .append("最终报告只保留 confirmed 与 downgraded 的问题，false_positive 需在报告中标明已被过滤。\n\n")
                .append("若证据包 projectPaths 含多个项目，需跨项目聚合对比：")
                .append("分别归纳各项目的问题与架构健康度，并在执行摘要中对比项目间的共性与差异，")
                .append("产出一份跨项目报告书（而非每个项目各出一份）。");
        return sb.toString();
    }

    protected String userPrompt(Map<?, ?> evidencePack, List<Map<String, Object>> history) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("evidencePack", evidencePack);
        ctx.put("history", history);
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            return String.valueOf(ctx);
        }
    }

    @SuppressWarnings("unchecked")
    protected static List<String> extractProjectPaths(Map<?, ?> evidencePack) {
        Object obj = evidencePack.get("projectPaths");
        if (obj instanceof List<?> list) {
            return list.stream().filter(o -> o instanceof String).map(o -> (String) o).toList();
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
