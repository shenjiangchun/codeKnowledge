package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Seed;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves target_methods (strings like {@code "ClassName#methodName"}) from the
 * Clarify LLM output into structured {@link MethodTarget} records carrying the
 * corresponding Neo4j {@code nodeId}, class name, method name, and an optional
 * reason.
 *
 * <p>Resolution strategy:</p>
 * <ol>
 *   <li>If the Clarify LLM provided explicit {@code targetMethods} strings, each
 *       is parsed as {@code className#methodName} and the nodeId is looked up via
 *       {@link KgMcpClient#calleesTree} with {@code depth=0}.</li>
 *   <li>If no target methods were provided, the resolver falls back to the hybrid
 *       search {@link Seed seeds}, mapping each seed directly to a MethodTarget.</li>
 * </ol>
 */
@Component
public class MethodTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(MethodTargetResolver.class);

    private final KgMcpClient kg;

    /**
     * A resolved method target with its Neo4j nodeId.
     *
     * @param nodeId     Neo4j node identifier (may be {@code null} if lookup failed)
     * @param className  fully-qualified class name (empty when resolved from seeds)
     * @param methodName method name (empty when resolved from seeds)
     * @param reason     optional reason / summary text
     */
    public record MethodTarget(String nodeId, String className, String methodName, String reason) {
    }

    public MethodTargetResolver(KgMcpClient kg) {
        this.kg = kg;
    }

    /**
     * Resolve target methods into structured MethodTarget records.
     *
     * <p>When {@code targetMethods} is non-empty, each entry is parsed as
     * {@code "ClassName#methodName"} and the nodeId is resolved via the knowledge
     * graph. Otherwise, the provided {@code searchFallback} seeds are mapped
     * directly.</p>
     *
     * @param targetMethods  target method strings from the Clarify LLM
     *                       (format: {@code "ClassName#methodName"})
     * @param searchFallback seeds from hybrid search, used as fallback when
     *                       {@code targetMethods} is empty or null
     * @param projectPath    Neo4j projectPath for KG queries
     * @return resolved method targets with nodeIds populated where possible
     */
    public List<MethodTarget> resolve(List<String> targetMethods,
                                      List<Seed> searchFallback,
                                      String projectPath) {
        if (targetMethods != null && !targetMethods.isEmpty()) {
            return resolveFromTargetMethods(targetMethods, projectPath);
        }
        return resolveFromSeeds(searchFallback != null ? searchFallback : List.of());
    }

    /**
     * Parse each target method string as {@code className#methodName} and resolve
     * the corresponding Neo4j nodeId via {@link KgMcpClient#calleesTree} with
     * depth 0. Falls back to {@link KgMcpClient#hybridSearch} when calleesTree
     * returns null (method not indexed or className is short form).
     */
    private List<MethodTarget> resolveFromTargetMethods(List<String> targetMethods,
                                                        String projectPath) {
        List<MethodTarget> targets = new ArrayList<>();
        for (String tm : targetMethods) {
            if (tm == null || tm.isBlank()) {
                continue;
            }
            String className;
            String methodName;

            // Format 1: "ClassName#methodName" (our preferred format)
            if (tm.contains("#")) {
                String[] parts = tm.split("#");
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    log.warn("Skipping malformed target_method '{}'; expected 'ClassName#methodName'", tm);
                    continue;
                }
                className = parts[0];
                methodName = parts[1];
            }
            // Format 2: "fully.qualified.ClassName.methodName" (LLM often uses dots)
            // The last dot-separated segment is the method name, everything before is className
            else if (tm.contains(".")) {
                int lastDot = tm.lastIndexOf('.');
                className = tm.substring(0, lastDot);
                methodName = tm.substring(lastDot + 1);
                if (className.isBlank() || methodName.isBlank()) {
                    log.warn("Skipping malformed target_method '{}'; cannot parse as Class.method", tm);
                    continue;
                }
                log.info("Parsing target_method '{}' as className='{}' methodName='{}' (dot-separated FQN)", tm, className, methodName);
            } else {
                log.warn("Skipping malformed target_method '{}'; expected 'ClassName#methodName' or 'pkg.Class.methodName'", tm);
                continue;
            }

            String nodeId = null;
            try {
                CallTreeNode node = kg.calleesTree(className, methodName, projectPath, 0);
                nodeId = (node != null && node.nodeId() != null) ? node.nodeId() : null;
            } catch (Exception ex) {
                log.warn("Failed to resolve nodeId for {}#{}: {}", className, methodName, ex.getMessage());
            }

            // Fallback: hybrid search by method signature
            if (nodeId == null) {
                log.info("calleesTree returned null for {}#{}, trying hybridSearch fallback", className, methodName);
                try {
                    String query = className + "#" + methodName;
                    List<Seed> results = kg.hybridSearch(query, projectPath, 5);
                    if (!results.isEmpty()) {
                        Seed best = results.get(0);
                        nodeId = best.nodeId();
                        log.info("hybridSearch fallback found nodeId={} for {}#{}", nodeId, className, methodName);
                    }
                } catch (Exception ex) {
                    log.warn("hybridSearch fallback also failed for {}#{}: {}", className, methodName, ex.getMessage());
                }
            }

            if (nodeId == null) {
                log.warn("No nodeId found for {}#{} in projectPath={}", className, methodName, projectPath);
            }
            targets.add(new MethodTarget(nodeId, className, methodName, ""));
        }
        return targets;
    }

    /**
     * Map hybrid-search seeds directly to MethodTarget records, using the seed's
     * summary as the reason field.
     */
    private List<MethodTarget> resolveFromSeeds(List<Seed> seeds) {
        return seeds.stream()
                .filter(s -> s != null && s.nodeId() != null)
                .map(s -> new MethodTarget(
                        s.nodeId(),
                        "",
                        "",
                        s.summary() != null ? s.summary() : ""))
                .toList();
    }
}
