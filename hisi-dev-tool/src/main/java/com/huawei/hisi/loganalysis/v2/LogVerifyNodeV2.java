package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.agentflow.EvidencePack;
import com.huawei.hisi.agentflow.GroundingResult;
import com.huawei.hisi.agentflow.GroundingVerifier;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日志分析 v2 的验证节点：在下结论之前检查每条假设是否站得住。
 *
 * <p>确定性检查（不调用模型，结果可复现）：
 * <ul>
 *   <li>引用完整性：假设里引用的证据编号是否都存在于证据包，是否存在编造的引用；</li>
 *   <li>根因落点：假设指认的根因方法是否精确存在于图谱；</li>
 *   <li>因果链连通性：指认的根因是否位于某个堆栈帧的上游调用链上，即图谱能否证实「根因 → 症状」这条路。</li>
 * </ul>
 *
 * <p>可选评审（{@code loganalysis.v2.evaluator.enabled=true} 时启用）：由独立的一次 LLM 调用充当评审，
 * 专查「幻觉解读、症状当根因、缺少交叉验证」三类缺陷。评审失败不影响主流程。
 *
 * <p>输出键：{@code verificationV2}、{@code selectedHypothesesV2}、{@code evaluatorIssuesV2}、
 * {@code analysisConfidence}、{@code patternConfidence}。
 */
@Slf4j
@Component
public class LogVerifyNodeV2 implements LogAnalysisDagNode {

    static final String KEY_SELECTED = "selectedHypothesesV2";
    static final String KEY_VERIFICATION = "verificationV2";
    static final String KEY_EVALUATOR_ISSUES = "evaluatorIssuesV2";

    /** 因果链在图谱中的核实状态。 */
    static final String CHAIN_VERIFIED = "verified";
    static final String CHAIN_STACK_VERIFIED = "stack_verified";
    static final String CHAIN_UNVERIFIED = "unverified";
    static final String CHAIN_UNKNOWN = "unknown";
    static final String CHAIN_AT_THROW_SITE = "at_throw_site";

    /** 是否属于「根因在上游」这一类的已核实状态。 */
    static boolean chainProven(String chainStatus) {
        return CHAIN_VERIFIED.equals(chainStatus) || CHAIN_STACK_VERIFIED.equals(chainStatus);
    }

    private static final String EVALUATOR_SYSTEM_PROMPT = """
            你是独立的故障诊断评审员，没有参与前面的分析。你的职责是找出问题，不是重写结论。

            对照「证据表」逐条检查每个假设，重点查三类缺陷：
            1. 幻觉解读：结论与证据内容不符，或证据里根本没有提到所依据的类、方法、配置。
            2. 症状当根因：把异常抛出点直接当成根因，却没有说明上游为什么会产生这个结果。
            3. 缺少交叉验证：结论只有一个来源支撑，存在同样说得通的其它解释而没有排除。

            只输出一个 JSON 对象，不要输出 markdown 代码块。格式：
            {
              "acceptedIds": ["通过评审的假设 id，如 H1"],
              "issues": [
                {"id": "H2", "type": "hallucination 或 symptom_as_cause 或 no_cross_validation 或 other", "detail": "具体问题"}
              ],
              "notes": "整体评价，一句话"
            }
            """;

    private final AgentLlmClient llm;
    private final boolean evaluatorEnabled;
    private final int evaluatorMaxTokens;

    public LogVerifyNodeV2(AgentLlmClient llm,
                           @Value("${loganalysis.v2.evaluator.enabled:false}") boolean evaluatorEnabled,
                           @Value("${loganalysis.v2.evaluator-max-tokens:1500}") int evaluatorMaxTokens) {
        this.llm = llm;
        this.evaluatorEnabled = evaluatorEnabled;
        this.evaluatorMaxTokens = evaluatorMaxTokens;
    }

