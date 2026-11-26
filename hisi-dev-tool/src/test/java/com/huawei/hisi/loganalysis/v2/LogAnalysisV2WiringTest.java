package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.controller.LogAnalysisController;
import com.huawei.hisi.loganalysis.event.LogAnalysisEventEmitter;
import com.huawei.hisi.loganalysis.event.LogNodeEvent;
import com.huawei.hisi.loganalysis.nodes.ParseNode;
import com.huawei.hisi.loganalysis.nodes.ReportNode;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.LogAnalyzeRequest;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import com.huawei.hisi.service.LogAnalysisExecutor;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 编排器、执行器、控制器的接线测试：事件协议、落库列、v1 回退与委托。 */
class LogAnalysisV2WiringTest {

    // ─────────────────────────── 编排器 ───────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void nodeFailureStopsTheFlowButStillEmitsDagComplete() {
        ParseNode parse = mock(ParseNode.class);
        LogKgEvidenceNodeV2 kg = mock(LogKgEvidenceNodeV2.class);
        LogHypothesisNodeV2 hypothesis = mock(LogHypothesisNodeV2.class);
        LogVerifyNodeV2 verify = mock(LogVerifyNodeV2.class);
        LogSynthesisNodeV2 synthesis = mock(LogSynthesisNodeV2.class);
        ReportNode report = mock(ReportNode.class);
        when(parse.name()).thenReturn("ParseNode");
        when(parse.execute(anyMap())).thenReturn(new LinkedHashMap<>(Map.of("step", "parsed")));
        when(kg.name()).thenReturn("KgEvidenceV2");
        // 异常没有 message，事件负载不允许出现 null
        when(kg.execute(anyMap())).thenThrow(new NullPointerException());
        LogAnalysisEventEmitter emitter = mock(LogAnalysisEventEmitter.class);
        ObjectProvider<LogAnalysisEventEmitter> provider = providerOf(emitter);
        LogAnalysisV2Orchestrator orchestrator = new LogAnalysisV2Orchestrator(
                parse, kg, hypothesis, verify, synthesis, report, provider);

        Map<String, Object> out = orchestrator.run(new LinkedHashMap<>(), 42L);

        assertEquals("KgEvidenceV2", out.get("errorNode"));
        assertEquals("NullPointerException", out.get("errorMessage"));
        // 抛错前的节点输出被保留
        assertEquals("parsed", out.get("step"));
        verify(hypothesis, never()).execute(anyMap());
        verify(report, never()).execute(anyMap());

