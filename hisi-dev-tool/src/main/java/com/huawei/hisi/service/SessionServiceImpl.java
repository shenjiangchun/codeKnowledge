package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ClaudeMessage;
import com.huawei.hisi.model.ClaudeSession;
import com.huawei.hisi.repository.ClaudeSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 会话服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final ClaudeSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ClaudeSession createSession(String scene, String title, String metadata) {
        return createSession(scene, title, metadata, null);
    }

    @Override
    @Transactional
    public ClaudeSession createSession(String scene, String title, String metadata, String workingDirectory) {
        ClaudeSession session = new ClaudeSession();
        session.setId(UUID.randomUUID().toString());
        session.setScene(scene);
        session.setTitle(title != null ? title : generateDefaultTitle(scene));
        session.setStatus("active");
        session.setMetadata(metadata);
        session.setWorkingDirectory(workingDirectory);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        sessionRepository.saveSession(session);
        log.info("创建会话: id={}, scene={}, workingDir={}", session.getId(), scene, workingDirectory);
        return session;
    }

    @Override
    @Transactional
    public ClaudeSession createSessionWithId(String sessionId, String scene, String title, String metadata) {
        return createSessionWithId(sessionId, scene, title, metadata, null);
    }

    @Override
    @Transactional
    public ClaudeSession createSessionWithId(String sessionId, String scene, String title, String metadata, String workingDirectory) {
        ClaudeSession session = new ClaudeSession();
        session.setId(sessionId);
        session.setScene(scene);
        session.setTitle(title != null ? title : generateDefaultTitle(scene));
        session.setStatus("active");
        session.setMetadata(metadata);
        session.setWorkingDirectory(workingDirectory);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        sessionRepository.saveSession(session);
        log.info("创建会话（指定ID）: id={}, scene={}, workingDir={}", session.getId(), scene, workingDirectory);
        return session;
    }

    @Override
    public ClaudeSession getSession(String sessionId) {
        return sessionRepository.findSessionById(sessionId).orElse(null);
    }

    @Override
    public List<ClaudeSession> getSessions(String status, int page, int pageSize) {
        if (status != null && !status.isEmpty()) {
            return sessionRepository.findSessionsByStatus(status, pageSize * 10);
        }
        return sessionRepository.findAllSessions(page, pageSize);
    }

    @Override
    public int getSessionCount(String status) {
        return sessionRepository.countSessions(status);
    }

    @Override
    @Transactional
    public void updateTitle(String sessionId, String title) {
        sessionRepository.updateSessionTitle(sessionId, title);
    }

    @Override
    @Transactional
    public void updateClaudeSessionCode(String sessionId, String claudeSessionCode) {
        sessionRepository.updateClaudeSessionCode(sessionId, claudeSessionCode);
    }

    @Override
    @Transactional
    public void archiveSession(String sessionId) {
        sessionRepository.updateSessionStatus(sessionId, "archived");
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        sessionRepository.deleteSession(sessionId);
    }

    @Override
    @Transactional
    public void clearMessages(String sessionId) {
        sessionRepository.deleteMessagesBySessionId(sessionId);
    }

    @Override
    @Transactional
    public ClaudeMessage addMessage(String sessionId, String role, String content) {
        ClaudeMessage message = new ClaudeMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());

        sessionRepository.saveMessage(message);
        return message;
    }

    @Override
    public List<ClaudeMessage> getMessages(String sessionId) {
        return sessionRepository.findMessagesBySessionId(sessionId);
    }

    @Override
    public String exportSession(String sessionId, String format) {
        ClaudeSession session = getSession(sessionId);
        if (session == null) {
            return null;
        }

        List<ClaudeMessage> messages = getMessages(sessionId);

        if ("json".equalsIgnoreCase(format)) {
            return exportAsJson(session, messages);
        } else {
            return exportAsMarkdown(session, messages);
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessionRepository.findSessionById(sessionId).isPresent();
    }

    private String generateDefaultTitle(String scene) {
        String sceneName = switch (scene) {
            case "log-analysis" -> "日志分析";
            case "code-analysis" -> "代码分析";
            case "trace-analysis" -> "调用链分析";
            case "impact-analysis" -> "影响分析";
            case "free-chat" -> "自由对话";
            default -> "会话";
        };
        return sceneName + " - " + LocalDateTime.now().toString().substring(0, 16);
    }

    private String exportAsMarkdown(ClaudeSession session, List<ClaudeMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(session.getTitle() != null ? session.getTitle() : "Claude 会话").append("\n\n");
        sb.append("**场景**: ").append(session.getScene()).append("\n");
        sb.append("**创建时间**: ").append(session.getCreatedAt()).append("\n\n");
        sb.append("---\n\n");

        for (ClaudeMessage msg : messages) {
            String role = "user".equals(msg.getRole()) ? "👤 用户" : "🤖 Claude";
            sb.append("### ").append(role).append("\n");
            sb.append(msg.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    private String exportAsJson(ClaudeSession session, List<ClaudeMessage> messages) {
        try {
            return objectMapper.writeValueAsString(new SessionExport(session, messages));
        } catch (Exception e) {
            log.error("导出JSON失败: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 会话导出数据结构
     */
    public record SessionExport(ClaudeSession session, List<ClaudeMessage> messages) {}
}
