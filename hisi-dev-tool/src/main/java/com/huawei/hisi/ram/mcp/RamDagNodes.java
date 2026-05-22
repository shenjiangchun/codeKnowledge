package com.huawei.hisi.ram.mcp;

import com.huawei.hisi.ram.nodes.ClarifyNode;
import com.huawei.hisi.ram.nodes.ImplementNode;
import com.huawei.hisi.ram.nodes.VerifyNode;
import com.huawei.hisi.ram.nodes.impact.ImpactNode;
import com.huawei.hisi.ram.orchestrator.DagNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Aggregates the four phase-one RAM nodes into an immutable, mockable bundle
 * so MCP tools only need a single constructor dependency.
 */
@Component
public class RamDagNodes {

    private final List<DagNode> phaseOne;

    public RamDagNodes(ClarifyNode clarifyNode,
                       ImpactNode impactNode,
                       ImplementNode implementNode,
                       VerifyNode verifyNode) {
        this.phaseOne = List.of(clarifyNode, impactNode, implementNode, verifyNode);
    }

    /** Direct ctor for tests — accepts any node list. */
    public RamDagNodes(List<DagNode> phaseOne) {
        this.phaseOne = phaseOne == null ? List.of() : List.copyOf(phaseOne);
    }

    public List<DagNode> phaseOne() {
        return phaseOne;
    }
}
