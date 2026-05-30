package com.huawei.hisi.agent;

import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentResult;

import reactor.core.publisher.Flux;

/**
 * 诊断 Agent 接口
 * 所有诊断 Agent 必须实现此接口
 *
 * 设计原则：
 * 1. 单一职责：每个 Agent 专注于一种诊断能力
 * 2. 置信度机制：Agent 需评估自身对当前问题的处理能力
 * 3. 并发安全：Agent 实现应支持并发调用
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface DiagnosticAgent {

    /**
     * 获取 Agent 类型标识
     * 用于日志、监控和结果追踪
     *
     * @return Agent 类型字符串，如 "STACK_TRACE", "LOG_ANALYSIS"
     */
    String getAgentType();

    /**
     * 获取 Agent 名称（用于展示）
     *
     * @return Agent 名称
     */
    String getAgentName();

    /**
     * 计算置信度
     * 评估 Agent 对当前上下文的处理能力
     *
     * 置信度范围：0.0 - 1.0
     * - 0.0：完全不适用，应跳过
     * - 0.5：可以处理，但结果可能不完整
     * - 1.0：完全适用，结果可信度高
     *
     * @param context 诊断上下文
     * @return 置信度值
     */
    double calculateConfidence(AgentContext context);

    /**
     * 执行诊断
     *
     * @param context 诊断上下文
     * @return 诊断结果
     */
    AgentResult execute(AgentContext context);

    /**
     * 获取 Agent 优先级
     * 数值越小优先级越高，越先执行
     *
     * @return 优先级值（默认 100）
     */
    default int getPriority() {
        return 100;
    }

    /**
     * 判断是否可以跳过
     * 当置信度过低时，编排器可以跳过此 Agent
     *
     * @param confidence 计算出的置信度
     * @return 是否可跳过
     */
    default boolean canSkip(double confidence) {
        return confidence < 0.3;
    }

    /**
     * 获取依赖的 Agent 类型
     * 返回此 Agent 需要先执行的 Agent 类型列表
     *
     * @return 依赖的 Agent 类型列表，空列表表示无依赖
     */
    default java.util.List<String> getDependencies() {
        return java.util.Collections.emptyList();
    }

    /**
     * 流式执行诊断
     * 返回实时流式输出，适用于需要 SSE 输出的场景
     *
     * 默认实现：调用 execute 方法，将结果转换为流式输出
     * 子类可覆盖此方法实现真正的流式诊断
     *
     * @param context 诊断上下文
     * @return 流式诊断结果
     */
    default Flux<AgentResult> executeStreaming(AgentContext context) {
        // 默认实现：将同步结果转换为流式输出
        AgentResult result = execute(context);
        if (result.isStreaming()) {
            // 如果已经是流式结果，直接返回
            return result.getStream()
                    .map(chunk -> AgentResult.builder()
                            .agentType(result.getAgentType())
                            .requestId(result.getRequestId())
                            .streaming(true)
                            .sessionId(result.getSessionId())
                            .conclusion(chunk)
                            .build());
        }
        // 将同步结果包装为单元素流
        return Flux.just(result);
    }
}