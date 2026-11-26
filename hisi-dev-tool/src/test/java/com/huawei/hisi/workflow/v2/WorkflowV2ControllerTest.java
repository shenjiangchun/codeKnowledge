package com.huawei.hisi.workflow.v2;

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
                dagExecutor, cancelRegistry, reactNodeFactory);
    }

    @Test
    void archDeepAnalysisSwapsOnlyTheReactNodeForPi() {
        DagNode evidenceNode = mock(DagNode.class);
        PiReActAgentNode v2React = mock(PiReActAgentNode.class);
        when(registry.getWorkflow("arch-deep-analysis")).thenReturn(
                definition(List.of("arch_evidence_collect", "arch_react_agent")));
        when(registry.getNode("arch_evidence_collect")).thenReturn(evidenceNode);
        when(reactNodeFactory.create()).thenReturn(v2React);
        when(dagExecutor.run(anyList(), anyLong(), anyMap(), any())).thenAnswer(inv ->
                new ExecutionResult(42L, SessionStatus.DONE, List.of(), List.of(), Map.of()));

        ApiResponse<WorkflowController.StartResponse> response = controller.start(
                new WorkflowController.StartRequest("arch-deep-analysis", Map.of("projectPaths", List.of("D:/repo"))));

        assertTrue(response.isSuccess());
        List<DagNode> nodes = executedNodes();
        // 证据收集节点共用同一个实例，只有 ReAct 节点换成 pi 版
        assertEquals(2, nodes.size());
        assertSame(evidenceNode, nodes.get(0));
        assertSame(v2React, nodes.get(1));
    }

    @Test
    void workflowWithoutV2VersionRunsExactlyTheV1Nodes() {
        List<String> v1NodeNames = List.of("clarify", "impact");
        List<DagNode> v1Nodes = List.of(mock(DagNode.class), mock(DagNode.class));
        when(registry.getWorkflow("demand")).thenReturn(definition(v1NodeNames));
        when(registry.resolveNodes(v1NodeNames)).thenReturn(v1Nodes);
        when(dagExecutor.run(anyList(), anyLong(), anyMap(), any())).thenAnswer(inv ->
                new ExecutionResult(42L, SessionStatus.DONE, List.of(), List.of(), Map.of()));

        controller.start(new WorkflowController.StartRequest("demand", Map.of("projectPaths", List.of("D:/repo"))));

        assertSame(v1Nodes, executedNodes());
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

    @Test
    void flowInterruptedByCancelEndsAsAbortedNotFailed() {
        cancelRegistry.requestCancel(42L);
        AtomicBoolean nodeSeesCancel = new AtomicBoolean();
        when(dagExecutor.run(anyList(), eq(42L), anyMap(), any())).thenAnswer(inv -> {
            nodeSeesCancel.set(cancelRegistry.isCurrentCancelled());
            return new ExecutionResult(42L, SessionStatus.FAILED, List.of(), List.of(), Map.of());
        });

        controller.runFlow(List.of(), 42L, Map.of(), ExecutionPolicy.DEFAULT);

        // 执行线程里的节点能感知到取消
        assertTrue(nodeSeesCancel.get());
        // 执行器记成 FAILED，控制器还原为 ABORTED
        verify(sessionRepo, atLeastOnce()).updateStatus(42L, SessionStatus.ABORTED);
        assertFalse(cancelRegistry.isCancelled(42L));
    }

    @Test
    void flowThatFailedForOtherReasonsStaysFailed() {
        when(dagExecutor.run(anyList(), eq(42L), anyMap(), any())).thenAnswer(inv ->
                new ExecutionResult(42L, SessionStatus.FAILED, List.of(), List.of(), Map.of()));

        controller.runFlow(List.of(), 42L, Map.of(), ExecutionPolicy.DEFAULT);

        verify(sessionRepo, never()).updateStatus(42L, SessionStatus.ABORTED);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<DagNode> executedNodes() {
        ArgumentCaptor<List<DagNode>> nodes = ArgumentCaptor.forClass((Class) List.class);
        verify(dagExecutor, timeout(2000)).run(nodes.capture(), eq(42L), anyMap(), any());
        return nodes.getValue();
    }

    private static WorkflowDefinition definition(List<String> nodes) {
        return new WorkflowDefinition("x", "显示名", "描述", nodes, Map.of("skipHitl", true, "emitNodeEvents", true));
    }
}
