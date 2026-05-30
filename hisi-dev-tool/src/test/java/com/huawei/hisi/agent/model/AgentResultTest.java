package com.huawei.hisi.agent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentResult 单元测试
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("AgentResult 单元测试")
class AgentResultTest {

    @Test
    @DisplayName("测试 Builder 创建成功结果")
    void testBuilderCreatesSuccessResult() {
        AgentResult result = AgentResult.builder()
                .agentType("STACK_TRACE")
                .requestId("req-001")
                .status(AgentResult.Status.SUCCESS)
                .confidence(0.85)
                .conclusion("NullPointerException in MyClass.myMethod")
                .rootCause("Object reference is null at line 10")
                .executionTimeMs(150L)
                .build();

        assertEquals("STACK_TRACE", result.getAgentType());
        assertEquals("req-001", result.getRequestId());
        assertEquals(AgentResult.Status.SUCCESS, result.getStatus());
        assertEquals(0.85, result.getConfidence());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("测试默认值初始化")
    void testDefaultValues() {
        AgentResult result = AgentResult.builder().build();

        assertNotNull(result.getAffectedCode());
        assertTrue(result.getAffectedCode().isEmpty());
        assertNotNull(result.getFixSuggestions());
        assertTrue(result.getFixSuggestions().isEmpty());
        assertNotNull(result.getExtractedInfo());
        assertTrue(result.getExtractedInfo().isEmpty());
        assertNotNull(result.getTimestamp());
    }

    @Test
    @DisplayName("测试添加受影响代码")
    void testAddAffectedCode() {
        AgentResult result = AgentResult.builder().build();

        result.addAffectedCode("com.example.MyClass.myMethod");
        result.addAffectedCode("com.example.OtherClass.process");

        assertEquals(2, result.getAffectedCode().size());
    }

    @Test
    @DisplayName("测试添加修复建议")
    void testAddFixSuggestion() {
        AgentResult result = AgentResult.builder().build();

        result.addFixSuggestion("Check null before calling method");
        result.addFixSuggestion("Add null validation in constructor");

        assertEquals(2, result.getFixSuggestions().size());
    }

    @Test
    @DisplayName("测试添加提取信息")
    void testAddExtractedInfo() {
        AgentResult result = AgentResult.builder().build();

        result.addExtractedInfo("exceptionType", "NullPointerException");
        result.addExtractedInfo("className", "MyClass");

        assertEquals("NullPointerException", result.getExtractedInfo().get("exceptionType"));
        assertEquals("MyClass", result.getExtractedInfo().get("className"));
    }

    @Test
    @DisplayName("测试 isSuccess 方法")
    void testIsSuccess() {
        // SUCCESS 状态
        AgentResult successResult = AgentResult.builder()
                .status(AgentResult.Status.SUCCESS)
                .build();
        assertTrue(successResult.isSuccess());

        // PARTIAL 状态
        AgentResult partialResult = AgentResult.builder()
                .status(AgentResult.Status.PARTIAL)
                .build();
        assertTrue(partialResult.isSuccess());

        // FAILED 状态
        AgentResult failedResult = AgentResult.builder()
                .status(AgentResult.Status.FAILED)
                .build();
        assertFalse(failedResult.isSuccess());

        // SKIPPED 状态
        AgentResult skippedResult = AgentResult.builder()
                .status(AgentResult.Status.SKIPPED)
                .build();
        assertFalse(skippedResult.isSuccess());
    }

    @Test
    @DisplayName("测试状态枚举值")
    void testStatusEnum() {
        assertEquals(4, AgentResult.Status.values().length);
        assertEquals(AgentResult.Status.SUCCESS, AgentResult.Status.valueOf("SUCCESS"));
        assertEquals(AgentResult.Status.PARTIAL, AgentResult.Status.valueOf("PARTIAL"));
        assertEquals(AgentResult.Status.FAILED, AgentResult.Status.valueOf("FAILED"));
        assertEquals(AgentResult.Status.SKIPPED, AgentResult.Status.valueOf("SKIPPED"));
    }

    @Test
    @DisplayName("测试 streaming() 静态工厂方法")
    void testStreamingFactoryMethod() {
        Flux<String> testStream = Flux.just("Hello", "World");
        AgentResult result = AgentResult.streaming("session-abc", testStream);

        assertEquals("session-abc", result.getSessionId());
        assertTrue(result.isStreaming());
        assertNotNull(result.getStream());
    }

    @Test
    @DisplayName("测试 isStreaming() 方法")
    void testIsStreamingMethod() {
        // 非流式结果
        AgentResult nonStreamingResult = AgentResult.builder()
                .streaming(false)
                .build();
        assertFalse(nonStreamingResult.isStreaming());

        // 流式结果但没有 stream
        AgentResult streamingWithoutStream = AgentResult.builder()
                .streaming(true)
                .stream(null)
                .build();
        assertFalse(streamingWithoutStream.isStreaming());

        // 完整的流式结果
        Flux<String> testStream = Flux.just("data");
        AgentResult fullStreamingResult = AgentResult.builder()
                .streaming(true)
                .stream(testStream)
                .build();
        assertTrue(fullStreamingResult.isStreaming());

        // 使用静态工厂方法创建的流式结果
        AgentResult factoryStreamingResult = AgentResult.streaming("session-xyz", Flux.just("test"));
        assertTrue(factoryStreamingResult.isStreaming());
    }

    @Test
    @DisplayName("测试 Flux 流式输出")
    void testFluxStreamingOutput() {
        // 创建一个包含多个元素的 Flux
        Flux<String> contentStream = Flux.just(
                "第一段内容",
                "第二段内容",
                "第三段内容"
        );

        AgentResult result = AgentResult.streaming("session-test", contentStream);

        // 使用 StepVerifier 验证 Flux 流
        StepVerifier.create(result.getStream())
                .expectNext("第一段内容")
                .expectNext("第二段内容")
                .expectNext("第三段内容")
                .verifyComplete();
    }

    @Test
    @DisplayName("测试 Flux 流式输出 - 异步数据流")
    void testFluxAsyncStreamingOutput() {
        // 模拟异步生成的数据流
        Flux<String> asyncStream = Flux.interval(java.time.Duration.ofMillis(100))
                .take(3)
                .map(i -> "chunk-" + i);

        AgentResult result = AgentResult.streaming("session-async", asyncStream);

        StepVerifier.create(result.getStream())
                .expectNext("chunk-0")
                .expectNext("chunk-1")
                .expectNext("chunk-2")
                .verifyComplete();
    }
}