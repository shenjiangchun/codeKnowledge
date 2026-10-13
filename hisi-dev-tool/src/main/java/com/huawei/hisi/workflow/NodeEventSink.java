package com.huawei.hisi.workflow;

/**
 * 节点执行期间的过程事件通道：节点可在 {@code execute} 过程中多次调用
 * {@link #emit(String)} 产出面向用户的过程消息。
 *
 * <p>nodeName 由 {@link DagExecutor} 在创建 sink 时绑定，节点无需感知；
 * 每条消息被包装为 {@code NODE_DELTA} 事件持久化并经 SSE 流式推送，
 * 前端据此渲染"类似 RAM 对话"的实时过程日志。</p>
 */
@FunctionalInterface
public interface NodeEventSink {

    /** 推送一条过程消息（如"正在调用 LLM 根因分析…"）。 */
    void emit(String message);
}
