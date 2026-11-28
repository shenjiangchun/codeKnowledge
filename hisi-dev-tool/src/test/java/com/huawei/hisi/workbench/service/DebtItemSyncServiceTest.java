package com.huawei.hisi.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.archdiagnosis.model.ArchDiagnosisSnapshot;
import com.huawei.hisi.archdiagnosis.repository.ArchDiagnosisSnapshotRepository;
import com.huawei.hisi.model.AppLogConfig;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.repository.AppLogConfigRepository;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebtItemSyncServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DebtItemSyncService service(LogAnalysisRepository logs, AppLogConfigRepository configs,
                                        ArchDiagnosisSnapshotRepository snapshots, AgentEventRepository events,
                                        WorkbenchRepository repo) {
        return new DebtItemSyncService(logs, configs, snapshots, events, repo, objectMapper, true);
    }

    // ──────────────────────────── 纯函数 ────────────────────────────

    @Test
    void severityMapsBothNotations() {
        assertEquals("P0", DebtItemSyncService.toSeverity("P0"));
        assertEquals("P0", DebtItemSyncService.toSeverity("HIGH"));
        assertEquals("P1", DebtItemSyncService.toSeverity("P1"));
        assertEquals("P1", DebtItemSyncService.toSeverity("Medium"));
        assertEquals("P2", DebtItemSyncService.toSeverity("LOW"));
        assertEquals("P2", DebtItemSyncService.toSeverity(null));
        assertEquals("P2", DebtItemSyncService.toSeverity("什么都不是"));
    }

    @Test
    void evidenceRefsAreSplitBackFromTheReportFormat() {
        assertEquals(List.of("a", "b c", "d"), DebtItemSyncService.parseEvidenceRefs("['a', 'b c', 'd']"));
        assertEquals(List.of("x"), DebtItemSyncService.parseEvidenceRefs("[x]"));
        assertTrue(DebtItemSyncService.parseEvidenceRefs("").isEmpty());
    }

    // ──────────────────────────── 项目归属 ────────────────────────────

    @Test
    void projectComesFromQueryParamsSplittingCommaSeparatedPaths() {
        DebtItemSyncService service = service(mock(LogAnalysisRepository.class), mock(AppLogConfigRepository.class),
                mock(ArchDiagnosisSnapshotRepository.class), mock(AgentEventRepository.class), mock(WorkbenchRepository.class));
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setQueryParams(Map.of("projectPath", "C:\\repos\\a, D:/repos/b/"));
        assertEquals("C:/repos/a", service.projectOfReport(report));
    }

    @Test
    void projectFallsBackToAppConfigWhenQueryParamsMissing() {
        LogAnalysisRepository logs = mock(LogAnalysisRepository.class);
        AppLogConfigRepository configs = mock(AppLogConfigRepository.class);
        AppLogConfig config = new AppLogConfig();
        config.setProjectPath("D:/svc");
        when(configs.findByAppId("app-1")).thenReturn(config);
        DebtItemSyncService service = service(logs, configs,
                mock(ArchDiagnosisSnapshotRepository.class), mock(AgentEventRepository.class), mock(WorkbenchRepository.class));
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setAppId("app-1");
        assertEquals("D:/svc", service.projectOfReport(report));
    }

    @Test
    void projectIsNullWithoutAnyLink() {
        DebtItemSyncService service = service(mock(LogAnalysisRepository.class), mock(AppLogConfigRepository.class),
                mock(ArchDiagnosisSnapshotRepository.class), mock(AgentEventRepository.class), mock(WorkbenchRepository.class));
        assertNull(service.projectOfReport(new LogAnalysisReportEntity()));
    }

    // ──────────────────────────── 日志映射 ────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void logReportBecomesItemWithHighestPriorityAndVerification() {
        LogAnalysisRepository logs = mock(LogAnalysisRepository.class);
        WorkbenchRepository repo = mock(WorkbenchRepository.class);
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(7L);
        report.setErrorType("NullPointerException");
        report.setErrorFingerprint("fp-1");
        report.setOccurrenceCount(3);
        report.setCreatedAt(LocalDateTime.of(2026, 11, 20, 10, 0));
        report.setUpdatedAt(LocalDateTime.of(2026, 11, 25, 9, 0));
        report.setQueryParams(Map.of("projectPath", "D:/svc"));
        report.setErrorSummary(Map.of("message", "Cannot invoke"));
        report.setRootCause(Map.of(
                "markdown", "## 根因结论\n\n内容",
                "confidence", "high",
                "verification", Map.of("hypotheses", List.of(Map.of(
                        "title", "依赖为空", "confidence", "medium", "chainStatus", "at_throw_site"))),
                "evidenceAnchors", List.of(Map.of("id", "E1", "summary", "堆栈帧"))));
        report.setFixSuggestions(List.of(
                Map.of("description", "低优先级", "priority", "P2"),
                Map.of("description", "先修这个", "priority", "P0")));
        report.setCodeSnippets(List.of(Map.of("className", "com.example.UserController", "methodName", "getUser", "lineNumber", 20)));
        when(logs.findByStatus("completed")).thenReturn(List.of(report));
        DebtItemSyncService service = service(logs, mock(AppLogConfigRepository.class),
                mock(ArchDiagnosisSnapshotRepository.class), mock(AgentEventRepository.class), repo);

        service.sync();

        ArgumentCaptor<WorkbenchItem> captor = ArgumentCaptor.forClass(WorkbenchItem.class);
        verify(repo).upsertItem(captor.capture());
        WorkbenchItem item = captor.getValue();
        assertEquals("log:D:/svc:fp-1", item.getItemKey());
        assertEquals("NullPointerException：Cannot invoke", item.getTitle());
        assertEquals("P0", item.getSeverity());
        assertEquals("先修这个", item.getSuggestion());
        assertEquals("com.example.UserController#getUser:20", item.getLocation());
        assertEquals("medium", item.getConfidence());
        assertEquals("at_throw_site", item.getChainStatus());
        assertEquals(3, item.getOccurrenceCount());
        assertTrue(item.getEvidenceJson().contains("E1"));
        assertTrue(item.getWhy().startsWith("## 根因结论"));
    }

    @Test
    void userDecisionIsNotOverwrittenBySync() {
        LogAnalysisRepository logs = mock(LogAnalysisRepository.class);
        WorkbenchRepository repo = mock(WorkbenchRepository.class);
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(9L);
        report.setErrorType("SQLException");
        report.setQueryParams(Map.of("projectPath", "D:/svc"));
        when(logs.findByStatus("completed")).thenReturn(List.of(report));
        WorkbenchItem existing = WorkbenchItem.builder()
                .itemKey("log:D:/svc:9").status(WorkbenchItem.STATUS_IGNORED).build();
        when(repo.listItems()).thenReturn(List.of(existing));
        DebtItemSyncService service = service(logs, mock(AppLogConfigRepository.class),
                mock(ArchDiagnosisSnapshotRepository.class), mock(AgentEventRepository.class), repo);

        service.sync();

        ArgumentCaptor<WorkbenchItem> captor = ArgumentCaptor.forClass(WorkbenchItem.class);
        verify(repo).upsertItem(captor.capture());
        // 同步提交的是 NEW（upsert 语句只更新内容列，不覆盖状态），落库由仓库的 SQL 保证
        assertEquals(WorkbenchItem.STATUS_NEW, captor.getValue().getStatus());
    }

    @Test
    void dayCountIsOnlyStampedOnFirstSeenOrOccurrenceGrowth() {
        LogAnalysisRepository logs = mock(LogAnalysisRepository.class);
        WorkbenchRepository repo = mock(WorkbenchRepository.class);
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(9L);
        report.setErrorType("SQLException");
        report.setQueryParams(Map.of("projectPath", "D:/svc"));
        report.setOccurrenceCount(3);
        when(logs.findByStatus("completed")).thenReturn(List.of(report));

        // 1) 存量待办、出现次数不变：同步后不记账（修复前每轮都给今天盖章，导致所有曲线一模一样）
        WorkbenchItem existing = WorkbenchItem.builder()
                .itemKey("log:D:/svc:9").status(WorkbenchItem.STATUS_NEW).occurrenceCount(3).build();
        when(repo.listItems()).thenReturn(List.of(existing));
        service(logs, mock(AppLogConfigRepository.class), mock(ArchDiagnosisSnapshotRepository.class),
                mock(AgentEventRepository.class), repo).sync();
        verify(repo, never()).upsertDay(anyString(), anyString(), any(int.class));

        // 2) 首次出现的待办：按当天出现次数记账一次
        when(repo.listItems()).thenReturn(List.of());
        service(logs, mock(AppLogConfigRepository.class), mock(ArchDiagnosisSnapshotRepository.class),
                mock(AgentEventRepository.class), repo).sync();
        verify(repo).upsertDay(eq("log:D:/svc:9"), anyString(), eq(3));

        // 3) 出现次数较上次增长：按增量记账
        report.setOccurrenceCount(5);
        when(repo.listItems()).thenReturn(List.of(existing));
        service(logs, mock(AppLogConfigRepository.class), mock(ArchDiagnosisSnapshotRepository.class),
                mock(AgentEventRepository.class), repo).sync();
        verify(repo).upsertDay(eq("log:D:/svc:9"), anyString(), eq(2));
    }

    // ──────────────────────────── 架构聚合 ────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void freshAiAnalysisWinsOverRuleSnapshot() {
        LogAnalysisRepository logs = mock(LogAnalysisRepository.class);
        ArchDiagnosisSnapshotRepository snapshots = mock(ArchDiagnosisSnapshotRepository.class);
        AgentEventRepository events = mock(AgentEventRepository.class);
        WorkbenchRepository repo = mock(WorkbenchRepository.class);
        when(logs.findByStatus("completed")).thenReturn(List.of());
        String checkpointPayload = """
                {"nodeName":"arch_react_agent","output":{
                  "projectPaths":["C:/proj/demo"],
                  "rootCauseResult":{"issues":[{
                    "ref":"I-01","title":"循环依赖","severity":"HIGH",
                    "rootCause":"两个域互相调用","evidenceRefs":"['risks[0] cyclic']",
                    "suggestion":"建立单向接口","verdict":"confirmed","reason":"证据充分"}]}}}
                """;
        AgentEvent event = AgentEvent.builder()
                .payload(checkpointPayload)
                .createdAt(System.currentTimeMillis() / 1000L - 3600)
                .build();
        when(events.findCheckpointsContaining(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of(event));
        DebtItemSyncService service = service(logs, mock(AppLogConfigRepository.class), snapshots, events, repo);

        service.sync();

        ArgumentCaptor<WorkbenchItem> captor = ArgumentCaptor.forClass(WorkbenchItem.class);
        verify(repo).upsertItem(captor.capture());
        WorkbenchItem item = captor.getValue();
        assertEquals(WorkbenchItem.SOURCE_ARCH_AI, item.getSource());
        assertEquals("arch:ai:C:/proj/demo:I-01", item.getItemKey());
        assertEquals("P0", item.getSeverity());
        assertEquals("high", item.getConfidence());
        assertEquals("confirmed", item.getChainStatus());
        assertEquals("建立单向接口", item.getSuggestion());
        assertTrue(item.getEvidenceJson().contains("risks[0] cyclic"));
        // 该项目有新鲜 AI 结果，不读快照
        verify(snapshots, never()).findLatestByScope(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ruleSnapshotIsTheFallbackWithoutFreshAiAnalysis() {
        LogAnalysisRepository logs = mock(LogAnalysisRepository.class);
        ArchDiagnosisSnapshotRepository snapshots = mock(ArchDiagnosisSnapshotRepository.class);
        AgentEventRepository events = mock(AgentEventRepository.class);
        WorkbenchRepository repo = mock(WorkbenchRepository.class);
        when(logs.findByStatus("completed")).thenReturn(List.of());
        when(events.findCheckpointsContaining(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());
        ArchDiagnosisSnapshot snapshot = ArchDiagnosisSnapshot.builder()
                .id(3L).scope("C:/proj/legacy").healthScore(62).grade("C")
                .reportJson("""
                        {"healthScore":62,"grade":"C","issues":[
                          {"source":"layer","sourceLevel":"package-cycle","severity":"MEDIUM",
                           "message":"service↔dao 循环","className":"com.legacy.Service"}]}
                        """)
                .build();
        when(snapshots.listScopes()).thenReturn(List.of("C:/proj/legacy"));
        when(snapshots.findLatestByScope("C:/proj/legacy")).thenReturn(snapshot);
        DebtItemSyncService service = service(logs, mock(AppLogConfigRepository.class), snapshots, events, repo);

        service.sync();

        ArgumentCaptor<WorkbenchItem> captor = ArgumentCaptor.forClass(WorkbenchItem.class);
        verify(repo).upsertItem(captor.capture());
        WorkbenchItem item = captor.getValue();
        assertEquals(WorkbenchItem.SOURCE_ARCH_RULE, item.getSource());
        assertEquals("P1", item.getSeverity());
        assertEquals("com.legacy.Service", item.getLocation());
        assertTrue(item.getItemKey().startsWith("arch:rule:C:/proj/legacy:"));
    }
}
