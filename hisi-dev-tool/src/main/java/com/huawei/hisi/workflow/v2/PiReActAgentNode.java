package com.huawei.hisi.workflow.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.knowledgegraph.aggregation.llm.DeepseekJsonClient;
import com.huawei.hisi.piagent.PiFlowClient;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.workflow.NodeEventSink;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import com.huawei.hisi.workflow.agent.ObservationTrimmer;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 架构深度分析的 pi 版 ReAct 节点：把「手动 ReAct 循环」换成 pi 会话——
 * pi 自己调用清单里的只读 MCP 工具查证，收敛后输出 final_answer。
 *
 * <p>节点名与 v1 相同（{@code arch_react_agent}，前端写死），输出契约不变
 * （{@code {rootCauseResult, markdownReport, diagrams, degraded}}）；prompt 的
 * 查证规则、schema 校验、diagramRefs 校验、降级模板全部复用父类。
 * 清单为 {@code arch-react}（纯只读：MCP 的 kg/hybrid 工具 + pi 的 read/grep/find/ls），
 * 不含任何写文件或执行命令的工具。
 */
@Slf4j
public class PiReActAgentNode extends com.huawei.hisi.workflow.nodes.ArchReActAgentNode {

    /** 与 v1 节点同名：事件、检查点、从节点重跑对前端保持一致。 */
    public static final String NODE_NAME = "arch_react_agent";

    private final PiFlowClient piClient;
    private final AgentFlowCancelRegistry cancelRegistry;

    public PiReActAgentNode(DeepseekJsonClient llm,
                            ArchAgentToolRegistry registry,
                            ObservationTrimmer trimmer,
                            SchemaValidator validator,
                            ArchDiagramGenerator diagramGenerator,
                            int maxSteps,
                            int observationMaxChars,
                            PiFlowClient piClient,
                            AgentFlowCancelRegistry cancelRegistry) {
        super(llm, registry, trimmer, validator, diagramGenerator, maxSteps, observationMaxChars);
        this.piClient = piClient;
        this.cancelRegistry = cancelRegistry;
    }

    @Override
    public String name() {
        return NODE_NAME;
    }

