package com.huawei.hisi.workbench.service;

import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.repository.WorkbenchFixRunRepository;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixRunServiceTest {

    private WorkbenchRepository workbenchRepository;
    private WorkbenchFixRunRepository runRepository;
    private AgentSessionRepository sessionRepository;
    private FixRunRunner runner;
    private ConfirmGate confirmGate;
    private FixRunService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        runRepository = mock(WorkbenchFixRunRepository.class);
        sessionRepository = mock(AgentSessionRepository.class);
        runner = mock(FixRunRunner.class);
        confirmGate = new ConfirmGate();
        service = new FixRunService(workbenchRepository, runRepository, sessionRepository,
                mock(SessionMappingService.class), new AgentFlowCancelRegistry(), runner, confirmGate, 2, true);
    }

    private WorkbenchItem item(String key, String status) {
        return WorkbenchItem.builder().id(11L).itemKey(key).projectPath("P1").title("t").severity("P0")
                .status(status).build();
    }

    private void stubSessionSave() {
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> {
            AgentSession session = inv.getArgument(0);
            session.setId(42L);
            return session;
        });
        when(runRepository.save(any(WorkbenchFixRun.class))).thenAnswer(inv -> {
            WorkbenchFixRun run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(7L);
            }
            return run;
        });
    }

    @Test
    void startCreatesSessionRunAndDispatches() {
        stubSessionSave();
        when(workbenchRepository.findByKey("k1")).thenReturn(item("k1", WorkbenchItem.STATUS_NEW));
        when(runRepository.findActiveByItemKey("k1")).thenReturn(Optional.empty());
        when(runRepository.countActive()).thenReturn(0L);

        FixRunService.StartResult result = service.start("k1", true);

        assertTrue(result.ok());
        assertEquals(7L, result.run().getId());
        assertEquals("k1", result.run().getItemKey());
        assertTrue(result.run().isPushRequested());
        verify(runner).submit(any(WorkbenchFixRun.class));
        // 状态必须走专门的状态更新（upsertItem 的冲突分支不写 status）
        verify(workbenchRepository).updateStatus(11L, WorkbenchItem.STATUS_RUNNING, 7L);
    }

    @Test
    void startRefusesUnknownItemActiveRunAndConcurrencyLimit() {
        when(workbenchRepository.findByKey("nope")).thenReturn(null);
        assertFalse(service.start("nope", false).ok());

        stubSessionSave();
        when(workbenchRepository.findByKey("k1")).thenReturn(item("k1", WorkbenchItem.STATUS_NEW));
        when(runRepository.findActiveByItemKey("k1")).thenReturn(Optional.of(WorkbenchFixRun.builder().id(1L).build()));
        assertFalse(service.start("k1", false).ok());

        when(runRepository.findActiveByItemKey("k2")).thenReturn(Optional.empty());
        when(runRepository.countActive()).thenReturn(2L);
        when(workbenchRepository.findByKey("k2")).thenReturn(item("k2", WorkbenchItem.STATUS_NEW));
        assertFalse(service.start("k2", false).ok());
        verify(runner, never()).submit(any());
    }

    @Test
    void confirmUpdatesChoiceAndWakesRunner() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(
                WorkbenchFixRun.builder().id(7L).itemKey("k1").status(WorkbenchFixRun.STATUS_WAITING_CONFIRM).build()));
        when(runRepository.save(any(WorkbenchFixRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workbenchRepository.findByKey("k1")).thenReturn(item("k1", WorkbenchItem.STATUS_WAITING));

        FixRunService.ConfirmOutcome outcome = service.confirm(7L, "B", true);

        assertTrue(outcome.ok());
        assertEquals("B", outcome.run().getChosenOption());
        assertTrue(outcome.run().isPushRequested());
        assertFalse(confirmGate.hasWaiter(7L));
        // runner 不在等待（单元测试里没人 await）：确认后应重新提交
        verify(runner).submit(any(WorkbenchFixRun.class));
    }

    @Test
    void confirmRefusesWhenNotWaiting() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(
                WorkbenchFixRun.builder().id(7L).status(WorkbenchFixRun.STATUS_RUNNING).build()));

        FixRunService.ConfirmOutcome outcome = service.confirm(7L, "B", false);

        assertFalse(outcome.ok());
        verify(runner, never()).submit(any());
    }

    @Test
    void rejectPassesFeedbackThroughTheGate() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(
                WorkbenchFixRun.builder().id(7L).itemKey("k1").status(WorkbenchFixRun.STATUS_WAITING_CONFIRM).build()));
        when(runRepository.save(any(WorkbenchFixRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workbenchRepository.findByKey("k1")).thenReturn(item("k1", WorkbenchItem.STATUS_WAITING));

        assertTrue(service.reject(7L, "考虑更小的改动").ok());
        verify(runner).submit(any(WorkbenchFixRun.class));
    }

    @Test
    void abortSignalsCancelAndKillsProcesses() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(
                WorkbenchFixRun.builder().id(7L).status(WorkbenchFixRun.STATUS_RUNNING).build()));

        assertTrue(service.abort(7L));

        verify(runner).killProcesses(7L);
    }

    @Test
    void abortRefusesFinishedRuns() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(
                WorkbenchFixRun.builder().id(7L).status(WorkbenchFixRun.STATUS_DONE).build()));
        assertFalse(service.abort(7L));
        verify(runner, never()).killProcesses(7L);
    }

    @Test
    void resumeContinuesInterruptedRun() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(
                WorkbenchFixRun.builder().id(7L).itemKey("k1").status(WorkbenchFixRun.STATUS_INTERRUPTED).stageIndex(4).build()));
        when(runRepository.save(any(WorkbenchFixRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workbenchRepository.findByKey("k1")).thenReturn(item("k1", WorkbenchItem.STATUS_FAILED));

        FixRunService.ConfirmOutcome outcome = service.resume(7L);

        assertTrue(outcome.ok());
        assertEquals(WorkbenchFixRun.STATUS_RUNNING, outcome.run().getStatus());
        assertNull(outcome.run().getErrorMessage());
        verify(runner).submit(any(WorkbenchFixRun.class));
    }

    @Test
    void restartRecoveryInterruptsRunningButKeepsWaiting() {
        WorkbenchFixRun running = WorkbenchFixRun.builder().id(1L).status(WorkbenchFixRun.STATUS_RUNNING).build();
        WorkbenchFixRun waiting = WorkbenchFixRun.builder().id(2L).status(WorkbenchFixRun.STATUS_WAITING_CONFIRM).build();
        when(runRepository.listActive()).thenReturn(List.of(running, waiting));

        service.recoverAfterRestart();

        assertEquals(WorkbenchFixRun.STATUS_INTERRUPTED, running.getStatus());
        assertEquals(WorkbenchFixRun.STATUS_WAITING_CONFIRM, waiting.getStatus());
        verify(runRepository).save(running);
        verify(runRepository, never()).save(waiting);
    }
}
