package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.agentflow.Citations;
import com.huawei.hisi.agentflow.EvidencePack;
import com.huawei.hisi.loganalysis.v2.LogHypothesisNodeV2Test.FakeLlm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogVerifySynthesisNodesTest {

    private static final String THROW_SITE = "com.svc.order.OrderService.deduct";
    private static final String BUSINESS = "com.svc.web.OrderController.submit";
    private static final String UPSTREAM_IN_GRAPH = "com.svc.order.OrderFacade.create";

    // ─────────────────────────── 验证节点 ───────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void hypothesisWithoutAnyCitationIsDropped() {
        Map<String, Object> output = verifyNode(false, null).execute(input(List.of(
                hypothesis("H1", "high", "没有任何引用的结论", THROW_SITE))));

        Map<String, Object> verdict = verdictOf(output, 0);
        assertEquals(false, verdict.get("kept"));
        assertEquals("medium", verdict.get("confidence"));
        assertTrue(((List<String>) verdict.get("reasons")).contains("未引用任何证据"));
        assertTrue(((List<?>) output.get(LogVerifyNodeV2.KEY_SELECTED)).isEmpty());
        assertEquals("low", output.get("analysisConfidence"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inventedCitationForcesLowConfidenceAndDropsHypothesis() {
        Map<String, Object> output = verifyNode(false, null).execute(input(List.of(
                hypothesis("H1", "high", "结论引用了不存在的证据 [[E9]]", THROW_SITE))));

        Map<String, Object> verdict = verdictOf(output, 0);
        assertEquals(List.of("E9"), verdict.get("unknownIds"));
        assertEquals("low", verdict.get("confidence"));
        assertEquals(false, verdict.get("kept"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rootCauseUpstreamInCallGraphIsVerifiedByGraph() {
        Map<String, Object> output = verifyNode(false, null).execute(input(List.of(
                hypothesis("H1", "high", "上游门面传入空库存 [[E3]]", UPSTREAM_IN_GRAPH))));

        Map<String, Object> verdict = verdictOf(output, 0);
        assertEquals(LogVerifyNodeV2.CHAIN_VERIFIED, verdict.get("chainStatus"));
        assertEquals(true, verdict.get("kept"));
        // 因果链被图谱证实，置信度不应被降
        assertEquals("high", verdict.get("confidence"));
        assertEquals("high", output.get("analysisConfidence"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rootCauseOnCallerSideStackFrameIsVerifiedByStack() {
        Map<String, Object> output = verifyNode(false, null).execute(input(List.of(
                hypothesis("H1", "high", "控制器没有校验入参 [[E2]]", BUSINESS))));

        assertEquals(LogVerifyNodeV2.CHAIN_STACK_VERIFIED, verdictOf(output, 0).get("chainStatus"));
        assertEquals("high", verdictOf(output, 0).get("confidence"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rootCauseAtThrowSiteIsFlaggedButNotPenalized() {
        Map<String, Object> output = verifyNode(false, null).execute(input(List.of(
                hypothesis("H1", "high", "抛出点就是问题所在 [[E1]]", THROW_SITE))));

        Map<String, Object> verdict = verdictOf(output, 0);
        assertEquals(LogVerifyNodeV2.CHAIN_AT_THROW_SITE, verdict.get("chainStatus"));
        assertEquals("high", verdict.get("confidence"));
        assertEquals(true, verdict.get("kept"));
        assertTrue(((List<String>) verdict.get("reasons")).get(0).contains("抛出处"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rootCauseOutsideEveryUpstreamChainIsUnverifiedAndDowngraded() {
        Map<String, Object> output = verifyNode(false, null).execute(input(List.of(
                hypothesis("H1", "high", "指向一个不在链上的方法 [[E4]]", "com.svc.order.StockChecker.check"))));

        Map<String, Object> verdict = verdictOf(output, 0);
        assertEquals(LogVerifyNodeV2.CHAIN_UNVERIFIED, verdict.get("chainStatus"));
        assertEquals("medium", verdict.get("confidence"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluatorCanRejectAHypothesisThatPassedDeterministicChecks() {
        FakeLlm llm = new FakeLlm();
        llm.json = Map.of("acceptedIds", List.of("H2"), "issues", List.of(
                Map.of("id", "H1", "type", "symptom_as_cause", "detail", "把抛出点当成了根因")));

        Map<String, Object> output = verifyNode(true, llm).execute(input(List.of(
                hypothesis("H1", "high", "抛出点就是问题所在 [[E1]]", THROW_SITE),
                hypothesis("H2", "medium", "上游门面传入空库存 [[E3]]", UPSTREAM_IN_GRAPH))));

        List<Map<String, Object>> selected = (List<Map<String, Object>>) output.get(LogVerifyNodeV2.KEY_SELECTED);
        assertEquals(List.of("H2"), selected.stream().map(h -> h.get("id")).toList());
        assertEquals(1, ((List<?>) output.get(LogVerifyNodeV2.KEY_EVALUATOR_ISSUES)).size());
        Map<String, Object> overall = (Map<String, Object>) ((Map<String, Object>) output.get(LogVerifyNodeV2.KEY_VERIFICATION)).get("overall");
        assertEquals(true, overall.get("evaluated"));
        assertEquals(1, llm.jsonCalls);
    }

    @Test
    void evaluatorFailureKeepsDeterministicResult() {
        FakeLlm llm = new FakeLlm();
        llm.failure = new RuntimeException("rate limited");

        Map<String, Object> output = verifyNode(true, llm).execute(input(List.of(
                hypothesis("H1", "high", "上游门面传入空库存 [[E3]]", UPSTREAM_IN_GRAPH))));

        Map<String, Object> overall = (Map<String, Object>) ((Map<String, Object>) output.get(LogVerifyNodeV2.KEY_VERIFICATION)).get("overall");
        assertEquals(false, overall.get("evaluated"));
        assertEquals(1, ((List<?>) output.get(LogVerifyNodeV2.KEY_SELECTED)).size());
    }

    @Test
    void keptHypothesesAreOrderedByConfidence() {
        Map<String, Object> output = verifyNode(false, null).execute(input(List.of(
                hypothesis("H1", "low", "低置信 [[E1]]", THROW_SITE),
                hypothesis("H2", "high", "高置信 [[E3]]", UPSTREAM_IN_GRAPH))));

        assertEquals(List.of("H2", "H1"), ((List<Map<String, Object>>) output.get(LogVerifyNodeV2.KEY_SELECTED)).stream()
                .map(h -> h.get("id")).toList());
    }

    // ─────────────────────────── 综合节点 ───────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void noHypothesisGivesDeterministicReportWithoutLlm() {
        FakeLlm llm = new FakeLlm();
        llm.available = false;
        Map<String, Object> input = verifiedInput(List.of());
        input.put("analysisConfidence", "low");
        input.put("patternType", "空指针");

        Map<String, Object> output = new LogSynthesisNodeV2(llm, 4000).execute(input);

        String markdown = String.valueOf(output.get("rootCauseAnalysis"));
        assertTrue(markdown.contains("LLM 未参与本次分析"));
        assertTrue(markdown.contains("谁调用了 " + THROW_SITE));
        assertTrue(markdown.contains("## 证据清单"));
        assertTrue(markdown.contains("| E1 | 堆栈帧 |"));
        assertEquals("low", output.get("analysisConfidence"));
        List<Map<String, Object>> fixes = (List<Map<String, Object>>) output.get("fixSuggestions");
        assertEquals(1, fixes.size());
        assertTrue(String.valueOf(fixes.get(0).get("suggestion")).contains("空值判断"));
        assertEquals(THROW_SITE, fixes.get(0).get("affectedCode"));
        assertEquals(0, llm.textCalls + llm.jsonCalls);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inventedCitationInFinalTextIsMarkedAndConfidenceLowered() {
        FakeLlm llm = new FakeLlm();
        llm.text = "## 根因结论\n真实依据 [[E1]]，编造依据 [[E9]]。";
        Map<String, Object> input = verifiedInput(List.of(
                selectedHypothesis("H1", "high", "上游门面传入空库存 [[E3]]", UPSTREAM_IN_GRAPH, LogVerifyNodeV2.CHAIN_VERIFIED)));

        Map<String, Object> output = new LogSynthesisNodeV2(llm, 4000).execute(input);

        String markdown = String.valueOf(output.get("rootCauseAnalysis"));
        // 成文里引用写成 [E1]，与证据清单的编号一致；页面和导出的 MD 里不会再出现 [[E1]] 这种原始标记
        assertTrue(markdown.contains("真实依据 [E1]，编造依据 " + Citations.UNVERIFIED_DISPLAY));
        assertFalse(markdown.contains("[["));
        assertTrue(markdown.contains("相关表述未经证实"));
        assertEquals("low", output.get("analysisConfidence"));
        Map<String, Object> grounding = (Map<String, Object>) output.get(LogSynthesisNodeV2.KEY_MARKDOWN_GROUNDING);
        assertEquals(List.of("E9"), grounding.get("unknownIds"));
    }

    @Test
    void cleanTextKeepsConfidenceAndAppendsOnlyCitedEvidence() {
        FakeLlm llm = new FakeLlm();
        llm.text = "## 根因结论\n库存为空 [[E1]]（已由调用图证实）";
        Map<String, Object> input = verifiedInput(List.of(
                selectedHypothesis("H1", "high", "上游门面传入空库存 [[E3]]", UPSTREAM_IN_GRAPH, LogVerifyNodeV2.CHAIN_VERIFIED)));

        Map<String, Object> output = new LogSynthesisNodeV2(llm, 4000).execute(input);

        assertEquals("high", output.get("analysisConfidence"));
        String markdown = String.valueOf(output.get("rootCauseAnalysis"));
        assertTrue(markdown.contains("| E1 |"));
        assertFalse(markdown.contains("| E2 |"));
        assertFalse(markdown.contains("| E3 |"));
        // 修复建议沿用 v1 ReportNode 消费的键名
        Map<String, Object> fix = ((List<Map<String, Object>>) output.get("fixSuggestions")).get(0);
        assertEquals(List.of("suggestion", "priority", "affectedCode", "expectedEffect"), new ArrayList<>(fix.keySet()));
    }

    @Test
    void finalTextWithoutCitationIsFlaggedAndLowered() {
        FakeLlm llm = new FakeLlm();
        llm.text = "## 根因结论\n一句没有任何证据编号的结论。";
        Map<String, Object> input = verifiedInput(List.of(
                selectedHypothesis("H1", "high", "上游门面传入空库存 [[E3]]", UPSTREAM_IN_GRAPH, LogVerifyNodeV2.CHAIN_VERIFIED)));

        Map<String, Object> output = new LogSynthesisNodeV2(llm, 4000).execute(input);

        assertTrue(String.valueOf(output.get("rootCauseAnalysis")).startsWith("> 注意：以下分析未引用任何图谱证据"));
        assertEquals("low", output.get("analysisConfidence"));
    }

    @Test
    void textGenerationFailureFallsBackToStructuredTemplate() {
        FakeLlm llm = new FakeLlm();
        llm.failure = new RuntimeException("boom");
        Map<String, Object> input = verifiedInput(List.of(
                selectedHypothesis("H1", "high", "上游门面传入空库存 [[E3]]", UPSTREAM_IN_GRAPH, LogVerifyNodeV2.CHAIN_VERIFIED)));

        Map<String, Object> output = new LogSynthesisNodeV2(llm, 4000).execute(input);

        String markdown = String.valueOf(output.get("rootCauseAnalysis"));
        assertTrue(markdown.contains("上游门面传入空库存"));
        assertTrue(markdown.contains("已由调用图证实"));
        assertTrue(markdown.contains("## 修复建议"));
        assertEquals("high", output.get("analysisConfidence"));
    }

    // ─────────────────────────── 测试夹具 ───────────────────────────

    private static LogVerifyNodeV2 verifyNode(boolean evaluatorEnabled, FakeLlm llm) {
        return new LogVerifyNodeV2(llm == null ? new FakeLlm() : llm, evaluatorEnabled, 1500);
    }

    /** E1 抛出点帧、E2 业务帧、E3 抛出点的上游调用链、E4 一个不在链上的图谱方法。 */
    private static EvidencePack evidencePack() {
        EvidencePack pack = new EvidencePack();
        pack.add("frame", "OrderService.deduct() 第 88 行 [根因帧]", null, "com.svc.order.OrderService", "deduct", null, true);
        pack.add("frame", "OrderController.submit() 第 40 行 [业务帧]", null, "com.svc.web.OrderController", "submit", null, true);
        pack.add("upstream", "谁调用了 " + THROW_SITE + "：OrderFacade.create ← OrderController.submit",
                null, "com.svc.order.OrderService", "deduct", null, true);
        pack.add("method", "精确命中 StockChecker.check()", "svc:com.svc.order.StockChecker.check.9a",
                "com.svc.order.StockChecker", "check", null, true);
        return pack;
    }

    private static Map<String, Object> input(List<Map<String, Object>> hypotheses) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK, evidencePack());
        input.put(LogKgEvidenceNodeV2.KEY_UPSTREAM, Map.of(
                "com.svc.order.OrderService#deduct", List.of("com.svc.order.OrderFacade.create", "com.svc.web.OrderController.submit")));
        input.put("rootCauseFrames", List.of(frame("com.svc.order.OrderService", "deduct")));
        input.put("businessFrames", List.of(frame("com.svc.web.OrderController", "submit")));
        input.put(LogHypothesisNodeV2.KEY_HYPOTHESES, hypotheses);
        return input;
    }

    /** 先跑一遍验证节点，拿到真实的验证结果，再交给综合节点。 */
    private static Map<String, Object> verifiedInput(List<Map<String, Object>> rawHypotheses) {
        return verifyNode(false, null).execute(input(rawHypotheses));
    }

    private static Map<String, Object> selectedHypothesis(String id, String confidence, String text,
                                                          String rootCauseRef, String chainStatus) {
        Map<String, Object> h = hypothesis(id, confidence, text, rootCauseRef);
        h.put("chainStatus", chainStatus);
        return h;
    }

    private static Map<String, Object> hypothesis(String id, String confidence, String text, String rootCauseRef) {
        int dot = rootCauseRef.lastIndexOf('.');
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("suggestion", "在 OrderFacade.create 入口校验库存");
        fix.put("priority", "P1");
        fix.put("affectedCode", rootCauseRef);
        fix.put("expectedEffect", "空库存时直接拒绝请求");
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("title", "上游未校验库存 " + text);
        h.put("confidence", confidence);
        h.put("symptom", "扣减库存时抛出空指针");
        h.put("rootCause", text);
        h.put("causalChain", List.of(text));
        h.put("rootCauseFrame", Map.of("className", rootCauseRef.substring(0, dot), "methodName", rootCauseRef.substring(dot + 1)));
        h.put("fix", fix);
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verdictOf(Map<String, Object> output, int index) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) output.get(LogVerifyNodeV2.KEY_VERIFICATION)).get("hypotheses")).get(index);
    }

    private static Map<String, Object> frame(String className, String methodName) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("className", className);
        f.put("methodName", methodName);
        return f;
    }
}
