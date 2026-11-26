package com.huawei.hisi.agentbase;

import com.huawei.hisi.workflow.DagExecutor;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.ExecutionPolicy;
import com.huawei.hisi.workflow.ExecutionResult;
import com.huawei.hisi.workflow.WorkflowRegistry;
import com.huawei.hisi.workflow.v2.ArchReActNodeV2Factory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作流引擎：按 {@link AgentRegistry} 里声明的节点链执行一个工作流会话。
 *
 * <p>骨架是有序管道（用户确认的方式，责任链只用于节点内「多选一」）：
 * 游标逐节点执行；检查点按节点输入哈希复用；节点可以「等待」（澄清 / 确认，状态落库，续跑）或失败；
 * 执行策略（节点间确认门、生命周期事件）来自声明里的 {@code policy}。
 *
 * <p>与 {@link DagExecutor} 的分工：这里是入口（声明 → 节点链 → 策略），
 * {@link DagExecutor} 是纯机械执行（游标、检查点、等待、中止、事件），不关心流程是什么。
 * 节点链里「需要模型自己探索或动手」的节点（arch_react_agent、develop…）由 {@link ReActSession} 执行，
 * 由对应节点实现声明在 {@link AgentRegistry#NODE_TO_AGENT} 的映射里。
 */
@Slf4j
@Component
public class FlowEngine {

    private final DagExecutor executor;
    private final WorkflowRegistry nodeRegistry;
    private final ArchReActNodeV2Factory reactFactory;

    public FlowEngine(DagExecutor executor, WorkflowRegistry nodeRegistry, ArchReActNodeV2Factory reactFactory) {
        this.executor = executor;
        this.nodeRegistry = nodeRegistry;
        this.reactFactory = reactFactory;
    }

    /** 按工作流声明执行；未知工作流或解析不出的节点在启动时即报错。 */
    public ExecutionResult run(String workflowKey, long sessionId, Map<String, Object> initialInput) {
        AgentRegistry.Workflow def = AgentRegistry.workflow(workflowKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + workflowKey));
        List<DagNode> nodes = resolve(def.nodes());
        log.info("[FlowEngine] run type={} session={} nodes={} policy={}",
                workflowKey, sessionId, def.nodes(), def.policy());
        return executor.run(nodes, sessionId, initialInput, ExecutionPolicy.fromMetadata(def.policy()));
    }

    /** 节点名 → 节点实例：bean 优先；ReAct 替代节点（工厂按次创建）按声明映射解析。 */
    public List<DagNode> resolve(List<String> names) {
        List<DagNode> nodes = new ArrayList<>(names.size());
        for (String name : names) {
            DagNode node = "arch_react_agent".equals(name) ? reactFactory.create() : nodeRegistry.getNode(name);
            if (node == null) {
                throw new IllegalArgumentException("Unknown node type: " + name
                        + "（工作流声明里的节点必须是已注册的 bean，或声明在 AgentRegistry 的 ReAct 映射里）");
            }
            nodes.add(node);
        }
        return nodes;
    }
}
