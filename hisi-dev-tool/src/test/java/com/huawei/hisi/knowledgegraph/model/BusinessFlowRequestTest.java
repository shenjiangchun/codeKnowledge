package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BusinessFlowRequest 单元测试
 */
class BusinessFlowRequestTest {

    @Test
    @DisplayName("创建请求 - Builder 模式")
    void testBuilder() {
        // Given & When
        BusinessFlowRequest request = BusinessFlowRequest.builder()
                .callChainData("{\"entryPoint\": \"GET /api/test\"}")
                .projectPath("/test/project")
                .entryPointKey("GET /api/test")
                .maxDepth(5)
                .includeDescription(true)
                .build();

        // Then
        assertNotNull(request);
        assertEquals("{\"entryPoint\": \"GET /api/test\"}", request.getCallChainData());
        assertEquals("/test/project", request.getProjectPath());
        assertEquals("GET /api/test", request.getEntryPointKey());
        assertEquals(5, request.getMaxDepth());
        assertTrue(request.getIncludeDescription());
    }

    @Test
    @DisplayName("创建请求 - 默认值")
    void testDefaultValues() {
        // Given & When
        BusinessFlowRequest request = BusinessFlowRequest.builder()
                .callChainData("test")
                .build();

        // Then
        assertNotNull(request);
        assertTrue(request.getIncludeDescription());
    }

    @Test
    @DisplayName("创建请求 - 无参构造函数")
    void testNoArgsConstructor() {
        // Given & When
        BusinessFlowRequest request = new BusinessFlowRequest();
        request.setCallChainData("test data");

        // Then
        assertNotNull(request);
        assertEquals("test data", request.getCallChainData());
    }

    @Test
    @DisplayName("创建请求 - 全参构造函数")
    void testAllArgsConstructor() {
        // Given & When
        BusinessFlowRequest request = new BusinessFlowRequest(
                "callChainData",
                "/project/path",
                "entryPoint",
                10,
                false
        );

        // Then
        assertEquals("callChainData", request.getCallChainData());
        assertEquals("/project/path", request.getProjectPath());
        assertEquals("entryPoint", request.getEntryPointKey());
        assertEquals(10, request.getMaxDepth());
        assertFalse(request.getIncludeDescription());
    }

    @Test
    @DisplayName("测试 equals 和 hashCode")
    void testEqualsAndHashCode() {
        // Given
        BusinessFlowRequest request1 = BusinessFlowRequest.builder()
                .callChainData("test")
                .projectPath("/path")
                .build();

        BusinessFlowRequest request2 = BusinessFlowRequest.builder()
                .callChainData("test")
                .projectPath("/path")
                .build();

        // Then
        assertEquals(request1, request2);
        assertEquals(request1.hashCode(), request2.hashCode());
    }

    @Test
    @DisplayName("测试 toString")
    void testToString() {
        // Given
        BusinessFlowRequest request = BusinessFlowRequest.builder()
                .callChainData("test data")
                .build();

        // When
        String str = request.toString();

        // Then
        assertNotNull(str);
        assertTrue(str.contains("test data"));
    }
}
