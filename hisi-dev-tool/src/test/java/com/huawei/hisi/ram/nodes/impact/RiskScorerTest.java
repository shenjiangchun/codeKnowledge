package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RiskScorerTest {

    @Test
    void score_matchesPlanExample_high() {
        // bridges = 3 → bridgeWeight = 60
        List<Bridge> bridges = List.of(
                new Bridge("b1", "FEIGN", "svc"),
                new Bridge("b2", "MQ", "topic"),
                new Bridge("b3", "BRIDGE", null));

        // entries = 20 → fanInNorm = 100
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(new Entry("e" + i, "C", "m", "CONTROLLER"));
        }

        // depth = 4 → depthInv = 25
        CallTreeNode leaf = new CallTreeNode("l", "L", "m", 3, List.of());
        CallTreeNode l2 = new CallTreeNode("l2", "L2", "m", 2, List.of(leaf));
        CallTreeNode l1 = new CallTreeNode("l1", "L1", "m", 1, List.of(l2));
        CallTreeNode root = new CallTreeNode("r", "R", "m", 0, List.of(l1));

        InvolvedRing involved = new InvolvedRing(List.of(), entries, List.of());
        ModifiedRing modified = new ModifiedRing(List.of(root));
        ImpactRing impact = new ImpactRing(List.of(), List.of(), List.of(), bridges);

        RiskScore score = new RiskScorer().score(involved, modified, impact);

        // 0.5*60 + 0.3*100 + 0.2*25 = 65
        assertThat(score.score()).isEqualTo(65.0, within(2.0));
        assertThat(score.level()).isEqualTo(RiskLevel.HIGH);
    }
}
