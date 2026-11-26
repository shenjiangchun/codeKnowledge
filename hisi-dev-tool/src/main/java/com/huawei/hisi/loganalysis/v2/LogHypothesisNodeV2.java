package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.agentflow.EvidencePack;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 日志分析 v2 的假设生成节点：一次 LLM 调用，给出按置信度排序的根因假设，每条必须引用证据编号。
 *
 * <p>与 v1 的区别：模式类型改由异常名的确定性规则判定，不再单独调用一次 LLM；
 * 假设是结构化中间产物，交给下游 {@link LogVerifyNodeV2} 验证，而不是直接写成结论。
 *
 * <p>输出键：{@code hypothesesV2}、{@code patternType}、{@code patternConfidence}、{@code analysisVersion}。
 * LLM 不可用或输出不合法时 {@code hypothesesV2} 为 null，由后续节点走确定性降级。
 */
@Slf4j
@Component
public class LogHypothesisNodeV2 implements LogAnalysisDagNode {

    static final String KEY_HYPOTHESES = "hypothesesV2";

    private static final int MAX_HYPOTHESES = 3;

    private static final String SYSTEM_PROMPT = """
            你是资深的 Java 后端故障诊断专家，负责根据异常堆栈、调用图证据和源码片段判断根因。

            必须遵守的规则：
            1. 只能依据「证据表」和「源码片段」里给出的事实下结论。每一条因果链步骤都必须用 [[E n]] 标注支撑它的证据编号（例如 [[E3]]、[[E5]]）。
            2. 只能引用证据表中真实存在的编号。不得编造证据编号，不得提到证据里没有出现的类、方法或配置。
            3. 区分「症状」与「根因」：异常抛出的位置不一定是根因。优先沿「上游调用链」向上追查，是谁传入了错误数据、缺少了校验或用错了参数。
            4. 最多给出 3 个假设，按可能性从高到低排列。证据不足时必须把 confidence 设为 low，并在 reasoning 中写明还缺什么证据。
            5. 修复建议必须落在证据中真实存在的方法上，affectedCode 写「类名.方法名」。
            6. 源码与异常信息或堆栈对不上时（例如消息说某个方法返回了 null，但它的源码里总会返回对象；
               或消息提到的调用在源码里根本不存在），必须明确指出这处矛盾，并把它列为第一个假设。
               不要顺着消息的措辞去推断，也不要为没有源码依据的说法编造细节。

            只输出一个 JSON 对象，不要输出 markdown 代码块，不要输出任何解释。格式：
            {
              "patternType": "故障模式：空指针 / 数据库 / 超时 / 并发 / 配置 / 外部依赖 / 类型转换 / 资源泄漏 / 其它",
              "hypotheses": [
                {
                  "title": "一句话结论",
                  "confidence": "high 或 medium 或 low",
                  "reasoning": "推断过程，以及还缺什么证据",
                  "symptom": "观察到的症状",
                  "rootCause": "推断的根因",
                  "causalChain": ["步骤一 [[E2]]", "步骤二 [[E7]]"],
                  "rootCauseFrame": {"className": "证据中的类全名", "methodName": "方法名"},
                  "fix": {
                    "suggestion": "具体怎么改",
                    "priority": "P0 或 P1 或 P2",
                    "affectedCode": "类名.方法名",
                    "expectedEffect": "改完之后的效果"
                  }
                }
              ]
            }
            """;

    private final AgentLlmClient llm;
    private final int maxTokens;
    private final int evidenceMaxChars;

    public LogHypothesisNodeV2(AgentLlmClient llm,
                               @Value("${loganalysis.v2.hypothesis-max-tokens:8000}") int maxTokens,
                               @Value("${loganalysis.v2.evidence-max-chars:12000}") int evidenceMaxChars) {
        this.llm = llm;
        this.maxTokens = maxTokens;
        this.evidenceMaxChars = evidenceMaxChars;
    }

    @Override
    public String name() {
        return "HypothesisV2";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[HypothesisV2] 开始生成根因假设");

        Map<String, Object> output = new LinkedHashMap<>(input);
        EvidencePack pack = packOf(input);
        Map<String, Object> parsedError = (Map<String, Object>) input.get("parsedError");

        // 模式类型由规则判定，不依赖 LLM；后续节点即使降级也能用到
        output.put("analysisVersion", "v2.0");
        output.put("patternType", inferPatternType(parsedError, input.get("message")));

        if (!llm.isAvailable()) {
            log.info("[HypothesisV2] LLM 不可用，交由后续节点做确定性分析");
            output.put(KEY_HYPOTHESES, null);
            return output;
        }

        try {
            Map<String, Object> json = llm.completeJson(SYSTEM_PROMPT, buildUserPrompt(input, pack), maxTokens);
            List<Map<String, Object>> hypotheses = normalize(json.get("hypotheses"));
            if (hypotheses.isEmpty()) {
                throw new IllegalStateException("LLM 未给出任何假设");
            }
            output.put(KEY_HYPOTHESES, hypotheses);
            if (json.get("patternType") instanceof String s && !s.isBlank()) {
                output.put("patternType", s.trim());
            }
            // 模式类型来自模型判断，先给中等置信，验证节点会再调整
            output.put("patternConfidence", "medium");
            log.info("[HypothesisV2] 生成 {} 个假设", hypotheses.size());
        } catch (Exception e) {
            log.warn("[HypothesisV2] LLM 假设生成失败，交由后续节点做确定性分析: {}", e.getMessage());
            output.put(KEY_HYPOTHESES, null);
        }
        return output;
    }

