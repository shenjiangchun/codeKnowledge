package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ClarifyLlmClient;
import com.huawei.hisi.ram.nodes.CodeContextItem;
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

    // ──────────────── System prompts ────────────────

    /** Original prompt: used when NO code context is available. */
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

    /**
     * Enhanced prompt: used when code context IS available from semantic search.
     * Key addition: Step 0 instructs the LLM to read code context FIRST and
     * answer technical questions itself, only asking the user about genuinely
     * ambiguous business intent.
     */
    private static final String SYSTEM_PROMPT_WITH_CODE_CONTEXT = """
            You are a senior product analyst with access to the project's actual
            codebase. Semantic search results from the project's knowledge graph
            are provided below the user's request.

            ## Step 0 — Code-aware analysis (MANDATORY when code context is provided)

            Before deciding whether to ask the user any questions, you MUST:
            1. READ the provided code snippets carefully — class names, method
               signatures, file paths, and descriptions.
            2. ANSWER YOURSELF any technical questions that the code makes obvious:
               - Which module/package handles this feature? → Look at the code paths.
               - What is the current implementation? → Read the method bodies.
               - What interfaces/classes are involved? → Check class names and
                 signatures.
               - What is the technology stack for this area? → Infer from imports
                 and patterns.
            3. Use the code context to fill in target_modules and project_paths
               PRECISELY — reference actual packages and classes found in the code.
            4. ONLY ask the user questions about things that CANNOT be determined
               from the code:
               - Ambiguous business intent (the user wants X, but X could mean
                 two different things)
               - Non-functional requirements (performance targets, compatibility)
               - Priority or scope decisions (do all cases or just the main one?)
               - Design preferences when multiple valid approaches exist

            ## Step 1 — Vagueness check (STRICT — but code-informed)

            A request needs clarification ONLY when:
            - The user's BUSINESS INTENT is ambiguous (not just technically
              underspecified — technical details are answerable from the code)
            - No acceptance criteria can be inferred even with code context
            - The user's request could affect multiple unrelated systems and
              scope is unclear
            - Non-functional requirements matter but are unspecified

            A request does NOT need clarification when:
            - Technical details (which class, which method, which module) are
              answerable from the code context
            - The modification scope is clear from the code structure
            - The "how" is a straightforward engineering decision

            If clarification IS needed, set "needs_clarification": true and
            provide 2-5 targeted questions. Each question must:
            - Be about something the CODE CANNOT answer
            - Be specific and actionable
            - Be in 简体中文

            ## Step 1b — Multi-round evaluation (when prior Q&A rounds exist)

            When previous clarification rounds are provided, you MUST:
            1. Read ALL prior Q&A carefully — incorporate every answer.
            2. Re-evaluate from scratch whether the requirement NOW meets ALL
               clarity criteria above.
            3. If STILL unclear: set needs_clarification=true and ask NEW
               questions only — never repeat already-answered questions.
            4. If NOW clear: set needs_clarification=false and fill ALL
               structured fields completely.

            ## Step 2 — Structured extraction (code-grounded)

            Fill structured fields using BOTH the user's words AND the code
            context:
            - intent: precise summary grounded in actual code structure
            - project_paths: from projectHints or code context file paths
            - target_modules: MUST reference actual packages/classes found in
              the code context
            - acceptance_criteria: concrete, testable, informed by current
              implementation
            - constraints: infer from code patterns (e.g. if code uses
              transactions, note atomicity constraint)

            ## Output schema (JSON only, no prose, no markdown fences):

            {
              "needs_clarification": true | false,
              "clarify_questions": ["<question in 简体中文>", ...],
              "intent": "<one short sentence summarising the request>",
              "project_paths": ["<path>", ...],
              "acceptance_criteria": ["<testable AC>", ...],
              "target_modules": ["<actual package/class from code context>", ...],
              "constraints": { "must": [...], "must_not": [...] },
              "code_analysis_summary": "<2-3 sentences in 简体中文: what you learned from the code>"
            }

            Rules:
            - needs_clarification: MUST be true only when BUSINESS INTENT is
              genuinely ambiguous.
            - clarify_questions: only questions the code CANNOT answer; 2-5
              when needed, empty array when false.
            - intent: 1 short sentence, never empty.
            - project_paths: from projectHints or code context file paths; do
              NOT invent paths.
            - target_modules: MUST reference real classes/packages from the
              provided code context — do NOT guess.
            - acceptance_criteria: only criteria you can CONFIDENTLY derive.
            - constraints: optional must/must_not lists.
            - code_analysis_summary: a brief summary of what you determined
              from reading the code. In 简体中文.

            Language requirement (MANDATORY):
            - All natural-language string values MUST be in 简体中文.
            - Keep JSON keys, file paths, package names in original form.
            """;

    // ──────────────── Dependencies ────────────────

    private final RamClaudeJsonClient claude;
    private final StubClarifyLlmClient fallback;

    public ClaudeClarifyLlmClient(RamClaudeJsonClient claude, StubClarifyLlmClient fallback) {
        this.claude = claude;
        this.fallback = fallback;
    }

    // ──────────────── Public API ────────────────

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractRequirements(String userRequest, Map<String, Object> hints) {
        return extractRequirements(userRequest, hints, List.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractRequirements(String userRequest,
                                                    Map<String, Object> hints,
                                                    List<CodeContextItem> codeContext) {
        List<String> hintPaths = extractProjectPaths(hints);
        boolean hasCodeContext = codeContext != null && !codeContext.isEmpty();
        log.info("[RAM][ClaudeClarifyLlmClient] extractRequirements userRequest.len={} hintPaths={} codeContext.size={}",
                userRequest == null ? 0 : userRequest.length(), hintPaths,
                hasCodeContext ? codeContext.size() : 0);

        if (!claude.isAvailable()) {
            log.error("[RAM][ClaudeClarifyLlmClient] Claude UNAVAILABLE (anthropic.api-key empty) — falling back to Stub. THIS IS WHY OUTPUT IS POOR.");
            return fallback.extractRequirements(userRequest, hints);
        }

        String systemPrompt = hasCodeContext
                ? SYSTEM_PROMPT_WITH_CODE_CONTEXT
                : SYSTEM_PROMPT;

        String userPrompt = buildUserPrompt(userRequest, hintPaths, hints, codeContext);
        try {
            // Use 4096 maxTokens when code context is present (larger prompt + richer output)
            int maxTokens = hasCodeContext ? 4096 : 2048;
            Map<String, Object> raw = claude.callJson(
                    systemPrompt, userPrompt,
                    new SendOptions(claude.defaultModel(), maxTokens, 0.2, null));
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

    // ──────────────── Prompt building ────────────────

    private String buildUserPrompt(String userRequest, List<String> hintPaths,
                                    Map<String, Object> hints,
                                    List<CodeContextItem> codeContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("User request:\n").append(userRequest == null ? "" : userRequest).append("\n\n");

        if (!hintPaths.isEmpty()) {
            sb.append("Caller-provided projectHints:\n");
            for (String p : hintPaths) sb.append("- ").append(p).append("\n");
            sb.append("\n");
        }

        // ★ Code context from semantic search
        if (codeContext != null && !codeContext.isEmpty()) {
            sb.append("=== Project code context (from semantic search) ===\n\n");
            int idx = 0;
            for (CodeContextItem item : codeContext) {
                idx++;
                sb.append("### Code snippet ").append(idx).append("\n");
                sb.append("- Class: ").append(item.className()).append("\n");
                sb.append("- Method: ").append(item.methodName()).append("\n");
                if (item.filePath() != null && !item.filePath().isBlank()) {
                    sb.append("- File: ").append(item.filePath()).append("\n");
                }
                if (item.description() != null && !item.description().isBlank()) {
                    sb.append("- Description: ").append(item.description()).append("\n");
                }
                if (item.methodBody() != null && !item.methodBody().isBlank()) {
                    sb.append("```java\n").append(item.methodBody()).append("\n```\n");
                }
                sb.append("\n");
            }
            sb.append("=== End of code context ===\n\n");
            sb.append("IMPORTANT: Use the above code snippets to understand the project's ")
              .append("current structure. Do NOT ask the user about things you can determine ")
              .append("from this code. Only ask about genuinely ambiguous business intent.\n\n");
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

    // ──────────────── Normalization ────────────────

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
        // Preserve code_analysis_summary if present (new field from enhanced prompt)
        if (raw != null && raw.get("code_analysis_summary") instanceof String summary
                && !summary.isBlank()) {
            out.put("code_analysis_summary", summary);
        }
        return out;
    }

    // ──────────────── Utilities ────────────────

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
