package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ClarifyLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub {@link ClarifyLlmClient} — fallback used when Claude API key is absent.
 *
 * <p>{@link ClaudeClarifyLlmClient} is {@code @Primary} and delegates to this
 * stub internally when the API key is not configured. This bean must NOT be
 * {@code @Primary} to avoid ambiguity.</p>
 */
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
