package com.huawei.hisi.agentflow;

/**
 * 流程被用户中止时由节点抛出，用来打断后续步骤。
 *
 * <p>继承 {@link RuntimeException}，因此能穿过只捕获运行时异常的执行器（如 {@code DagExecutor}）。
 */
public class AgentFlowCancelledException extends RuntimeException {

    public AgentFlowCancelledException() {
        super("flow cancelled by user");
    }
}
