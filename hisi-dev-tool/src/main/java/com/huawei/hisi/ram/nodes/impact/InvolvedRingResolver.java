package com.huawei.hisi.ram.nodes.impact;

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
 * <p>Uses {@link QueryDecomposer} to split the intent into focused sub-queries,
 * then {@link MultiQuerySearcher} to execute multi-path recall with RRF fusion
 * — replacing the previous single-shot hybrid search.</p>
 */
@Component
public class InvolvedRingResolver {

    private static final Logger log = LoggerFactory.getLogger(InvolvedRingResolver.class);

    private static final int DEFAULT_SEED_LIMIT = 10;
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

    public InvolvedRing resolve(String query, String projectPath) {
        // 1. Decompose intent → sub-queries, then multi-path search + RRF
        List<String> subQueries = decomposer.decompose(query);
        log.info("[InvolvedRingResolver] decomposed intent into {} sub-queries", subQueries.size());

        List<Seed> seeds = searcher.search(subQueries, projectPath,
                PER_QUERY_LIMIT, DEFAULT_SEED_LIMIT);
        log.info("[InvolvedRingResolver] RRF produced {} seeds", seeds.size());

        // 2. Entry points (unchanged)
        List<Entry> entries = kg.entryPoints(projectPath, "ALL");

        // 3. Interface implementations for each seed (unchanged)
        List<Impl> allImpls = new ArrayList<>();
        for (Seed seed : seeds) {
            if (seed == null || seed.nodeId() == null) continue;
            List<Impl> impls = kg.implementations(seed.nodeId(), projectPath);
            if (impls != null) allImpls.addAll(impls);
        }
        return new InvolvedRing(seeds, entries, allImpls);
    }
}
