package com.huawei.hisi.workflow.nodes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.knowledgegraph.aggregation.llm.DeepseekJsonClient;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.NodeEventSink;
import com.huawei.hisi.workflow.agent.ArchAgentTool;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import com.huawei.hisi.workflow.agent.ObservationTrimmer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 架构深度分析 ReAct 智能体节点：在 {@link DeepseekJsonClient}（json_object 强制）
 * 之上跑手动 ReAct 循环 —— 推理 → 调只读工具查 KG → 观察 → 再推理，
 * 收敛后产出根因列表 + 四段式 Markdown 报告。
 *
 * <p>输出契约与旧根因/报告节点兼容：{@code {rootCauseResult, markdownReport, degraded}}。
 * 未收敛 / JSON 连续失败时降级为确定性模板报告（degraded=true），不阻断。</p>
 */
@Slf4j
@Component
public class ArchReActAgentNode implements DagNode {

    static final String SCHEMA_NAME = "arch/react-step";

    private final DeepseekJsonClient llm;
    private final ArchAgentToolRegistry registry;
    private final ObservationTrimmer trimmer;
    private final SchemaValidator validator;
    private final ArchDiagramGenerator diagramGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int maxSteps;
    private final int observationMaxChars;

    public ArchReActAgentNode(DeepseekJsonClient llm,
                              ArchAgentToolRegistry registry,
                              ObservationTrimmer trimmer,
                              SchemaValidator validator,
                              ArchDiagramGenerator diagramGenerator,
                              @Value("${arch.react.max-steps:6}") int maxSteps,
                              @Value("${arch.react.observation-max-chars:2000}") int observationMaxChars) {
        this.llm = llm;
        this.registry = registry;
        this.trimmer = trimmer;
        this.validator = validator;
        this.diagramGenerator = diagramGenerator;
        this.maxSteps = maxSteps;
        this.observationMaxChars = observationMaxChars;
    }

    @Override
    public String name() {
        return "arch_react_agent";
    }

    @Override
    public String agentId() {
        return "arch-react-agent-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        return execute(input, msg -> {});
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, NodeEventSink sink) {
        if (input == null || !(input.get("evidencePack") instanceof Map<?, ?> evidencePack)) {
            throw new IllegalArgumentException("evidencePack is required (upstream arch_evidence_collect output)");
        }
        List<String> projectPaths = extractProjectPaths(evidencePack);
        // 图生成器在 ReAct 前运行：确定性产出，供 LLM 在 views.diagramRefs 中引用，降级时仍保留
        List<Map<String, Object>> diagrams = generateDiagrams(evidencePack, projectPaths);

        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Object> finalAnswer = null;
        int consecutiveFailures = 0;

        for (int step = 1; step <= maxSteps; step++) {
            sink.emit("推理（第 " + step + " 轮）…");
            String json = llm.chatJson(systemPrompt(diagrams), userPrompt(evidencePack, history), String.class);
            Map<String, Object> stepResult = parseStep(json);
            if (stepResult == null) {
                consecutiveFailures++;
                if (consecutiveFailures >= 2) {
                    sink.emit("连续解析失败，提前终止");
                    break;
                }
                continue;
            }
            ValidationResult vr = validator.validate(SCHEMA_NAME, stepResult);
            if (!vr.passed()) {
                consecutiveFailures++;
                sink.emit("第 " + step + " 轮契约校验未过：" + vr.missingFields() + vr.violations());
                if (consecutiveFailures >= 2) {
                    break;
                }
                continue;
            }
            // 运行时补验 diagramRefs：引用的图 id 必须真实存在（静态 JSON Schema 无法枚举动态图 id）
            ValidationResult drVr = validateDiagramRefs(stepResult, diagrams);
            if (!drVr.passed()) {
                consecutiveFailures++;
                sink.emit("第 " + step + " 轮图引用校验未过：" + drVr.violations());
                if (consecutiveFailures >= 2) {
                    break;
                }
                continue;
            }
            consecutiveFailures = 0;

            if (stepResult.get("final_answer") instanceof Map<?, ?> fa) {
                @SuppressWarnings("unchecked")
                Map<String, Object> answer = (Map<String, Object>) fa;
                finalAnswer = answer;
                break;
            }
            if (stepResult.get("action") instanceof Map<?, ?> action) {
                String tool = (String) action.get("tool");
                @SuppressWarnings("unchecked")
                Map<String, Object> args = action.get("args") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                Object raw;
                ArchAgentTool t = registry.get(tool);
                if (t == null) {
                    raw = Map.of("error", "未知工具：" + tool);
                    consecutiveFailures++;
                } else {
                    sink.emit("查询工具：" + tool);
                    try {
                        raw = t.invoke(args, projectPaths);
                    } catch (Exception e) {
                        log.warn("[ArchReActAgentNode] tool {} failed: {}", tool, e.getMessage());
                        raw = Map.of("error", e.getMessage());
                    }
                }
                String observation = trimmer.trim(raw, observationMaxChars);
                sink.emit("观察到：" + observation);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("thought", stepResult.get("thought"));
                record.put("action", action);
                record.put("observation", observation);
                history.add(record);
                if (consecutiveFailures >= 2) {
                    break;
                }
                continue;
            }
            consecutiveFailures++;
            sink.emit("第 " + step + " 轮无 action/final_answer");
            if (consecutiveFailures >= 2) {
                break;
            }
        }

        return finalAnswer != null
                ? buildOutput(evidencePack, finalAnswer, sink, diagrams)
                : buildDegradedOutput(evidencePack, sink, diagrams);
    }

