package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.neo4j.config.SearchIntentProperties;
import com.huawei.hisi.neo4j.model.IntentType;
import com.huawei.hisi.neo4j.model.SubQuery;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Seed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes independent hybrid searches for each intent-aware sub-query, then merges
 * results using <em>Weighted Reciprocal Rank Fusion</em> (W-RRF) to produce a
 * single ranked seed list.
 *
 * <p>W-RRF formula: {@code score(d) = Σ w_eff / (K + rank_i(d))}
 * where {@code w_eff = 1 + α·ln(w_raw / w_base)} is the log-scaled effective weight
 * derived from each sub-query's {@link IntentType}.</p>
 *
 * <p>Nodes that appear in multiple sub-query result lists accumulate RRF
 * score, naturally rewarding "multi-facet hits", but with normalization
 * to prevent weak multi-hit stacking from surpassing precise single-hit results.</p>
 */
@Component
public class MultiQuerySearcher {

    private static final Logger log = LoggerFactory.getLogger(MultiQuerySearcher.class);

    private final KgMcpClient kg;
    private final SearchIntentProperties intentProperties;

    public MultiQuerySearcher(KgMcpClient kg, SearchIntentProperties intentProperties) {
        this.kg = kg;
        this.intentProperties = intentProperties;
    }

    /**
     * Search with intent-aware sub-queries across multiple project paths, fuse via weighted RRF.
     *
     * @param subQueries    list of intent-tagged sub-queries (from {@link QueryDecomposer})
     * @param projectPaths  target project directory paths
     * @param perQueryLimit max results per sub-query
     * @param topK          number of final seeds to return (unused — returns all)
     * @return ranked list of seeds with RRF-based scores
     */
    public List<Seed> search(List<SubQuery> subQueries, List<String> projectPaths,
                             int perQueryLimit, int topK) {
        if (subQueries == null || subQueries.isEmpty()) {
            return List.of();
        }

        int rrfK = intentProperties.getRrfK();
        double maxMultiHitRatio = intentProperties.getMaxMultiHitRatio();

        // 1. Expand dual-channel for low-confidence sub-queries
        List<SubQuery> expanded = expandDualChannels(subQueries);

        // 2. Execute each sub-query independently with weighted RRF
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Seed> seedMap = new LinkedHashMap<>();
        Map<String, Double> maxEffWeightMap = new LinkedHashMap<>();

        for (SubQuery sq : expanded) {
            List<Seed> groupResults;
            try {
                // Use multi-path overload when available
                groupResults = kg.hybridSearch(sq.query(), projectPaths, perQueryLimit);
            } catch (Exception ex) {
                log.warn("[MultiQuerySearcher] hybridSearch failed for query='{}': {}",
                        sq.query(), ex.getMessage());
                continue;
            }
            if (groupResults == null || groupResults.isEmpty()) {
                continue;
            }

            // 3. Accumulate weighted RRF score per nodeId
            double wEff = intentProperties.effectiveWeight(sq.intentType());
            for (int rank = 0; rank < groupResults.size(); rank++) {
                Seed seed = groupResults.get(rank);
                if (seed == null || seed.nodeId() == null) continue;

                String nodeId = seed.nodeId();
                double contribution = wEff / (rrfK + rank + 1);
                rrfScores.merge(nodeId, contribution, Double::sum);
                seedMap.putIfAbsent(nodeId, seed);
                maxEffWeightMap.merge(nodeId, wEff, Double::max);
            }
        }

        // 4. Multi-hit normalization
        normalizeMultiHitScores(rrfScores, maxEffWeightMap, maxMultiHitRatio);

        if (rrfScores.isEmpty()) {
            log.info("[MultiQuerySearcher] no results from {} sub-queries", subQueries.size());
            return List.of();
        }

        // 5. Sort by RRF score descending
        List<Seed> ranked = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> {
                    Seed original = seedMap.get(e.getKey());
                    return new Seed(e.getKey(), e.getValue(), original.summary());
                })
                .toList();

        log.info("[MultiQuerySearcher] {} sub-queries ({} expanded) → {} unique nodes → seeds (scores: {}-{})",
                subQueries.size(), expanded.size(), ranked.size(),
                ranked.isEmpty() ? "N/A" : String.format("%.4f", ranked.get(0).score()),
                ranked.isEmpty() ? "N/A" : String.format("%.4f", ranked.get(ranked.size() - 1).score()));

        return ranked;
    }

    /**
     * Backward-compatible single-path overload.
     */
    public List<Seed> search(List<SubQuery> subQueries, String projectPath,
                             int perQueryLimit, int topK) {
        return search(subQueries, List.of(projectPath), perQueryLimit, topK);
    }

    /**
     * Backward-compatible: accepts plain string sub-queries,
     * wraps them as GENERAL SubQueries with full confidence.
     */
    public List<Seed> searchByStrings(List<String> subQueryStrings, String projectPath,
                                      int perQueryLimit, int topK) {
        List<SubQuery> subQueries = subQueryStrings.stream()
                .map(SubQuery::general)
                .collect(java.util.stream.Collectors.toList());
        return search(subQueries, List.of(projectPath), perQueryLimit, topK);
    }

    /**
     * Expand low-confidence sub-queries with GENERAL fallback.
     */
    private List<SubQuery> expandDualChannels(List<SubQuery> subQueries) {
        List<SubQuery> expanded = new java.util.ArrayList<>();
        double threshold = intentProperties.getDualChannelThreshold();

        for (SubQuery sq : subQueries) {
            expanded.add(sq);
            if (sq.intentType() != IntentType.GENERAL && sq.confidence() < threshold) {
                expanded.add(new SubQuery(sq.query(), IntentType.GENERAL, 1.0));
            }
        }
        return expanded;
    }

    /**
     * Normalize multi-hit scores to prevent weak stacking.
     * Cap per-node score at max(w_eff) * ratio.
     */
    private void normalizeMultiHitScores(Map<String, Double> rrfScores,
                                          Map<String, Double> maxEffWeightMap,
                                          double ratio) {
        if (ratio <= 0) return;
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            Double maxW = maxEffWeightMap.get(entry.getKey());
            if (maxW == null) continue;
            double cap = maxW * ratio;
            if (entry.getValue() > cap) {
                entry.setValue(cap);
            }
        }
    }
}
