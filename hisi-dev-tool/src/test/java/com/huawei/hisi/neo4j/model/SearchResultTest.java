package com.huawei.hisi.neo4j.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchResult 模型测试类
 */
class SearchResultTest {

    @Test
    void testBuilderAndGetters() {
        // Arrange
        QueryIntent intent = QueryIntent.builder()
                .entity("UserService")
                .methodType("create")
                .build();

        MethodNode method1 = MethodNode.builder()
                .nodeId("test.method.1")
                .className("com.example.UserService")
                .methodName("createUser")
                .build();

        MethodNode method2 = MethodNode.builder()
                .nodeId("test.method.2")
                .className("com.example.UserService")
                .methodName("deleteUser")
                .build();

        // Act
        SearchResult result = SearchResult.builder()
                .query("how to create user")
                .intent(intent)
                .results(Arrays.asList(method1, method2))
                .totalCount(2)
                .costTimeMs(150L)
                .build();

        // Assert
        assertEquals("how to create user", result.getQuery());
        assertNotNull(result.getIntent());
        assertEquals("UserService", result.getIntent().getEntity());
        assertEquals(2, result.getResults().size());
        assertEquals(2, result.getTotalCount());
        assertEquals(150L, result.getCostTimeMs());
    }

    @Test
    void testEmptyResults() {
        // Arrange & Act
        SearchResult result = SearchResult.builder()
                .query("test query")
                .results(Collections.emptyList())
                .totalCount(0)
                .costTimeMs(10L)
                .build();

        // Assert
        assertNotNull(result.getResults());
        assertTrue(result.getResults().isEmpty());
        assertEquals(0, result.getTotalCount());
    }

    @Test
    void testNullIntent() {
        // Arrange & Act
        SearchResult result = SearchResult.builder()
                .query("test query")
                .intent(null)
                .results(Collections.emptyList())
                .build();

        // Assert
        assertNull(result.getIntent());
    }

    @Test
    void testSetter() {
        // Arrange
        SearchResult result = SearchResult.builder().build();

        // Act
        result.setQuery("new query");
        result.setTotalCount(100);
        result.setCostTimeMs(200L);

        // Assert
        assertEquals("new query", result.getQuery());
        assertEquals(100, result.getTotalCount());
        assertEquals(200L, result.getCostTimeMs());
    }

    @Test
    void testEqualsAndHashCode() {
        // Arrange
        QueryIntent intent = QueryIntent.builder().entity("Test").build();
        SearchResult result1 = SearchResult.builder()
                .query("test")
                .intent(intent)
                .totalCount(1)
                .costTimeMs(100L)
                .build();

        SearchResult result2 = SearchResult.builder()
                .query("test")
                .intent(intent)
                .totalCount(1)
                .costTimeMs(100L)
                .build();

        // Assert
        assertEquals(result1, result2);
        assertEquals(result1.hashCode(), result2.hashCode());
    }

    @Test
    void testToString() {
        // Arrange
        SearchResult result = SearchResult.builder()
                .query("test query")
                .totalCount(5)
                .costTimeMs(123L)
                .build();

        // Act
        String str = result.toString();

        // Assert
        assertTrue(str.contains("test query"));
        assertTrue(str.contains("5"));
        assertTrue(str.contains("123"));
    }
}
