package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.nodes.impact.AffectedEntriesAnnotator.AnnotatedEntries;
import com.huawei.hisi.ram.nodes.impact.AffectedEntriesAnnotator.AnnotatedEntry;
import com.huawei.hisi.ram.nodes.impact.MethodTargetResolver.MethodTarget;
import com.huawei.hisi.workflow.ClarifyRequiredException;
import com.huawei.hisi.workflow.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Impact stage DAG node — redesigned for SE-facing output.
 *
 * <p>Outputs only what a software engineer cares about:</p>
 * <ol>
 *     <li>{@code methods_to_modify} — the methods that need code changes.</li>
 *     <li>{@code affected_entries} — upstream root entry points (direct + indirect).</li>
 *     <li>{@code risk} — risk score and level.</li>
 *     <li>{@code validation} — deterministic check results.</li>
 *     <li>{@code reasoning} — summary of analysis steps.</li>
 *     <li>{@code markdown_report} — formatted report for display.</li>
 * </ol>
 */
@Slf4j
@Component
public class ImpactNode implements DagNode {

    static final String INPUT_INTENT = "intent";
    static final String INPUT_PROJECT_PATHS = "project_paths";
    static final String INPUT_PROJECT_HINTS = "projectHints";
    static final String INPUT_TARGET_MODULES = "target_modules";
    static final String INPUT_TARGET_METHODS = "target_methods";

    private final KgMcpClient kg;
    private final MethodTargetResolver methodTargetResolver;
    private final InvolvedRingResolver involvedRingResolver;
    private final ScopeNarrowingService scopeNarrowingService;
    private final AffectedEntriesAnnotator affectedEntriesAnnotator;
    private final RiskScorer riskScorer;
    private final DeterministicValidator deterministicValidator;

    public ImpactNode(KgMcpClient kg,
                      MethodTargetResolver methodTargetResolver,
                      InvolvedRingResolver involvedRingResolver,
                      ScopeNarrowingService scopeNarrowingService,
                      AffectedEntriesAnnotator affectedEntriesAnnotator,
                      RiskScorer riskScorer,
                      DeterministicValidator deterministicValidator) {
        this.kg = kg;
        this.methodTargetResolver = methodTargetResolver;
        this.involvedRingResolver = involvedRingResolver;
        this.scopeNarrowingService = scopeNarrowingService;
        this.affectedEntriesAnnotator = affectedEntriesAnnotator;
        this.riskScorer = riskScorer;
        this.deterministicValidator = deterministicValidator;
    }

    @Override
    public String name() { return "impact"; }

