package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.orchestrator.ClarifyRequiredException;
import com.huawei.hisi.ram.orchestrator.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Clarify stage DAG node.
 *
 * <p>Pipeline:
 * <ol>
 *     <li>Read {@code userRequirement} from the input map (required).</li>
 *     <li><strong>NEW</strong>: Perform semantic search via {@link KgMcpClient}
 *         to gather project code context (classes, methods, signatures) related
 *         to the user's request.</li>
 *     <li>Ask {@link ClarifyLlmClient} to extract structured requirements,
 *         supplying the code context so the LLM can answer technical questions
 *         itself rather than asking the user.</li>
 *     <li>Validate the result against the {@code clarify.output} JSON schema.</li>
 *     <li>If validation fails, throw {@link ClarifyRequiredException} with
 *         one human-readable question per missing field — the orchestrator
 *         parks the session in {@code WAITING_CLARIFY} until the user
 *         supplies the missing data.</li>
 * </ol>
 */
@Slf4j
@Component
public class ClarifyNode implements DagNode {

    static final String INPUT_USER_REQUIREMENT = "userRequirement";
    private static final String SCHEMA_NAME = "clarify.output";

    /** Max number of search results to feed into the LLM prompt. */
    private static final int SEARCH_LIMIT = 10;

    /** Truncate method bodies to prevent prompt overflow. */
    private static final int MAX_BODY_CHARS = 1500;

    private final SchemaValidator schemaValidator;
    private final ClarifyLlmClient clarifyLlmClient;
    private final KgMcpClient kgClient; // nullable — graceful when Neo4j unavailable

    public ClarifyNode(SchemaValidator schemaValidator,
                       ClarifyLlmClient clarifyLlmClient,
                       @Autowired(required = false) KgMcpClient kgClient) {
        this.schemaValidator = schemaValidator;
        this.clarifyLlmClient = clarifyLlmClient;
        this.kgClient = kgClient;
    }

    @Override
    public String name() {
        return "clarify";
    }

    @Override
    public String agentId() {
        return "clarify-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
        log.info("[RAM][ClarifyNode] execute input.keys={}", input == null ? "null" : input.keySet());
        if (input == null) {
            throw new IllegalArgumentException("ClarifyNode input must not be null");
        }
        Object raw = input.get(INPUT_USER_REQUIREMENT);
        if (!(raw instanceof String userRequirement) || userRequirement.isBlank()) {
            throw new IllegalArgumentException(
                    "ClarifyNode requires non-blank '" + INPUT_USER_REQUIREMENT + "' in input");
        }

        // ★ Semantic search: gather project code context before asking the LLM
        List<CodeContextItem> codeContext = searchProjectContext(userRequirement, input);
        log.info("[RAM][ClarifyNode] code context: {} items from semantic search", codeContext.size());

        Map<String, Object> extracted = clarifyLlmClient.extractRequirements(
                userRequirement, input, codeContext);
        log.info("[RAM][ClarifyNode] llm extracted keys={} intent.len={} project_paths={} acceptance_criteria.size={} needs_clarification={}",
                extracted == null ? List.of() : extracted.keySet(),
                extracted == null ? 0 : String.valueOf(extracted.getOrDefault("intent", "")).length(),
                extracted == null ? null : extracted.get("project_paths"),
                extracted == null || !(extracted.get("acceptance_criteria") instanceof List<?> ac) ? 0 : ac.size(),
                extracted == null ? null : extracted.get("needs_clarification"));

        // ★ Check if LLM flagged the request as needing clarification
        if (Boolean.TRUE.equals(extracted.get("needs_clarification"))) {
            Object rawQuestions = extracted.get("clarify_questions");
            List<String> questions = new ArrayList<>();
            if (rawQuestions instanceof List<?> qList) {
                for (Object q : qList) {
                    if (q instanceof String s && !s.isBlank()) {
                        questions.add(s);
                    }
                }
            }
            if (!questions.isEmpty()) {
                log.info("[RAM][ClarifyNode] LLM says needs_clarification=true questions={}", questions);
                throw new ClarifyRequiredException(questions);
            }
            log.warn("[RAM][ClarifyNode] needs_clarification=true but no valid questions — proceeding to schema validation");
        }

        ValidationResult result = schemaValidator.validate(SCHEMA_NAME, extracted);
        if (!result.passed()) {
            List<String> questions = buildQuestions(result);
            log.info("[RAM][ClarifyNode] schema validation FAILED missing={} violations={} questions={}",
                    result.missingFields(), result.violations(), questions);
            throw new ClarifyRequiredException(questions);
        }

        // Strip internal fields before passing output downstream
        Map<String, Object> output = new java.util.LinkedHashMap<>(extracted);
        output.remove("needs_clarification");
        output.remove("clarify_questions");

        log.info("[RAM][ClarifyNode] OK schema passed");
        return output;
    }

