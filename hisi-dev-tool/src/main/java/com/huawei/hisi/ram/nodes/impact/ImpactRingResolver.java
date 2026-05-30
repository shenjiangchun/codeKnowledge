package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the outer {@link ImpactRing} (upstream/downstream/bridges/cross-service). */
@Component
public class ImpactRingResolver {

    private static final Logger log = LoggerFactory.getLogger(ImpactRingResolver.class);

    private static final int DOWNSTREAM_DEPTH = 2;

    /** Maximum upstream (affecting) traversal depth — was hardcoded 10 in DirectKgClient. */
    private static final int UPSTREAM_DEPTH = 3;

    /**
     * Maximum number of modified-ring nodeIds to process for upstream/downstream/bridges.
     * Beyond this limit the outer ring only processes seed nodes (tree roots) to avoid
     * excessive Neo4j queries for large callees trees.
     */
    private static final int MAX_NODES_FOR_FULL_RING = 20;

    private final KgMcpClient kg;

    public ImpactRingResolver(KgMcpClient kg) {
        this.kg = kg;
    }

    public ImpactRing resolve(ModifiedRing modified, String projectPath) {
        if (modified == null) {
            return new ImpactRing(List.of(), List.of(), List.of(), List.of());
        }
        List<Entry> upstream = new ArrayList<>();
        List<Entry> downstream = new ArrayList<>();
        List<Bridge> bridges = new ArrayList<>();

        Set<String> allNodeIds = modified.allNodeIds();
        boolean capped = allNodeIds.size() > MAX_NODES_FOR_FULL_RING;
        if (capped) {
            log.info("ImpactRing: {} modified nodeIds exceeds cap {}; processing tree roots only",
                    allNodeIds.size(), MAX_NODES_FOR_FULL_RING);
            // Only process tree-root nodeIds to keep queries manageable
            allNodeIds = new LinkedHashSet<>();
            for (var tree : modified.tree()) {
                if (tree != null && tree.nodeId() != null) {
                    allNodeIds.add(tree.nodeId());
                }
            }
        }

        log.info("ImpactRing: resolving {} nodeIds (capped={})", allNodeIds.size(), capped);

        // ── Upstream: root entry points (batch) ──
        // Return the top-level entry points (Controller, Scheduled, MQ consumer, etc.)
        // that can reach these nodes via the caller chain, NOT intermediate callers.
        List<Entry> rootEntries = kg.rootEntryAncestors(
                allNodeIds.stream().filter(n -> n != null).toList(), projectPath, UPSTREAM_DEPTH);
        if (rootEntries != null) upstream.addAll(rootEntries);

        for (String nodeId : allNodeIds) {
            if (nodeId == null) continue;

            // ── Downstream ──
            List<Entry> down = kg.downstream(nodeId, projectPath, DOWNSTREAM_DEPTH);
            if (down != null) downstream.addAll(down);

            // ── Bridges (Feign / MQ) ──
            List<Bridge> br = kg.bridges(nodeId, projectPath);
            if (br != null) bridges.addAll(br);
        }

        log.info("ImpactRing: upstream={}, downstream={}, bridges={}",
                upstream.size(), downstream.size(), bridges.size());

        // Cross-service expansion via Feign / MQ chains.
        Set<String> feignTargets = new LinkedHashSet<>();
        Set<String> mqTopics = new LinkedHashSet<>();
        for (Bridge b : bridges) {
            if (b == null || b.bridgeType() == null) continue;
            String type = b.bridgeType().toUpperCase();
            if (type.contains("FEIGN") && b.target() != null) feignTargets.add(b.target());
            if (type.contains("MQ") && b.target() != null) mqTopics.add(b.target());
        }
        List<Bridge> crossService = new ArrayList<>();
        for (String svc : feignTargets) {
            List<Bridge> chain = kg.feignChain(svc, projectPath);
            if (chain != null) crossService.addAll(chain);
        }
        for (String topic : mqTopics) {
            List<Bridge> chain = kg.mqChain(topic, projectPath);
            if (chain != null) crossService.addAll(chain);
        }
        return new ImpactRing(upstream, downstream, crossService, bridges);
    }

    /**
     * Best-effort parse of a nodeId into class and method components.
     * <p>Supported formats:
     * <ul>
     *   <li>{@code projectPath:className.methodName.signatureHash} — standard nodeId format</li>
     *   <li>{@code com.foo.Bar#method} — hash-separated</li>
     *   <li>{@code com.foo.Bar.method} — dot-separated (class must be multi-segment)</li>
     * </ul>
     * Returns {@code null} when not parseable.
     */
    static ClassMethod parseClassMethod(String nodeId) {
        if (nodeId == null) return null;

        // Format 1: projectPath:className.methodName.signatureHash
        int colon = nodeId.indexOf(':');
        if (colon > 0 && colon < nodeId.length() - 1) {
            String afterColon = nodeId.substring(colon + 1);
            // afterColon = "com.foo.Bar.methodName.hexHash"
            // Split: last segment is hash, second-to-last is methodName, rest is className
            int lastDot = afterColon.lastIndexOf('.');
            if (lastDot > 0) {
                int secondLastDot = afterColon.lastIndexOf('.', lastDot - 1);
                if (secondLastDot > 0) {
                    String className = afterColon.substring(0, secondLastDot);
                    String methodName = afterColon.substring(secondLastDot + 1, lastDot);
                    if (!className.isEmpty() && !methodName.isEmpty()) {
                        return new ClassMethod(className, methodName);
                    }
                }
            }
        }

        // Format 2: com.foo.Bar#method or com.foo.Bar#method(params)
        int hash = nodeId.indexOf('#');
        if (hash > 0 && hash < nodeId.length() - 1) {
            String cls = nodeId.substring(0, hash);
            String mth = nodeId.substring(hash + 1);
            int paren = mth.indexOf('(');
            if (paren > 0) mth = mth.substring(0, paren);
            return new ClassMethod(cls, mth);
        }

        // Format 3: com.foo.Bar.method (class must contain at least one dot)
        int lastDot = nodeId.lastIndexOf('.');
        if (lastDot > 0 && lastDot < nodeId.length() - 1) {
            String cls = nodeId.substring(0, lastDot);
            String mth = nodeId.substring(lastDot + 1);
            if (cls.contains(".")) {
                return new ClassMethod(cls, mth);
            }
        }
        return null;
    }

    record ClassMethod(String className, String methodName) {}
}
