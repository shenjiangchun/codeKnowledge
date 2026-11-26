package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.agentflow.EvidencePack;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogHypothesisNodeV2Test {

    private final FakeLlm llm = new FakeLlm();
    private final LogHypothesisNodeV2 node = new LogHypothesisNodeV2(llm, 4000, 12000);

    @Test
    @SuppressWarnings("unchecked")
    void normalizesModelOutputAndCapsAtThreeHypotheses() {
        llm.json = Map.of("patternType", "数据库", "hypotheses", List.of(
                hypothesis("H-a", "High"), hypothesis("H-b", "medium"), hypothesis("H-c", "weird"), hypothesis("H-d", "low")));

        Map<String, Object> output = node.execute(input());

        List<Map<String, Object>> hyps = (List<Map<String, Object>>) output.get(LogHypothesisNodeV2.KEY_HYPOTHESES);
        assertEquals(3, hyps.size());
        assertEquals("H1", hyps.get(0).get("id"));
        // 置信度与优先级都被收进受控取值
        assertEquals("high", hyps.get(0).get("confidence"));
        assertEquals("medium", hyps.get(1).get("confidence"));
        assertEquals("low", hyps.get(2).get("confidence"));
        assertEquals("P2", ((Map<String, Object>) hyps.get(0).get("fix")).get("priority"));
        assertEquals("数据库", output.get("patternType"));
        assertEquals("medium", output.get("patternConfidence"));
        assertEquals("v2.0", output.get("analysisVersion"));
        assertEquals(1, llm.jsonCalls);
        assertTrue(llm.lastUserPrompt.contains("E1 | 堆栈帧"));
        // 一次假设生成只调一次 LLM，不再有单独的模式识别轮次
        assertEquals(0, llm.textCalls);
    }

    @Test
    void unavailableLlmKeepsRuleBasedPatternAndLeavesHypothesesEmpty() {
        llm.available = false;

        Map<String, Object> output = node.execute(input());

        assertNull(output.get(LogHypothesisNodeV2.KEY_HYPOTHESES));
        assertEquals("空指针", output.get("patternType"));
        assertEquals(0, llm.jsonCalls);
    }

    @Test
    void emptyOrFailedModelOutputFallsBack() {
        llm.json = Map.of("hypotheses", List.of());
        assertNull(node.execute(input()).get(LogHypothesisNodeV2.KEY_HYPOTHESES));

        llm.json = Map.of("patternType", "其它");
        assertNull(node.execute(input()).get(LogHypothesisNodeV2.KEY_HYPOTHESES));

        FakeLlm failing = new FakeLlm();
        failing.failure = new RuntimeException("timeout");
        LogHypothesisNodeV2 failingNode = new LogHypothesisNodeV2(failing, 4000, 12000);
        assertNull(failingNode.execute(input()).get(LogHypothesisNodeV2.KEY_HYPOTHESES));
        // LLM 失败不影响规则判定的模式类型
        assertEquals("空指针", failingNode.execute(input()).get("patternType"));
    }

    @Test
    void patternTypeIsInferredFromExceptionName() {
        assertEquals("超时", LogHypothesisNodeV2.inferPatternType(
                Map.of("rootCauseException", "java.net.SocketTimeoutException"), "read timed out"));
        assertEquals("数据库", LogHypothesisNodeV2.inferPatternType(
                Map.of("rootCauseException", "org.springframework.jdbc.BadSqlGrammarException"), null));
        assertEquals("外部依赖", LogHypothesisNodeV2.inferPatternType(Map.of(), "Connection refused"));
        assertEquals("其它", LogHypothesisNodeV2.inferPatternType(null, null));
    }

    private static Map<String, Object> hypothesis(String title, String confidence) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("suggestion", "在入口处校验库存");
        fix.put("affectedCode", "com.svc.order.OrderService.deduct");
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("title", title);
        h.put("confidence", confidence);
        h.put("rootCause", "库存为空时未校验 [[E1]]");
        h.put("causalChain", List.of("请求进入 [[E1]]", "扣减时库存为空 [[E1]]"));
        h.put("rootCauseFrame", Map.of("className", "com.svc.order.OrderService", "methodName", "deduct"));
        h.put("fix", fix);
        return h;
    }

    private static Map<String, Object> input() {
        EvidencePack pack = new EvidencePack();
        pack.add("frame", "com.svc.order.OrderService.deduct() 第 88 行（OrderService.java）[根因帧]",
                null, "com.svc.order.OrderService", "deduct", "OrderService.java", true);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(LogKgEvidenceNodeV2.KEY_EVIDENCE_PACK, pack);
        input.put("message", "java.lang.NullPointerException");
        return input;
    }

    /** 可编程的假 LLM：按预设返回 JSON 或文本，或直接抛错。 */
    static final class FakeLlm implements AgentLlmClient {
        boolean available = true;
        Map<String, Object> json;
        String text;
        RuntimeException failure;
        int jsonCalls;
        int textCalls;
        String lastUserPrompt;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String completeText(String systemPrompt, String userPrompt, int maxTokens) {
            textCalls++;
            lastUserPrompt = userPrompt;
            if (failure != null) {
                throw failure;
            }
            return text;
        }

        @Override
        public Map<String, Object> completeJson(String systemPrompt, String userPrompt, int maxTokens) {
            jsonCalls++;
            lastUserPrompt = userPrompt;
            if (failure != null) {
                throw failure;
            }
            return json;
        }
    }
}
