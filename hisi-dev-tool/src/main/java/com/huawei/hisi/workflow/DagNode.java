package com.huawei.hisi.workflow;

import java.util.Map;

/**
 * A single executable step in a workflow DAG.
 */
public interface DagNode {

    /** Logical node name used for checkpoint lookup, e.g. {@code "clarify"}. */
    String name();

    /** Agent id used for {@code AgentRegistry} lookup. */
    String agentId();

    /**
     * Execute this node with the supplied input and return its output.
     *
     * @throws ClarifyRequiredException when the node cannot produce its output
     *                                  without additional user clarification
     */
    Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException;

    /**
     * Execute this node with progress reporting. Default delegates to
     * {@link #execute(Map)} and ignores the sink — backward compatible with
     * nodes that have no intermediate progress to report.
     *
     * @param sink 过程消息通道；节点可在执行期间多次 {@link NodeEventSink#emit(String)}
     *             输出进度，供 {@link DagExecutor} 包装为 {@code NODE_DELTA} 事件
     * @throws ClarifyRequiredException 同 {@link #execute(Map)}
     */
    default Map<String, Object> execute(Map<String, Object> input, NodeEventSink sink)
            throws ClarifyRequiredException {
        return execute(input);
    }
}
