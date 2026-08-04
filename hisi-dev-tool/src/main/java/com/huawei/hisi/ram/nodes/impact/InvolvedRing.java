package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.Seed;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Innermost ring: directly involved nodes (seeds + entries + implementations). */
public record InvolvedRing(List<Seed> seeds, List<Entry> entries, List<Impl> impls) {

    public InvolvedRing {
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
        entries = entries == null ? List.of() : List.copyOf(entries);
        impls = impls == null ? List.of() : List.copyOf(impls);
    }

    public Set<String> allNodeIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Seed s : seeds) {
            if (s.nodeId() != null) ids.add(s.nodeId());
        }
        for (Entry e : entries) {
            if (e.nodeId() != null) ids.add(e.nodeId());
        }
        for (Impl i : impls) {
            if (i.nodeId() != null) ids.add(i.nodeId());
        }
        return ids;
    }
}
