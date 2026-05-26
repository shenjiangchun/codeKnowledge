package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ImplementLlmClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Claude-backed {@link ImplementLlmClient}. Active only when
 * {@code anthropic.api-key} is set; otherwise {@link StubImplementLlmClient}
 * remains the only candidate.
 */
@Slf4j
@Primary
@Component
public class ClaudeImplementLlmClient implements ImplementLlmClient {

    private static final String SYSTEM_PROMPT = """
            You are a senior tech lead drafting a 3-artifact requirement
            implementation plan (business / UI / tech) from an impact analysis
            and acceptance criteria.

            You MUST respond with a single JSON object — no prose, no markdown
            fences — that matches this schema exactly:

            {
              "biz_plan": {
                "steps": ["<ordered step>", ...],
                "data_flow": "<one-paragraph description of the data flow>"
              },
              "ui_plan": {
                "screens": ["<screen or component name>", ...],
                "interactions": ["<user interaction>", ...]
              },
              "tech_plan": {
                "files": ["<repo-relative file path>", ...],
                "new_apis": ["<METHOD /path or service.method>", ...],
                "schema_changes": ["<table.column or migration name>", ...]
              }
            }

            Rules:
            - biz_plan.steps: 3-7 concrete, ordered steps that satisfy the ACs.
            - biz_plan.data_flow: one sentence/paragraph naming the actors and
              direction of data movement.
            - ui_plan: required if any user-facing surface is touched, else
              return empty arrays for screens/interactions.
            - tech_plan.files: real file paths inferred from the involved
              components in the impact output; never invent unrelated ones.
            - tech_plan.new_apis: HTTP routes or service methods to add/modify.
            - tech_plan.schema_changes: DB schema/migration items, may be empty.
            - Output JSON only.

            Language requirement (MANDATORY):
            - All natural-language string values MUST be written in 简体中文
              (Simplified Chinese). This includes:
                * every entry of biz_plan.steps
                * biz_plan.data_flow
                * every entry of ui_plan.screens and ui_plan.interactions
                  (component/screen names may stay in English if they are real
                  code identifiers, but descriptive text must be Chinese)
                * descriptive parts of tech_plan.new_apis (the HTTP method +
                  path / service.method identifier stays in English, but any
                  附带说明使用中文)
                * descriptive parts of tech_plan.schema_changes
            - Keep JSON keys, file paths, class/method identifiers, HTTP routes,
              and SQL/column names in their original form — do NOT translate code
              identifiers.
            """;

    private final RamClaudeJsonClient claude;
    private final StubImplementLlmClient fallback;

    public ClaudeImplementLlmClient(RamClaudeJsonClient claude, StubImplementLlmClient fallback) {
        this.claude = claude;
        this.fallback = fallback;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> draft(Map<String, Object> impactOutput,
                                     List<String> acceptanceCriteria,
                                     String model) {
        log.info("[RAM][ClaudeImplementLlmClient] draft impact.keys={} acs={} model={}",
                impactOutput == null ? "null" : impactOutput.keySet(),
                acceptanceCriteria == null ? 0 : acceptanceCriteria.size(),
                model);

        if (!claude.isAvailable()) {
            log.error("[RAM][ClaudeImplementLlmClient] Claude UNAVAILABLE (anthropic.api-key empty) — falling back to Stub. THIS IS WHY OUTPUT IS POOR.");
            return fallback.draft(impactOutput, acceptanceCriteria, model);
        }

        String prompt = buildUserPrompt(impactOutput, acceptanceCriteria);
        String effectiveModel = (model == null || model.isBlank()) ? claude.defaultModel() : model;
        try {
            Map<String, Object> raw = claude.callJson(
                    SYSTEM_PROMPT, prompt,
                    new SendOptions(effectiveModel, 4096, 0.3, null));
            log.info("[RAM][ClaudeImplementLlmClient] Claude returned keys={}",
                    raw == null ? "null" : raw.keySet());
            return normalize(raw, impactOutput, acceptanceCriteria, model);
        } catch (Exception ex) {
            log.error("[RAM][ClaudeImplementLlmClient] Claude call FAILED — falling back to Stub. err={}", ex.toString(), ex);
            return fallback.draft(impactOutput, acceptanceCriteria, model);
        }
    }

    private String buildUserPrompt(Map<String, Object> impactOutput, List<String> acs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Impact analysis output:\n");
        sb.append(impactOutput == null ? "{}" : impactOutput.toString()).append("\n\n");
        sb.append("Acceptance criteria:\n");
        if (acs == null || acs.isEmpty()) {
            sb.append("(none provided)\n");
        } else {
            for (String c : acs) sb.append("- ").append(c).append("\n");
        }
        sb.append("\nReturn the JSON object now.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw,
                                          Map<String, Object> impactOutput,
                                          List<String> acs,
                                          String model) {
        if (raw == null) {
            return fallback.draft(impactOutput, acs, model);
        }
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> biz = asMap(raw.get("biz_plan"));
        Map<String, Object> normBiz = new LinkedHashMap<>();
        normBiz.put("steps", asList(biz.get("steps")));
        normBiz.put("data_flow", biz.get("data_flow") instanceof String s ? s : "");
        out.put("biz_plan", normBiz);

        Map<String, Object> tech = asMap(raw.get("tech_plan"));
        Map<String, Object> normTech = new LinkedHashMap<>();
        normTech.put("files", asList(tech.get("files")));
        normTech.put("new_apis", asList(tech.get("new_apis")));
        normTech.put("schema_changes", asList(tech.get("schema_changes")));
        out.put("tech_plan", normTech);

        if (raw.containsKey("ui_plan")) {
            Map<String, Object> ui = asMap(raw.get("ui_plan"));
            Map<String, Object> normUi = new LinkedHashMap<>();
            normUi.put("screens", asList(ui.get("screens")));
            normUi.put("interactions", asList(ui.get("interactions")));
            out.put("ui_plan", normUi);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private List<Object> asList(Object o) {
        return o instanceof List<?> l ? List.copyOf(l) : List.of();
    }
}
