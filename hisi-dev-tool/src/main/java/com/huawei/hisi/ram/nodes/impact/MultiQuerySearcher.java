package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Seed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes independent hybrid searches for each sub-query, then merges
 * results using <em>Reciprocal Rank Fusion</em> (RRF) to produce a
 * single ranked seed list.
 *
 * <p>RRF formula: {@code score(d) = Σ 1 / (K + rank_i(d))} where {@code K}
 * is a smoothing constant (default 60, the de-facto standard).</p>
 *
 * <p>Nodes that appear in multiple sub-query result lists accumulate RRF
 * score, naturally rewarding "multi-facet hits".</p>
 */
@Component
public class MultiQuerySearcher {

    private static final Logger log = LoggerFactory.getLogger(MultiQuerySearcher.class);

    /** RRF smoothing constant — industry standard value. */
    private static final int K = 60;

    private final KgMcpClient kg;

    public MultiQuerySearcher(KgMcpClient kg) {
        this.kg = kg;
    }

    /**
     * Search with multiple sub-queries and fuse results via RRF.
     *
     * @param subQueries    list of focused sub-queries (from {@link QueryDecomposer})
     * @param projectPath   target project path
     * @param perQueryLimit max results per sub-query
     * @param topK          number of final seeds to return
     * @return ranked list of at most {@code topK} seeds
     */
    public List<Seed> search(List<String> subQueries, String projectPath,
                             int perQueryLimit, int topK) {
        if (subQueries == null || subQueries.isEmpty()) {
            return List.of();
        }

        // 1. Execute each sub-query independently
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Seed> seedMap = new LinkedHashMap<>();

        for (String query : subQueries) {
            List<Seed> groupResults;
            try {
                groupResults = kg.hybridSearch(query, projectPath, perQueryLimit);
            } catch (Exception ex) {
                log.warn("[MultiQuerySearcher] hybridSearch failed for query='{}': {}",
                        query, ex.getMessage());
                continue;
            }
            if (groupResults == null || groupResults.isEmpty()) {
                continue;
            }

            // 2. Accumulate RRF score per nodeId
            for (int rank = 0; rank < groupResults.size(); rank++) {
                Seed seed = groupResults.get(rank);
                if (seed == null || seed.nodeId() == null) continue;

                String nodeId = seed.nodeId();
                double rrfContribution = 1.0 / (K + rank + 1);
                rrfScores.merge(nodeId, rrfContribution, Double::sum);
                seedMap.putIfAbsent(nodeId, seed);
            }
        }

        if (rrfScores.isEmpty()) {
            log.info("[MultiQuerySearcher] no results from {} sub-queries", subQueries.size());
            return List.of();
        }

        // 3. Sort by RRF score descending, take top-K
        List<Seed> ranked = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    Seed original = seedMap.get(e.getKey());
                    return new Seed(e.getKey(), e.getValue(), original.summary());
                })
                .toList();

        log.info("[MultiQuerySearcher] {} sub-queries → {} unique nodes → top-{} seeds (scores: {}-{})",
                subQueries.size(),
                rrfScores.size(),
                ranked.size(),
                ranked.isEmpty() ? "N/A" : String.format("%.4f", ranked.get(0).score()),
                ranked.isEmpty() ? "N/A" : String.format("%.4f", ranked.get(ranked.size() - 1).score()));

        return ranked;
    }
}
