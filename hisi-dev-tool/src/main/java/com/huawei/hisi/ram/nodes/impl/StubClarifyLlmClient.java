package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ClarifyLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub {@link ClarifyLlmClient} used until the real Claude wiring lands in Task 11.
 *
 * <p>It deliberately returns a minimal extraction echoing the user request and
 * any {@code projectHints} found in the caller-supplied hints map. Acceptance
 * criteria are intentionally left empty so that the clarify schema will fail
 * fast and the orchestrator can demonstrate the HITL clarify loop.</p>
 */
@Primary
@Component
public class StubClarifyLlmClient implements ClarifyLlmClient {

    private static final Logger log = LoggerFactory.getLogger(StubClarifyLlmClient.class);

    @Override
    public Map<String, Object> extractRequirements(String userRequest, Map<String, Object> hints) {
        List<String> paths = extractProjectPaths(hints);
        log.info("[RAM][StubClarifyLlmClient] extractRequirements userRequest.len={} hints.keys={} projectPaths={}",
                userRequest == null ? 0 : userRequest.length(),
                hints == null ? "null" : hints.keySet(),
                paths);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intent", userRequest == null ? "" : userRequest);
        out.put("project_paths", paths);
        out.put("acceptance_criteria", List.of());
        return out;
    }

    private List<String> extractProjectPaths(Map<String, Object> hints) {
        if (hints == null) {
            return List.of();
        }
        Object raw = hints.get("projectHints");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .toList();
        }
        return List.of();
    }
}
