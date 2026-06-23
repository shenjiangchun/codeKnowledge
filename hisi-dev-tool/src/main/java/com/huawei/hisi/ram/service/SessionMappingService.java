package com.huawei.hisi.ram.service;

import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared service for managing frontend UUID handle to backend long session ID mapping.
 *
 * <p>Used by all RAM controllers (RamController, RamStatusController, RamPhase2Controller)
 * to resolve frontend session handles and track new session creations.
 */
@Service
public class SessionMappingService {

    private static final Logger log = LoggerFactory.getLogger(SessionMappingService.class);
    private static final int MAX_SESSION_MAPPINGS = 10_000;

    private final AgentSessionRepository sessionRepository;

    /** Maps the frontend UUID handle to the backend long session id (LRU, capped). */
    private final Map<String, Long> sessionIdMap = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_SESSION_MAPPINGS;
                }
            });

    public SessionMappingService(AgentSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** Recover UUID->id mappings from DB on startup so existing frontend connections still work. */
    @PostConstruct
    void recoverSessionMappings() {
        try {
            List<AgentSession> recent = sessionRepository.listRecentExcludingUserId("merge-analysis", MAX_SESSION_MAPPINGS);
            for (AgentSession s : recent) {
                if (s.getUuid() != null && !s.getUuid().isBlank()) {
                    sessionIdMap.put(s.getUuid(), s.getId());
                }
            }
            log.info("[SessionMapping] Recovered {} session UUID mappings from DB", sessionIdMap.size());
        } catch (Exception e) {
            log.warn("[SessionMapping] Failed to recover session mappings: {}", e.getMessage());
        }
    }

    /** Register a new session UUID handle to backend ID mapping. */
    public void register(String handle, Long backendId) {
        sessionIdMap.put(handle, backendId);
    }

    /** Resolve frontend UUID handle to backend long ID, with DB fallback for post-restart lookups. */
    public Long resolveBackendId(String handle) {
        if (handle == null || handle.isBlank()) return null;
        Long id = sessionIdMap.get(handle);
        if (id != null) return id;
        Optional<AgentSession> found = sessionRepository.findByUuid(handle);
        if (found.isPresent()) {
            id = found.get().getId();
            sessionIdMap.put(handle, id);
            return id;
        }
        return null;
    }

    /** Test-only seam to pre-populate the UUID-to-backend mapping. */
    public void registerForTest(String handle, long backendId) {
        sessionIdMap.put(handle, backendId);
    }

    /** Test-only seam: get the internal map for verification. */
    public Map<String, Long> getMapForTest() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sessionIdMap));
    }
}