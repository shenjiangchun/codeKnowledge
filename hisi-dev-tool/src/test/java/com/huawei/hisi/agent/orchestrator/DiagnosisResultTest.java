package com.huawei.hisi.agent.orchestrator;

import com.huawei.hisi.agent.model.AgentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiagnosisResult 单元测试
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("DiagnosisResult 单元测试")
class DiagnosisResultTest {

    @Test
    @DisplayName("测试 Builder 创建完整结果")
    void testBuilderCreatesFullResult() {
        DiagnosisResult result = DiagnosisResult.builder()
                .requestId("req-001")
                .primaryConclusion("NullPointerException in MyClass.myMethod")
                .primaryRootCause("Object reference is null at line 10")
                .primaryConfidence(0.85)
                .primaryAgentType("STACK_TRACE")
                .overallConfidence(0.80)
                .successCount(2)
                .failedCount(1)
                .totalTimeMs(500L)
                .build();

        assertEquals("req-001", result.getRequestId());
        assertEquals("NullPointerException in MyClass.myMethod", result.getPrimaryConclusion());
        assertEquals(0.85, result.getPrimaryConfidence());
        assertEquals("STACK_TRACE", result.getPrimaryAgentType());
        assertEquals(2, result.getSuccessCount());
        assertEquals(1, result.getFailedCount());
    }

    @Test
    @DisplayName("测试默认值初始化")
    void testDefaultValues() {
        DiagnosisResult result = DiagnosisResult.builder().build();

        assertNotNull(result.getCombinedAffectedCode());
        assertTrue(result.getCombinedAffectedCode().isEmpty());
        assertNotNull(result.getCombinedFixSuggestions());
        assertTrue(result.getCombinedFixSuggestions().isEmpty());
        assertNotNull(result.getAgentResults());
        assertTrue(result.getAgentResults().isEmpty());
        assertNotNull(result.getTimestamp());
    }

    @Test
    @DisplayName("测试 empty 静态方法")
    void testEmptyStaticMethod() {
        DiagnosisResult result = DiagnosisResult.empty("req-001", "没有可用的诊断 Agent");

        assertEquals("req-001", result.getRequestId());
        assertEquals("没有可用的诊断 Agent", result.getPrimaryConclusion());
        assertEquals(0.0, result.getPrimaryConfidence());
        assertEquals(0, result.getSuccessCount());
        assertEquals(0, result.getFailedCount());
    }

    @Test
    @DisplayName("测试 hasValidConclusion 方法")
    void testHasValidConclusion() {
        // 有效结论
        DiagnosisResult validResult = DiagnosisResult.builder()
                .primaryConclusion("Valid conclusion")
                .primaryConfidence(0.5)
                .build();
        assertTrue(validResult.hasValidConclusion());

        // 无效结论（置信度过低）
        DiagnosisResult lowConfidenceResult = DiagnosisResult.builder()
                .primaryConclusion("Low confidence")
                .primaryConfidence(0.2)
                .build();
        assertFalse(lowConfidenceResult.hasValidConclusion());

        // 无结论
        DiagnosisResult noConclusionResult = DiagnosisResult.builder()
                .primaryConfidence(0.5)
                .build();
        assertFalse(noConclusionResult.hasValidConclusion());
    }

    @Test
    @DisplayName("测试 getSuccessfulResults 方法")
    void testGetSuccessfulResults() {
        AgentResult success1 = AgentResult.builder()
                .agentType("AGENT_1")
                .status(AgentResult.Status.SUCCESS)
                .build();

        AgentResult success2 = AgentResult.builder()
                .agentType("AGENT_2")
                .status(AgentResult.Status.PARTIAL)
                .build();

        AgentResult failed = AgentResult.builder()
                .agentType("AGENT_3")
                .status(AgentResult.Status.FAILED)
                .build();

        DiagnosisResult result = DiagnosisResult.builder()
                .agentResults(List.of(success1, success2, failed))
                .build();

        List<AgentResult> successful = result.getSuccessfulResults();
        assertEquals(2, successful.size());
        assertTrue(successful.stream().allMatch(AgentResult::isSuccess));
    }

    @Test
    @DisplayName("测试 getFailedResults 方法")
    void testGetFailedResults() {
        AgentResult success = AgentResult.builder()
                .agentType("AGENT_1")
                .status(AgentResult.Status.SUCCESS)
                .build();

        AgentResult failed1 = AgentResult.builder()
                .agentType("AGENT_2")
                .status(AgentResult.Status.FAILED)
                .build();

        AgentResult failed2 = AgentResult.builder()
                .agentType("AGENT_3")
                .status(AgentResult.Status.SKIPPED)
                .build();

        DiagnosisResult result = DiagnosisResult.builder()
                .agentResults(List.of(success, failed1, failed2))
                .build();

        List<AgentResult> failed = result.getFailedResults();
        assertEquals(2, failed.size());
        assertTrue(failed.stream().noneMatch(AgentResult::isSuccess));
    }
}