    @Override
    public String agentId() {
        return "arch-react-agent-pi";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, NodeEventSink sink) {
        if (input == null || !(input.get("evidencePack") instanceof Map<?, ?> evidencePack)) {
            throw new IllegalArgumentException("evidencePack is required (upstream arch_evidence_collect output)");
        }
        List<String> projectPaths = extractProjectPaths(evidencePack);
        List<Map<String, Object>> diagrams = generateDiagrams(evidencePack, projectPaths);

        String sessionId = "arch-" + UUID.randomUUID().toString().substring(0, 12);
        Path workdir = projectPaths.isEmpty() ? Path.of(System.getProperty("user.dir")) : Path.of(projectPaths.get(0));
        if (!Files.isDirectory(workdir)) {
            workdir = Path.of(System.getProperty("user.dir"));
        }

        String systemPrompt = piSystemPrompt(diagrams, projectPaths);
        String userPrompt = userPrompt(evidencePack, List.of());
        sink.emit("pi 会话启动：按只读清单查证中…");

        PiFlowClient.PiResult result = piClient.run("arch-react", sessionId, workdir, null,
                systemPrompt, userPrompt, event -> reportEvent(event, sink), cancelRegistry::isCurrentCancelled);

        if (result.timedOut()) {
            sink.emit("pi 会话超时，降级为模板报告");
            return buildDegradedOutput(evidencePack, sink, diagrams);
        }
        if ("cancelled".equals(result.stopReason()) || "aborted".equals(result.stopReason())) {
            throw new com.huawei.hisi.agentflow.AgentFlowCancelledException();
        }
        if (result.finalText().isBlank()) {
            // 证据包不再由这里裁剪：提交前的预算检查与压缩是底座的能力（PiFlowClient → PromptBudget），所有流共用
            sink.emit("pi 会话未产出结果（" + result.stopReason()
                    + (result.note() == null ? "" : "：" + result.note()) + "），降级为模板报告");
            return buildDegradedOutput(evidencePack, sink, diagrams);
        }

        // 结论要过契约（JSON 可解析、schema、图引用）。模型的输出格式并不总听话——真机上出现过结论后面
        // 拖着工具调用标记、evidence_appendix 给成对象——不符时带着具体问题让它修正，而不是直接丢掉整份分析
        String text = result.finalText();
        Map<String, Object> finalAnswer = finalStep(text);
        String problem = contractProblem(finalAnswer, diagrams);
        for (int round = 1; problem != null && round <= REPAIR_ROUNDS; round++) {
            sink.emit("结论不符合契约（" + problem + "），第 " + round + " 轮修正…");
            PiFlowClient.PiResult repair = piClient.run("arch-react", sessionId + "-fix" + round, workdir, null,
                    systemPrompt, repairPrompt(text, problem), event -> reportEvent(event, sink), cancelRegistry::isCurrentCancelled);
            if ("cancelled".equals(repair.stopReason()) || "aborted".equals(repair.stopReason())) {
                throw new com.huawei.hisi.agentflow.AgentFlowCancelledException();
            }
            if (repair.timedOut() || repair.finalText().isBlank()) {
                break;
            }
            text = repair.finalText();
            finalAnswer = finalStep(text);
            problem = contractProblem(finalAnswer, diagrams);
        }
        if (problem != null) {
            sink.emit(problem + "，降级为模板报告");
            return buildDegradedOutput(evidencePack, sink, diagrams);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = (Map<String, Object>) finalAnswer.get("final_answer");
        return buildOutput(evidencePack, answer, sink, diagrams);
    }

    /** 结论不符契约时最多让模型修正几轮（每轮是一次只读 pi 会话）。 */
    static final int REPAIR_ROUNDS = 2;

    /** 契约问题描述；符合契约返回 null。 */
    private String contractProblem(Map<String, Object> step, List<Map<String, Object>> diagrams) {
        if (step == null) {
            return "输出不是 JSON，或缺少 final_answer";
        }
        ValidationResult vr = validator.validate(SCHEMA_NAME, step);
        if (!vr.passed()) {
            return "契约校验未过：" + vr.missingFields() + vr.violations();
        }
        ValidationResult drVr = validateDiagramRefs(step, diagrams);
        if (!drVr.passed()) {
            return "图引用校验未过：" + drVr.violations();
        }
        return null;
    }

    /** 修正提示：带着具体的契约问题，在上一份结论的基础上改，不重新查证。 */
    static String repairPrompt(String previousOutput, String problem) {
        return "你上一次的输出没有通过契约校验。\n问题：" + problem + "\n\n"
                + "请在上一份结论的基础上修正后重新输出，要求：\n"
                + "1. 只输出一个 JSON 对象：{\"thought\":\"...\",\"final_answer\":{...}}，字段与系统提示词里的 schema 逐项一致；\n"
                + "2. 不要调用任何工具，不要输出 JSON 以外的任何文字、说明或标记；\n"
                + "3. 不改变已经查证过的结论，只修格式与不符的字段。\n\n"
                + "上一份输出：\n" + previousOutput;
    }

    /**
     * pi 的最终输出整理成契约里的一步。pi 模式没有逐步的 thought，这个字段只有说明作用：
     * 模型漏掉它时不该把整份结论判成契约不符而丢弃，这里补一个默认值。
     *
     * @return 找不到带 final_answer 的 JSON 对象时返回 null
     */
    Map<String, Object> finalStep(String finalText) {
        for (String candidate : jsonObjectCandidates(finalText)) {
            Map<String, Object> step = parseStep(candidate);
            if (step != null && step.get("final_answer") instanceof Map<?, ?>) {
                step.putIfAbsent("thought", "pi 会话结论");
                coerceEvidenceAppendix(step);
                return step;
            }
        }
        return null;
    }

    /** evidence_appendix 契约上是字符串；模型给了对象/数组时转成 JSON 文本，不因此丢掉整份结论。 */
    @SuppressWarnings("unchecked")
    private static void coerceEvidenceAppendix(Map<String, Object> step) {
        if (step.get("final_answer") instanceof Map<?, ?> answer && answer.get("report") instanceof Map<?, ?> report
                && report.get("evidence_appendix") != null && !(report.get("evidence_appendix") instanceof String)) {
            ((Map<String, Object>) report).put("evidence_appendix", toJson(report.get("evidence_appendix")));
        }
    }

    private static String toJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 正文里每个「配对完整的 JSON 对象」片段，从最外层起逐个给出。
     * 不能用「第一个 { 到最后一个 }」：模型会在结论后面拖上工具调用的标记文本，那样整段都解析不了。
     */
    static List<String> jsonObjectCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return candidates;
        }
        String trimmed = text.trim();
        for (int start = trimmed.indexOf('{'); start >= 0 && candidates.size() < 50; start = trimmed.indexOf('{', start + 1)) {
            int end = matchingBrace(trimmed, start);
            if (end > start) {
                candidates.add(trimmed.substring(start, end + 1));
            }
        }
        return candidates;
    }

    /** {@code start} 处 '{' 的配对 '}' 位置，跳过字符串里的括号；配不上返回 -1。 */
    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把底座的上下文管理动作显示在进度里：提交前的预算压缩（所有流都有），
     * 以及 pi 运行中的会话压缩（上下文接近窗口上限时总结较早的内容）。
     */
    static void reportEvent(Map<String, Object> event, NodeEventSink sink) {
        String type = String.valueOf(event.get("type"));
        if ("tool_execution_start".equals(type)) {
            sink.emit("查询工具：" + event.getOrDefault("toolName", "?"));
        } else if ("context_compaction".equals(type)) {
            sink.emit(String.format("上下文预算：输入 %.1f 万字符 → %.1f 万字符（%s），完整原文已落盘可供回看",
                    number(event.get("beforeChars")) / 10_000.0, number(event.get("afterChars")) / 10_000.0,
                    Boolean.TRUE.equals(event.get("structured")) ? "长列表已按预算截短" : "只保留了开头"));
        } else if ("auto_compaction_start".equals(type) || "compaction_start".equals(type)) {
            sink.emit("上下文压缩：会话接近窗口上限，正在总结较早的内容…");
        } else if ("auto_compaction_end".equals(type) || "compaction_end".equals(type)) {
            Object error = event.get("errorMessage");
            if (error != null) {
                sink.emit("上下文压缩失败：" + error);
            } else {
                sink.emit(String.format("上下文压缩完成：%s token → %s token",
                        event.getOrDefault("tokensBefore", "?"), event.getOrDefault("tokensAfter", "?")));
            }
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** pi 模式系统提示词：查证规则与父类一致，协议改为自由工具调用 + 最终 JSON。 */
    private String piSystemPrompt(List<Map<String, Object>> diagrams, List<String> projectPaths) {
        StringBuilder sb = new StringBuilder(super.systemPrompt(diagrams));
        sb.append("\n\n—— 执行协议（pi 模式）——\n")
                .append("你可以直接调用工具，不必输出 thought/action JSON；工具返回后继续查证，直到证据充分。\n")
                .append("第一步：用 kg_select_scope 设置项目路径（list 然后 set），项目：")
                .append(String.join("、", projectPaths)).append("。\n")
                .append("最后一步：只输出一个 JSON 对象，形如 {\"thought\":\"<一句话说明结论依据>\",\"final_answer\":{...}}，")
                .append("final_answer 与上面 schema 一致；不要输出其他文字、不要加代码围栏。\n")
                .append("查证原则：先看 evidencePack 里的证据，只对拿不准的候选问题调工具抽查，")
                .append("不要逐条调用；工具返回 scannedFileCount=0 时不得判 false_positive。\n")
                .append("输入经过上下文预算：过长的列表带 _truncated 标记（说明还有多少条没展示），")
                .append("_contextNotice 给出完整原文的保存路径；被截掉的部分不要当作不存在，必要时用 read 工具分段查看。\n")
                .append("evidence_appendix 是字符串字段（把证据附录写成一段文字），不要给成对象或数组。");
        return sb.toString();
    }
}
