package com.huawei.hisi.loganalysis.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.agentflow.Citations;
import com.huawei.hisi.agentflow.EvidenceAnchor;
import com.huawei.hisi.agentflow.EvidencePack;
import com.huawei.hisi.agentflow.GroundingResult;
import com.huawei.hisi.agentflow.GroundingVerifier;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日志分析 v2 的综合节点：把通过验证的假设写成根因分析，并产出修复建议。
 *
 * <p>与 v1 的区别：
 * <ul>
 *   <li>写作的输入只有「通过验证的假设 + 证据」，不让模型重新推理；</li>
 *   <li>写完之后再做一次引用校验，编造的引用会被替换成「未验证」标记并拉低置信度；</li>
 *   <li>证据清单由代码追加，保证与证据包一致；</li>
 *   <li>LLM 不可用时由证据直接生成确定性报告，流程不中断。</li>
 * </ul>
 *
 * <p>输出键与 v1 的 {@code ClaudeAnalyzeNode} 保持一致（供复用的 {@code ReportNode} 消费）：
 * {@code rootCauseAnalysis}、{@code fixSuggestions}、{@code analysisConfidence}、{@code patternType}、
 * {@code patternConfidence}；另附 {@code markdownGroundingV2}、{@code rawAnalysis}。
 */
@Slf4j
@Component
public class LogSynthesisNodeV2 implements LogAnalysisDagNode {

    static final String KEY_MARKDOWN_GROUNDING = "markdownGroundingV2";

    private static final String SYSTEM_PROMPT = """
            你是资深的 Java 后端工程师，负责把已经过验证的根因分析写成给开发者看的报告，使用中文 markdown。

            必须遵守的规则：
            1. 只能使用「已验证的假设」和「证据表」里的内容，不得补充证据之外的事实。
            2. 每一句事实性陈述都要用 [[E n]] 标注证据编号，且只能引用证据表中存在的编号。
            3. 「核实状态」只标在根因结论上，也就是每个假设的结论句末尾，取三种之一：「已由调用图证实」「堆栈已证实」「未验证」。
               验证结果里 chainStatus 为 verified 的写「已由调用图证实」，为 stack_verified 的写「堆栈已证实」，其余写「未验证」。
               引用证据的事实陈述（堆栈、调用关系、源码内容）不加核实状态，修复建议的标题里也不加。
            4. 不要把「未验证」的结论写成已确认的事实，也不要把它写成已排除。
            5. 如果给了「评审意见」，必须逐条回应：采纳并修正，或说明为何不采纳。

            直接输出 markdown 正文，不要加代码块围栏。结构：
            ## 根因结论
            ## 因果链
            ## 修复建议
            """;

    private static final Map<String, String> GENERIC_FIX = Map.of(
            "空指针", "在抛出位置补充空值判断，并沿上游调用链确认该值由谁传入、为何为空",
            "数据库", "核对 SQL 映射与表结构，检查参数绑定、约束与事务边界",
            "超时", "检查下游依赖的耗时与超时配置，评估重试、熔断或降级",
            "并发", "检查共享可变状态的加锁范围与可见性，确认是否存在竞态",
            "配置", "核对配置项、Bean 装配与依赖版本是否完整匹配",
            "外部依赖", "确认下游服务的地址、鉴权与可用性，补充失败时的兜底处理",
            "类型转换", "在入参边界增加类型与格式校验，避免非法值进入业务逻辑",
            "资源泄漏", "检查资源是否在 try-with-resources 或 finally 中释放",
            "其它", "结合堆栈与源码定位抛出位置的直接原因，再向上游追查触发条件");

    private static final int APPENDIX_MAX_ROWS = 20;

    private final AgentLlmClient llm;
    private final int maxTokens;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LogSynthesisNodeV2(AgentLlmClient llm,
                              @Value("${loganalysis.v2.synthesis-max-tokens:12000}") int maxTokens) {
        this.llm = llm;
        this.maxTokens = maxTokens;
    }

