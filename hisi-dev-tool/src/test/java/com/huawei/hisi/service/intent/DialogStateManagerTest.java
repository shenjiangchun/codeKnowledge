package com.huawei.hisi.service.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DialogStateManager 单元测试
 * 验证对话状态管理功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
class DialogStateManagerTest {

    private DialogStateManager stateManager;

    @BeforeEach
    void setUp() {
        stateManager = new DialogStateManager();
    }

    @Test
    @DisplayName("测试创建新会话")
    void testCreateNewSession() {
        DialogContext context = stateManager.getOrCreateContext(null);

        assertNotNull(context);
        assertNotNull(context.getSessionId());
        assertEquals(0, context.getMessages().size());
        assertEquals(DialogContext.TaskState.IDLE, context.getTaskState());
    }

    @Test
    @DisplayName("测试获取现有会话")
    void testGetExistingSession() {
        // 先创建
        DialogContext created = stateManager.getOrCreateContext("test-session");

        // 再获取
        DialogContext retrieved = stateManager.getOrCreateContext("test-session");

        assertEquals(created.getSessionId(), retrieved.getSessionId());
    }

    @Test
    @DisplayName("测试保存上下文")
    void testSaveContext() {
        DialogContext context = stateManager.getOrCreateContext("save-test");
        context.addUserMessage("测试消息", IntentType.DIAGNOSE_LOG);
        context.setLastConclusion("测试结论");

        stateManager.saveContext(context);

        DialogContext retrieved = stateManager.getContext("save-test");
        assertNotNull(retrieved);
        assertEquals(1, retrieved.getMessages().size());
        assertEquals("测试结论", retrieved.getLastConclusion());
    }

    @Test
    @DisplayName("测试删除上下文")
    void testRemoveContext() {
        String sessionId = "remove-test";
        stateManager.getOrCreateContext(sessionId);

        stateManager.removeContext(sessionId);

        DialogContext retrieved = stateManager.getContext(sessionId);
        assertNull(retrieved);
    }

    @Test
    @DisplayName("测试获取不存在的会话")
    void testGetNonExistingSession() {
        DialogContext context = stateManager.getContext("non-existing");

        assertNull(context);
    }

    @Test
    @DisplayName("测试活跃会话计数")
    void testActiveSessionCount() {
        int initialCount = stateManager.getActiveSessionCount();

        stateManager.getOrCreateContext("session-1");
        stateManager.getOrCreateContext("session-2");

        assertEquals(initialCount + 2, stateManager.getActiveSessionCount());
    }

    @Test
    @DisplayName("测试上下文更新 - 添加消息")
    void testContextUpdate_AddMessage() {
        DialogContext context = stateManager.getOrCreateContext("msg-test");

        context.addUserMessage("用户输入", IntentType.DIAGNOSE_LOG);
        context.addAssistantMessage("系统回复");

        assertEquals(2, context.getMessages().size());
        assertEquals("user", context.getMessages().get(0).getRole());
        assertEquals("assistant", context.getMessages().get(1).getRole());
    }

    @Test
    @DisplayName("测试上下文更新 - 实体累积")
    void testContextUpdate_EntityAccumulation() {
        DialogContext context = stateManager.getOrCreateContext("entity-test");

        java.util.Map<String, String> entities1 = new java.util.HashMap<>();
        entities1.put("errorType", "NullPointerException");
        context.updateEntities(entities1);

        java.util.Map<String, String> entities2 = new java.util.HashMap<>();
        entities2.put("className", "UserService");
        context.updateEntities(entities2);

        assertEquals("NullPointerException", context.getEntity("errorType"));
        assertEquals("UserService", context.getEntity("className"));
    }

    @Test
    @DisplayName("测试上下文摘要生成")
    void testContextSummary() {
        DialogContext context = stateManager.getOrCreateContext("summary-test");
        context.addUserMessage("分析这个NPE错误", IntentType.DIAGNOSE_LOG);
        context.setLastConclusion("问题出在空指针引用");

        String summary = context.getContextSummary();

        assertNotNull(summary);
        assertTrue(summary.contains("上次结论"));
        assertTrue(summary.contains("对话历史摘要"));
    }

    @Test
    @DisplayName("测试最近消息获取")
    void testGetRecentMessages() {
        DialogContext context = stateManager.getOrCreateContext("recent-test");

        for (int i = 0; i < 10; i++) {
            context.addUserMessage("消息" + i, IntentType.DIAGNOSE_LOG);
        }

        java.util.List<DialogContext.Message> recent = context.getRecentMessages(3);

        assertEquals(3, recent.size());
        // 最近3条是消息7, 8, 9
        assertTrue(recent.get(2).getContent().contains("消息9"));
    }

    @Test
    @DisplayName("测试任务状态更新")
    void testTaskStateUpdate() {
        DialogContext context = stateManager.getOrCreateContext("state-test");

        context.updateTaskState(DialogContext.TaskState.ANALYZING);
        assertEquals(DialogContext.TaskState.ANALYZING, context.getTaskState());

        context.updateTaskState(DialogContext.TaskState.COMPLETED);
        assertEquals(DialogContext.TaskState.COMPLETED, context.getTaskState());
    }
}