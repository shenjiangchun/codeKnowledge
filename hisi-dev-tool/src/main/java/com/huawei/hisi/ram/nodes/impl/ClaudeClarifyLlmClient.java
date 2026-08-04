package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.agent.tools.AgentTools;
import com.huawei.hisi.ram.nodes.ClarifyLlmClient;
import com.huawei.hisi.ram.nodes.CodeContextItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatClient-backed {@link ClarifyLlmClient} — replaces raw
 * {@link RamClaudeJsonClient} calls with Spring AI {@link ChatClient}.
 *
 * <p>Prompt selection and normalization logic unchanged.
 * LLM call replaced with {@code ChatClient.prompt().system(...).user(...).call().entity(Map.class)}.
 * Tool-use mode uses Spring AI 1.1.8 automatic tool calling via
 * {@link AgentTools}.
 */
@Slf4j
@Primary
@Component
public class ClaudeClarifyLlmClient implements ClarifyLlmClient {

    // ── System prompts (unchanged business logic) ──

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

            Language requirement (MANDATORY):
            - All natural-language string values MUST be written in 简体中文.
            - Keep JSON keys, file paths, package names, and module identifiers
              in their original form (do NOT translate code identifiers).
            """;

    private static final String SYSTEM_PROMPT_WITH_CODE_CONTEXT = """
            You are a senior product analyst with access to the project's actual
            codebase. Semantic search results from the project's knowledge graph
            are provided below the user's request.

            ## Step 0 — Code-aware analysis (MANDATORY)

            Before deciding whether to ask the user any questions, you MUST:
            1. READ the provided code snippets carefully.
            2. ANSWER YOURSELF any technical/implementation questions.
            3. Use the code context to fill in target_modules and project_paths PRECISELY.
            4. Frame your understanding as: "现状是 X，用户想要 Y，gap 是 Z"

            ## FORBIDDEN QUESTION PATTERNS — NEVER ask these:
            You must NEVER ask the user technical questions that code context answers —
            e.g., "当前使用什么技术/框架/库？" or "XXService 的具体实现是什么？"

            ## ALLOWED QUESTION PATTERNS — only ask these:
            You may ONLY ask the user about BUSINESS DECISIONS, SCOPE CHOICES,
            PRIORITY, NON-FUNCTIONAL TARGETS, and DESIGN TRADE-OFFS.

            ## Output schema (JSON only, no prose, no markdown fences):
            {
              "needs_clarification": true | false,
              "clarify_questions": ["<decision question in 简体中文>", ...],
              "intent": "<one short sentence>",
              "project_paths": ["<path>", ...],
              "acceptance_criteria": ["<testable AC>", ...],
              "target_modules": ["<actual package/class from code context>", ...],
              "target_methods": ["<fully.qualified.ClassName#methodName>", ...],
              "constraints": { "must": [...], "must_not": [...] },
              "code_analysis_summary": "<现状/需求/差距 in 简体中文>"
            }
            Language: All natural-language values in 简体中文.
            """;

    private static final String SYSTEM_PROMPT_WITH_TOOLS = """
            You are a senior product analyst with DIRECT ACCESS to the project's
            codebase via the provided tools. You can search code, read source files,
            and trace call chains to understand the current implementation.

            ## MANDATORY Workflow
            1. First search: use hybrid_search to find relevant code.
            2. Deep-dive: use load_method_bodies on top results.
            3. Trace: use callees_tree / root_entries if needed.
            4. Config search: use grep_project or read_file.
            5. Summarize: "现状：X。需求：Y。差距：Z。"
            6. Decide: only ask HUMAN JUDGMENT questions.

            Tool budget: 5-7 calls max. After gathering enough context, output JSON immediately.

