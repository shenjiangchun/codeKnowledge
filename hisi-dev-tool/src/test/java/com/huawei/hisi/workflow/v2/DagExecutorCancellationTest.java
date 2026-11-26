package com.huawei.hisi.workflow.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentflow.AgentFlowCancelledException;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.workflow.DagExecutor;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 用户中止工作流时，执行器对取消异常的处理。 */
class DagExecutorCancellationTest {

    @Test
    void cancellationEndsTheFlowAsFailedAndStopsLaterNodes() {
        AgentEventRepository eventRepo = mock(AgentEventRepository.class);
        AgentSessionRepository sessionRepo = mock(AgentSessionRepository.class);
        DagNode cancelled = new DagNode() {
            @Override
            public String name() {
                return "arch_react_agent";
            }

            @Override
            public String agentId() {
                return "agent";
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> input) {
                throw new AgentFlowCancelledException();
            }
        };
        DagNode later = mock(DagNode.class);

        ExecutionResult result = new DagExecutor(eventRepo, sessionRepo, new ObjectMapper())
                .run(List.of(cancelled, later), 42L, Map.of());

        // 按失败收尾（发起方据此把会话状态还原为 ABORTED），后续节点不再执行
        assertEquals(SessionStatus.FAILED, result.status());
        verify(later, never()).execute(any());
        verify(sessionRepo).updateStatus(42L, SessionStatus.FAILED);
        // 仍然落一条事件，重新进入页面时能看到这个节点是因为用户中止而停下的
        ArgumentCaptor<AgentEvent> event = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventRepo).append(event.capture());
        assertEquals(EventType.ERROR, event.getValue().getType());
        assertTrue(event.getValue().getPayload().contains("flow cancelled by user"));
    }
}
