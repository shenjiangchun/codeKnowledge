package com.huawei.hisi.workflow.v2;

import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.piagent.PiAgentProperties;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import org.springframework.stereotype.Component;

/**
 * 按需创建架构 ReAct 节点实例。
 *
 * <p>架构分析已经整体平替为 pi 会话（手动 ReAct 循环的 v1/v2 实现已删除）：
 * 每次启动流程都创建新的 {@link PiReActAgentNode}。{@code piagent.flows.arch-react}
 * 开关关闭时创建请求直接失败，提示切回老实现的入口已不存在——那是本次版本刻意移除的旧代码。
 *
 * <p>节点本身不做成 bean：{@code WorkflowRegistry} 按节点名收集所有 {@code DagNode} bean，
 * 而替代节点为了保持事件协议必须与 v1 同名，注册进去会覆盖 v1。
 */
@Component
public class ArchReActNodeV2Factory {

    private final ArchAgentToolRegistry registry;
    private final ArchDiagramGenerator diagramGenerator;
    private final AgentFlowCancelRegistry cancelRegistry;
    private final ReActSession session;
    private final PiAgentProperties piProperties;

    public ArchReActNodeV2Factory(ArchAgentToolRegistry registry,
                                  ArchDiagramGenerator diagramGenerator,
                                  AgentFlowCancelRegistry cancelRegistry,
                                  ReActSession session,
                                  PiAgentProperties piProperties) {
        this.registry = registry;
        this.diagramGenerator = diagramGenerator;
        this.cancelRegistry = cancelRegistry;
        this.session = session;
        this.piProperties = piProperties;
    }

    /** 每次启动流程创建一个新 pi 节点实例，与旧节点的用法保持一致。 */
    public DagNode create() {
        if (!piProperties.flowEnabled("arch-react")) {
            throw new IllegalStateException(
                    "piagent.flows.arch-react 已关闭：手动 ReAct 循环已随本次版本移除，架构分析只能走 pi");
        }
        return new PiReActAgentNode(registry, diagramGenerator, session, cancelRegistry);
    }
}

