package com.huawei.hisi.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Registers built-in workflow definitions at startup.
 */
@Component
public class WorkflowAutoConfig {

    public WorkflowAutoConfig(WorkflowRegistry registry) {
        registerBuiltIns(registry);
    }

    /** 内置工作流注册（静态以便测试复用）：声明全部来自 AgentRegistry，这里只把它们装进注册表。 */
    static void registerBuiltIns(WorkflowRegistry registry) {
        for (com.huawei.hisi.agentbase.AgentRegistry.Workflow def
                : com.huawei.hisi.agentbase.AgentRegistry.workflows().values()) {
            registry.registerWorkflow(new WorkflowDefinition(
                    def.key(), def.displayName(), def.description(),
                    def.nodes(), def.policy()));
        }
    }
}
