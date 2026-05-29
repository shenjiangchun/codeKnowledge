package com.huawei.hisi.service.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话状态管理器
 * 管理多轮对话的上下文状态
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class DialogStateManager {

    /**
     * 会话上下文缓存（内存存储，可后续替换为Redis）
     */
    private final Map<String, DialogContext> contextCache = new ConcurrentHashMap<>();

    /**
     * 会话超时时间（毫秒）- 30分钟
     */
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000;

    /**
     * 获取或创建对话上下文
     *
     * @param sessionId 会话ID（可选，如果为空则创建新会话）
     * @return 对话上下文
     */
    public DialogContext getOrCreateContext(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = generateSessionId();
            log.info("Creating new dialog session: {}", sessionId);
            DialogContext newContext = DialogContext.newSession(sessionId);
            contextCache.put(sessionId, newContext);
            return newContext;
        }

        DialogContext context = contextCache.get(sessionId);
        if (context == null) {
            log.info("Session not found, creating new: {}", sessionId);
            context = DialogContext.newSession(sessionId);
            contextCache.put(sessionId, context);
        } else {
            // 检查会话是否过期
            if (isSessionExpired(context)) {
                log.info("Session expired, creating new: {}", sessionId);
                context = DialogContext.newSession(sessionId);
                contextCache.put(sessionId, context);
            }
        }

        return context;
    }

    /**
     * 获取对话上下文
     *
     * @param sessionId 会话ID
     * @return 对话上下文，如果不存在返回null
     */
    public DialogContext getContext(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        return contextCache.get(sessionId);
    }

    /**
     * 保存对话上下文
     *
     * @param context 对话上下文
     */
    public void saveContext(DialogContext context) {
        if (context == null || context.getSessionId() == null) {
            log.warn("Cannot save context: invalid context or sessionId");
            return;
        }
        contextCache.put(context.getSessionId(), context);
        log.debug("Saved dialog context: sessionId={}", context.getSessionId());
    }

    /**
     * 删除对话上下文
     *
     * @param sessionId 会话ID
     */
    public void removeContext(String sessionId) {
        if (sessionId != null) {
            contextCache.remove(sessionId);
            log.info("Removed dialog context: sessionId={}", sessionId);
        }
    }

    /**
     * 清理过期会话
     */
    public void cleanExpiredSessions() {
        int removedCount = 0;
        for (Map.Entry<String, DialogContext> entry : contextCache.entrySet()) {
            if (isSessionExpired(entry.getValue())) {
                contextCache.remove(entry.getKey());
                removedCount++;
            }
        }
        if (removedCount > 0) {
            log.info("Cleaned {} expired dialog sessions", removedCount);
        }
    }

    /**
     * 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return contextCache.size();
    }

    /**
     * 检查会话是否过期
     */
    private boolean isSessionExpired(DialogContext context) {
        if (context == null || context.getLastActivityTime() == null) {
            return true;
        }
        long elapsedMs = java.time.Duration.between(
                context.getLastActivityTime(),
                java.time.LocalDateTime.now()
        ).toMillis();
        return elapsedMs > SESSION_TIMEOUT_MS;
    }

    /**
     * 生成会话ID
     */
    private String generateSessionId() {
        return "dialog-" + UUID.randomUUID().toString().substring(0, 8);
    }
}