    @Override
    public String name() {
        return "VerifyV2";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[VerifyV2] 开始验证假设");

        Map<String, Object> output = new LinkedHashMap<>(input);
        EvidencePack pack = LogHypothesisNodeV2.packOf(input);
        List<Map<String, Object>> hypotheses =
                input.get(LogHypothesisNodeV2.KEY_HYPOTHESES) instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        Map<String, List<String>> upstream = input.get(LogKgEvidenceNodeV2.KEY_UPSTREAM) instanceof Map<?, ?> m
                ? (Map<String, List<String>>) m : Map.of();

        List<Map<String, Object>> verdicts = new ArrayList<>();
        Set<String> throwSiteRefs = new LinkedHashSet<>();
        Set<String> callerSideRefs = new LinkedHashSet<>();
        if (input.get(LogKgEvidenceNodeV2.KEY_THROW_SITE) instanceof String throwSite && !throwSite.isBlank()) {
            throwSiteRefs.add(throwSite);
            if (input.get(LogKgEvidenceNodeV2.KEY_STACK_CALLERS) instanceof List<?> callers) {
                callers.forEach(c -> { if (c != null) callerSideRefs.add(String.valueOf(c)); });
            }
        } else {
            // 没有取证节点的结论时（例如单独运行本节点），退回 v1 ParseNode 的分组
            throwSiteRefs.addAll(frameRefs(input.get("rootCauseFrames")));
            callerSideRefs.addAll(frameRefs(input.get("businessFrames")));
        }
        for (Map<String, Object> h : hypotheses) {
            verdicts.add(verifyOne(h, pack, upstream, throwSiteRefs, callerSideRefs));
        }

        List<String> evaluatorIssues = new ArrayList<>();
        boolean evaluatorRan = false;
        if (evaluatorEnabled && !verdicts.isEmpty() && llm.isAvailable()) {
            List<String> result = runEvaluator(pack, hypotheses, verdicts);
            if (result != null) {
                evaluatorIssues.addAll(result);
                evaluatorRan = true;
            }
        }

        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> v : verdicts) {
            if (Boolean.TRUE.equals(v.get("kept"))) {
                Map<String, Object> h = (Map<String, Object>) v.get("hypothesis");
                h.put("confidence", v.get("confidence"));
                h.put("chainStatus", v.get("chainStatus"));
                kept.add(h);
            }
        }
        kept.sort(Comparator.comparingInt((Map<String, Object> h) -> rank(String.valueOf(h.get("confidence")))).reversed());

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("total", verdicts.size());
        overall.put("kept", kept.size());
        overall.put("chainVerifiedCount", verdicts.stream()
                .filter(v -> chainProven(String.valueOf(v.get("chainStatus")))).count());
        overall.put("evaluated", evaluatorRan);
        output.put(KEY_VERIFICATION, Map.of("hypotheses", verdicts, "overall", overall));
        output.put(KEY_EVALUATOR_ISSUES, evaluatorIssues);
        output.put(KEY_SELECTED, kept);

