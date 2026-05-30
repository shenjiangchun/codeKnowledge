package com.huawei.hisi.service;

import com.huawei.hisi.model.ClaudeWorkspaceSession;
import com.huawei.hisi.repository.ClaudeWorkspaceSessionRepository;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TD-003: AgentSessionHelper单元测试
 *
 * 测试session创建、获取、清理等功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TD-003: AgentSessionHelper测试")
class AgentSessionHelperTest {

    @Mock
    private ClaudeWorkspaceSessionRepository sessionRepository;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @InjectMocks
    private WorkspaceSessionServiceImpl sessionService;

    private ClaudeWorkspaceSession testSession;

    @BeforeEach
    void setUp() {
        testSession = new ClaudeWorkspaceSession();
        testSession.setId("session-123");
        testSession.setScene("DIAGNOSIS");
        testSession.setTitle("Test Session");
        testSession.setStatus("active");
        testSession.setCreatedAt(LocalDateTime.now());
        testSession.setUpdatedAt(LocalDateTime.now());
    }

    @Test
    @DisplayName("测试session创建")
    void testCreateSession() {
        // Arrange
        when(snowflakeIdGenerator.nextId()).thenReturn(123456L);
        when(sessionRepository.save(any(ClaudeWorkspaceSession.class))).thenAnswer(invocation -> {
            ClaudeWorkspaceSession session = invocation.getArgument(0);
            return session;
        });

        // Act
        ClaudeWorkspaceSession result = sessionService.createSession(
            "DIAGNOSIS",
            "分析NullPointerException",
            "/project/path"
        );

        // Assert
        assertNotNull(result, "创建的session不应为空");
        assertEquals("DIAGNOSIS", result.getScene(), "场景应为DIAGNOSIS");
        assertEquals("新会话", result.getTitle(), "默认标题应为'新会话'");
        assertEquals("active", result.getStatus(), "状态应为active");
        assertEquals("分析NullPointerException", result.getInitialPrompt(), "初始提示词应正确");
        assertEquals("/project/path", result.getWorkingDirectory(), "工作目录应正确");
        assertNotNull(result.getCreatedAt(), "创建时间不应为空");
        assertNotNull(result.getUpdatedAt(), "更新时间不应为空");

        verify(sessionRepository, times(1)).save(any(ClaudeWorkspaceSession.class));
    }