            ## Output schema (PURE JSON only):
            {
              "needs_clarification": true | false,
              "clarify_questions": ["<decision question in 简体中文>", ...],
              "intent": "<one short sentence>",
              "project_paths": ["<path>", ...],
              "acceptance_criteria": ["<testable AC>", ...],
              "target_modules": ["<actual package/class found via tools>", ...],
              "target_methods": ["<fully.qualified.ClassName#methodName>", ...],
              "constraints": { "must": [...], "must_not": [...] },
              "code_analysis_summary": "<现状/需求/差距 in 简体中文>"
            }
            Language: All natural-language values in 简体中文.
            """;

    private final ChatClient agentChatClient;
    private final StubClarifyLlmClient fallback;
    private final AgentTools agentTools;

    public ClaudeClarifyLlmClient(ChatClient agentChatClient,
                                   StubClarifyLlmClient fallback,
                                   @Autowired(required = false) AgentTools agentTools) {
        this.agentChatClient = agentChatClient;
        this.fallback = fallback;
        this.agentTools = agentTools;
    }

    @Override
    public Map<String, Object> extractRequirements(String userRequest, Map<String, Object> hints) {
        return extractRequirements(userRequest, hints, List.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractRequirements(String userRequest,
                                                    Map<String, Object> hints,
                                                    List<CodeContextItem> codeContext) {
        List<String> hintPaths = extractProjectPaths(hints);
        log.info("[RAM][ClaudeClarifyLlmClient] extractRequirements userRequest.len={} hintPaths={}",
                userRequest == null ? 0 : userRequest.length(), hintPaths);

        boolean hasClarifyHistory = hints != null
                && hints.get("clarify_history") instanceof List<?> histList && !histList.isEmpty();
        boolean useTools = agentTools != null && !hintPaths.isEmpty() && !hasClarifyHistory;
        String projectPath = useTools ? hintPaths.get(0) : null;

        String systemPrompt;
        if (useTools) {
            systemPrompt = SYSTEM_PROMPT_WITH_TOOLS;
        } else if (codeContext != null && !codeContext.isEmpty()) {
            systemPrompt = SYSTEM_PROMPT_WITH_CODE_CONTEXT;
        } else {
            systemPrompt = SYSTEM_PROMPT;
        }

        String userPrompt = buildUserPrompt(userRequest, hintPaths, hints, codeContext);
        try {
            Map<String, Object> raw;
            if (useTools) {
                raw = agentChatClient.prompt()
                        .system(systemPrompt).user(userPrompt)
                        .toolContext(Map.of("projectPath", projectPath))
                        .tools(agentTools)
                        .call()
                        .entity(new ParameterizedTypeReference<Map<String, Object>>() {});
            } else {
                raw = agentChatClient.prompt()
                        .system(systemPrompt).user(userPrompt)
                        .call()
                        .entity(new ParameterizedTypeReference<Map<String, Object>>() {});
            }

            log.info("[RAM][ClaudeClarifyLlmClient] returned keys={} needs_clarification={}",
                    raw == null ? "null" : raw.keySet(),
                    raw == null ? null : raw.get("needs_clarification"));
            return normalize(raw, userRequest, hintPaths);
        } catch (Exception ex) {
            log.error("[RAM][ClaudeClarifyLlmClient] call FAILED — falling back to Stub. err={}", ex.toString(), ex);
            return fallback.extractRequirements(userRequest, hints);
        }
    }

    // ── Prompt building (unchanged) ──

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
        if (codeContext != null && !codeContext.isEmpty()) {
            sb.append("=== Project code context (from semantic search) ===\n\n");
            int idx = 0;
            for (CodeContextItem item : codeContext) {
                idx++;
                sb.append("### Code snippet ").append(idx).append("\n");
                sb.append("- Class: ").append(item.className()).append("\n")
                  .append("- Method: ").append(item.methodName()).append("\n");
                if (item.filePath() != null && !item.filePath().isBlank())
                    sb.append("- File: ").append(item.filePath()).append("\n");
                if (item.description() != null && !item.description().isBlank())
                    sb.append("- Description: ").append(item.description()).append("\n");
                if (item.methodBody() != null && !item.methodBody().isBlank())
                    sb.append("```java\n").append(item.methodBody()).append("\n```\n");
                sb.append("\n");
            }
            sb.append("=== End of code context ===\n\n");
            sb.append("IMPORTANT: Use the above code snippets to understand the project's current state. NEVER ask 'what technology/library/framework is this using?' — determine that yourself.\n\n");
        }
        // Multi-round clarify history
        Object clarifyHistory = hints == null ? null : hints.get("clarify_history");
        if (clarifyHistory instanceof List<?> rounds && !rounds.isEmpty()) {
            sb.append("=== Previous clarification rounds ===\n\n");
            int roundNum = 0;
            for (Object roundObj : rounds) {
                if (!(roundObj instanceof Map<?, ?> round)) continue;
                roundNum++;
                sb.append("--- Round ").append(roundNum).append(" ---\n");
                Object qs = round.get("questions");
                if (qs instanceof List<?> questions)
                    for (Object q : questions) sb.append("Q: ").append(q).append("\n");
                Object ans = round.get("answers");
                if (ans instanceof Map<?, ?> ansMap)
                    for (var entry : ansMap.entrySet())
                        sb.append("Q: ").append(entry.getKey()).append("\nA: ").append(entry.getValue()).append("\n");
                sb.append("\n");
            }
            sb.append("=== End of clarification history ===\n\n");
            sb.append("OUTPUT THE JSON NOW. Do NOT call any tools. Do NOT explore any code.\n\n");
        }
        sb.append("Return the JSON object now.");
        return sb.toString();
    }

    // ── Normalization (unchanged) ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw, String userRequest, List<String> hintPaths) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean needsClarification = raw != null && Boolean.TRUE.equals(raw.get("needs_clarification"));
        List<String> clarifyQuestions = asStringList(raw == null ? null : raw.get("clarify_questions"));
        out.put("needs_clarification", needsClarification && !clarifyQuestions.isEmpty());
        out.put("clarify_questions", clarifyQuestions);
        Object intent = raw == null ? null : raw.get("intent");
        out.put("intent", (intent instanceof String s && !s.isBlank()) ? s : (userRequest == null ? "" : userRequest));
        List<String> llmPaths = asStringList(raw == null ? null : raw.get("project_paths"));
        List<String> mergedPaths = new java.util.ArrayList<>(hintPaths);
        for (String p : llmPaths) { if (!mergedPaths.contains(p)) mergedPaths.add(p); }
        out.put("project_paths", mergedPaths);
        out.put("projectHints", hintPaths);
        out.put("acceptance_criteria", asStringList(raw == null ? null : raw.get("acceptance_criteria")));
        if (raw != null && raw.get("target_modules") != null)
            out.put("target_modules", asStringList(raw.get("target_modules")));
        if (raw != null && raw.get("target_methods") != null)
            out.put("target_methods", asStringList(raw.get("target_methods")));
        if (raw != null && raw.get("constraints") instanceof Map<?, ?> c)
            out.put("constraints", c);
        if (raw != null && raw.get("code_analysis_summary") instanceof String summary && !summary.isBlank())
            out.put("code_analysis_summary", summary);
        return out;
    }

    private List<String> asStringList(Object o) {
        if (o instanceof List<?> list) return list.stream().filter(x -> x instanceof String).map(x -> (String) x).toList();
        return List.of();
    }

    private List<String> extractProjectPaths(Map<String, Object> hints) {
        if (hints == null) return List.of();
        Object raw = hints.get("projectHints");
        if (raw instanceof List<?> list)
            return list.stream().filter(o -> o instanceof String).map(o -> (String) o).toList();
        return List.of();
    }
}
