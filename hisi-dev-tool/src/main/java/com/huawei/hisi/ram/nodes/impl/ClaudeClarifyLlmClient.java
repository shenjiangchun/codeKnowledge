package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ClarifyLlmClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Claude-backed {@link ClarifyLlmClient}. Active only when
 * {@code anthropic.api-key} is set; otherwise {@link StubClarifyLlmClient}
 * remains the {@code @Primary} bean.
 */
@Slf4j
@Primary
@Component
public class ClaudeClarifyLlmClient implements ClarifyLlmClient {

    private static final String SYSTEM_PROMPT = """
            You are a senior product analyst helping translate a developer's
            natural-language request into a structured requirement record.

            You MUST respond with a single JSON object — no prose, no markdown
            fences — that matches this schema exactly:

            {
              "intent": "<one short sentence summarising the request>",
              "project_paths": ["<repo-relative path hint>", ...],
              "acceptance_criteria": ["<testable AC>", ...],
              "target_modules": ["<module/package name>", ...],
              "constraints": { "must": [...], "must_not": [...] }
            }

            Rules:
            - intent: 1 short sentence, never empty.
            - project_paths: include any explicit paths the user mentioned plus
              the caller-provided projectHints; deduplicate.
            - acceptance_criteria: 2-5 concise, testable bullets. Always provide
              at least 2 — derive them from the intent if the user did not list any.
            - target_modules: package or feature module names, may be empty.
            - constraints: optional must/must_not lists, may be empty arrays.
            - Output JSON only.

            Language requirement (MANDATORY):
            - All natural-language string values (intent, every entry in
              acceptance_criteria, constraints.must / constraints.must_not text)
              MUST be written in 简体中文 (Simplified Chinese).
            - Keep JSON keys, file paths, package names, and module identifiers
              in their original form (do NOT translate code identifiers).
            - target_modules entries: keep package/module identifiers as-is.
            """;

    private final RamClaudeJsonClient claude;
    private final StubClarifyLlmClient fallback;

    public ClaudeClarifyLlmClient(RamClaudeJsonClient claude, StubClarifyLlmClient fallback) {
        this.claude = claude;
        this.fallback = fallback;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractRequirements(String userRequest, Map<String, Object> hints) {
        List<String> hintPaths = extractProjectPaths(hints);
        log.info("[RAM][ClaudeClarifyLlmClient] extractRequirements userRequest.len={} hintPaths={}",
                userRequest == null ? 0 : userRequest.length(), hintPaths);

        if (!claude.isAvailable()) {
            log.error("[RAM][ClaudeClarifyLlmClient] Claude UNAVAILABLE (anthropic.api-key empty) — falling back to Stub. THIS IS WHY OUTPUT IS POOR.");
            return fallback.extractRequirements(userRequest, hints);
        }

        String userPrompt = buildUserPrompt(userRequest, hintPaths);
        try {
            Map<String, Object> raw = claude.callJson(
                    SYSTEM_PROMPT, userPrompt,
                    new SendOptions(claude.defaultModel(), 2048, 0.2, null));
            log.info("[RAM][ClaudeClarifyLlmClient] Claude returned keys={} acs={} intent.len={}",
                    raw == null ? "null" : raw.keySet(),
                    raw == null ? 0 : asStringList(raw.get("acceptance_criteria")).size(),
                    raw == null || !(raw.get("intent") instanceof String s) ? 0 : s.length());
            return normalize(raw, userRequest, hintPaths);
        } catch (Exception ex) {
            log.error("[RAM][ClaudeClarifyLlmClient] Claude call FAILED — falling back to Stub. err={}", ex.toString(), ex);
            return fallback.extractRequirements(userRequest, hints);
        }
    }

    private String buildUserPrompt(String userRequest, List<String> hintPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("User request:\n").append(userRequest == null ? "" : userRequest).append("\n\n");
        if (!hintPaths.isEmpty()) {
            sb.append("Caller-provided projectHints:\n");
            for (String p : hintPaths) sb.append("- ").append(p).append("\n");
            sb.append("\n");
        }
        sb.append("Return the JSON object now.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw, String userRequest, List<String> hintPaths) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object intent = raw == null ? null : raw.get("intent");
        out.put("intent", (intent instanceof String s && !s.isBlank())
                ? s : (userRequest == null ? "" : userRequest));

        List<String> paths = asStringList(raw == null ? null : raw.get("project_paths"));
        if (paths.isEmpty()) paths = hintPaths;
        out.put("project_paths", paths);

        List<String> acs = asStringList(raw == null ? null : raw.get("acceptance_criteria"));
        out.put("acceptance_criteria", acs);

        if (raw != null && raw.get("target_modules") != null) {
            out.put("target_modules", asStringList(raw.get("target_modules")));
        }
        if (raw != null && raw.get("constraints") instanceof Map<?, ?> c) {
            out.put("constraints", c);
        }
        return out;
    }

    private List<String> asStringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream()
                    .filter(x -> x instanceof String)
                    .map(x -> (String) x)
                    .toList();
        }
        return List.of();
    }

    private List<String> extractProjectPaths(Map<String, Object> hints) {
        if (hints == null) return List.of();
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
