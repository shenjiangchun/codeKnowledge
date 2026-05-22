package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.Seed;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Resolves the innermost {@link InvolvedRing} for a query. */
@Component
public class InvolvedRingResolver {

    private static final int DEFAULT_SEED_LIMIT = 15;

    private final KgMcpClient kg;

    public InvolvedRingResolver(KgMcpClient kg) {
        this.kg = kg;
    }

    public InvolvedRing resolve(String query, String projectPath) {
        List<Seed> seeds = kg.hybridSearch(query, projectPath, DEFAULT_SEED_LIMIT);
        List<Entry> entries = kg.entryPoints(projectPath, "ALL");

        List<Impl> allImpls = new ArrayList<>();
        for (Seed seed : seeds) {
            if (seed == null || seed.nodeId() == null) continue;
            List<Impl> impls = kg.implementations(seed.nodeId(), projectPath);
            if (impls != null) allImpls.addAll(impls);
        }
        return new InvolvedRing(seeds, entries, allImpls);
    }
}
