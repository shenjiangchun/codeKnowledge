package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Seed;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Resolves the {@link ModifiedRing} (callees trees of each seed). */
@Component
public class ModifiedRingResolver {

    private final KgMcpClient kg;

    public ModifiedRingResolver(KgMcpClient kg) {
        this.kg = kg;
    }

    public ModifiedRing resolve(InvolvedRing involved, String projectPath, int depth) {
        if (involved == null) {
            return new ModifiedRing(List.of());
        }
        List<CallTreeNode> roots = new ArrayList<>();
        for (Seed seed : involved.seeds()) {
            if (seed == null || seed.nodeId() == null) continue;
            // Note: className/methodName not parsed from nodeId here — placeholder for later refinement.
            CallTreeNode tree = kg.calleesTree(seed.nodeId(), "", projectPath, depth);
            if (tree != null) roots.add(tree);
        }
        return new ModifiedRing(roots);
    }
}
