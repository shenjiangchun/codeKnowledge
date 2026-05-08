package com.huawei.hisi.service;

import com.huawei.hisi.model.ClaudeWorkspaceSession;
import com.huawei.hisi.repository.ClaudeWorkspaceSessionRepository;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作空间会话服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceSessionServiceImpl implements WorkspaceSessionService {

    private final ClaudeWorkspaceSessionRepository sessionRepository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public List<ClaudeWorkspaceSession> getSessions(String status) {
        if (status != null && !status.isEmpty()) {
            return sessionRepository.findByStatus(status);
        }
        return sessionRepository.findAll();
    }

    @Override
    public ClaudeWorkspaceSession getSession(String id) {
        return sessionRepository.findById(id).orElse(null);
    }

    @Override
    @Transactional
    public ClaudeWorkspaceSession createSession(String scene, String initialPrompt, String workingDirectory) {
        ClaudeWorkspaceSession session = new ClaudeWorkspaceSession();
        session.setId(String.valueOf(snowflakeIdGenerator.nextId()));
        session.setScene(scene);
        session.setInitialPrompt(initialPrompt);
        session.setWorkingDirectory(workingDirectory);
        // claudeSessionId will be set later when we extract it from Claude CLI output
        session.setTitle("新会话");
        session.setStatus("active");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        ClaudeWorkspaceSession saved = sessionRepository.save(session);
        log.info("创建工作空间会话: id={}, scene={}, workingDir={}, prompt={}",
            saved.getId(), scene, workingDirectory, initialPrompt != null ? initialPrompt.substring(0, Math.min(30, initialPrompt.length())) + "..." : "null");
        return saved;
    }

    @Override
    @Transactional
    public ClaudeWorkspaceSession updateSession(String id, String title, String status) {
        ClaudeWorkspaceSession session = sessionRepository.findById(id).orElse(null);
        if (session == null) {
            log.warn("会话不存在: id={}", id);
            return null;
        }

        if (title != null) {
            session.setTitle(title);
        }
        if (status != null) {
            session.setStatus(status);
        }
        session.setUpdatedAt(LocalDateTime.now());

        ClaudeWorkspaceSession saved = sessionRepository.save(session);
        log.info("更新工作空间会话: id={}, title={}, status={}", id, title, status);
        return saved;
    }

    @Override
    @Transactional
    public void deleteSession(String id) {
        if (!sessionRepository.existsById(id)) {
            log.warn("删除会话失败，会话不存在: id={}", id);
            return;
        }
        sessionRepository.deleteById(id);
        log.info("删除工作空间会话: id={}", id);
    }

    @Override
    @Transactional
    public ClaudeWorkspaceSession archiveSession(String id) {
        return updateSession(id, null, "archived");
    }

    @Override
    @Transactional
    public ClaudeWorkspaceSession bindClaudeSession(String id, String claudeSessionId) {
        ClaudeWorkspaceSession session = sessionRepository.findById(id).orElse(null);
        if (session == null) {
            log.warn("会话不存在, 无法绑定 Claude session: id={}", id);
            return null;
        }

        session.setClaudeSessionId(claudeSessionId);
        session.setUpdatedAt(LocalDateTime.now());

        ClaudeWorkspaceSession saved = sessionRepository.save(session);
        log.info("绑定 Claude session: workspaceId={}, claudeSessionId={}", id, claudeSessionId);
        return saved;
    }
}