    @Override
    public String agentId() { return "impact-v1"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
        log.info("[RAM][ImpactNode] execute input.keys={}", input == null ? "null" : input.keySet());
        if (input == null) {
            throw new IllegalArgumentException("ImpactNode input must not be null");
        }

        String intent = stringInput(input, INPUT_INTENT);
        List<String> rawProjectPaths = stringListInput(input, INPUT_PROJECT_PATHS);
        List<String> projectHints = stringListInput(input, INPUT_PROJECT_HINTS);
        List<String> targetModules = stringListInput(input, INPUT_TARGET_MODULES);
        List<String> targetMethods = stringListInput(input, INPUT_TARGET_METHODS);

        // Resolve project paths
        List<String> resolvedPaths = resolvePaths(projectHints, rawProjectPaths, targetModules);
        String primaryPath = resolvedPaths.get(0);

        List<String> reasoningSteps = new ArrayList<>();

        // ── Step 1: Resolve methods to modify ──
        List<MethodTarget> targets;
        if (!targetMethods.isEmpty()) {
            reasoningSteps.add("使用Clarify阶段识别的target_methods: " + targetMethods);
            targets = methodTargetResolver.resolve(targetMethods, List.of(), primaryPath);
            reasoningSteps.add("解析为" + targets.size() + "个目标方法");
        } else {
            reasoningSteps.add("target_methods为空，执行hybrid search + AI筛选");
            InvolvedRing involved = involvedRingResolver.resolve(intent, resolvedPaths);
            reasoningSteps.add("hybrid search获得" + involved.seeds().size() + "个候选seed");

            var narrowedSeeds = scopeNarrowingService.narrow(intent, involved.seeds(), primaryPath);
            reasoningSteps.add("AI筛选后保留" + narrowedSeeds.size() + "个相关seed");

            targets = methodTargetResolver.resolve(List.of(), narrowedSeeds, primaryPath);
            reasoningSteps.add("映射为" + targets.size() + "个目标方法");
        }

        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "ImpactNode: 无法确定需要修改的方法 (target_methods和hybrid search均无结果)");
        }

        // ── Step 2: Find upstream root entry points ──
        List<String> targetNodeIds = targets.stream()
                .map(MethodTarget::nodeId)
                .filter(n -> n != null)
                .toList();
        List<Entry> upstream = kg.rootEntryAncestors(targetNodeIds, primaryPath, 10);
        reasoningSteps.add("向上追溯调用链发现" + upstream.size() + "个上游根入口");

        // ── Step 3: AI-annotate entries as DIRECT/INDIRECT ──
        String targetSummary = targets.stream()
                .map(t -> t.className() + "#" + t.methodName())
                .filter(s -> !s.equals("#"))
                .collect(Collectors.joining(", "));
        AnnotatedEntries annotated = affectedEntriesAnnotator.annotate(
                intent, upstream, targetSummary, primaryPath);
        reasoningSteps.add("AI标注: " + annotated.direct().size() + "个直接相关, "
                + annotated.indirect().size() + "个间接相关入口");

        // ── Step 3.5: Programmatic call_path fill ──
        Set<String> targetNodeIdSet = new LinkedHashSet<>(targetNodeIds);
        AnnotatedEntries withCallPath = fillCallPaths(annotated, targetNodeIdSet, primaryPath);
        reasoningSteps.add("调用路径填充完成");

        // ── Step 4: Risk scoring ──
        // Build minimal rings for risk scorer compatibility
        InvolvedRing involvedForRisk = new InvolvedRing(
                targets.stream().map(t -> new Seed(t.nodeId(), 0, t.reason())).toList(),
                upstream, List.of());
        ModifiedRing modifiedForRisk = new ModifiedRing(List.of());
        ImpactRing impactForRisk = new ImpactRing(upstream, List.of(), List.of(), List.of());
        RiskScore risk = riskScorer.score(involvedForRisk, modifiedForRisk, impactForRisk);
        reasoningSteps.add("风险评分: " + risk.score() + " (" + risk.level().name() + ")");

        // ── Step 5: Validation ──
        DeterministicValidator.ValidationOutcome validation =
                deterministicValidator.validate(involvedForRisk, modifiedForRisk, impactForRisk, primaryPath);
        if (!validation.passed()) {
            reasoningSteps.add("验证警告: " + validation.violations().size() + "项");
        }

        // ── Step 6: Build output ──
        Map<String, Object> output = new LinkedHashMap<>();

        // methods_to_modify
        List<Map<String, String>> methodsToModify = new ArrayList<>();
        for (MethodTarget t : targets) {
            Map<String, String> m = new LinkedHashMap<>();
            if (t.nodeId() != null) m.put("nodeId", t.nodeId());
            m.put("className", t.className());
            m.put("methodName", t.methodName());
            m.put("reason", t.reason() != null ? t.reason() : "");
            methodsToModify.add(m);
        }
        output.put("methods_to_modify", methodsToModify);

        // affected_entries
        output.put("affected_entries", Map.of(
                "direct", withCallPath.direct().stream().map(this::annotatedEntryToMap).toList(),
                "indirect", withCallPath.indirect().stream().map(this::annotatedEntryToMap).toList()));

        // risk + validation
        output.put("risk", Map.of("score", risk.score(), "level", risk.level().name()));
        output.put("validation", Map.of("passed", validation.passed(), "violations", validation.violations()));

        // reasoning
        String reasoning = String.join("\n", reasoningSteps);
        output.put("reasoning", reasoning);

        // markdown_report
        output.put("markdown_report", generateMarkdownReport(methodsToModify, withCallPath, risk, validation));

        log.info("[RAM][ImpactNode] output: methods_to_modify={}, affected_direct={}, affected_indirect={}, risk={}",
                methodsToModify.size(), withCallPath.direct().size(), withCallPath.indirect().size(), risk.level());
        return output;
    }

    // ─────────────────────── Helpers ───────────────────────

    /**
     * Fill empty callPath fields by tracing each entry's callees tree
     * to find a path from the entry to any target method.
     */
    private AnnotatedEntries fillCallPaths(AnnotatedEntries annotated,
                                           Set<String> targetNodeIds,
                                           String projectPath) {
        List<AnnotatedEntry> directWithPath = annotated.direct().stream()
                .map(ae -> fillCallPath(ae, targetNodeIds, projectPath))
                .toList();
        List<AnnotatedEntry> indirectWithPath = annotated.indirect().stream()
                .map(ae -> fillCallPath(ae, targetNodeIds, projectPath))
                .toList();
        return new AnnotatedEntries(directWithPath, indirectWithPath);
    }

    private AnnotatedEntry fillCallPath(AnnotatedEntry ae, Set<String> targetNodeIds, String projectPath) {
        if (ae.callPath() != null && !ae.callPath().isBlank()) return ae;
        if (ae.className() == null || ae.className().isBlank()
                || ae.methodName() == null || ae.methodName().isBlank()) return ae;

        try {
            CallTreeNode tree = kg.calleesTree(ae.className(), ae.methodName(), projectPath, 3);
            if (tree == null) return ae;
            String path = findPathToTarget(tree, targetNodeIds);
            if (path == null || path.isBlank()) return ae;
            return new AnnotatedEntry(ae.nodeId(), ae.className(), ae.methodName(),
                    ae.type(), ae.relevance(), ae.reason(),
                    ae.businessFunction(), ae.impactMechanism(), ae.changeBehavior(), path);
        } catch (Exception ex) {
            log.debug("[ImpactNode] calleesTree failed for {}#{}: {}", ae.className(), ae.methodName(), ex.getMessage());
            return ae;
        }
    }

    /**
     * DFS search in the callees tree to find a path from root to any target node.
     * Returns a string like "deliver → RequireStatusService.syncReqStatus"
     * or null if no target found.
     */
    private String findPathToTarget(CallTreeNode node, Set<String> targetNodeIds) {
        return findPathToTarget(node, targetNodeIds, new ArrayList<>());
    }

    private String findPathToTarget(CallTreeNode node, Set<String> targetNodeIds,
                                    List<String> pathSegments) {
        String segment = shortMethodRef(node.className(), node.methodName());
        pathSegments.add(segment);

        // Check if this node is a target
        if (node.nodeId() != null && targetNodeIds.contains(node.nodeId())) {
            return String.join(" → ", pathSegments);
        }

        // Also match by className#methodName for targets without nodeId
        String ref = node.className() + "#" + node.methodName();
        for (String targetNodeId : targetNodeIds) {
            if (targetNodeId.equals(ref)) {
                return String.join(" → ", pathSegments);
            }
        }

        // Recurse into children
        for (CallTreeNode child : node.children()) {
            String found = findPathToTarget(child, targetNodeIds, pathSegments);
            if (found != null) return found;
        }

        pathSegments.remove(pathSegments.size() - 1);
        return null;
    }

    private String shortMethodRef(String className, String methodName) {
        String shortClass = className;
        if (className != null && className.contains(".")) {
            shortClass = className.substring(className.lastIndexOf('.') + 1);
        }
        if (shortClass != null && !shortClass.isBlank() && methodName != null && !methodName.isBlank()) {
            return shortClass + "." + methodName;
        }
        return methodName != null ? methodName : "(unknown)";
    }

    private List<String> resolvePaths(List<String> projectHints, List<String> rawPaths, List<String> targetModules) {
        if (!projectHints.isEmpty()) {
            log.info("[RAM][ImpactNode] using projectHints: {}", projectHints);
            return projectHints;
        }
        List<String> resolved = kg.resolveProjectPaths(rawPaths, targetModules);
        if (resolved.isEmpty() && !rawPaths.isEmpty()) {
            log.warn("[RAM][ImpactNode] path resolution returned 0 — using raw paths as fallback");
            return rawPaths;
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "ImpactNode requires non-empty project paths (tried projectHints + project_paths + target_modules)");
        }
        return resolved;
    }

    private Map<String, String> annotatedEntryToMap(AnnotatedEntry ae) {
        Map<String, String> m = new LinkedHashMap<>();
        if (ae.nodeId() != null) m.put("nodeId", ae.nodeId());
        m.put("className", ae.className() != null ? ae.className() : "");
        m.put("methodName", ae.methodName() != null ? ae.methodName() : "");
        m.put("type", ae.type() != null ? ae.type() : "");
        m.put("relevance", ae.relevance());
        m.put("reason", ae.reason());
        if (ae.businessFunction() != null && !ae.businessFunction().isBlank())
            m.put("business_function", ae.businessFunction());
        if (ae.impactMechanism() != null && !ae.impactMechanism().isBlank())
            m.put("impact_mechanism", ae.impactMechanism());
        if (ae.changeBehavior() != null && !ae.changeBehavior().isBlank())
            m.put("change_behavior", ae.changeBehavior());
        if (ae.callPath() != null && !ae.callPath().isBlank())
            m.put("call_path", ae.callPath());
        return m;
    }

    private String generateMarkdownReport(List<Map<String, String>> methodsToModify,
                                           AnnotatedEntries annotated,
                                           RiskScore risk,
                                           DeterministicValidator.ValidationOutcome validation) {
        StringBuilder md = new StringBuilder();

        md.append("## 影响分析报告\n\n");

        // Methods to modify
        md.append("### 需要修改的方法 (").append(methodsToModify.size()).append("个)\n\n");
        md.append("| # | 方法 | 说明 |\n|---|------|------|\n");
        for (int i = 0; i < methodsToModify.size(); i++) {
            Map<String, String> m = methodsToModify.get(i);
            String methodDisplay = formatMethodDisplay(m.get("className"), m.get("methodName"));
            md.append("| ").append(i + 1).append(" | `").append(methodDisplay).append("` | ")
              .append(m.getOrDefault("reason", "")).append(" |\n");
        }
        md.append("\n");

        // Affected entries — direct
        if (!annotated.direct().isEmpty()) {
            md.append("### 受影响的入口 — 直接相关 (").append(annotated.direct().size()).append("个)\n\n");
            md.append("以下入口的功能与需求直接相关，修改后行为会直接体现：\n\n");
            for (AnnotatedEntry ae : annotated.direct()) {
                md.append("- ").append(entryTypeIcon(ae.type())).append(" **")
                  .append(entryTypeLabel(ae.type())).append("** `")
                  .append(formatMethodDisplay(ae.className(), ae.methodName()))
                  .append("` — ").append(ae.reason()).append("\n");
                if (ae.businessFunction() != null && !ae.businessFunction().isBlank()) {
                    md.append("  - **功能**: ").append(ae.businessFunction()).append("\n");
                }
                if (ae.impactMechanism() != null && !ae.impactMechanism().isBlank()) {
                    md.append("  - **影响机制**: ").append(ae.impactMechanism()).append("\n");
                }
                if (ae.changeBehavior() != null && !ae.changeBehavior().isBlank()) {
                    md.append("  - **行为变化**: ").append(ae.changeBehavior()).append("\n");
                }
                if (ae.callPath() != null && !ae.callPath().isBlank()) {
                    md.append("  - **调用路径**: `").append(ae.callPath()).append("`\n");
                }
            }
            md.append("\n");
        }

        // Affected entries — indirect
        if (!annotated.indirect().isEmpty()) {
            md.append("### 受影响的入口 — 间接相关 (").append(annotated.indirect().size()).append("个)\n\n");
            md.append("以下入口通过调用链间接受影响：\n\n");
            for (AnnotatedEntry ae : annotated.indirect()) {
                md.append("- ").append(entryTypeIcon(ae.type())).append(" **")
                  .append(entryTypeLabel(ae.type())).append("** `")
                  .append(formatMethodDisplay(ae.className(), ae.methodName()))
                  .append("` — ").append(ae.reason()).append("\n");
                if (ae.businessFunction() != null && !ae.businessFunction().isBlank()) {
                    md.append("  - **功能**: ").append(ae.businessFunction()).append("\n");
                }
                if (ae.impactMechanism() != null && !ae.impactMechanism().isBlank()) {
                    md.append("  - **影响机制**: ").append(ae.impactMechanism()).append("\n");
                }
                if (ae.changeBehavior() != null && !ae.changeBehavior().isBlank()) {
                    md.append("  - **行为变化**: ").append(ae.changeBehavior()).append("\n");
                }
                if (ae.callPath() != null && !ae.callPath().isBlank()) {
                    md.append("  - **调用路径**: `").append(ae.callPath()).append("`\n");
                }
            }
            md.append("\n");
        }

        // Risk
        md.append("### 风险评估\n\n");
        md.append("**").append(riskLevelLabel(risk.level())).append("** (")
          .append(String.format("%.1f", risk.score())).append("/100)");
        if (!annotated.indirect().isEmpty()) {
            md.append(" — 涉及").append(annotated.indirect().size()).append("个间接影响入口");
        }
        md.append("\n\n");

        // Validation warnings
        if (!validation.passed()) {
            md.append("### 验证警告\n\n");
            for (String v : validation.violations()) {
                md.append("- ").append(v).append("\n");
            }
        }

        return md.toString();
    }

    private String formatMethodDisplay(String className, String methodName) {
        String shortClass = className;
        if (className != null && className.contains(".")) {
            shortClass = className.substring(className.lastIndexOf('.') + 1);
        }
        if (shortClass != null && !shortClass.isBlank() && methodName != null && !methodName.isBlank()) {
            return shortClass + "#" + methodName;
        }
        if (methodName != null && !methodName.isBlank()) return methodName;
        if (shortClass != null && !shortClass.isBlank()) return shortClass;
        return "(未知方法)";
    }

    private String entryTypeIcon(String type) {
        if (type == null) return "🔌";
        return switch (type.toUpperCase()) {
            case "HTTP", "CONTROLLER", "REST_ENDPOINT" -> "🔌";
            case "SCHEDULED" -> "⏰";
            case "MQ_CONSUMER", "MQ_LISTENER" -> "📨";
            case "FEIGN_CLIENT" -> "🔗";
            case "GRPC", "RMI" -> "🔗";
            default -> "🔌";
        };
    }

    private String entryTypeLabel(String type) {
        if (type == null) return "接口";
        return switch (type.toUpperCase()) {
            case "HTTP", "CONTROLLER" -> "HTTP接口";
            case "REST_ENDPOINT" -> "REST接口";
            case "SCHEDULED" -> "定时任务";
            case "MQ_CONSUMER", "MQ_LISTENER" -> "消息监听";
            case "FEIGN_CLIENT" -> "Feign调用";
            case "GRPC" -> "gRPC";
            case "RMI" -> "RMI";
            default -> type;
        };
    }

    private String riskLevelLabel(RiskLevel level) {
        return switch (level) {
            case LOW -> "低风险";
            case MEDIUM -> "中风险";
            case HIGH -> "高风险";
            case CRITICAL -> "极高风险";
        };
    }

    private static String stringInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "ImpactNode requires non-blank '" + key + "' in input");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