    @Override
    public String name() {
        return "SynthesisV2";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[SynthesisV2] 开始综合成文");

        Map<String, Object> output = new LinkedHashMap<>(input);
        EvidencePack pack = LogHypothesisNodeV2.packOf(input);
        List<Map<String, Object>> selected = input.get(LogVerifyNodeV2.KEY_SELECTED) instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        List<String> evaluatorIssues = input.get(LogVerifyNodeV2.KEY_EVALUATOR_ISSUES) instanceof List<?> l
                ? (List<String>) l : List.of();
        String confidence = textOr(input.get("analysisConfidence"), "low");
        String patternType = textOr(input.get("patternType"), "其它");

        String markdown;
        if (selected.isEmpty()) {
            // LLM 未给出可用假设：由证据直接生成确定性报告
            markdown = buildEvidenceOnlyMarkdown(input, pack, patternType);
            output.put("fixSuggestions", genericFixSuggestions(input, patternType));
            confidence = "low";
        } else {
            output.put("fixSuggestions", fixSuggestionsOf(selected));
            markdown = null;
            if (llm.isAvailable()) {
                try {
                    markdown = llm.completeText(SYSTEM_PROMPT, buildUserPrompt(selected, evaluatorIssues, pack), maxTokens);
                } catch (Exception e) {
                    log.warn("[SynthesisV2] LLM 综合成文失败，改用确定性模板: {}", e.getMessage());
                }
            }
            if (markdown == null || markdown.isBlank()) {
                log.warn("[SynthesisV2] LLM 没有返回正文，改用结构化模板成文");
                markdown = buildMarkdownFromHypotheses(selected);
            }
        }

        // 先对原文做引用校验，再把编造的引用替换成「未验证」标记——替换之后就查不出它们了
        GroundingResult grounding = GroundingVerifier.verify(pack, markdown);
        markdown = Citations.toDisplay(Citations.markUnknown(markdown, pack::contains));
        output.put(KEY_MARKDOWN_GROUNDING,
                Map.of("citedIds", grounding.citedIds(), "unknownIds", grounding.unknownIds()));
        if (!grounding.unknownIds().isEmpty()) {
            markdown += "\n\n> 说明：" + Citations.UNVERIFIED_DISPLAY + " 在证据中找不到对应条目，相关表述未经证实。";
            confidence = "low";
        } else if (!selected.isEmpty() && !grounding.hasCitation()) {
            markdown = "> 注意：以下分析未引用任何图谱证据，请结合原始堆栈判断。\n\n" + markdown;
            confidence = "low";
        }
        markdown += renderEvidenceAppendix(pack, grounding.citedIds());

        output.put("rootCauseAnalysis", markdown);
        output.put("analysisConfidence", confidence);
        output.put("rawAnalysis", rawAnalysis(selected, evaluatorIssues));
        log.info("[SynthesisV2] 完成: hypotheses={}, evidence={}, cited={}, unknown={}, confidence={}",
                selected.size(), pack.size(), grounding.citedIds().size(), grounding.unknownIds().size(), confidence);
        return output;
    }

    private String buildUserPrompt(List<Map<String, Object>> selected, List<String> evaluatorIssues,
                                   EvidencePack pack) {
        StringBuilder sb = new StringBuilder("## 已验证的假设\n");
        for (Map<String, Object> h : selected) {
            sb.append("### ").append(h.get("id")).append(" ").append(h.get("title")).append('\n');
            sb.append("- 置信度：").append(h.get("confidence")).append('\n');
            sb.append("- 核实状态：").append(chainStatusLabel(String.valueOf(h.get("chainStatus")))).append('\n');
            sb.append("- 症状：").append(h.get("symptom")).append('\n');
            sb.append("- 根因：").append(h.get("rootCause")).append('\n');
            sb.append("- 因果链：\n");
            if (h.get("causalChain") instanceof List<?> chain) {
                chain.forEach(step -> sb.append("  - ").append(step).append('\n'));
            }
            Map<String, Object> fix = (Map<String, Object>) h.get("fix");
            sb.append("- 修复：[").append(fix.get("priority")).append("] ").append(fix.get("suggestion"))
                    .append("（").append(fix.get("affectedCode")).append("）；预期效果：")
                    .append(fix.get("expectedEffect")).append("\n\n");
        }
        if (!evaluatorIssues.isEmpty()) {
            sb.append("## 评审意见（必须逐条回应）\n");
            evaluatorIssues.forEach(issue -> sb.append("- ").append(issue).append('\n'));
        }
        sb.append("\n## 证据表（编号 | 类型 | 说明）\n").append(pack.render(12000));
        sb.append("\n请按规则输出报告，不要输出「证据清单」，它会被自动追加。");
        return sb.toString();
    }

    /** LLM 不可用时的报告：只陈述证据里能直接读到的事实。 */
    @SuppressWarnings("unchecked")
    private String buildEvidenceOnlyMarkdown(Map<String, Object> input, EvidencePack pack, String patternType) {
        StringBuilder sb = new StringBuilder("## 根因结论\n\n");
        sb.append("> LLM 未参与本次分析，以下内容由确定性规则根据堆栈与调用图整理，置信度：低。\n\n");
        Map<String, Object> parsedError = input.get("parsedError") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        sb.append("- 故障模式：").append(patternType).append('\n');
        sb.append("- 根因异常：").append(textOr(parsedError.get("rootCauseException"), "未知")).append('\n');
        pack.list().stream().filter(a -> "frame".equals(a.kind())).limit(4).forEach(a ->
                sb.append("- ").append(a.kindLabel()).append("：`").append(a.methodRef()).append("()` ").append("[[").append(a.id()).append("]]\n"));

        sb.append("\n## 因果链\n\n");
        List<EvidenceAnchor> upstream = pack.list().stream().filter(a -> "upstream".equals(a.kind())).toList();
        if (upstream.isEmpty()) {
            sb.append("- 调用图中没有找到上游调用信息（项目未建图，或相关方法不在图中）。\n");
        } else {
            upstream.forEach(a -> sb.append("- ").append(a.summary()).append(" [[").append(a.id()).append("]]\n"));
        }
        List<EvidenceAnchor> entries = pack.list().stream().filter(a -> "entry".equals(a.kind())).toList();
        if (!entries.isEmpty()) {
            sb.append("\n可达的入口点：\n");
            entries.forEach(a -> sb.append("- ").append(a.summary()).append(" [[").append(a.id()).append("]]\n"));
        }
        return sb.toString();
    }

