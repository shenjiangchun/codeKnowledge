package com.huawei.hisi.ram.kg.dto;

import java.util.List;

/** A node in a downstream callees tree. */
public record CallTreeNode(String nodeId,
                           String className,
                           String methodName,
                           int depth,
                           List<CallTreeNode> children) {

    public CallTreeNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