    // ────────────────────── Semantic search ──────────────────────

    /**
     * Search the project's knowledge graph for code relevant to the user's
     * requirement. Returns an empty list (graceful degradation) when:
     * <ul>
     *   <li>Neo4j / KgMcpClient is not configured</li>
     *   <li>No projectHints are provided in the input</li>
     *   <li>The search fails for any reason</li>
     * </ul>
     */
    private List<CodeContextItem> searchProjectContext(String userRequirement,
                                                       Map<String, Object> input) {
        if (kgClient == null) {
            log.info("[RAM][ClarifyNode] KgMcpClient unavailable (Neo4j not configured) — skipping code search");
            return List.of();
        }

        List<String> projectPaths = extractProjectPaths(input);
        if (projectPaths.isEmpty()) {
            log.warn("[RAM][ClarifyNode] No projectHints in input — cannot perform code search");
            return List.of();
        }
        String projectPath = projectPaths.get(0);

        try {
            // Step 1: semantic search
            List<Seed> seeds = kgClient.hybridSearch(userRequirement, projectPath, SEARCH_LIMIT);
            if (seeds.isEmpty()) {
                log.info("[RAM][ClarifyNode] semantic search returned 0 results for projectPath={}",
                        projectPath);
                return List.of();
            }
            log.info("[RAM][ClarifyNode] semantic search returned {} seeds", seeds.size());

            // Step 2: load method bodies for richer context
            List<String> nodeIds = seeds.stream().map(Seed::nodeId).toList();
            List<MethodBodyInfo> bodies = kgClient.loadMethodBodies(nodeIds, projectPath);

            // Step 3: assemble CodeContextItem list
            return bodies.stream()
                    .map(b -> new CodeContextItem(
                            b.className(),
                            b.methodName(),
                            "",   // MethodBodyInfo doesn't carry signature
                            b.filePath(),
                            b.description(),
                            truncate(b.methodBody(), MAX_BODY_CHARS),
                            seeds.stream()
                                    .filter(s -> s.nodeId().equals(b.nodeId()))
                                    .mapToDouble(Seed::score)
                                    .findFirst().orElse(0.0)))
                    .toList();
        } catch (Exception e) {
            log.warn("[RAM][ClarifyNode] code search failed — proceeding without context: {}",
                    e.getMessage());
            return List.of();
        }
    }

    // ────────────────────── Helpers ──────────────────────

    private List<String> extractProjectPaths(Map<String, Object> input) {
        // Try projectHints first (set by AnalyzeRequirementTool)
        Object hints = input.get("projectHints");
        if (hints instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .toList();
        }
        // Fallback to project_paths (may be set by earlier runs)
        Object paths = input.get("project_paths");
        if (paths instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .toList();
        }
        return List.of();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n// ... truncated";
    }

    private List<String> buildQuestions(ValidationResult result) {
        List<String> questions = new ArrayList<>();
        for (String missing : result.missingFields()) {
            questions.add("Please specify: " + missing);
        }
        for (String violation : result.violations()) {
            questions.add("Please clarify: " + violation);
        }
        if (questions.isEmpty()) {
            questions.add("Please provide more details about your requirement.");
        }
        return questions;
    }
}