    @Test
    @DisplayName("测试session创建 - 无初始提示词")
    void testCreateSessionWithoutPrompt() {
        // Arrange
        when(snowflakeIdGenerator.nextId()).thenReturn(123456L);
        when(sessionRepository.save(any(ClaudeWorkspaceSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ClaudeWorkspaceSession result = sessionService.createSession(
            "ANALYSIS",
            null,
            "/project/path"
        );

        // Assert
        assertNotNull(result);
        assertNull(result.getInitialPrompt(), "初始提示词应为null");
    }

    @Test
    @DisplayName("测试session获取 - 存在的session")
    void testGetSessionExists() {
        // Arrange
        when(sessionRepository.findById("session-123")).thenReturn(Optional.of(testSession));

        // Act
        ClaudeWorkspaceSession result = sessionService.getSession("session-123");

        // Assert
        assertNotNull(result, "应返回存在的session");
        assertEquals("session-123", result.getId(), "ID应匹配");
        assertEquals("Test Session", result.getTitle(), "标题应匹配");
    }

    @Test
    @DisplayName("测试session获取 - 不存在的session")
    void testGetSessionNotExists() {
        // Arrange
        when(sessionRepository.findById("non-existent")).thenReturn(Optional.empty());

        // Act
        ClaudeWorkspaceSession result = sessionService.getSession("non-existent");

        // Assert
        assertNull(result, "不存在的session应返回null");
    }

    @Test
    @DisplayName("测试session列表获取 - 全部")
    void testGetSessionsAll() {
        // Arrange
        ClaudeWorkspaceSession session1 = new ClaudeWorkspaceSession();
        session1.setId("session-1");
        session1.setStatus("active");

        ClaudeWorkspaceSession session2 = new ClaudeWorkspaceSession();
        session2.setId("session-2");
        session2.setStatus("archived");

        when(sessionRepository.findAll()).thenReturn(Arrays.asList(session1, session2));

        // Act
        List<ClaudeWorkspaceSession> results = sessionService.getSessions(null);

        // Assert
        assertEquals(2, results.size(), "应返回2个session");
    }

    @Test
    @DisplayName("测试session列表获取 - 按状态过滤")
    void testGetSessionsByStatus() {
        // Arrange
        ClaudeWorkspaceSession activeSession = new ClaudeWorkspaceSession();
        activeSession.setId("session-1");
        activeSession.setStatus("active");

        when(sessionRepository.findByStatus("active")).thenReturn(List.of(activeSession));

        // Act
        List<ClaudeWorkspaceSession> results = sessionService.getSessions("active");

        // Assert
        assertEquals(1, results.size(), "应返回1个active状态的session");
        assertEquals("active", results.get(0).getStatus(), "状态应为active");
    }

    @Test
    @DisplayName("测试session更新")
    void testUpdateSession() {
        // Arrange
        when(sessionRepository.findById("session-123")).thenReturn(Optional.of(testSession));
        when(sessionRepository.save(any(ClaudeWorkspaceSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ClaudeWorkspaceSession result = sessionService.updateSession(
            "session-123",
            "Updated Title",
            "archived"
        );

        // Assert
        assertNotNull(result, "更新结果不应为空");
        assertEquals("Updated Title", result.getTitle(), "标题应更新");
        assertEquals("archived", result.getStatus(), "状态应更新");
        assertNotNull(result.getUpdatedAt(), "更新时间应被设置");

        verify(sessionRepository, times(1)).save(any(ClaudeWorkspaceSession.class));
    }

    @Test
    @DisplayName("测试session更新 - 不存在的session")
    void testUpdateSessionNotExists() {
        // Arrange
        when(sessionRepository.findById("non-existent")).thenReturn(Optional.empty());

        // Act
        ClaudeWorkspaceSession result = sessionService.updateSession(
            "non-existent",
            "New Title",
            "active"
        );

        // Assert
        assertNull(result, "更新不存在的session应返回null");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("测试session删除")
    void testDeleteSession() {
        // Arrange
        when(sessionRepository.existsById("session-123")).thenReturn(true);
        doNothing().when(sessionRepository).deleteById("session-123");

        // Act
        sessionService.deleteSession("session-123");

        // Assert
        verify(sessionRepository, times(1)).deleteById("session-123");
    }

    @Test
    @DisplayName("测试session删除 - 不存在的session")
    void testDeleteSessionNotExists() {
        // Arrange
        when(sessionRepository.existsById("non-existent")).thenReturn(false);

        // Act
        sessionService.deleteSession("non-existent");

        // Assert
        verify(sessionRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("测试session归档")
    void testArchiveSession() {
        // Arrange
        when(sessionRepository.findById("session-123")).thenReturn(Optional.of(testSession));
        when(sessionRepository.save(any(ClaudeWorkspaceSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ClaudeWorkspaceSession result = sessionService.archiveSession("session-123");

        // Assert
        assertNotNull(result, "归档结果不应为空");
        assertEquals("archived", result.getStatus(), "状态应为archived");
    }

    @Test
    @DisplayName("测试绑定Claude Session")
    void testBindClaudeSession() {
        // Arrange
        when(sessionRepository.findById("session-123")).thenReturn(Optional.of(testSession));
        when(sessionRepository.save(any(ClaudeWorkspaceSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ClaudeWorkspaceSession result = sessionService.bindClaudeSession(
            "session-123",
            "claude-session-456"
        );

        // Assert
        assertNotNull(result, "绑定结果不应为空");
        assertEquals("claude-session-456", result.getClaudeSessionId(), "Claude Session ID应正确绑定");
    }

    @Test
    @DisplayName("测试绑定Claude Session - 不存在的session")
    void testBindClaudeSessionNotExists() {
        // Arrange
        when(sessionRepository.findById("non-existent")).thenReturn(Optional.empty());

        // Act
        ClaudeWorkspaceSession result = sessionService.bindClaudeSession(
            "non-existent",
            "claude-session-456"
        );

        // Assert
        assertNull(result, "绑定不存在的session应返回null");
    }

    @Test
    @DisplayName("测试session清理功能 - 通过状态过滤")
    void testSessionCleanupByStatus() {
        // Arrange
        ClaudeWorkspaceSession archivedSession = new ClaudeWorkspaceSession();
        archivedSession.setId("archived-1");
        archivedSession.setStatus("archived");

        when(sessionRepository.findByStatus("archived")).thenReturn(List.of(archivedSession));

        // Act
        List<ClaudeWorkspaceSession> archivedSessions = sessionService.getSessions("archived");

        // Assert
        assertEquals(1, archivedSessions.size(), "应能获取到已归档的session");
        assertEquals("archived", archivedSessions.get(0).getStatus(), "状态应为archived");
    }
}