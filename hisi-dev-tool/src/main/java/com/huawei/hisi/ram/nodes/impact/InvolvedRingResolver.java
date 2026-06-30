package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.neo4j.model.SubQuery;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.Seed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the innermost {@link InvolvedRing} for a query.
 *
 * <p>Uses {@link QueryDecomposer} to split the intent into focused,
 * intent-aware sub-queries, then {@link MultiQuerySearcher} to execute
 * multi-path recall with weighted RRF fusion across all project paths.</p>
 */
@Component
public class InvolvedRingResolver {

    private static final Logger log = LoggerFactory.getLogger(InvolvedRingResolver.class);

    private static final int DEFAULT_SEED_LIMIT = 30;
    private static final int PER_QUERY_LIMIT = 10;

    private final KgMcpClient kg;
    private final QueryDecomposer decomposer;
    private final MultiQuerySearcher searcher;

    public InvolvedRingResolver(KgMcpClient kg,
                                QueryDecomposer decomposer,
                                MultiQuerySearcher searcher) {
        this.kg = kg;
        this.decomposer = decomposer;
        this.searcher = searcher;
    }

    /**
     * Resolve with pre-resolved project paths (recommended).
     * Callers should use {@link KgMcpClient#resolveProjectPaths} to resolve
     * LLM-provided hints into actual Neo4j projectPaths before calling this.
     */
    public InvolvedRing resolve(String query, List<String> projectPaths) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            log.warn("[InvolvedRingResolver] no project paths provided");
            return new InvolvedRing(List.of(), List.of(), List.of());
        }

        log.info("[InvolvedRingResolver] searching across {} project paths: {}", projectPaths.size(), projectPaths);

        // 1. Decompose intent → intent-aware sub-queries, then multi-path search + weighted RRF
        List<SubQuery> subQueries = decomposer.decompose(query);
        log.info("[InvolvedRingResolver] decomposed intent into {} sub-queries (types: {})",
                subQueries.size(),
                subQueries.stream().map(sq -> sq.intentType().name()).toList());

        List<Seed> seeds = searcher.search(subQueries, projectPaths,
                PER_QUERY_LIMIT, DEFAULT_SEED_LIMIT);
        log.info("[InvolvedRingResolver] weighted RRF produced {} seeds", seeds.size());

        // 2. Entry points — search across all project paths
        List<Entry> entries = new ArrayList<>();
        try {
            List<Entry> pathEntries = kg.entryPoints(projectPaths, "ALL");
            if (pathEntries != null) entries.addAll(pathEntries);
        } catch (Exception e) {
            log.debug("[InvolvedRingResolver] entryPoints failed: {}", e.getMessage());
        }

        // 3. Interface implementations for each seed
        List<Impl> allImpls = new ArrayList<>();
        for (Seed seed : seeds) {
            if (seed == null || seed.nodeId() == null) continue;
            try {
                List<Impl> impls = kg.implementations(seed.nodeId(), projectPaths);
                if (impls != null) allImpls.addAll(impls);
            } catch (Exception e) {
                log.debug("[InvolvedRingResolver] implementations failed for nodeId={}: {}",
                        seed.nodeId(), e.getMessage());
            }
        }
        return new InvolvedRing(seeds, entries, allImpls);
    }

    /**
     * Backward-compatible single-path overload.
     */
    public InvolvedRing resolve(String query, String projectPath) {
        return resolve(query, List.of(projectPath));
    }
}
