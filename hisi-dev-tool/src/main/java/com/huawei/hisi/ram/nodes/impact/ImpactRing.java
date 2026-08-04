package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.Entry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Outer ring: nodes impacted by the modifications (upstream, downstream, bridges). */
public record ImpactRing(List<Entry> upstream,
                         List<Entry> downstream,
                         List<Bridge> crossService,
                         List<Bridge> bridges) {

    public ImpactRing {
        upstream = upstream == null ? List.of() : List.copyOf(upstream);
        downstream = downstream == null ? List.of() : List.copyOf(downstream);
        crossService = crossService == null ? List.of() : List.copyOf(crossService);
        bridges = bridges == null ? List.of() : List.copyOf(bridges);
    }

    public Set<String> allNodeIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry e : upstream) if (e.nodeId() != null) ids.add(e.nodeId());
        for (Entry e : downstream) if (e.nodeId() != null) ids.add(e.nodeId());
        for (Bridge b : crossService) if (b.nodeId() != null) ids.add(b.nodeId());
        for (Bridge b : bridges) if (b.nodeId() != null) ids.add(b.nodeId());
        return ids;
    }
}