    /** LLM 综合成文失败时的报告：按假设逐条列出，不加工。 */
    @SuppressWarnings("unchecked")
    static String buildMarkdownFromHypotheses(List<Map<String, Object>> selected) {
        StringBuilder sb = new StringBuilder("## 根因结论\n\n");
        for (Map<String, Object> h : selected) {
            sb.append("### ").append(h.get("title"))
                    .append("（置信度：").append(h.get("confidence"))
                    .append("，").append(chainStatusLabel(String.valueOf(h.get("chainStatus")))).append("）\n\n");
            sb.append("- 症状：").append(h.get("symptom")).append('\n');
            sb.append("- 根因：").append(h.get("rootCause")).append('\n');
            sb.append("- 因果链：\n");
            if (h.get("causalChain") instanceof List<?> chain) {
                chain.forEach(step -> sb.append("  - ").append(step).append('\n'));
            }
            sb.append('\n');
        }
        sb.append("## 修复建议\n\n");
        for (Map<String, Object> h : selected) {
            Map<String, Object> fix = (Map<String, Object>) h.get("fix");
            sb.append("- **[").append(fix.get("priority")).append("]** ").append(fix.get("suggestion"))
                    .append("（`").append(fix.get("affectedCode")).append("`）").append('\n');
        }
        return sb.toString();
    }

    /** 从通过的假设里提取修复建议，键名与 v1 ReportNode 消费的一致。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fixSuggestionsOf(List<Map<String, Object>> selected) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> h : selected) {
            Map<String, Object> fix = (Map<String, Object>) h.get("fix");
            String suggestion = textOr(fix.get("suggestion"), "");
            if (suggestion.isEmpty() || !seen.add(suggestion)) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("suggestion", suggestion);
            s.put("priority", textOr(fix.get("priority"), "P2"));
            s.put("affectedCode", fix.get("affectedCode"));
            s.put("expectedEffect", fix.get("expectedEffect"));
            out.add(s);
        }
        return out;
    }

    private List<Map<String, Object>> genericFixSuggestions(Map<String, Object> input, String patternType) {
        String affected = input.get("rootCauseFrames") instanceof List<?> frames && !frames.isEmpty()
                && frames.get(0) instanceof Map<?, ?> f
                ? textOr(f.get("className"), "") + "." + textOr(f.get("methodName"), "")
                : "";
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("suggestion", GENERIC_FIX.getOrDefault(patternType, GENERIC_FIX.get("其它")));
        s.put("priority", "P1");
        s.put("affectedCode", affected);
        s.put("expectedEffect", "定位并消除触发该异常的直接条件");
        return List.of(s);
    }

    /** 证据清单由代码追加：优先列出正文引用过的证据。 */
    static String renderEvidenceAppendix(EvidencePack pack, List<String> citedIds) {
        List<EvidenceAnchor> rows = new ArrayList<>();
        if (citedIds.isEmpty()) {
            pack.list().stream().limit(APPENDIX_MAX_ROWS).forEach(rows::add);
        } else {
            for (String id : citedIds) {
                if (rows.size() >= APPENDIX_MAX_ROWS) {
                    break;
                }
                EvidenceAnchor a = pack.get(id);
                if (a != null) {
                    rows.add(a);
                }
            }
        }
        StringBuilder sb = new StringBuilder("\n\n## 证据清单\n\n| 编号 | 类型 | 说明 |\n|---|---|---|\n");
        for (EvidenceAnchor a : rows) {
            sb.append("| ").append(a.id()).append(" | ").append(a.kindLabel()).append(" | ")
                    .append(a.summary().replace("|", "\\|").replace("\n", " ").replace("\r", " "))
                    .append(" |\n");
        }
        return sb.toString();
    }

    static String chainStatusLabel(String chainStatus) {
        return switch (chainStatus) {
            case LogVerifyNodeV2.CHAIN_VERIFIED -> "已由调用图证实";
            case LogVerifyNodeV2.CHAIN_STACK_VERIFIED -> "堆栈已证实";
            default -> "未验证";
        };
    }

    private String rawAnalysis(List<Map<String, Object>> selected, List<String> evaluatorIssues) {
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("version", "v2.0");
            raw.put("hypotheses", selected.stream().map(h -> Map.of(
                    "id", textOr(h.get("id"), ""),
                    "title", textOr(h.get("title"), ""),
                    "confidence", textOr(h.get("confidence"), "low"),
                    "chainStatus", textOr(h.get("chainStatus"), "unknown"))).toList());
            raw.put("evaluatorIssues", evaluatorIssues);
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            return "{\"version\":\"v2.0\"}";
        }
    }

    private static String textOr(Object raw, String fallback) {
        return raw == null || String.valueOf(raw).isBlank() ? fallback : String.valueOf(raw);
    }
}
