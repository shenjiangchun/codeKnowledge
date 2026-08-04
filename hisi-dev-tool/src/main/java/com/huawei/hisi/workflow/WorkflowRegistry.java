package com.huawei.hisi.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for workflow node types and workflow definitions.
 *
 * <p>All {@link DagNode} beans are auto-discovered via Spring and registered
 * by their {@link DagNode#name()}. Workflow definitions are registered at
 * startup or via the user-defined workflow API.
 */
@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final Map<String, DagNode> nodeRegistry = new ConcurrentHashMap<>();
    private final Map<String, WorkflowDefinition> workflowRegistry = new ConcurrentHashMap<>();

    /**
     * Auto-register all {@link DagNode} beans from the Spring context.
     */
    public WorkflowRegistry(List<DagNode> nodes) {
        for (DagNode node : nodes) {
            nodeRegistry.put(node.name(), node);
            log.info("[WorkflowRegistry] registered node: name={} agentId={}", node.name(), node.agentId());
        }
        log.info("[WorkflowRegistry] registered {} nodes", nodeRegistry.size());
    }

    /**
     * Register a workflow definition.
     */
    public void registerWorkflow(WorkflowDefinition def) {
        workflowRegistry.put(def.workflowType(), def);
        log.info("[WorkflowRegistry] registered workflow: type={} nodes={}",
                def.workflowType(), def.nodeNames());
    }

    /**
     * Get a workflow definition by type.
     */
    public WorkflowDefinition getWorkflow(String type) {
        return workflowRegistry.get(type);
    }

    /**
     * Get all registered workflow definitions.
     */
    public List<WorkflowDefinition> listWorkflows() {
        return List.copyOf(workflowRegistry.values());
    }

    /**
     * Get a node by name.
     */
    public DagNode getNode(String name) {
        return nodeRegistry.get(name);
    }

    /**
     * Get all available nodes (read-only view for UI).
     */
    public Map<String, DagNode> getAvailableNodes() {
        return Collections.unmodifiableMap(nodeRegistry);
    }

    /**
     * Build a {@link DagNode} list from node names in the given order.
     *
     * @throws IllegalArgumentException if any node name is not registered
     */
    public List<DagNode> resolveNodes(List<String> nodeNames) {
        List<DagNode> resolved = new ArrayList<>(nodeNames.size());
        for (String name : nodeNames) {
            DagNode node = nodeRegistry.get(name);
            if (node == null) {
                throw new IllegalArgumentException("Unknown node type: " + name);
            }
            resolved.add(node);
        }
        return resolved;
    }

    /**
     * Build and register a workflow from a list of node names.
     *
     * @throws IllegalArgumentException if any node name is not registered
     */
    public WorkflowDefinition buildWorkflow(String type, String displayName,
                                            String description, List<String> nodeNames) {
        // Validate all nodes exist
        for (String name : nodeNames) {
            if (!nodeRegistry.containsKey(name)) {
                throw new IllegalArgumentException("Unknown node type: " + name);
            }
        }
        WorkflowDefinition def = new WorkflowDefinition(type, displayName, description, nodeNames, Map.of());
        registerWorkflow(def);
        return def;
    }
}
