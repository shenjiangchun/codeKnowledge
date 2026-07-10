package com.huawei.hisi.fixengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.fixengine.agent.FixAgent;
import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Handles multi-turn follow-up chat for an in-progress fix session.
 * Persists USER_MSG, calls {@link FixAgent#handleFollowUp}, persists ASSISTANT_DELTA.
 */
@Slf4j
@Service
public class FixChatService {

    private final FixSessionRepository fixSessionRepository;
    private final AgentEventRepository agentEventRepository;
    private final FixAgent fixAgent;
    private final ObjectMapper json = new ObjectMapper();

    public FixChatService(FixSessionRepository fixSessionRepository,
                          AgentEventRepository agentEventRepository,
                          FixAgent fixAgent) {
        this.fixSessionRepository = fixSessionRepository;
        this.agentEventRepository = agentEventRepository;
        this.fixAgent = fixAgent;
    }

    /**
     * @param sessionId   fix session id (String)
     * @param userMessage user's follow-up message
     * @return AI reply
     */
    public String chat(String sessionId, String userMessage) {
        FixSession session = fixSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("FixSession not found: " + sessionId));

        long chatSessionId;
        try {
            chatSessionId = Long.parseLong(session.getChatSessionId());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalStateException(
                    "FixSession " + sessionId + " has invalid chatSessionId: " + session.getChatSessionId());
        }

        // 1. Persist USER_MSG
        String userPayload = toJson("text", userMessage);
        AgentEvent userEvent = AgentEvent.userMsg(
                chatSessionId, 0, userPayload, idemKey(chatSessionId, userPayload));
        agentEventRepository.append(userEvent);
        log.info("[FixChatService] persisted USER_MSG sid={}", chatSessionId);

        // 2. Call FixAgent.handleFollowUp
        String reply = fixAgent.handleFollowUp(chatSessionId, userMessage);

        // 3. Persist ASSISTANT_DELTA
        String replyPayload = toJson("text", reply);
        AgentEvent replyEvent = AgentEvent.assistantDelta(
                chatSessionId, 0, replyPayload, idemKey(chatSessionId, replyPayload));
        agentEventRepository.append(replyEvent);
        log.info("[FixChatService] persisted ASSISTANT_DELTA sid={} reply.len={}",
                chatSessionId, reply.length());

        return reply;
    }

    private String toJson(String key, String value) {
        try {
            return json.writeValueAsString(Map.of(key, value == null ? "" : value));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String idemKey(long sid, String payload) {
        return "fix-" + sid + "-" + Integer.toHexString(payload.hashCode());
    }
}
