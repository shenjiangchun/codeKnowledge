package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ClarifyLlmClient;
import com.huawei.hisi.ram.nodes.CodeContextItem;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
            - target_methods: specific methods (fully.qualified.ClassName#methodName format) that need code changes to implement this requirement, may be empty. MUST use fully-qualified class name when known; short class name is acceptable only if the full name is unknown.
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
     * ambiguous business intent or design DECISIONS.
     */
    private static final String SYSTEM_PROMPT_WITH_CODE_CONTEXT = """
            You are a senior product analyst with access to the project's actual
            codebase. Semantic search results from the project's knowledge graph
            are provided below the user's request.

            ## Step 0 — Code-aware analysis (MANDATORY)

            Before deciding whether to ask the user any questions, you MUST:
            1. READ the provided code snippets carefully — class names, method
               signatures, file paths, and descriptions.
            2. ANSWER YOURSELF any technical/implementation questions:
               - Which module/package handles this feature? → Look at the code.
               - What is the current implementation? → Read the method bodies.
               - What interfaces/classes are involved? → Check class names.
               - What is the technology stack? → Infer from imports and patterns.
               - If you see a service call (e.g. vectorGenerationService.generate())
                 but don't see the implementation, state in code_analysis_summary
                 "当前使用 XXXService 进行 YYY 操作" and MOVE ON. Do NOT ask the
                 user what technology it uses — that is an implementation detail
                 you can discover from the code or assume as-is.
            3. Use the code context to fill in target_modules and project_paths
               PRECISELY — reference actual packages and classes found in the code.
            4. Frame your understanding as: "现状是 X，用户想要 Y，gap 是 Z"

            ## FORBIDDEN QUESTION PATTERNS — NEVER ask these:

            You must NEVER ask the user:
            - "当前使用什么技术/框架/库？" — look at the code yourself
            - "XXService 的具体实现是什么？" — that's your job to infer or state as-is
            - "是否有更换/升级/迁移 XXX 的意愿？" — unless the user's request
              EXPLICITLY mentions migration
            - "代码中看到 XXX 调用，能否确认 XXX？" — confirm it yourself from code
            - Any question about HOW the current code works
            - Any question about WHAT technology is being used
            - Any question phrased as "代码中只看到 XXX，未看到 YYY"

            These are ALL implementation details that you must resolve by reading
            code, inferring from patterns, or accepting the current state as-is.

            ## ALLOWED QUESTION PATTERNS — only ask these:

            You may ONLY ask the user about:
            - BUSINESS DECISIONS: "需求是 A 还是 B？" (when genuinely ambiguous)
            - SCOPE CHOICES: "只改主流程还是包括边缘情况？"
            - PRIORITY: "先做核心功能还是同时处理错误场景？"
            - NON-FUNCTIONAL TARGETS: "性能要求是什么？并发量预期？"
            - DESIGN TRADE-OFFS: "方案A更简单但有限制，方案B更灵活但复杂，选哪个？"

            The user should only see questions that require HUMAN JUDGMENT to answer,
            not questions that require CODE READING to answer.

            ## Step 1 — Vagueness check (code-informed)

            A request needs clarification ONLY when:
            - The user's BUSINESS INTENT is ambiguous (could mean 2+ things)
            - The SCOPE requires a human decision (all cases vs. main path)
            - Non-functional requirements matter but are unspecified
            - Multiple valid design approaches exist and user preference matters

            A request does NOT need clarification when:
            - Technical details are answerable from the code context
            - The modification scope is clear from the code structure
            - The "how" is a straightforward engineering decision
            - You can see what exists and what the user wants to change

            If clarification IS needed, set "needs_clarification": true and
            provide 2-5 targeted questions. Each question must:
            - Require HUMAN JUDGMENT (not code reading) to answer
            - Be a DECISION question, not a "what is?" question
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
            - code_analysis_summary: "现状：XXX。需求：YYY。差距：ZZZ。"
              Summarize what you learned from code, the user's intent, and what
              needs to change.

            ## Output schema (JSON only, no prose, no markdown fences):

            {
              "needs_clarification": true | false,
              "clarify_questions": ["<decision question in 简体中文>", ...],
              "intent": "<one short sentence summarising the request>",
              "project_paths": ["<path>", ...],
              "acceptance_criteria": ["<testable AC>", ...],
              "target_modules": ["<actual package/class from code context>", ...],
              "target_methods": ["<fully.qualified.ClassName#methodName from code context>", ...],
              "constraints": { "must": [...], "must_not": [...] },
              "code_analysis_summary": "<现状/需求/差距 in 简体中文>"
            }

            Rules:
            - needs_clarification: MUST be true only when a HUMAN DECISION is
              needed. False when only engineering decisions remain.
            - clarify_questions: only DECISION questions (not "what is?" questions);
              2-5 when needed, empty array when false.
            - intent: 1 short sentence, never empty.
            - project_paths: from projectHints or code context file paths; do
              NOT invent paths.
            - target_modules: MUST reference real classes/packages from the
              provided code context — do NOT guess.
            - target_methods: MUST reference actual methods found in the code context that need modification — use fully.qualified.ClassName#methodName format. Use full package path when available.
            - acceptance_criteria: only criteria you can CONFIDENTLY derive.
            - constraints: optional must/must_not lists.
            - code_analysis_summary: MANDATORY. Format: "现状：...。需求：...。差距：..."

            Language requirement (MANDATORY):
            - All natural-language string values MUST be in 简体中文.
            - Keep JSON keys, file paths, package names in original form.
            """;

    /**
     * Tool-use prompt: used when KG tools are available. The LLM is instructed
     * to use tools to explore the codebase AUTONOMOUSLY, only asking the user
     * about genuine business decisions.
     */
    private static final String SYSTEM_PROMPT_WITH_TOOLS = """
            You are a senior product analyst with DIRECT ACCESS to the project's
            codebase via the provided tools. You can search code, read source files,
            and trace call chains to understand the current implementation.

            ## Your Tools

            You have access to code exploration tools:
            - hybrid_search: 语义搜索项目代码（输入自然语言查询）
            - load_method_bodies: 查看方法源码（输入 nodeId 列表，从 hybrid_search 获取）
            - callees_tree: 查看方法的下游调用链
            - root_entries: 查看方法的上游入口（谁调用了它）
            - entry_points: 列出系统入口点（Controller、定时任务等）
            - grep_project: 文本搜索项目文件（配置、注解等）
            - read_file: 读取项目中的文件
            - list_files: 列出目录结构

            ## MANDATORY Workflow

            1. **先搜索再判断**：收到用户需求后，MUST use hybrid_search 搜索相关代码。
               不要凭猜测判断需求是否清晰 — 你必须先了解代码现状。
            2. **深入关键方法**：对搜索到的关键方法，用 load_method_bodies 查看源码。
            3. **追踪调用链**：如果需要理解影响范围，用 callees_tree / root_entries。
            4. **查配置**：如果涉及配置/依赖，用 grep_project 或 read_file。
            5. **总结现状**：充分了解代码后，总结 "现状：X。需求：Y。差距：Z。"
            6. **决定是否需要澄清**：只有需要用户做 BUSINESS DECISION 的问题才要问。

            ## CRITICAL: Tool Usage Budget

            You have a STRICT budget of 5-7 tool calls total. Plan your exploration
            efficiently:
            - Round 1: hybrid_search to find relevant code (1 call)
            - Round 2: load_method_bodies on top 2-3 results (1 call)
            - Round 3-4: At most 2-3 more calls (callees_tree, grep, read_file) ONLY
              if truly needed
            - Round 5: Output your final JSON answer

            After at most 7 tool calls, you MUST stop calling tools and produce your
            final JSON output. Do NOT exhaustively explore the entire codebase —
            focus on the code most relevant to the user's request.
            When you have enough context, OUTPUT THE JSON IMMEDIATELY.

            ## FORBIDDEN — NEVER ask these:

            - "当前使用什么技术/框架/库？" — 自己用工具查
            - "XXService 的具体实现是什么？" — 用 hybrid_search + load_method_bodies 查
            - "代码中看到 XXX，能否确认？" — 自己确认
            - 任何关于代码 HOW / WHAT 的问题 — 用工具回答自己

            ## ALLOWED — only ask these:

            - BUSINESS DECISIONS: "需求是 A 还是 B？"
            - SCOPE CHOICES: "只改主流程还是包括边缘情况？"
            - PRIORITY: "先做核心功能还是同时处理错误场景？"
            - NON-FUNCTIONAL: "性能要求？并发量？"
            - TRADE-OFFS: "方案A简单有限制 vs 方案B灵活但复杂，选哪个？"

            ## Multi-round evaluation

            When previous clarification rounds are provided:
            1. Read ALL prior Q&A — incorporate every answer.
            2. Re-evaluate whether requirement is now clear.
            3. If still unclear: ask NEW questions only (never repeat).
            4. If now clear: fill all fields completely.

            ## Output schema (PURE JSON only — no prose, no markdown fences, no markdown tables, no text before or after):

            Your final response MUST be a single JSON object and NOTHING ELSE.
            Do NOT start with "I now have..." or "Let me compile..." or any text.
            Do NOT wrap JSON in ```json``` code blocks.
            Any multi-line string values must use \\n for line breaks, not literal newlines.
            Output ONLY the raw JSON starting with { and ending with }.

            {
              "needs_clarification": true | false,
              "clarify_questions": ["<decision question in 简体中文>", ...],
              "intent": "<one short sentence>",
              "project_paths": ["<path>", ...],
              "acceptance_criteria": ["<testable AC>", ...],
              "target_modules": ["<actual package/class found via tools>", ...],
              "target_methods": ["<fully.qualified.ClassName#methodName found via tools>", ...],
              "constraints": { "must": [...], "must_not": [...] },
              "code_analysis_summary": "<现状/需求/差距 in 简体中文>"
            }

            Rules:
            - needs_clarification: true ONLY when HUMAN DECISION needed.
            - clarify_questions: DECISION questions only; empty when false.
            - target_modules: MUST reference real classes/packages you found
              via tools — never guess.
            - target_methods: specific methods you found via tools that need modification — use fully.qualified.ClassName#methodName format. Use full package path when available.
            - code_analysis_summary: MANDATORY — summarize what you discovered.

            Language: All natural-language values in 简体中文.
            Keep JSON keys, paths, package names in original form.
            """;

    // ──────────────── Dependencies ────────────────

    private final RamClaudeJsonClient claude;
    private final StubClarifyLlmClient fallback;
    private final KgToolRegistry toolRegistry; // nullable

    public ClaudeClarifyLlmClient(RamClaudeJsonClient claude,
                                   StubClarifyLlmClient fallback,
                                   @Autowired(required = false) KgToolRegistry toolRegistry) {
        this.claude = claude;
        this.fallback = fallback;
        this.toolRegistry = toolRegistry;
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
        log.info("[RAM][ClaudeClarifyLlmClient] extractRequirements userRequest.len={} hintPaths={}",
                userRequest == null ? 0 : userRequest.length(), hintPaths);

        if (!claude.isAvailable()) {
            log.error("[RAM][ClaudeClarifyLlmClient] Claude UNAVAILABLE (anthropic.api-key empty) — falling back to Stub.");
            return fallback.extractRequirements(userRequest, hints);
        }

        // Determine if we can use tool_use mode
        // ★ Follow-up rounds (clarify_history present) skip tools entirely —
        //   the codebase was already explored in round 1.  Re-exploring wastes
        //   30-40 seconds and 8 tool rounds for zero new information.
        boolean hasClarifyHistory = hints != null
                && hints.get("clarify_history") instanceof List<?> histList
                && !histList.isEmpty();
        boolean useTools = toolRegistry != null && !hintPaths.isEmpty() && !hasClarifyHistory;
        String projectPath = useTools ? hintPaths.get(0) : null;

        log.info("[RAM][ClaudeClarifyLlmClient] useTools={} hasClarifyHistory={} hasKgTools={} projectPath={}",
                useTools,
                hasClarifyHistory,
                useTools && toolRegistry.hasKgTools(),
                projectPath);

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
                // ★ Tool-use mode: LLM autonomously explores code via tools
                List<ToolDefinition> tools = toolRegistry.buildToolDefinitions(projectPath);
                Map<String, Function<Map<String, Object>, Object>> handlers =
                        toolRegistry.buildToolHandlers(projectPath);
                log.info("[RAM][ClaudeClarifyLlmClient] tool_use mode: {} tools registered", tools.size());
                raw = claude.callJsonWithTools(
                        systemPrompt, userPrompt, tools, handlers,
                        new SendOptions(claude.defaultModel(), 16384, 0.2, null));
            } else {
                // Non-tool mode: single-turn JSON call
                // Follow-up rounds (with clarify history) need more tokens for the fuller prompt,
                // but still much less than the tool_use path since there's no multi-turn conversation.
                boolean hasCodeContext = codeContext != null && !codeContext.isEmpty();
                int maxTokens = hasClarifyHistory ? 4096 : (hasCodeContext ? 4096 : 2048);
                log.info("[RAM][ClaudeClarifyLlmClient] single-turn mode: maxTokens={} hasClarifyHistory={} hasCodeContext={}",
                        maxTokens, hasClarifyHistory, hasCodeContext);
                raw = claude.callJson(
                        systemPrompt, userPrompt,
                        new SendOptions(claude.defaultModel(), maxTokens, 0.2, null));
            }

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
              .append("current state. NEVER ask the user 'what technology/library/framework is ")
              .append("this using?' — determine that yourself from the code. Only ask questions ")
              .append("that require a HUMAN DECISION (scope, priority, trade-offs).\n\n");
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
            sb.append("## CRITICAL — Follow-up round instructions\n\n");
            sb.append("The user has answered ").append(roundNum)
              .append(" round(s) of clarifying questions.\n");
            sb.append("The codebase was ALREADY explored in the first round — you do NOT need ")
              .append("to search, read, or explore any code again. All technical information ")
              .append("you need is already known from the prior round.\n\n");
            sb.append("YOUR ONLY TASK: Combine the user's answers with your prior understanding ")
              .append("and output the JSON result IMMEDIATELY.\n\n");
            sb.append("- If the requirement is NOW clear (intent unambiguous, acceptance criteria ")
              .append("derivable, scope defined): set needs_clarification=false and fill ALL ")
              .append("structured fields completely.\n");
            sb.append("- If there are STILL remaining ambiguities that the answers did NOT resolve: ")
              .append("set needs_clarification=true and ask only NEW questions (never repeat ")
              .append("already-answered ones).\n\n");
            sb.append("OUTPUT THE JSON NOW. Do NOT call any tools. Do NOT explore any code.\n\n");
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

        // ★ Merge paths: hintPaths (frontend-selected, correct) first, then LLM paths as supplements
        // Previously LLM paths overwrote hintPaths, causing file paths to replace correct projectPaths
        List<String> llmPaths = asStringList(raw == null ? null : raw.get("project_paths"));
        List<String> mergedPaths = new java.util.ArrayList<>(hintPaths);
        for (String p : llmPaths) {
            if (!mergedPaths.contains(p)) {
                mergedPaths.add(p);
            }
        }
        out.put("project_paths", mergedPaths);
        // Preserve projectHints so downstream nodes (ImpactNode) can use the ground-truth paths
        out.put("projectHints", hintPaths);

        List<String> acs = asStringList(raw == null ? null : raw.get("acceptance_criteria"));
        out.put("acceptance_criteria", acs);

        if (raw != null && raw.get("target_modules") != null) {
            out.put("target_modules", asStringList(raw.get("target_modules")));
        }
        if (raw != null && raw.get("target_methods") != null) {
            out.put("target_methods", asStringList(raw.get("target_methods")));
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
