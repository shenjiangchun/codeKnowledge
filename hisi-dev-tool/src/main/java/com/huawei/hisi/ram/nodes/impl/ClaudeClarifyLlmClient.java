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
            You are a senior product analyst. Your job is to decide whether a
            developer's request is CLEAR ENOUGH to proceed, or whether you
            need to ask clarifying questions first.

            ## Step 1 — Vagueness check (STRICT — err on the side of asking)

            A request is UNCLEAR and needs clarification when ANY of these apply:
            - The user's intent could be interpreted in 2+ materially different ways
            - No acceptance criteria can be inferred (what does "done" look like?)
            - Critical scope is missing (which module? which behaviour? which users?)
            - Technical approach is ambiguous (frontend vs backend? API vs batch?)
            - Non-functional requirements are unspecified when they clearly matter
              (performance? concurrency? backwards compatibility?)

            If the request IS vague, set "needs_clarification": true and provide
            2-5 targeted questions in "clarify_questions". Each question must be:
            - Specific (not "请提供更多细节")
            - Actionable (the answer directly fills a gap in the requirement)
            - In 简体中文

            ## Step 1b — Multi-round evaluation (when prior Q&A rounds exist)

            When previous clarification rounds are provided, you MUST:
            1. Read ALL prior Q&A carefully — incorporate every answer into your
               understanding of the requirement.
            2. Re-evaluate from scratch whether the requirement NOW meets ALL five
               clarity criteria above.
            3. If STILL unclear: set needs_clarification=true and ask NEW questions
               only — never repeat questions the user already answered.
            4. If NOW clear: set needs_clarification=false and fill ALL structured
               fields completely using the accumulated understanding.

            Key principle: do NOT lower your bar just because the user has already
            answered questions. Keep asking until the requirement is genuinely
            unambiguous and actionable. Typical requirements need 1-3 rounds.

            ## Step 2 — Structured extraction

            Regardless of whether clarification is needed, ALSO fill in the
            structured fields below with whatever you CAN confidently extract.
            For fields you genuinely cannot determine, use empty arrays / empty
            strings — do NOT invent or guess.

            ## Output schema (JSON only, no prose, no markdown fences):

            {
              "needs_clarification": true | false,
              "clarify_questions": ["<question in 简体中文>", ...],
              "intent": "<one short sentence summarising the request>",
              "project_paths": ["<path hint>", ...],
              "acceptance_criteria": ["<testable AC>", ...],
              "target_modules": ["<module/package name>", ...],
              "constraints": { "must": [...], "must_not": [...] }
            }

            Rules:
            - needs_clarification: MUST be true when the request is vague.
            - clarify_questions: 2-5 questions when needs_clarification is true;
              empty array when false.
            - intent: 1 short sentence, never empty. Summarise what you understood.
            - project_paths: only paths the user explicitly mentioned or from
              caller-provided projectHints; do NOT invent paths.
            - acceptance_criteria: only criteria you can CONFIDENTLY derive from
              the user's words. If the user was vague, this may be empty — that's
              fine, the user will fill it in after answering your questions.
            - target_modules: package or feature module names, may be empty.
            - constraints: optional must/must_not lists, may be empty arrays.

            Language requirement (MANDATORY):
            - All natural-language string values (intent, clarify_questions,
              acceptance_criteria, constraints.must / constraints.must_not)
              MUST be written in 简体中文 (Simplified Chinese).
            - Keep JSON keys, file paths, package names, and module identifiers
              in their original form (do NOT translate code identifiers).
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

        // If the user has already answered clarifying questions, include those
        // answers in the prompt so Claude can produce a complete output.
        String userPrompt = buildUserPrompt(userRequest, hintPaths, hints);
        try {
            Map<String, Object> raw = claude.callJson(
                    SYSTEM_PROMPT, userPrompt,
                    new SendOptions(claude.defaultModel(), 2048, 0.2, null));
            log.info("[RAM][ClaudeClarifyLlmClient] Claude returned keys={} needs_clarification={} questions={} acs={} intent.len={}",
                    raw == null ? "null" : raw.keySet(),
                    raw == null ? null : raw.get("needs_clarification"),
                    raw == null ? 0 : asStringList(raw.get("clarify_questions")).size(),
                    raw == null ? 0 : asStringList(raw.get("acceptance_criteria")).size(),
                    raw == null || !(raw.get("intent") instanceof String s) ? 0 : s.length());
            return normalize(raw, userRequest, hintPaths);
        } catch (Exception ex) {
            log.error("[RAM][ClaudeClarifyLlmClient] Claude call FAILED — falling back to Stub. err={}", ex.toString(), ex);
            return fallback.extractRequirements(userRequest, hints);
        }
    }

    private String buildUserPrompt(String userRequest, List<String> hintPaths, Map<String, Object> hints) {
        StringBuilder sb = new StringBuilder();
        sb.append("User request:\n").append(userRequest == null ? "" : userRequest).append("\n\n");
        if (!hintPaths.isEmpty()) {
            sb.append("Caller-provided projectHints:\n");
            for (String p : hintPaths) sb.append("- ").append(p).append("\n");
            sb.append("\n");
        }

        // Multi-round clarify history: list of {questions: [...], answers: {...}} rounds
        Object clarifyHistory = hints == null ? null : hints.get("clarify_history");
        if (clarifyHistory instanceof List<?> rounds && !rounds.isEmpty()) {
            sb.append("=== Previous clarification rounds ===\n\n");
            int roundNum = 0;
            for (Object roundObj : rounds) {
                if (!(roundObj instanceof Map<?, ?> round)) continue;
                roundNum++;
                sb.append("--- Round ").append(roundNum).append(" ---\n");

                Object qs = round.get("questions");
                if (qs instanceof List<?> questions) {
                    for (Object q : questions) {
                        sb.append("Q: ").append(q).append("\n");
                    }
                }
                Object ans = round.get("answers");
                if (ans instanceof Map<?, ?> ansMap) {
                    for (var entry : ansMap.entrySet()) {
                        sb.append("Q: ").append(entry.getKey()).append("\n");
                        sb.append("A: ").append(entry.getValue()).append("\n");
                    }
                }
                sb.append("\n");
            }
            sb.append("=== End of clarification history ===\n\n");
            sb.append("IMPORTANT: The user has answered ").append(roundNum)
              .append(" round(s) of clarifying questions.\n");
            sb.append("Re-evaluate the requirement with ALL the above answers incorporated.\n");
            sb.append("If the requirement is NOW clear enough (intent unambiguous, ")
              .append("acceptance criteria derivable, scope defined), ")
              .append("set needs_clarification to false and fill in the structured fields completely.\n");
            sb.append("If there are STILL remaining ambiguities that the answers did NOT resolve, ")
              .append("set needs_clarification to true and ask NEW questions (do NOT repeat already-answered questions).\n\n");
        } else {
            // Legacy single-round fallback: check old "answers" key
            Object answers = hints == null ? null : hints.get("answers");
            if (answers instanceof Map<?, ?> ansMap && !ansMap.isEmpty()) {
                sb.append("The user has already answered previous clarifying questions:\n");
                for (var entry : ansMap.entrySet()) {
                    sb.append("Q: ").append(entry.getKey()).append("\n");
                    sb.append("A: ").append(entry.getValue()).append("\n\n");
                }
                sb.append("Use these answers to fill in the structured fields. ")
                  .append("Set needs_clarification to false unless there are STILL ")
                  .append("remaining ambiguities after incorporating the answers.\n\n");
            }
        }

        sb.append("Return the JSON object now.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw, String userRequest, List<String> hintPaths) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Preserve clarification flag and questions for ClarifyNode to act on
        boolean needsClarification = raw != null
                && Boolean.TRUE.equals(raw.get("needs_clarification"));
        List<String> clarifyQuestions = asStringList(raw == null ? null : raw.get("clarify_questions"));
        out.put("needs_clarification", needsClarification && !clarifyQuestions.isEmpty());
        out.put("clarify_questions", clarifyQuestions);

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
