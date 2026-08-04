package com.huawei.hisi.apm.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Maps OpenTelemetry spans to Neo4j MethodNodes using a 4-level fallback strategy:
 * <ol>
 *   <li>Level 1 (EXACT): className + methodName from {@code code.namespace} / {@code code.function} attributes</li>
 *   <li>Level 2 (UNIQUE): Extract className.methodName from the span operation name</li>
 *   <li>Level 3 (OVERLOADED): HTTP route matching against controller entry points</li>
 *   <li>Level 4: Unmatched (no result returned)</li>
 * </ol>
 * <p>
 * Maintains an in-memory index per project for fast lookups. The index must be
 * initialized via {@link #initializeProject(String)} when a debug session starts,
 * and cleared via {@link #clearProject(String)} when the session ends.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpanToKgMapper {

    private final Neo4jMethodNodeRepository methodNodeRepository;

    /** Cache: projectPath -> { className -> List&lt;MethodNode&gt; } */
    private final Map<String, Map<String, List<MethodNode>>> projectMethodIndex = new ConcurrentHashMap<>();

    /** Match result carrying the resolved nodeId and the confidence level. */
    public record MatchResult(String nodeId, int matchLevel) {}

    // Match level constants
    public static final int MATCH_EXACT = 0;
    public static final int MATCH_UNIQUE = 1;
    public static final int MATCH_OVERLOADED = 2;
    public static final int MATCH_UNMATCHED = 3;

    /** Proxy class suffixes injected by Spring CGLIB, ByteBuddy, and Hibernate. */
    private static final Pattern PROXY_PATTERN = Pattern.compile(
        "\\$\\$EnhancerBySpringCGLIB\\$\\$.*|" +
        "\\$\\$FastClassBySpringCGLIB\\$\\$.*|" +
        "\\$ByteBuddy\\$.*|" +
        "\\$HibernateProxy\\$.*|" +
        "\\$Proxy\\d+"
    );

    /**
     * Initialize the method index for a project.
     * Call this when an APM/debug session starts.
     * Uses {@code findByProjectPathWithoutRelationships} to avoid loading
     * Neo4j CALLS relationships (bulk-processing optimized query).
     *
     * @param projectPath the project root path to index
     */
    public void initializeProject(String projectPath) {
        if (projectMethodIndex.containsKey(projectPath)) {
            return;
        }

        List<MethodNode> methods = methodNodeRepository.findByProjectPathWithoutRelationships(projectPath);
        Map<String, List<MethodNode>> index = methods.stream()
            .collect(Collectors.groupingBy(MethodNode::getClassName));

        projectMethodIndex.put(projectPath, index);
        log.info("[SpanToKgMapper] Indexed {} methods across {} classes for project: {}",
            methods.size(), index.size(), projectPath);
    }

    /**
     * Clear the method index for a project (on session end).
     *
     * @param projectPath the project root path to evict
     */
    public void clearProject(String projectPath) {
        projectMethodIndex.remove(projectPath);
    }

    /**
     * Match a span to a MethodNode using the 4-level fallback strategy.
     *
     * @param attributes    span attributes (containing code.namespace, code.function, etc.)
     * @param operationName span operation name
     * @param projectPath   the project to match against
     * @return match result or empty if unmatched (level 4)
     */
    public Optional<MatchResult> match(Map<String, String> attributes, String operationName, String projectPath) {
        Map<String, List<MethodNode>> index = projectMethodIndex.get(projectPath);
        if (index == null || index.isEmpty()) {
            return Optional.empty();
        }

        // Level 1: className + methodName from code.namespace / code.function (highest confidence)
        String codeNamespace = attributes != null ? attributes.get("code.namespace") : null;
        String codeFunction = attributes != null ? attributes.get("code.function") : null;

        if (codeNamespace != null && codeFunction != null) {
            Optional<MatchResult> result = matchByClassAndMethod(index, codeNamespace, codeFunction);
            if (result.isPresent()) {
                // Upgrade to EXACT since the source is explicit OTel code attributes
                MatchResult original = result.get();
                return Optional.of(new MatchResult(original.nodeId(), MATCH_EXACT));
            }
        }

        // Level 2: Extract className.methodName from operation name
        // OTel Java agent often sets the span name to "ClassName.methodName"
        if (operationName != null && operationName.contains(".")) {
            int lastDot = operationName.lastIndexOf('.');
            String className = operationName.substring(0, lastDot);
            String methodName = operationName.substring(lastDot + 1);
            Optional<MatchResult> result = matchByClassAndMethod(index, className, methodName);
            if (result.isPresent()) {
                return result;
            }
        }

        // Level 3: HTTP span -> entry point matching
        if (attributes != null) {
            String httpMethod = attributes.get("http.method");
            String httpRoute = attributes.get("http.route");
            if (httpRoute == null) {
                httpRoute = attributes.get("http.target");
            }

            if (httpMethod != null && httpRoute != null) {
                Optional<MatchResult> result = matchByHttpRoute(index, httpMethod, httpRoute);
                if (result.isPresent()) {
                    return result;
                }
            }
        }

        // Level 4: unmatched
        return Optional.empty();
    }

    /**
     * Attempt to match by fully-qualified class name and method name.
     * Tries direct lookup, then proxy-stripped lookup, then suffix-based fallback.
     */
    private Optional<MatchResult> matchByClassAndMethod(
            Map<String, List<MethodNode>> index, String className, String methodName) {

        // Direct match
        List<MethodNode> candidates = index.get(className);

        // If no direct match, try with proxy suffix stripping
        if (candidates == null) {
            String stripped = normalizeClassName(className);
            if (!stripped.equals(className)) {
                candidates = index.get(stripped);
            }
        }

        // If still no match, try suffix match (inner classes, partial package names)
        if (candidates == null) {
            String simpleName = getSimpleName(className);
            for (Map.Entry<String, List<MethodNode>> entry : index.entrySet()) {
                if (entry.getKey().endsWith("." + simpleName) ||
                    entry.getKey().endsWith(className)) {
                    candidates = entry.getValue();
                    break;
                }
            }
        }

        if (candidates == null) {
            return Optional.empty();
        }

        List<MethodNode> methodMatches = candidates.stream()
            .filter(m -> m.getMethodName().equals(methodName))
            .toList();

        if (methodMatches.size() == 1) {
            return Optional.of(new MatchResult(methodMatches.get(0).getNodeId(), MATCH_UNIQUE));
        }
        if (methodMatches.size() > 1) {
            // Multiple overloads -- return first (best we can do without signature info)
            return Optional.of(new MatchResult(methodMatches.get(0).getNodeId(), MATCH_OVERLOADED));
        }

        return Optional.empty();
    }

    /**
     * Attempt to match an HTTP span to a controller entry point.
     * <p>
     * Current implementation uses a heuristic scan over Controller/Resource classes.
     * Full HTTP route matching via EntryPointNode query is deferred to Phase 2.
     */
    private Optional<MatchResult> matchByHttpRoute(
            Map<String, List<MethodNode>> index, String httpMethod, String httpRoute) {
        // Normalize the route for comparison
        // A proper implementation would query EntryPointNodes, but for MVP
        // we log and defer to Phase 2
        String routeKey = httpMethod.toUpperCase() + " " + normalizeRoute(httpRoute);
        log.debug("[SpanToKgMapper] HTTP route matching deferred to Phase 2: {}", routeKey);

        // TODO: Query EntryPointNodes for proper HTTP route matching
        // For MVP, HTTP route matching is deferred to Phase 2
        return Optional.empty();
    }

    /**
     * Strip Spring CGLIB / ByteBuddy / Hibernate proxy suffixes from class names.
     *
     * @param className the potentially proxied class name
     * @return the normalized class name with proxy suffixes removed
     */
    static String normalizeClassName(String className) {
        if (className == null) {
            return "";
        }
        return PROXY_PATTERN.matcher(className).replaceAll("");
    }

    /**
     * Normalize an HTTP route by stripping query strings and replacing
     * numeric path segments with {@code {id}} placeholders.
     * <p>
     * Example: {@code "/api/users/123?detail=true"} becomes {@code "/api/users/{id}"}
     *
     * @param route the raw HTTP route or target path
     * @return the normalized route
     */
    static String normalizeRoute(String route) {
        if (route == null) {
            return "";
        }
        // Remove query string
        int queryIdx = route.indexOf('?');
        if (queryIdx >= 0) {
            route = route.substring(0, queryIdx);
        }
        // Replace numeric path segments with {id}
        return route.replaceAll("/\\d+", "/{id}");
    }

    /**
     * Extract the simple (unqualified) class name from a fully-qualified class name.
     */
    private static String getSimpleName(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }
}
