package com.huawei.hisi.loganalysis.orchestrator;

import java.util.Map;

/**
 * DAG node interface for log root cause analysis.
 * Each node transforms input to output, following RAM's DagNode pattern.
 */
public interface LogAnalysisDagNode {

    /**
     * Node name for logging and checkpoint identification.
     */
    String name();

    /**
     * Execute the node with given input.
     *
     * @param input Input from previous node or initial input
     * @return Output to pass to next node
     * @throws Exception on failure (will be caught by DagExecutor)
     */
    Map<String, Object> execute(Map<String, Object> input) throws Exception;
}