    static EvidencePack packOf(Map<String, Object> input) {
        return input.get(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK) instanceof EvidencePack p ? p : new EvidencePack();
    }

    /** 把模型输出整理成受控结构：最多 3 条，字段齐全，置信度取值受限。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> normalize(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> items)) {
            return out;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m) || out.size() >= MAX_HYPOTHESES) {
                continue;
            }
            Map<String, Object> src = (Map<String, Object>) m;
            Map<String, Object> fix = src.get("fix") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
            Map<String, Object> frame = src.get("rootCauseFrame") instanceof Map<?, ?> r
                    ? (Map<String, Object>) r : Map.of();
            List<String> chain = new ArrayList<>();
            if (src.get("causalChain") instanceof List<?> chainItems) {
                chainItems.forEach(c -> { if (c != null) chain.add(String.valueOf(c)); });
            }
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", "H" + (out.size() + 1));
            h.put("title", text(src.get("title")));
            h.put("confidence", normalizeConfidence(src.get("confidence")));
            h.put("reasoning", text(src.get("reasoning")));
            h.put("symptom", text(src.get("symptom")));
            h.put("rootCause", text(src.get("rootCause")));
            h.put("causalChain", chain);
            h.put("rootCauseFrame", Map.of("className", text(frame.get("className")), "methodName", text(frame.get("methodName"))));
            Map<String, Object> fixOut = new LinkedHashMap<>();
            fixOut.put("suggestion", text(fix.get("suggestion")));
            fixOut.put("priority", normalizePriority(fix.get("priority")));
            fixOut.put("affectedCode", text(fix.get("affectedCode")));
            fixOut.put("expectedEffect", text(fix.get("expectedEffect")));
            h.put("fix", fixOut);
            out.add(h);
        }
        return out;
    }

    private String buildUserPrompt(Map<String, Object> input, EvidencePack pack) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 异常信息\n");
        if (input.get("parsedError") instanceof Map<?, ?> parsed) {
            sb.append("- 异常类型：").append(text(parsed.get("errorType"))).append('\n');
            sb.append("- 根因异常：").append(text(parsed.get("rootCauseException"))).append('\n');
            sb.append("- 消息：").append(truncate(text(parsed.get("fullMessage")), 800)).append('\n');
        }
        sb.append("\n## 证据表（编号 | 类型 | 说明）\n");
        sb.append(pack.isEmpty() ? "（无）\n" : pack.render(evidenceMaxChars));

        List<MethodBodyInfo> bodies = bodiesOf(input);
        if (!bodies.isEmpty()) {
            sb.append("\n## 源码片段\n");
            for (MethodBodyInfo b : bodies) {
                sb.append("### ").append(b.className()).append('.').append(b.methodName())
                        .append("()").append(b.filePath() != null ? "（" + b.filePath() + "）" : "").append('\n');
                sb.append("```java\n").append(b.methodBody()).append("\n```\n");
            }
        }
        sb.append("\n请按规则输出 JSON。");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static List<MethodBodyInfo> bodiesOf(Map<String, Object> input) {
        return input.get(LogKgEvidenceNodeV2.KEY_CODE_BODIES) instanceof List<?> l ? (List<MethodBodyInfo>) l : List.of();
    }

    /** 按异常名与消息里的关键字判定故障模式，规则互斥优先级从上到下。 */
    @SuppressWarnings("unchecked")
    static String inferPatternType(Map<String, Object> parsedError, Object message) {
        String joined = "";
        if (parsedError != null) {
            joined += " " + text(parsedError.get("rootCauseException")) + " " + text(parsedError.get("errorType"))
                    + " " + text(parsedError.get("fullMessage"));
        }
        joined += " " + text(message);
        String upper = joined.toUpperCase(Locale.ROOT);
        if (upper.contains("NULLPOINTER") || upper.contains("NULL ARGUMENT") || upper.contains("NULLVALUE")) return "空指针";
        if (upper.contains("TIMEOUT") || upper.contains("TIMEDOUT") || upper.contains("SOCKETTIMEOUT")) return "超时";
        if (upper.contains("SQL") || upper.contains("JDBC") || upper.contains("MYBATIS") || upper.contains("DATAACCESS")) return "数据库";
        if (upper.contains("OUTOFMEMORY") || upper.contains("STACKOVERFLOW")) return "资源泄漏";
        if (upper.contains("CLASSCAST") || upper.contains("NUMBERFORMAT") || upper.contains("ILLEGALARGUMENT")) return "类型转换";
        // 「Connection refused」与异常类名 ConnectionRefusedException 两种写法都要认
        if (upper.contains("REFUSED") || upper.contains("UNKNOWNHOST") || upper.contains("CONNECTEXCEPTION") || upper.contains("UNREACHABLE")) return "外部依赖";
        if (upper.contains("CONCURRENTMODIFICATION") || upper.contains("DEADLOCK") || upper.contains("INTERRUPTED")) return "并发";
        if (upper.contains("NOSUCHBEAN") || upper.contains("PROPERTY") || upper.contains("CONFIG")) return "配置";
        return "其它";
    }

    static String normalizeConfidence(Object raw) {
        String c = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "high", "高" -> "high";
            case "medium", "中" -> "medium";
            default -> "low";
        };
    }

    private static String normalizePriority(Object raw) {
        String p = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        return switch (p) {
            case "P0" -> "P0";
            case "P1" -> "P1";
            default -> "P2";
        };
    }

    static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
