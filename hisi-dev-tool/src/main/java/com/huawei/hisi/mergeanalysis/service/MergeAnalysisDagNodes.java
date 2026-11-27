package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.WorkflowRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 合入分析的节点链：按 {@code AgentRegistry} 里 {@code merge-analysis} 的声明顺序，从 {@link WorkflowRegistry} 解析。
 * 节点名与顺序只在注册表一处声明——此前这里手工 {@code new} 三个节点，与注册表声明（节点名、数量）各说各话，
 * 声明形同虚设。
 */
@Component
public class MergeAnalysisDagNodes {

    private static final String WORKFLOW_KEY = "merge-analysis";

    private final List<DagNode> nodes;

    public MergeAnalysisDagNodes(WorkflowRegistry nodeRegistry) {
        List<String> names = AgentRegistry.workflow(WORKFLOW_KEY)
                .orElseThrow(() -> new IllegalStateException("AgentRegistry 未声明工作流 " + WORKFLOW_KEY))
                .nodes();
        List<DagNode> resolved = new ArrayList<>(names.size());
        for (String name : names) {
            DagNode node = nodeRegistry.getNode(name);
            if (node == null) {
                throw new IllegalStateException("合入分析节点 " + name + " 未注册为 bean（见 " + WORKFLOW_KEY + " 的声明）");
            }
            resolved.add(node);
        }
        this.nodes = List.copyOf(resolved);
    }

    public List<DagNode> nodes() {
        return nodes;
    }
}
