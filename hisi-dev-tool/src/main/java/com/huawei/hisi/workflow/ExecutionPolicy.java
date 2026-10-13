package com.huawei.hisi.workflow;

import java.util.Map;

/**
 * 执行策略：工作流定义声明「如何执行」（区别于 {@link WorkflowDefinition#nodeNames()} 的「执行什么」）。
 *
 * <p>引擎（{@link DagExecutor}）按策略执行，策略本身从 {@link WorkflowDefinition#metadata()} 派生 ——
 * 新的执行策略需求（并行度、重试策略等）在此类型上扩展，而不是在执行器里累加 if/else。
 *
 * <p>向后兼容：不带 metadata 的既有工作流派生出 {@link #DEFAULT}，行为与改动前完全一致。
 */
public record ExecutionPolicy(boolean interNodeHitlEnabled, boolean emitNodeEvents) {

    /** 既有行为：非首跑且非强制重跑时，每个非末位节点执行后插入 HITL 确认门；不发出节点生命周期事件。 */
    public static final ExecutionPolicy DEFAULT = new ExecutionPolicy(true, false);

    /**
     * 从工作流 metadata 派生策略；目前支持：
     * <ul>
     *   <li>{@code skipHitl=true} 关闭节点间确认门</li>
     *   <li>{@code emitNodeEvents=true} 为每个实际执行的节点发出 {@code NODE_START}/{@code NODE_COMPLETE}
     *       生命周期事件（供 SSE 向前端推送节点进度）</li>
     * </ul>
     */
    public static ExecutionPolicy fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return DEFAULT;
        }
        boolean skipHitl = Boolean.TRUE.equals(metadata.get("skipHitl"));
        boolean emitNodeEvents = Boolean.TRUE.equals(metadata.get("emitNodeEvents"));
        return new ExecutionPolicy(!skipHitl, emitNodeEvents);
    }
}
