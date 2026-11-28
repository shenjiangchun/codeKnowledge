package com.huawei.hisi.workbench.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.repository.WorkbenchFixRunRepository;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import com.huawei.hisi.workbench.service.DebtItemSyncService;
import com.huawei.hisi.workbench.service.FixRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkbenchControllerTest {

    private WorkbenchRepository repository;
    private WorkbenchFixRunRepository runRepository;
    private DebtItemSyncService syncService;
    private FixRunService fixRunService;
    private WorkbenchController controller;

    @BeforeEach
    void setUp() {
        repository = mock(WorkbenchRepository.class);
        runRepository = mock(WorkbenchFixRunRepository.class);
        syncService = mock(DebtItemSyncService.class);
        fixRunService = mock(FixRunService.class);
        controller = new WorkbenchController(repository, runRepository, mock(AgentEventRepository.class),
                syncService, fixRunService, new ObjectMapper());
    }

    private WorkbenchItem item(String key, String status, String severity, String project) {
        return WorkbenchItem.builder()
                .id(Math.abs(key.hashCode()) + 1L)
                .itemKey(key).source("log").projectPath(project).title("标题").severity(severity)
                .status(status).occurrenceCount(1)
                .firstSeenAt(1_700_000_000L).lastSeenAt(1_700_000_000L).updatedAt(1_700_000_000L)
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryCountsStatusesAndSeverities() {
        when(repository.listItems()).thenReturn(List.of(
                item("k1", WorkbenchItem.STATUS_NEW, "P0", "P1"),
                item("k2", WorkbenchItem.STATUS_RUNNING, "P1", "P1"),
                item("k3", WorkbenchItem.STATUS_WAITING, "P2", "P2"),
                item("k4", WorkbenchItem.STATUS_DONE, "P0", "P2"),
                item("k5", WorkbenchItem.STATUS_IGNORED, "P0", "P2"),
                item("k6", WorkbenchItem.STATUS_FAILED, "P0", "P2")));

        ApiResponse<Map<String, Object>> response = controller.summary();

        assertTrue(response.isSuccess());
        Map<String, Object> summary = response.getData();
        assertEquals(6, ((Number) summary.get("total")).intValue());
        assertEquals(3, ((Number) summary.get("open")).intValue());
        // 在途修复 = RUNNING + WAITING（方案确认门阶段也算在途），waiting 是其中的子集
        assertEquals(2, ((Number) summary.get("running")).intValue());
        assertEquals(1, ((Number) summary.get("waiting")).intValue());
        assertEquals(1, ((Number) summary.get("done")).intValue());
        // 严重度只统计未清零的
        assertEquals(1, ((Number) summary.get("p0")).intValue());
        assertEquals(1, ((Number) summary.get("p1")).intValue());
        assertEquals(1, ((Number) summary.get("p2")).intValue());
        // cleared7d：updatedAt 是 2023-11 的时间戳，不在近 7 天，应为 0
        assertEquals(0, ((Number) summary.get("cleared7d")).intValue());
        Map<String, Map<String, Long>> byProject = (Map<String, Map<String, Long>>) summary.get("byProject");
        assertEquals(2, byProject.size());
    }

    @Test
    void syncDelegatesAndReturnsStats() {
        when(syncService.sync()).thenReturn(new DebtItemSyncService.SyncStats(3, 2, 1));

        ApiResponse<Map<String, Object>> response = controller.sync();

        assertTrue(response.isSuccess());
        assertEquals(3, ((Number) response.getData().get("logItems")).intValue());
        assertEquals(2, ((Number) response.getData().get("archItems")).intValue());
        assertEquals(1, ((Number) response.getData().get("created")).intValue());
        verify(syncService).sync();
    }

    @Test
    void ignoreReturns404ForUnknownItem() {
        when(repository.findById(999L)).thenReturn(null);

        ApiResponse<Void> response = controller.ignore(999L);

        assertFalse(response.isSuccess());
        assertEquals(404, response.getCode());
        verifyNoInteractions(syncService);
    }

    @Test
    void ignoreSetsStatusAndPersists() {
        WorkbenchItem existing = item("k1", WorkbenchItem.STATUS_NEW, "P0", "P1");
        when(repository.findById(existing.getId())).thenReturn(existing);

        assertTrue(controller.ignore(existing.getId()).isSuccess());

        assertEquals(WorkbenchItem.STATUS_IGNORED, existing.getStatus());
        // 状态必须走专门的状态更新：upsertItem 的冲突分支不写 status
        verify(repository).updateStatus(existing.getId(), WorkbenchItem.STATUS_IGNORED, existing.getCurrentRunId());
        verify(repository, never()).upsertItem(any());
    }

    @Test
    void itemsCarrySevenDayTrend() {
        WorkbenchItem existing = item("k1", WorkbenchItem.STATUS_NEW, "P0", "P1");
        when(repository.listItems()).thenReturn(List.of(existing));
        when(repository.listDayCounts(org.mockito.ArgumentMatchers.eq("k1"), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of(java.time.LocalDate.now().toString(), 2));

        ApiResponse<List<Map<String, Object>>> response = controller.items();

        assertTrue(response.isSuccess());
        Map<String, Object> view = response.getData().get(0);
        assertEquals("k1", view.get("itemKey"));
        List<Integer> trend = (List<Integer>) view.get("trend");
        assertEquals(7, trend.size());
        assertEquals(2, trend.get(6));
        assertEquals(0, trend.get(0));
    }

    // ──────────────────────────── 自动修复运行 ────────────────────────────

    private WorkbenchFixRun run(long id, String status) {
        return WorkbenchFixRun.builder().id(id).itemKey("k1").sessionUuid("u-" + id)
                .sessionId(100L + id).projectPath("P1").status(status).stageIndex(1).iteration(1).build();
    }

    @Test
    void autofixDelegatesAndReturnsRunView() {
        WorkbenchItem existing = item("k1", WorkbenchItem.STATUS_NEW, "P0", "P1");
        when(repository.findById(existing.getId())).thenReturn(existing);
        when(fixRunService.start("k1", false)).thenReturn(FixRunService.StartResult.ok(run(7L, "RUNNING")));

        ApiResponse<Map<String, Object>> response = controller.autofix(existing.getId(), new WorkbenchController.AutofixRequest(null, null, false));

        assertTrue(response.isSuccess());
        assertEquals(7L, ((Number) response.getData().get("id")).longValue());
        assertEquals("RUNNING", response.getData().get("status"));
        assertEquals("u-7", response.getData().get("sessionUuid"));
    }

    @Test
    void autofixReturns409WhenServiceRefuses() {
        WorkbenchItem existing = item("k1", WorkbenchItem.STATUS_NEW, "P0", "P1");
        when(repository.findById(existing.getId())).thenReturn(existing);
        when(fixRunService.start("k1", false)).thenReturn(FixRunService.StartResult.error("该待办已有正在进行的修复"));

        ApiResponse<Map<String, Object>> response = controller.autofix(existing.getId(), null);

        assertFalse(response.isSuccess());
        assertEquals(409, response.getCode());
    }

    @Test
    void batchAutofixReportsStartedAndFailed() {
        WorkbenchItem a = item("ka", WorkbenchItem.STATUS_NEW, "P0", "P1");
        WorkbenchItem b = item("kb", WorkbenchItem.STATUS_NEW, "P1", "P1");
        when(repository.findById(a.getId())).thenReturn(a);
        when(repository.findById(b.getId())).thenReturn(b);
        when(fixRunService.start("ka", true)).thenReturn(FixRunService.StartResult.ok(run(1L, "RUNNING")));
        when(fixRunService.start("kb", true)).thenReturn(FixRunService.StartResult.error("已达上限"));

        ApiResponse<Map<String, Object>> response = controller.autofixBatch(
                new WorkbenchController.AutofixRequest(List.of(a.getId(), b.getId()), null, true));

        assertTrue(response.isSuccess());
        assertEquals(List.of("ka"), response.getData().get("started"));
        assertEquals(1, ((List<?>) response.getData().get("failed")).size());
    }

    @Test
    void confirmRejectsMissingOption() {
        when(fixRunService.confirm(7L, null, false))
                .thenReturn(new FixRunService.ConfirmOutcome(false, "请选择方案", null));

        ApiResponse<Map<String, Object>> response = controller.confirm(7L, new WorkbenchController.ConfirmRequest(null, false));

        assertFalse(response.isSuccess());
        assertEquals(409, response.getCode());
    }

    @Test
    void confirmPassesPushChoiceThrough() {
        when(fixRunService.confirm(7L, "B", true))
                .thenReturn(new FixRunService.ConfirmOutcome(true, null, run(7L, "RUNNING")));

        ApiResponse<Map<String, Object>> response = controller.confirm(7L, new WorkbenchController.ConfirmRequest("B", true));

        assertTrue(response.isSuccess());
        verify(fixRunService).confirm(7L, "B", true);
    }

    @Test
    void rejectAndAbortAndResumeDelegate() {
        when(fixRunService.reject(7L, "换一个方案")).thenReturn(new FixRunService.ConfirmOutcome(true, null, run(7L, "RUNNING")));
        assertTrue(controller.reject(7L, new WorkbenchController.RejectRequest("换一个方案")).isSuccess());

        when(fixRunService.abort(7L)).thenReturn(true);
        assertTrue(controller.abort(7L).isSuccess());

        when(fixRunService.resume(7L)).thenReturn(new FixRunService.ConfirmOutcome(true, null, run(7L, "RUNNING")));
        assertTrue(controller.resume(7L).isSuccess());
    }

    @Test
    void runDetailAttachesCheckpointsAndStageDefinitions() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(run(7L, "RUNNING")));

        ApiResponse<Map<String, Object>> response = controller.runDetail(7L);

        assertTrue(response.isSuccess());
        assertEquals(8, ((List<?>) response.getData().get("stages")).size());
    }
}
