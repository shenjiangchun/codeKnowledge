package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.dto.CallTreeNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Middle ring: methods modified along the callees tree of each seed. */
public record ModifiedRing(List<CallTreeNode> tree) {

    public ModifiedRing {
        tree = tree == null ? List.of() : List.copyOf(tree);
    }

    public Set<String> allNodeIds() {
        Set<String> ids = new LinkedHashSet<>();
        Deque<CallTreeNode> stack = new ArrayDeque<>(tree);
        while (!stack.isEmpty()) {
            CallTreeNode n = stack.pop();
            if (n == null) continue;
            if (n.nodeId() != null) ids.add(n.nodeId());
            if (n.children() != null) stack.addAll(n.children());
        }
        return ids;
    }
}
