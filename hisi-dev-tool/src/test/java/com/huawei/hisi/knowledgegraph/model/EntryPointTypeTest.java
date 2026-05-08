package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EntryPointType enum.
 * Tests cover enum values, code/description getters, and valueOf operations.
 */
@DisplayName("EntryPointType Tests")
class EntryPointTypeTest {

    @Test
    @DisplayName("Test all entry point types exist")
    void testAllTypesExist() {
        EntryPointType[] types = EntryPointType.values();
        assertEquals(11, types.length, "Should have 11 entry point types");
    }

    @Test
    @DisplayName("Test HTTP type properties")
    void testHttpType() {
        assertEquals("HTTP", EntryPointType.HTTP.getCode());
        assertEquals("REST API入口", EntryPointType.HTTP.getDescription());
    }

    @Test
    @DisplayName("Test SCHEDULED type properties")
    void testScheduledType() {
        assertEquals("SCHEDULED", EntryPointType.SCHEDULED.getCode());
        assertEquals("定时任务入口", EntryPointType.SCHEDULED.getDescription());
    }

    @Test
    @DisplayName("Test MQ type properties")
    void testMqType() {
        assertEquals("MQ", EntryPointType.MQ.getCode());
        assertEquals("消息队列消费者入口", EntryPointType.MQ.getDescription());
    }

    @Test
    @DisplayName("Test EVENT type properties")
    void testEventType() {
        assertEquals("EVENT", EntryPointType.EVENT.getCode());
        assertEquals("事件监听器入口", EntryPointType.EVENT.getDescription());
    }

    @Test
    @DisplayName("Test WEBSOCKET type properties")
    void testWebSocketType() {
        assertEquals("WEBSOCKET", EntryPointType.WEBSOCKET.getCode());
        assertEquals("WebSocket入口", EntryPointType.WEBSOCKET.getDescription());
    }

    @Test
    @DisplayName("Test RPC type properties")
    void testRpcType() {
        assertEquals("RPC", EntryPointType.RPC.getCode());
        assertEquals("远程服务入口", EntryPointType.RPC.getDescription());
    }

    @Test
    @DisplayName("Test LIFECYCLE type properties")
    void testLifecycleType() {
        assertEquals("LIFECYCLE", EntryPointType.LIFECYCLE.getCode());
        assertEquals("生命周期入口", EntryPointType.LIFECYCLE.getDescription());
    }

    @Test
    @DisplayName("Test FASTAPI_ROUTE type exists")
    void testFastApiRouteType() {
        assertEquals("FASTAPI_ROUTE", EntryPointType.FASTAPI_ROUTE.getCode());
        assertNotNull(EntryPointType.FASTAPI_ROUTE.getDescription());
    }

    @Test
    @DisplayName("Test FLASK_ROUTE type exists")
    void testFlaskRouteType() {
        assertEquals("FLASK_ROUTE", EntryPointType.FLASK_ROUTE.getCode());
        assertNotNull(EntryPointType.FLASK_ROUTE.getDescription());
    }

    @Test
    @DisplayName("Test DJANGO_VIEW type exists")
    void testDjangoViewType() {
        assertEquals("DJANGO_VIEW", EntryPointType.DJANGO_VIEW.getCode());
        assertNotNull(EntryPointType.DJANGO_VIEW.getDescription());
    }

    @Test
    @DisplayName("Test CELERY_TASK type exists")
    void testCeleryTaskType() {
        assertEquals("CELERY_TASK", EntryPointType.CELERY_TASK.getCode());
        assertNotNull(EntryPointType.CELERY_TASK.getDescription());
    }

    @Test
    @DisplayName("Test valueOf for valid type")
    void testValueOfValid() {
        assertEquals(EntryPointType.HTTP, EntryPointType.valueOf("HTTP"));
        assertEquals(EntryPointType.SCHEDULED, EntryPointType.valueOf("SCHEDULED"));
        assertEquals(EntryPointType.MQ, EntryPointType.valueOf("MQ"));
    }

    @Test
    @DisplayName("Test valueOf for invalid type throws exception")
    void testValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            EntryPointType.valueOf("INVALID_TYPE");
        });
    }

    @Test
    @DisplayName("Test enum ordinal values")
    void testOrdinal() {
        assertEquals(0, EntryPointType.HTTP.ordinal());
        assertEquals(1, EntryPointType.SCHEDULED.ordinal());
        assertEquals(2, EntryPointType.MQ.ordinal());
        assertEquals(3, EntryPointType.EVENT.ordinal());
        assertEquals(4, EntryPointType.WEBSOCKET.ordinal());
        assertEquals(5, EntryPointType.RPC.ordinal());
        assertEquals(6, EntryPointType.LIFECYCLE.ordinal());
        assertEquals(7, EntryPointType.FASTAPI_ROUTE.ordinal());
        assertEquals(8, EntryPointType.FLASK_ROUTE.ordinal());
        assertEquals(9, EntryPointType.DJANGO_VIEW.ordinal());
        assertEquals(10, EntryPointType.CELERY_TASK.ordinal());
    }

    @Test
    @DisplayName("Test enum name matches code")
    void testNameMatchesCode() {
        for (EntryPointType type : EntryPointType.values()) {
            assertEquals(type.name(), type.getCode());
        }
    }
}
