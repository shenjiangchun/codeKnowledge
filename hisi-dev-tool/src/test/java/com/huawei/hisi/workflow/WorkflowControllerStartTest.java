package com.huawei.hisi.workflow;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用工作流启动端点 POST /api/workflow/start 单测：
 * 合法 type 种会话并异步执行、未知 type / 缺 projectPaths 返回 400 且不建会话。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowControllerStartTest {

    @Mock
    private WorkflowRegistry registry;
    @Mock
    private SessionMappingService sessionMappingService;
    @Mock
    private AgentSessionRepository sessionRepository;
    @Mock
    private AgentEventRepository eventRepository;
    @Mock
    private DagExecutor dagExecutor;

    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowController(registry, sessionMappingService,
                sessionRepository, eventRepository, dagExecutor);
    }

    private void stubSessionSave() {
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            AgentSession s = inv.getArgument(0);
            s.setId(42L);
            return s;
        });
        when(sessionRepository.update(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
    }

    @Test
    @DisplayName("合法 type：返回会话标识，创建 agent_session 并异步执行 DAG")
    void startValidTypeSeedsSessionAndDispatches() {
        stubSessionSave();
        DagNode node = mock(DagNode.class);
        WorkflowDefinition def = new WorkflowDefinition(
                "arch-deep-analysis", "架构深度分析", "desc", List.of("n1"), Map.of());
        when(registry.getWorkflow("arch-deep-analysis")).thenReturn(def);
        when(registry.resolveNodes(List.of("n1"))).thenReturn(List.of(node));

        Map<String, Object> input = Map.of("projectPaths", List.of("p1"));
        ApiResponse<WorkflowController.StartResponse> resp = controller.start(
                new WorkflowController.StartRequest("arch-deep-analysis", input));

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().sessionId()).isNotBlank();

        ArgumentCaptor<AgentSession> sessionCaptor = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getStatus()).isEqualTo(SessionStatus.RUNNING);
        assertThat(sessionCaptor.getValue().getSessionType()).isEqualTo(SessionType.WORKFLOW);

        verify(sessionMappingService).register(eq(resp.getData().sessionId()), eq(42L));
        verify(dagExecutor, timeout(2000)).run(anyList(), eq(42L), eq(input), eq(ExecutionPolicy.DEFAULT));
    }

    @Test
    @DisplayName("DAG 异步执行异常：会话状态标记为 FAILED（不向调用方抛异常）")
    void asyncExecutionFailureMarksSessionFailed() {
        stubSessionSave();
        DagNode node = mock(DagNode.class);
        WorkflowDefinition def = new WorkflowDefinition(
                "arch-deep-analysis", "架构深度分析", "desc", List.of("n1"), Map.of());
        when(registry.getWorkflow("arch-deep-analysis")).thenReturn(def);
        when(registry.resolveNodes(List.of("n1"))).thenReturn(List.of(node));
        doThrow(new RuntimeException("boom"))
                .when(dagExecutor).run(anyList(), eq(42L), any(), any());

        Map<String, Object> input = Map.of("projectPaths", List.of("p1"));
        ApiResponse<WorkflowController.StartResponse> resp = controller.start(
                new WorkflowController.StartRequest("arch-deep-analysis", input));

        assertThat(resp.isSuccess()).isTrue();
        verify(sessionRepository, timeout(2000)).updateStatus(42L, SessionStatus.FAILED);
    }

    @Test
    @DisplayName("未注册 type：400 且不创建会话")
    void unknownTypeReturns400() {
        when(registry.getWorkflow("nope")).thenReturn(null);

        ApiResponse<WorkflowController.StartResponse> resp = controller.start(
                new WorkflowController.StartRequest("nope", Map.of("projectPaths", List.of("p1"))));

        assertThat(resp.getCode()).isEqualTo(400);
        verify(sessionRepository, never()).save(any());
        verify(dagExecutor, never()).run(anyList(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("缺 workflowType：400 且不创建会话")
    void missingTypeReturns400() {
        ApiResponse<WorkflowController.StartResponse> resp = controller.start(
                new WorkflowController.StartRequest(null, Map.of("projectPaths", List.of("p1"))));

        assertThat(resp.getCode()).isEqualTo(400);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("input 缺 projectPaths：400 且不创建会话")
    void missingProjectPathsReturns400() {
        WorkflowDefinition def = new WorkflowDefinition(
                "arch-deep-analysis", "架构深度分析", "desc", List.of("n1"), Map.of());
        when(registry.getWorkflow("arch-deep-analysis")).thenReturn(def);

        ApiResponse<WorkflowController.StartResponse> resp = controller.start(
                new WorkflowController.StartRequest("arch-deep-analysis", Map.of()));

        assertThat(resp.getCode()).isEqualTo(400);
        verify(sessionRepository, never()).save(any());
        verify(dagExecutor, never()).run(anyList(), org.mockito.ArgumentMatchers.anyLong(), any());
    }
}