        String confidence = kept.isEmpty() ? "low" : String.valueOf(kept.get(0).get("confidence"));
        output.put("analysisConfidence", confidence);
        // 被保留的假设不足一半时，模式判断同样要打折扣
        if (!verdicts.isEmpty() && kept.size() * 2 < verdicts.size()) {
            output.put("patternConfidence", "low");
        }
        log.info("[VerifyV2] 验证完成: total={}, kept={}, chainVerified={}, confidence={}",
                verdicts.size(), kept.size(), overall.get("chainVerifiedCount"), confidence);
        return output;
    }

    /** 对单条假设做确定性检查。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> verifyOne(Map<String, Object> h, EvidencePack pack,
                                         Map<String, List<String>> upstream,
                                         Set<String> throwSiteRefs, Set<String> callerSideRefs) {
        Map<String, Object> frame = h.get("rootCauseFrame") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> fix = h.get("fix") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String className = LogHypothesisNodeV2.text(frame.get("className"));
        String methodName = LogHypothesisNodeV2.text(frame.get("methodName"));
        String ref = className + "." + methodName;

        List<String> texts = new ArrayList<>();
        texts.add(LogHypothesisNodeV2.text(h.get("title")));
        texts.add(LogHypothesisNodeV2.text(h.get("rootCause")));
        texts.add(LogHypothesisNodeV2.text(h.get("symptom")));
        texts.add(LogHypothesisNodeV2.text(fix.get("suggestion")));
        if (h.get("causalChain") instanceof List<?> chain) {
            chain.forEach(c -> texts.add(String.valueOf(c)));
        }
        GroundingResult grounding = GroundingVerifier.verify(pack, texts.toArray(new String[0]));

        boolean inUpstream = upstream.values().stream().flatMap(List::stream).anyMatch(ref::equals);
        // 上游链里的方法本身就是从图谱查出来的，同样算作「在图谱中」
        boolean rootCauseInKg = pack.hasGraphMethod(className, methodName) || inUpstream;
        String chainStatus = chainStatus(ref, rootCauseInKg, inUpstream, throwSiteRefs, callerSideRefs);

        String confidence = LogHypothesisNodeV2.normalizeConfidence(h.get("confidence"));
        List<String> reasons = new ArrayList<>();
        if (!grounding.hasCitation()) {
            confidence = downgrade(confidence);
            reasons.add("未引用任何证据");
        }
        if (!grounding.unknownIds().isEmpty()) {
            confidence = "low";
            reasons.add("引用了不存在的证据 " + grounding.unknownIds());
        }
        if (CHAIN_UNVERIFIED.equals(chainStatus)) {
            confidence = downgrade(confidence);
            reasons.add("指认的根因不在任何堆栈帧的上游调用链上");
        }
        if (CHAIN_AT_THROW_SITE.equals(chainStatus)) {
            reasons.add("根因落在异常抛出处，未见更上游的依据");
        }
        boolean kept = grounding.hasCitation() && grounding.unknownIds().isEmpty();

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", h.get("id"));
        v.put("title", h.get("title"));
        v.put("confidence", confidence);
        v.put("citedIds", grounding.citedIds());
        v.put("unknownIds", grounding.unknownIds());
        v.put("rootCauseInKg", rootCauseInKg);
        v.put("chainStatus", chainStatus);
        v.put("kept", kept);
        v.put("reasons", reasons);
        v.put("hypothesis", h);
        return v;
    }

    /**
     * 判断「根因 → 症状」这条路能否被证实，按依据从强到弱：
     * <ol>
     *   <li>根因是堆栈里偏上游的帧（业务帧）——堆栈本身就是这次调用的因果链，记为已证实；</li>
     *   <li>根因出现在某个帧的调用图上游链里——图谱证实了可达性，记为已证实；</li>
     *   <li>根因就是抛出异常的那一帧——只是落点，没有更上游的依据，记为 {@link #CHAIN_AT_THROW_SITE}；</li>
     *   <li>有图谱依据但都对不上——记为未证实；</li>
     *   <li>根因不在图谱中——记为未知。</li>
     * </ol>
     */
    static String chainStatus(String rootCauseRef, boolean rootCauseInKg, boolean inUpstream,
                              Set<String> throwSiteRefs, Set<String> callerSideRefs) {
        if (callerSideRefs.contains(rootCauseRef)) {
            return CHAIN_STACK_VERIFIED;
        }
        if (inUpstream) {
            return CHAIN_VERIFIED;
        }
        if (throwSiteRefs.contains(rootCauseRef)) {
            return CHAIN_AT_THROW_SITE;
        }
        return rootCauseInKg ? CHAIN_UNVERIFIED : CHAIN_UNKNOWN;
    }

    /** 一组堆栈帧的「类名.方法名」集合。 */
    @SuppressWarnings("unchecked")
    private static Set<String> frameRefs(Object frames) {
        Set<String> out = new LinkedHashSet<>();
        if (frames instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m
                        && m.get("className") != null && m.get("methodName") != null) {
                    out.add(m.get("className") + "." + m.get("methodName"));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> runEvaluator(EvidencePack pack, List<Map<String, Object>> hypotheses,
                                      List<Map<String, Object>> verdicts) {
        try {
            StringBuilder user = new StringBuilder("## 证据表（编号 | 类型 | 说明）\n")
                    .append(pack.render(12000)).append("\n## 待评审的假设\n");
            for (Map<String, Object> h : hypotheses) {
                user.append("- ").append(h.get("id")).append("：").append(h.get("title")).append('\n');
                user.append("  根因：").append(h.get("rootCause")).append('\n');
                user.append("  因果链：").append(h.get("causalChain")).append('\n');
                user.append("  修复：").append(((Map<?, ?>) h.get("fix")).get("suggestion")).append('\n');
            }
            Map<String, Object> json = llm.completeJson(EVALUATOR_SYSTEM_PROMPT, user.toString(), evaluatorMaxTokens);

            List<String> accepted = new ArrayList<>();
            if (json.get("acceptedIds") instanceof List<?> ids) {
                ids.forEach(id -> accepted.add(String.valueOf(id)));
            }
            List<String> issues = new ArrayList<>();
            if (json.get("issues") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> issue) {
                        issues.add(issue.get("id") + " [" + issue.get("type") + "] " + issue.get("detail"));
                        boolean reject = verdicts.stream().anyMatch(v ->
                                v.get("id").equals(issue.get("id")) && Boolean.TRUE.equals(v.get("kept")))
                                && !accepted.contains(String.valueOf(issue.get("id")));
                        if (reject) {
                            verdicts.stream().filter(v -> v.get("id").equals(issue.get("id"))).forEach(v -> {
                                v.put("kept", false);
                                ((List<String>) v.get("reasons")).add("评审未通过：" + issue.get("detail"));
                            });
                        }
                    }
                }
            }
            log.info("[VerifyV2] 评审完成: accepted={}, issues={}", accepted, issues.size());
            return issues;
        } catch (Exception e) {
            log.warn("[VerifyV2] 评审调用失败，沿用确定性验证结果: {}", e.getMessage());
            return null;
        }
    }

    private static String downgrade(String confidence) {
        return switch (confidence) {
            case "high" -> "medium";
            case "medium" -> "low";
            default -> "low";
        };
    }

    static int rank(String confidence) {
        return switch (confidence) {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }
}