        ArgumentCaptor<LogNodeEvent> captor = ArgumentCaptor.forClass(LogNodeEvent.class);
        verify(emitter, times(5)).emit(captor.capture());
        List<String> types = captor.getAllValues().stream().map(LogNodeEvent::type).toList();
        assertEquals(List.of("NODE_START", "NODE_COMPLETE", "NODE_START", "NODE_ERROR", "DAG_COMPLETE"), types);
        assertEquals(42L, captor.getAllValues().get(0).reportId());
        assertEquals("_dag", captor.getAllValues().get(4).nodeName());
    }

    @Test
    void eventsAreSkippedWithoutReportId() {
        LogAnalysisEventEmitter emitter = mock(LogAnalysisEventEmitter.class);
        LogAnalysisV2Orchestrator orchestrator = new LogAnalysisV2Orchestrator(
                named(mock(ParseNode.class), "ParseNode"),
                named(mock(LogKgEvidenceNodeV2.class), "KgEvidenceV2"),
                named(mock(LogHypothesisNodeV2.class), "HypothesisV2"),
                named(mock(LogVerifyNodeV2.class), "VerifyV2"),
                named(mock(LogSynthesisNodeV2.class), "SynthesisV2"),
                named(mock(ReportNode.class), "ReportNode"),
                providerOf(emitter));

        Map<String, Object> out = orchestrator.analyzeLog("message", "stack", "D:/repo", "svc", "trace-1", null);

        assertFalse(out.containsKey("errorNode"));
        // 没有 reportId 时不推送事件
        verifyNoInteractions(emitter);
        assertEquals(List.of("ParseNode", "KgEvidenceV2", "HypothesisV2", "VerifyV2", "SynthesisV2", "ReportNode"),
                orchestrator.getNodeNames());
    }

    private static <T extends LogAnalysisDagNode> T named(T node, String name) {
        when(node.name()).thenReturn(name);
        return node;
    }

    // ─────────────────────────── 执行器 ───────────────────────────

    private LogAnalysisRepository repository;
    private LogAnalysisV2Orchestrator orchestrator;
    private LogAnalysisExecutor legacy;

    @BeforeEach
    void setUpExecutor() {
        repository = mock(LogAnalysisRepository.class);
        orchestrator = mock(LogAnalysisV2Orchestrator.class);
        legacy = mock(LogAnalysisExecutor.class);
        LogAnalysisReportEntity report = new LogAnalysisReportEntity();
        report.setReportId(7L);
        report.setLogMessage("NPE");
        report.setLogStackTrace("at com.svc.A.b(A.java:1)");
        report.setQueryParams(new LinkedHashMap<>(Map.of("projectPath", "D:/repo")));
        when(repository.findById(7L)).thenReturn(report);
    }

    @Test
    void fallsBackToLegacyFlowWhenV2ProducesNoReport() {
        when(orchestrator.analyzeLog(any(), any(), any(), any(), any(), any(), eq(7L)))
                .thenReturn(Map.of("errorNode", "KgEvidenceV2"));

        new LogAnalysisExecutorV2(repository, orchestrator, legacy).executeAnalysis(7L);

        verify(repository).updateAnalysisStatus(7L, "analyzing");
        // 交给 v1 之前把阶段还原成 pending
        verify(repository).updateAnalysisStatus(7L, "pending");
        verify(legacy).reanalyze(7L);
        verify(repository, never()).updateAnalysisResult(anyLong(), any(), any(), any(), any());
        verify(repository, never()).updateError(anyLong(), any());
    }

    @Test
    void reanalyzeRunsTheStagesInOrder() {
        when(orchestrator.analyzeLog(any(), any(), any(), any(), any(), any(), eq(7L))).thenReturn(Map.of(
                "finalReport", Map.of("errorSummary", Map.of(), "rootCauseAnalysis", Map.of(),
                        "fixSuggestions", List.of(), "keyStackFrames", List.of())));

        new LogAnalysisExecutorV2(repository, orchestrator, legacy).reanalyze(7L);

        // 任务状态接口的阶段与进度由 analysisStatus 算出：先复位，再分析中，最后完成
        InOrder order = inOrder(repository);
        order.verify(repository).updateAnalysisStatus(7L, "pending");
        order.verify(repository).updateAnalysisStatus(7L, "analyzing");
        order.verify(repository).updateAnalysisResult(eq(7L), any(), any(), any(), any());
        order.verify(repository).updateAnalysisStatus(7L, "completed");
    }

    @Test
    void duplicateTriggerIsIgnoredWhileTheReportIsStillBeingAnalyzed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(orchestrator.analyzeLog(any(), any(), any(), any(), any(), any(), eq(7L))).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Map.of("finalReport", Map.of("errorSummary", Map.of(), "rootCauseAnalysis", Map.of(),
                    "fixSuggestions", List.of(), "keyStackFrames", List.of()));
        });
        LogAnalysisExecutorV2 executor = new LogAnalysisExecutorV2(repository, orchestrator, legacy);

        Thread first = new Thread(() -> executor.executeAnalysis(7L));
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        // 第一轮还没结束就又触发了一次重新分析和一次分析：不清结果，也不再起一轮
        executor.reanalyze(7L);
        executor.executeAnalysis(7L);
        verify(repository, never()).updateAnalysisResult(anyLong(), any(), any(), any(), any());
        verify(orchestrator, times(1)).analyzeLog(any(), any(), any(), any(), any(), any(), eq(7L));

        release.countDown();
        first.join(5000);
        assertFalse(first.isAlive());
        verify(repository).updateAnalysisStatus(7L, "completed");

        // 第一轮结束后可以再次触发，重新分析照常先清掉上一轮的结果
        executor.reanalyze(7L);
        verify(orchestrator, times(2)).analyzeLog(any(), any(), any(), any(), any(), any(), eq(7L));
        verify(repository).updateAnalysisResult(eq(7L), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsV1ColumnsPlusEvidenceAndVerification() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("finalReport", Map.of(
                "errorSummary", Map.of("rootCauseException", "java.lang.NullPointerException"),
                "rootCauseAnalysis", Map.of("markdown", "## 根因结论", "confidence", "high"),
                "fixSuggestions", List.of(),
                "keyStackFrames", List.of()));
        result.put(LogKgEvidenceNodeV2.KEY_EVIDENCE_ANCHORS, List.of(Map.of("id", "E1", "summary", "堆栈帧")));
        Map<String, Object> fullHypothesis = Map.of("id", "H1", "title", "t", "fix", "should-be-stripped");
        result.put(LogVerifyNodeV2.KEY_VERIFICATION, Map.of(
                "overall", Map.of("kept", 1),
                "hypotheses", List.of(Map.of(
                        "id", "H1", "title", "t", "confidence", "high", "chainStatus", "verified", "kept", true,
                        "hypothesis", fullHypothesis))));
        when(orchestrator.analyzeLog(any(), any(), any(), any(), any(), any(), eq(7L))).thenReturn(result);

        new LogAnalysisExecutorV2(repository, orchestrator, legacy).executeAnalysis(7L);

        ArgumentCaptor<Map<String, Object>> rootCause = ArgumentCaptor.forClass(Map.class);
        verify(repository).updateAnalysisResult(eq(7L), any(), rootCause.capture(), any(), any());
        Map<String, Object> rc = rootCause.getValue();
        // v1 的三个键原样保留
        assertEquals("java.lang.NullPointerException", rc.get("rootCauseType"));
        assertEquals("## 根因结论", rc.get("markdown"));
        assertEquals("high", rc.get("confidence"));
        // v2 附加两段
        assertEquals(1, ((List<?>) rc.get("evidenceAnchors")).size());
        Map<String, Object> verification = (Map<String, Object>) rc.get("verification");
        Map<String, Object> compact = ((List<Map<String, Object>>) verification.get("hypotheses")).get(0);
        assertEquals("verified", compact.get("chainStatus"));
        assertFalse(compact.containsKey("hypothesis"));
        verify(legacy, never()).reanalyze(anyLong());
        // 任务状态接口的阶段与进度由 analysisStatus 算出，完成后要落成 completed
        verify(repository).updateAnalysisStatus(7L, "completed");
    }

    @Test
    void unexpectedFailureIsRecordedOnTheReport() {
        when(orchestrator.analyzeLog(any(), any(), any(), any(), any(), any(), eq(7L))).thenThrow(new RuntimeException("boom"));

        new LogAnalysisExecutorV2(repository, orchestrator, legacy).executeAnalysis(7L);

        verify(repository).updateError(eq(7L), contains("boom"));
        verify(repository).updateAnalysisStatus(7L, "failed");
        verify(repository, never()).updateAnalysisResult(anyLong(), any(), any(), any(), any());
    }

    // ─────────────────────────── 控制器 ───────────────────────────

    @Test
    void analyzeSavesPendingReportAndDispatchesToV2() {
        LogAnalysisRepository repository = mock(LogAnalysisRepository.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        LogAnalysisExecutorV2 executor = mock(LogAnalysisExecutorV2.class);
        LogAnalysisController legacyController = mock(LogAnalysisController.class);
        when(idGenerator.nextId()).thenReturn(99L);
        LogAnalysisV2Controller controller =
                new LogAnalysisV2Controller(repository, idGenerator, executor, legacyController);
        LogAnalyzeRequest request = new LogAnalyzeRequest();
        request.setMessage("NPE");
        request.setStackTrace("at com.svc.A.b(A.java:1)");
        request.setProjectPath("D:/repo");

        ApiResponse<com.huawei.hisi.model.AnalyzeTaskResponse> response = controller.analyze(request);

        assertTrue(response.isSuccess());
        assertEquals(99L, response.getData().getReportId());
        assertEquals("pending", response.getData().getStatus());
        ArgumentCaptor<LogAnalysisReportEntity> saved = ArgumentCaptor.forClass(LogAnalysisReportEntity.class);
        verify(repository).save(saved.capture());
        assertEquals("pending", saved.getValue().getStatus());
        assertEquals("sys_admin", saved.getValue().getUserId());
        assertEquals("D:/repo", saved.getValue().getQueryParams().get("projectPath"));
        verify(executor).executeAnalysis(99L);
        verifyNoInteractions(legacyController);
    }

    @Test
    void analyzeRejectsEmptyRequest() {
        LogAnalysisV2Controller controller = new LogAnalysisV2Controller(
                mock(LogAnalysisRepository.class), mock(SnowflakeIdGenerator.class),
                mock(LogAnalysisExecutorV2.class), mock(LogAnalysisController.class));

        ApiResponse<com.huawei.hisi.model.AnalyzeTaskResponse> response = controller.analyze(new LogAnalyzeRequest());

        assertFalse(response.isSuccess());
        assertEquals(400, response.getCode());
    }

    @Test
    void readsAndReanalysisGoWhereExpected() {
        LogAnalysisRepository repository = mock(LogAnalysisRepository.class);
        LogAnalysisExecutorV2 executor = mock(LogAnalysisExecutorV2.class);
        LogAnalysisController legacyController = mock(LogAnalysisController.class);
        LogAnalysisV2Controller controller = new LogAnalysisV2Controller(
                repository, mock(SnowflakeIdGenerator.class), executor, legacyController);
        ApiResponse<Map<String, Object>> status = ApiResponse.success(Map.of("status", "completed"));
        ApiResponse<com.huawei.hisi.model.DetailedAnalysisReport> detail =
                ApiResponse.success(new com.huawei.hisi.model.DetailedAnalysisReport());
        when(legacyController.getReportStatus(5L)).thenReturn(status);
        when(legacyController.getReport(5L)).thenReturn(detail);
        when(repository.findById(5L)).thenReturn(new LogAnalysisReportEntity());

        // 读接口原样委托 v1，响应对象不做二次加工
        assertSame(status, controller.getReportStatus(5L));
        assertSame(detail, controller.getReport(5L));
        // 重新分析走 v2 执行器，不会落到 v1
        assertTrue(controller.reanalyze(5L).isSuccess());
        verify(executor).reanalyze(5L);
        verify(legacyController, never()).reanalyze(anyLong());

        // 报告不存在时返回 404，不触发执行
        assertFalse(controller.reanalyze(404L).isSuccess());
        assertEquals(404, controller.reanalyze(404L).getCode());
        verify(executor, times(1)).reanalyze(anyLong());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LogAnalysisEventEmitter> providerOf(LogAnalysisEventEmitter emitter) {
        ObjectProvider<LogAnalysisEventEmitter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(emitter);
        return provider;
    }
}
