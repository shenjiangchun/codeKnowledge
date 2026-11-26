package com.huawei.hisi.workflow.v2;

import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import com.huawei.hisi.workflow.DagExecutor;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.ExecutionPolicy;
import com.huawei.hisi.workflow.ExecutionResult;
import com.huawei.hisi.workflow.WorkflowController;
import com.huawei.hisi.workflow.WorkflowDefinition;
import com.huawei.hisi.workflow.WorkflowRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 工作流 v2 接口：{@code /api/v2/workflow}。
 *
 * <p>继承 v1 控制器，所以会话读写端点（status / report / events / stream）是同一份代码，请求与响应完全一致。
 * 本类只重写两处：
 * <ul>
 *   <li>{@code start}：{@link #V2_WORKFLOWS} 里登记过的工作流类型，沿用 v1 定义的节点顺序与执行策略，
 *       只把其中的 ReAct 节点换成 v2 实现；其余类型与 v1 完全相同。</li>
 *   <li>{@code abort}：除了断开事件流，还为仍在运行的会话登记取消标记，让正在执行的节点真正停下来。</li>
 * </ul>
 * v2 节点与 v1 同名，事件里的 {@code nodeName} 不变，前端只需要把接口基地址换成 v2。
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/workflow")
public class WorkflowV2Controller extends WorkflowController {

    /** 启用 v2 流程的工作流类型。 */
    static final Set<String> V2_WORKFLOWS = Set.of("arch-deep-analysis");

    private final AgentFlowCancelRegistry cancelRegistry;
    private final ArchReActNodeV2Factory reactNodeFactory;

    public WorkflowV2Controller(WorkflowRegistry registry,
                                SessionMappingService sessionMappingService,
                                AgentSessionRepository sessionRepository,
                                AgentEventRepository eventRepository,
                                DagExecutor dagExecutor,
                                AgentFlowCancelRegistry cancelRegistry,
                                ArchReActNodeV2Factory reactNodeFactory) {
        super(registry, sessionMappingService, sessionRepository, eventRepository, dagExecutor);
        this.cancelRegistry = cancelRegistry;
        this.reactNodeFactory = reactNodeFactory;
    }

    @Override
    @PostMapping("/start")
    public ApiResponse<StartResponse> start(@RequestBody StartRequest request) {
        if (request == null || request.workflowType() == null || request.workflowType().isBlank()) {
            return ApiResponse.error(400, "workflowType is required");
        }
        WorkflowDefinition def = registry.getWorkflow(request.workflowType());
        if (def == null) {
            return ApiResponse.error(400, "unknown workflowType: " + request.workflowType());
        }
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        if (!(input.get("projectPaths") instanceof List<?> paths) || paths.isEmpty()) {
            return ApiResponse.error(400, "input.projectPaths is required");
        }

        List<DagNode> nodes = V2_WORKFLOWS.contains(request.workflowType())
                ? v2Nodes(def) : registry.resolveNodes(def.nodeNames());
        ExecutionPolicy policy = ExecutionPolicy.fromMetadata(def.metadata());

        // 与 v1 一致：先种会话行并注册 UUID→id 映射，SSE 可以立即挂接
        String handle = UUID.randomUUID().toString();
        AgentSession seeded = sessionRepository.save(AgentSession.newRunning("anonymous", SessionType.WORKFLOW));
        long backendId = seeded.getId();
        seeded.setUuid(handle);
        sessionRepository.update(seeded);
        sessionMappingService.register(handle, backendId);
        log.info("[WorkflowV2][start] type={} nodeNames={} handle={} backendId={}",
                request.workflowType(), def.nodeNames(), handle, backendId);

        cancelRegistry.clear(backendId);
        CompletableFuture.runAsync(() -> runFlow(nodes, backendId, input, policy))
                .exceptionally(ex -> {
                    log.error("[WorkflowV2][start] DAG execution failed backendId={}", backendId, ex);
                    sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
                    return null;
                });
        return ApiResponse.success(new StartResponse(handle));
    }

    /** 按 v1 定义的顺序取节点：ReAct 节点换成 v2 实现，其余节点与 v1 共用同一个实例。 */
    List<DagNode> v2Nodes(WorkflowDefinition def) {
        List<DagNode> nodes = new ArrayList<>(def.nodeNames().size());
        for (String name : def.nodeNames()) {
            DagNode node = PiReActAgentNode.NODE_NAME.equals(name)
                    ? reactNodeFactory.create() : registry.getNode(name);
            if (node == null) {
                throw new IllegalArgumentException("Unknown node type: " + name);
            }
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 在绑定了取消登记的线程里执行流程。节点因取消而中断时，执行器会把会话记成 FAILED，
     * 这里还原成 ABORTED，与 v1 中止后的状态保持一致。
     */
    void runFlow(List<DagNode> nodes, long backendId, Map<String, Object> input, ExecutionPolicy policy) {
        try {
            ExecutionResult result = cancelRegistry.callBound(backendId,
                    () -> dagExecutor.run(nodes, backendId, input, policy));
            if (result != null && result.status() == SessionStatus.FAILED && cancelRegistry.isCancelled(backendId)) {
                sessionRepository.updateStatus(backendId, SessionStatus.ABORTED);
            }
        } finally {
            cancelRegistry.clear(backendId);
        }
    }

    @Override
    @PostMapping("/sessions/{sid}/abort")
    public ApiResponse<Void> abort(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        // 只有还在运行的流程才需要登记取消；已结束的会话没人再来清这个标记，登记了只会一直留在集合里
        boolean running = backendId != null && sessionRepository.findById(backendId)
                .map(s -> s.getStatus() == SessionStatus.RUNNING).orElse(false);
        ApiResponse<Void> result = super.abort(handle);
        if (running) {
            cancelRegistry.requestCancel(backendId);
        }
        return result;
    }
}
