package com.huawei.hisi.agentflow;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * agent 流的取消登记：让「中止」真正停掉执行，而不只是断开事件流。
 *
 * <p>用法：执行线程在调用流程前用 {@link #callBound} 绑定流程 ID；节点在耗时步骤之间调用
 * {@link #isCurrentCancelled()}，为真时抛出 {@link AgentFlowCancelledException} 结束执行。
 * 流程 ID 由调用方定义——工作流用会话的后端 ID，日志分析用报告 ID。
 */
@Component
public class AgentFlowCancelRegistry {

    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Long> bound = new ThreadLocal<>();

    /**
     * 在当前线程上绑定流程 ID 并执行，返回执行结果；节点在同一线程内运行时才能感知取消。
     * 只提供这一个重载，避免 lambda 同时匹配 {@code Runnable} 与 {@code Supplier} 造成歧义。
     */
    public <T> T callBound(long flowId, Supplier<T> body) {
        bound.set(flowId);
        try {
            return body.get();
        } finally {
            bound.remove();
        }
    }

    /** 当前线程绑定的流程是否已被请求取消。没有绑定流程时恒为 false。 */
    public boolean isCurrentCancelled() {
        Long flowId = bound.get();
        return flowId != null && cancelled.contains(flowId);
    }

    public void requestCancel(long flowId) {
        cancelled.add(flowId);
    }

    public boolean isCancelled(long flowId) {
        return cancelled.contains(flowId);
    }

    /** 流程结束后清掉标记，避免长期运行时集合无限增长。 */
    public void clear(long flowId) {
        cancelled.remove(flowId);
    }
}
