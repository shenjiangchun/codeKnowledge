package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scores impact risk on a 0..100 scale and buckets into a {@link RiskLevel}.
 *
 * <p>Formula:
 * <ul>
 *     <li>bridgeWeight = min(100, (bridges + crossService) * 20) — 50%</li>
 *     <li>fanInNorm    = min(100, max(involved.entries, impact.upstream) * 5) — 30%</li>
 *     <li>depthInv     = 100 / max(1, depth(modified.tree)) — 20%</li>
 * </ul>
 * Score &lt; 30 = LOW, &lt; 60 = MEDIUM, &lt; 85 = HIGH, otherwise CRITICAL.</p>
 */
@Component
public class RiskScorer {

    public RiskScore score(InvolvedRing involved, ModifiedRing modified, ImpactRing impact) {
        int bridgeCount = impact.bridges().size() + impact.crossService().size();
        double bridgeWeight = Math.min(100.0, bridgeCount * 20.0);

        int maxFanIn = Math.max(involved.entries().size(), impact.upstream().size());
        double fanInNorm = Math.min(100.0, maxFanIn * 5.0);

        int maxDepth = computeMaxDepth(modified.tree());
        double depthInv = 100.0 / Math.max(1, maxDepth);

        double score = 0.5 * bridgeWeight + 0.3 * fanInNorm + 0.2 * depthInv;
        return new RiskScore(score, bucket(score));
    }

    private static RiskLevel bucket(double score) {
        if (score < 30) return RiskLevel.LOW;
        if (score < 60) return RiskLevel.MEDIUM;
        if (score < 85) return RiskLevel.HIGH;
        return RiskLevel.CRITICAL;
    }

    static int computeMaxDepth(List<CallTreeNode> roots) {
        int max = 0;
        if (roots == null) return 0;
        for (CallTreeNode root : roots) {
            max = Math.max(max, depthOf(root, 1));
        }
        return max;
    }

    private static int depthOf(CallTreeNode node, int current) {
        if (node == null || node.children() == null || node.children().isEmpty()) {
            return current;
        }
        int max = current;
        for (CallTreeNode child : node.children()) {
            max = Math.max(max, depthOf(child, current + 1));
        }
        return max;
    }
}
