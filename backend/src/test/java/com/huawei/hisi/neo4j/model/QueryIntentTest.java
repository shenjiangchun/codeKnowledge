package com.huawei.hisi.neo4j.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryIntent 模型测试类
 */
class QueryIntentTest {

    @Test
    void testBuilderAndGetters() {
        // Arrange & Act
        List<String> keywords = Arrays.asList("user", "create", "database");
        QueryIntent intent = QueryIntent.builder()
                .entity("UserService")
                .methodType("create")
                .serviceName("user-service")
                .keywords(keywords)
                .build();

        // Assert
        assertEquals("UserService", intent.getEntity());
        assertEquals("create", intent.getMethodType());
        assertEquals("user-service", intent.getServiceName());
        assertEquals(3, intent.getKeywords().size());
        assertTrue(intent.getKeywords().contains("user"));
    }

    @Test
    void testSetter() {
        // Arrange
        QueryIntent intent = QueryIntent.builder().build();

        // Act
        intent.setEntity("OrderService");
        intent.setMethodType("processOrder");
        intent.setServiceName("order-service");
        intent.setKeywords(Arrays.asList("order", "process"));

        // Assert
        assertEquals("OrderService", intent.getEntity());
        assertEquals("processOrder", intent.getMethodType());
        assertEquals("order-service", intent.getServiceName());
        assertEquals(2, intent.getKeywords().size());
    }

    @Test
    void testEqualsAndHashCode() {
        // Arrange
        List<String> keywords = Arrays.asList("test");
        QueryIntent intent1 = QueryIntent.builder()
                .entity("TestService")
                .methodType("test")
                .serviceName("test-service")
                .keywords(keywords)
                .build();

        QueryIntent intent2 = QueryIntent.builder()
                .entity("TestService")
                .methodType("test")
                .serviceName("test-service")
                .keywords(keywords)
                .build();

        // Assert
        assertEquals(intent1, intent2);
        assertEquals(intent1.hashCode(), intent2.hashCode());
    }

    @Test
    void testToString() {
        // Arrange
        QueryIntent intent = QueryIntent.builder()
                .entity("TestService")
                .methodType("test")
                .serviceName("test-service")
                .keywords(Collections.singletonList("test"))
                .build();

        // Act
        String result = intent.toString();

        // Assert
        assertTrue(result.contains("TestService"));
        assertTrue(result.contains("test-service"));
    }

    @Test
    void testNullKeywords() {
        // Arrange & Act
        QueryIntent intent = QueryIntent.builder()
                .entity("TestService")
                .keywords(null)
                .build();

        // Assert
        assertNull(intent.getKeywords());
    }

    @Test
    void testEmptyKeywords() {
        // Arrange & Act
        QueryIntent intent = QueryIntent.builder()
                .entity("TestService")
                .keywords(Collections.emptyList())
                .build();

        // Assert
        assertNotNull(intent.getKeywords());
        assertTrue(intent.getKeywords().isEmpty());
    }
}
