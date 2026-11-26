package com.huawei.hisi.workflow.v2;

import com.huawei.hisi.agentbase.FlowEngine;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowV2ControllerTest {

    private final WorkflowRegistry registry = mock(WorkflowRegistry.class);
    private final SessionMappingService mapping = mock(SessionMappingService.class);
    private final AgentSessionRepository sessionRepo = mock(AgentSessionRepository.class);
    private final DagExecutor dagExecutor = mock(DagExecutor.class);
    private final ArchReActNodeV2Factory reactNodeFactory = mock(ArchReActNodeV2Factory.class);
    private final FlowEngine flowEngine = new FlowEngine(dagExecutor, registry, reactNodeFactory);
    private final AgentFlowCancelRegistry cancelRegistry = new AgentFlowCancelRegistry();
    private WorkflowV2Controller controller;

    @BeforeEach
    void setUp() {
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            AgentSession session = inv.getArgument(0);
            session.setId(42L);
            return session;
        });
        controller = new WorkflowV2Controller(registry, mapping, sessionRepo, mock(AgentEventRepository.class),
                dagExecutor, cancelRegistry, flowEngine);
    }

    @Test
    void archDeepAnalysisSwapsOnlyTheReactNodeForPi() {
        DagNode evidenceNode = mock(DagNode.class);
        PiReActAgentNode piReact = mock(PiReActAgentNode.class);
        when(registry.getWorkflow("arch-deep-analysis")).thenReturn(
                definition(List.of("arch_evidence_collect", "arch_react_agent")));
        when(registry.getNode("arch_evidence_collect")).thenReturn(evidenceNode);
        when(reactNodeFactory.create()).thenReturn(piReact);
        when(dagExecutor.run(anyList(), anyLong(), anyMap(), any())).thenAnswer(inv ->
                new ExecutionResult(42L, SessionStatus.DONE, List.of(), List.of(), Map.of()));

        ApiResponse<WorkflowController.StartResponse> response = controller.start(
                new WorkflowController.StartRequest("arch-deep-analysis", Map.of("projectPaths", List.of("D:/repo"))));

        assertTrue(response.isSuccess());
        List<DagNode> nodes = executedNodes();
        // 证据收集节点共用同一个实例，只有 ReAct 节点换成 pi 版
        assertEquals(2, nodes.size());
        assertSame(evidenceNode, nodes.get(0));
        assertSame(piReact, nodes.get(1));
    }

    @Test
    void workflowWithoutV2VersionRunsExactlyTheV1Nodes() {
        List<String> v1NodeNames = List.of("clarify", "impact");
        List<DagNode> v1Nodes = List.of(mock(DagNode.class), mock(DagNode.class));
        when(registry.getWorkflow("demand")).thenReturn(definition(v1NodeNames));
        when(registry.getNode("clarify")).thenReturn(v1Nodes.get(0));
        when(registry.getNode("impact")).thenReturn(v1Nodes.get(1));
        when(dagExecutor.run(anyList(), anyLong(), anyMap(), any())).thenAnswer(inv ->
                new ExecutionResult(42L, SessionStatus.DONE, List.of(), List.of(), Map.of()));

        controller.start(new WorkflowController.StartRequest("demand", Map.of("projectPaths", List.of("D:/repo"))));

        // 执行在异步线程上：等它跑到为止；节点链由引擎按名解析，元素同一性不变
        List<DagNode> nodes = executedNodes();
        assertSame(v1Nodes.get(0), nodes.get(0));
        assertSame(v1Nodes.get(1), nodes.get(1));
        verify(reactNodeFactory, never()).create();
    }

    @Test
    void unknownTypeAndMissingPathsAreRejectedWithoutCreatingASession() {
        when(registry.getWorkflow("nope")).thenReturn(null);
        assertEquals(400, controller.start(
                new WorkflowController.StartRequest("nope", Map.of("projectPaths", List.of("D:/repo")))).getCode());

        when(registry.getWorkflow("demand")).thenReturn(definition(List.of("clarify")));
        assertEquals(400, controller.start(new WorkflowController.StartRequest("demand", Map.of())).getCode());

        verify(sessionRepo, never()).save(any());
    }

    @Test
    void abortRequestsCancellationOfTheRunningFlow() {
        when(mapping.resolveBackendId("handle-1")).thenReturn(42L);
        when(sessionRepo.findById(42L)).thenReturn(Optional.of(sessionWithStatus(SessionStatus.RUNNING)));

        ApiResponse<Void> response = controller.abort("handle-1");

        assertTrue(response.isSuccess());
        assertTrue(cancelRegistry.isCancelled(42L));
        verify(sessionRepo).updateStatus(42L, SessionStatus.ABORTED);
    }

    @Test
    void abortingAFinishedSessionDoesNotLeaveACancelFlagBehind() {
        when(mapping.resolveBackendId("handle-1")).thenReturn(42L);
        when(sessionRepo.findById(42L)).thenReturn(Optional.of(sessionWithStatus(SessionStatus.DONE)));

        ApiResponse<Void> response = controller.abort("handle-1");

        // 与 v1 一致：会话状态照样记成 ABORTED；但流程已经不在运行，不登记取消
        assertTrue(response.isSuccess());
        verify(sessionRepo).updateStatus(42L, SessionStatus.ABORTED);
        assertFalse(cancelRegistry.isCancelled(42L));
    }

    private static AgentSession sessionWithStatus(SessionStatus status) {
        return AgentSession.builder().id(42L).status(status).build();
    }

    private List<DagNode> executedNodes() {
        ArgumentCaptor<List<DagNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(dagExecutor, timeout(5000).atLeastOnce()).run(captor.capture(), anyLong(), anyMap(), any());
        return captor.getValue();
    }

    private static WorkflowDefinition definition(List<String> nodeNames) {
        return new WorkflowDefinition("demand", "需求分析", "desc", nodeNames, Map.of());
    }
}
