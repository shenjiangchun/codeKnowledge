package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.LogChunkNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Neo4jLogChunkRepository 单元测试
 *
 * Note: Spring Data Neo4j Repository tests typically require integration tests
 * with a real Neo4j database. Here we test the entity model only.
 */
class Neo4jLogChunkRepositoryTest {

    @Test
    @DisplayName("实体类 - Builder 和 Getter 测试")
    void testEntity_BuilderAndGetters() {
        // Given & When
        LogChunkNode chunk = LogChunkNode.builder()
            .nodeId("test-node")
            .errorType("NullPointerException")
            .message("Test message")
            .stackTrace("at com.example.Service.method(Service.java:10)")
            .fingerprint("abc123")
            .embedding(List.of(0.5, 0.6, 0.7))
            .projectPath("/test/project")
            .build();

        // Then
        assertEquals("test-node", chunk.getNodeId());
        assertEquals("NullPointerException", chunk.getErrorType());
        assertEquals("Test message", chunk.getMessage());
        assertEquals("abc123", chunk.getFingerprint());
        assertEquals(3, chunk.getEmbedding().size());
        assertEquals("/test/project", chunk.getProjectPath());
    }

    @Test
    @DisplayName("实体类 - 默认构造器")
    void testEntity_DefaultConstructor() {
        // Given & When
        LogChunkNode chunk = new LogChunkNode();

        // Then - should be null-safe
        assertNull(chunk.getNodeId());
        assertNull(chunk.getErrorType());
        assertNull(chunk.getEmbedding());
    }

    @Test
    @DisplayName("实体类 - Setter 测试")
    void testEntity_Setters() {
        // Given
        LogChunkNode chunk = new LogChunkNode();

        // When
        chunk.setNodeId("new-node");
        chunk.setErrorType("RuntimeException");
        chunk.setMessage("Runtime error");
        chunk.setEmbedding(List.of(0.1, 0.2));
        chunk.setReportId(12345L);

        // Then
        assertEquals("new-node", chunk.getNodeId());
        assertEquals("RuntimeException", chunk.getErrorType());
        assertEquals(12345L, chunk.getReportId());
    }
}