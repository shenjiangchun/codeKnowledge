package com.huawei.hisi.workbench.service;

import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import org.springframework.stereotype.Component;

/**
 * 让阶段代码无需注入注册表即可检查取消（runner 已用 callBound 绑定线程）。
 */
@Component
public final class AgentFlowCancelHolder {

    private static volatile AgentFlowCancelRegistry registry;

    public AgentFlowCancelHolder(AgentFlowCancelRegistry registry) {
        AgentFlowCancelHolder.registry = registry;
    }

    public static boolean isCancelled() {
        return registry != null && registry.isCurrentCancelled();
    }

    private AgentFlowCancelHolder() {}
}