    /** 确定性图生成，失败降级为空列表，不阻断 ReAct 主流程。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> generateDiagrams(Map<?, ?> evidencePack, List<String> projectPaths) {
        try {
            List<Map<String, Object>> diagrams =
                    diagramGenerator.generate((Map<String, Object>) evidencePack, projectPaths);
            return diagrams == null ? List.of() : diagrams;
        } catch (Exception e) {
            log.warn("[ArchReActAgentNode] 图生成失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildOutput(Map<?, ?> evidencePack,
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

    private Map<String, Object> buildDegradedOutput(Map<?, ?> evidencePack, NodeEventSink sink,
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

    private Map<String, Object> parseStep(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[ArchReActAgentNode] step parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 运行时校验 diagramRefs：引用的图 id 必须真实存在于图生成器产物。
     * 静态 JSON Schema（react-step.json）只能约束 diagramRefs 为 string[]，
     * 无法枚举运行时动态产出的图 id，故在此补硬校验，根治 LLM 臆造/改写图 id
     * （如把 cycle_rings 写成 loop_rings、deployment_topology 写成 service_topology）。
     */
    private ValidationResult validateDiagramRefs(Map<String, Object> stepResult,
                                                 List<Map<String, Object>> diagrams) {
        Set<String> validIds = diagrams.stream()
                .map(d -> String.valueOf(d.get("id")))
                .collect(Collectors.toSet());
        List<String> violations = new ArrayList<>();
        Object fa = stepResult.get("final_answer");
        if (!(fa instanceof Map<?, ?> faMap)) {
            return new ValidationResult(true, List.of(), violations);
        }
        Object report = faMap.get("report");
        if (!(report instanceof Map<?, ?> reportMap)) {
            return new ValidationResult(true, List.of(), violations);
        }
        Object views = reportMap.get("views");
        if (!(views instanceof Map<?, ?> viewsMap)) {
            return new ValidationResult(true, List.of(), violations);
        }
        for (Object v : viewsMap.values()) {
            if (!(v instanceof Map<?, ?> viewMap)) {
                continue;
            }
            Object refs = viewMap.get("diagramRefs");
            if (!(refs instanceof List<?> list)) {
                continue;
            }
            for (Object ref : list) {
                String s = String.valueOf(ref);
                if (s.isBlank() || validIds.contains(s)) {
                    continue;
                }
                violations.add("diagramRefs 引用不存在的图 id：" + s + "（可用：" + validIds + "）");
            }
        }
        return new ValidationResult(violations.isEmpty(), List.of(), violations);
    }

    private String systemPrompt(List<Map<String, Object>> diagrams) {
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
                .append("用只读工具逐条查证其真伪（如用 method_by_class/upstream/root_entries/interfaces/implementations 等")
                .append("核对定位信息 className/methodName/nodeId 指向的真实代码），再给出 verdict：\n")
                .append("- confirmed：查证属实，保留原严重度；\n")
                .append("- false_positive：误报（如调用方为测试类触发的实现类外泄），不予采纳；\n")
                .append("- downgraded：问题属实但严重度虚高，severity 给出下调后的等级，reason 说明为何虚高。\n")
                .append("最终报告只保留 confirmed 与 downgraded 的问题，false_positive 需在报告中标明已被过滤。\n\n")
                .append("若证据包 projectPaths 含多个项目，需跨项目聚合对比：")
                .append("分别归纳各项目的问题与架构健康度，并在执行摘要中对比项目间的共性与差异，")
                .append("产出一份跨项目报告书（而非每个项目各出一份）。");
        return sb.toString();
    }

    private String userPrompt(Map<?, ?> evidencePack, List<Map<String, Object>> history) {
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
    private static List<String> extractProjectPaths(Map<?, ?> evidencePack) {
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
