package com.huawei.hisi.apm.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@code OTEL_INSTRUMENTATION_METHODS_INCLUDE} environment value
 * by traversing the KG callee tree from a given entry method.
 *
 * <p>OpenTelemetry Java agent does not auto-instrument arbitrary
 * {@code @Service}/{@code @Repository}/{@code @Component} methods. To capture
 * a method-level span tree that matches the KG call chain we need to enumerate
 * the methods up-front and pass them via the {@code methods} instrumentation:
 * {@code com.foo.Bar[m1,m2];com.baz.Qux[m3]}.
 *
 * <p>Empty / blank entry id returns an empty string — the caller should treat
 * that as "do not set the env var" so the agent falls back to defaults.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KgMethodIncludeBuilder {

    /** Default callee tree traversal depth. */
    public static final int DEFAULT_MAX_DEPTH = 5;

    /** Per-class method cap to avoid pathological include strings. */
    private static final int MAX_METHODS_PER_CLASS = 32;

    /** Total method cap to keep agent startup cost bounded. */
    private static final int MAX_TOTAL_METHODS = 256;

    /**
     * Class prefixes we never want to instrument — JDK, Spring framework, common libs.
     * Project code rarely lives under these; instrumenting them explodes the bytecode budget.
     */
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "sun.", "com.sun.",
            "org.springframework.", "org.apache.", "org.slf4j.", "org.hibernate.",
            "io.netty.", "io.opentelemetry.", "com.fasterxml.", "lombok."
    );

    private final Neo4jMethodNodeRepository methodNodeRepository;

    /**
     * Build the include string for the entry method's downstream callees.
     *
     * @param entryNodeId KG nodeId of the entry method (controller method)
     * @param maxDepth    callee traversal depth (use {@link #DEFAULT_MAX_DEPTH} if &le; 0)
     * @return formatted include string, or empty string if input is missing / no callees
     */
    public String build(String entryNodeId, int maxDepth) {
        if (entryNodeId == null || entryNodeId.isBlank()) {
            return "";
        }
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;

        List<MethodNode> nodes = new ArrayList<>();
        try {
            Optional<MethodNode> entry = methodNodeRepository.findByNodeId(entryNodeId);
            entry.ifPresent(nodes::add);
            nodes.addAll(methodNodeRepository.findCalleesUpToDepth(entryNodeId, depth));
        } catch (Exception e) {
            log.warn("[KgInclude] Failed to load callee tree for entryNodeId={}: {}",
                    entryNodeId, e.getMessage());
            return "";
        }

        if (nodes.isEmpty()) {
            log.info("[KgInclude] No callee methods found for entryNodeId={}", entryNodeId);
            return "";
        }

        // Group by className, preserve insertion order, dedupe methods per class.
        Map<String, Set<String>> byClass = new LinkedHashMap<>();
        int total = 0;
        for (MethodNode mn : nodes) {
            String cn = mn.getClassName();
            String mname = mn.getMethodName();
            if (cn == null || cn.isBlank() || mname == null || mname.isBlank()) {
                continue;
            }
            if (isFrameworkClass(cn)) {
                continue;
            }
            // Skip constructors / static init — OTel agent rejects "<init>" in methods include
            if (mname.startsWith("<")) {
                continue;
            }
            Set<String> methods = byClass.computeIfAbsent(cn, k -> new LinkedHashSet<>());
            if (methods.size() >= MAX_METHODS_PER_CLASS) {
                continue;
            }
            if (methods.add(mname)) {
                total++;
                if (total >= MAX_TOTAL_METHODS) {
                    log.info("[KgInclude] Reached total method cap ({}), truncating include list",
                            MAX_TOTAL_METHODS);
                    break;
                }
            }
        }

        if (byClass.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Set<String>> e : byClass.entrySet()) {
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(e.getKey()).append('[');
            boolean firstM = true;
            for (String m : e.getValue()) {
                if (!firstM) {
                    sb.append(',');
                }
                firstM = false;
                sb.append(m);
            }
            sb.append(']');
        }
        String result = sb.toString();
        log.info("[KgInclude] Built include string: {} classes, {} methods (entryNodeId={})",
                byClass.size(), total, entryNodeId);
        log.debug("[KgInclude] include = {}", result);
        return result;
    }

    /** Convenience overload using the default depth. */
    public String build(String entryNodeId) {
        return build(entryNodeId, DEFAULT_MAX_DEPTH);
    }

    private static boolean isFrameworkClass(String className) {
        for (String p : FRAMEWORK_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
