package com.huawei.hisi.fixengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.fixengine.executor.MavenExecutor;
import com.huawei.hisi.fixengine.executor.TestRunResult;
import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.model.TestGenInput;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagOrchestrator;
import com.huawei.hisi.ram.chat.RamChatWebSocketHandler;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.repository.LogAnalysisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Executes the 9-step fix pipeline for a {@link FixSession}.
 *
 * <p>Each step emits a pair of {@code tool_use_start} / {@code tool_result} events
 * (same shape as RAM chat) so the frontend can render them with the same StepCard
 * component. The flow ends with a {@code checkpoint} event (or {@code error} on failure),
 * all persisted to {@code agent_event} via {@link AgentEventRepository}.
 */
@Slf4j
@Service
public class FixFlowRunner {

    private static final int MAX_REPRO_ROUNDS = 3;
    private static final String FIX_FLOW_TURN_PREFIX = "fix-flow-";

    private final LogAnalysisDagOrchestrator logAnalysisOrchestrator;
    private final WorktreeService worktreeService;
    private final TestGenService testGenService;
    private final ReproService reproService;
    private final FixService fixService;
    private final MavenExecutor mavenExecutor;
    private final FixSessionRepository fixSessionRepository;
    private final RamChatWebSocketHandler wsHandler;
    private final LogAnalysisRepository logAnalysisRepository;
    private final AgentEventRepository agentEventRepository;
    private final ObjectMapper objectMapper;

    public FixFlowRunner(LogAnalysisDagOrchestrator logAnalysisOrchestrator,
                         WorktreeService worktreeService,
                         TestGenService testGenService,
                         ReproService reproService,
                         FixService fixService,
                         MavenExecutor mavenExecutor,
                         FixSessionRepository fixSessionRepository,
                         RamChatWebSocketHandler wsHandler,
                         LogAnalysisRepository logAnalysisRepository,
                         AgentEventRepository agentEventRepository,
                         ObjectMapper objectMapper) {
        this.logAnalysisOrchestrator = logAnalysisOrchestrator;
        this.worktreeService = worktreeService;
        this.testGenService = testGenService;
        this.reproService = reproService;
        this.fixService = fixService;
        this.mavenExecutor = mavenExecutor;
        this.fixSessionRepository = fixSessionRepository;
        this.wsHandler = wsHandler;
        this.logAnalysisRepository = logAnalysisRepository;
        this.agentEventRepository = agentEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Run the full 9-step fix pipeline.
     *
     * @param session the fix session (must already be persisted)
     */
    public void run(FixSession session) {
        // chatSessionId 现为 String，需解析为 long 给 pushEvent 使用
        long sid = 0L;
        if (session.getChatSessionId() != null) {
            try { sid = Long.parseLong(session.getChatSessionId()); } catch (NumberFormatException ignored) {}
        }
        String turnId = FIX_FLOW_TURN_PREFIX + UUID.randomUUID();
        // Lenient policy: build/repro/pass failures don't abort the flow.
        // Collected here, surfaced in the final checkpoint so the model/user
        // can decide whether to trust the unverified fix.
        List<String> buildNotes = new ArrayList<>();

        try {
            currentReportId = session.getReportId();

            // ---- Step 1: Log recognition ----
            Map<String, Object> analysisResult = runStep(sid, turnId, "log_recognition",
                    Map.of("message", "Analyzing log to extract exception details..."),
                    () -> {
                        Map<String, Object> r = stepLogRecognition(session);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("exceptionType", r.get("exceptionType"));
                        out.put("throwPointSig", r.get("throwPointSig"));
                        out.put("projectPath", r.get("projectPath"));
                        return out;
                    });
            String exceptionType = str(analysisResult.get("exceptionType"), session.getErrorMsg());
            String throwPointSig = str(analysisResult.get("throwPointSig"), session.getThrowPointSig());
            log.info("[FixFlowRunner] step1 done: exceptionType={} throwPointSig={}", exceptionType, throwPointSig);

            // ---- Step 2: KG search ----
            runStep(sid, turnId, "kg_search",
                    Map.of("message", "Searching knowledge graph for target method..."),
                    () -> {
                        // TODO: integrate with KG hybrid_search to find method location
                        log.info("[FixFlowRunner] step2: KG search not yet integrated, using throwPointSig={}", throwPointSig);
                        return Map.of("throwPointSig", String.valueOf(throwPointSig));
                    });

            // ---- Step 3: Create worktree ----
            String repoPath = extractRepoPath(analysisResult, session);
            String branchName = session.getBranchName();
            String baseBranch = resolveBaseBranch(repoPath);
            String worktreePath = runStep(sid, turnId, "create_worktree",
                    Map.of("message", "Creating git worktree...", "branchName", branchName, "baseBranch", baseBranch),
                    () -> {
                        String wt = worktreeService.createWorktree(branchName, repoPath, baseBranch);
                        session.setWorktreePath(wt);
                        fixSessionRepository.update(session);
                        return Map.of("worktreePath", wt);
                    }).get("worktreePath").toString();
            log.info("[FixFlowRunner] step3 done: worktree={}", worktreePath);

            // ---- Step 4: AI generate test（带编译检查，最多 3 轮修复） ----
            if (throwPointSig == null || throwPointSig.isBlank()
                    || exceptionType == null || exceptionType.isBlank()) {
                String missing = throwPointSig == null || throwPointSig.isBlank()
                        ? "throwPointSig" : "exceptionType";
                log.warn("[FixFlowRunner] step4 skipped: {} not extracted from log", missing);
                pushError(sid, turnId, "Cannot extract " + missing + " from log; aborting fix flow");
                markFailed(session, "MISSING_SIGNATURE");
                return;
            }
            TestGenInput testInput = buildTestGenInput(analysisResult, throwPointSig, exceptionType);
            String testClassName = extractTestClassName(testInput);
            String testPackage = extractTestPackage(testInput);
            String testFqn = testPackage + "." + testClassName;
            final String finalWorktreePath = worktreePath;
            runStep(sid, turnId, "generate_test",
                    Map.of("message", "Generating reproduction test...",
                            "testFqn", testFqn, "throwPointSig", throwPointSig),
                    () -> {
                        // 使用 compileCheck 重载：生成 → 写文件 → 编译 → 失败则 AI 修复 → 重试
                        String testCode = testGenService.generate(testInput, code -> {
                            worktreeService.writeTestFile(finalWorktreePath, testPackage, testClassName, code);
                            TestRunResult result = mavenExecutor.runTest(finalWorktreePath, testFqn, null);
                            return result.isPassed() ? null : result.output();
                        });
                        worktreeService.writeTestFile(finalWorktreePath, testPackage, testClassName, testCode);
                        return Map.of("testClass", testFqn);
                    });
            log.info("[FixFlowRunner] step4 done: testClass={}", testClassName);

            // ---- Step 5: Run repro test ----
            // Lenient policy: build/repro failure doesn't abort the flow.
            // We record the failure cause and continue to step 6 (ai_fix);
            // the final checkpoint will note "unverified fix".
            String exceptionMsg = str(analysisResult.get("exceptionMessage"), null);
            boolean reproduced = runStep(sid, turnId, "run_repro",
                    Map.of("message", "Running reproducibility test (" + MAX_REPRO_ROUNDS + " rounds)...",
                            "exceptionType", exceptionType, "exceptionMsg", String.valueOf(exceptionMsg)),
                    () -> {
                        boolean r = reproService.runAndCheckRepro(
                                worktreePath, testPackage + "." + testClassName,
                                exceptionType, exceptionMsg, MAX_REPRO_ROUNDS);
                        return Map.of("reproduced", r);
                    }).get("reproduced").equals(Boolean.TRUE);
            if (!reproduced) {
                String note = "Repro: could not reproduce after " + MAX_REPRO_ROUNDS + " rounds (build/env failure likely — see maven output)";
                buildNotes.add(note);
                log.warn("[FixFlowRunner] step5: {}", note);
                // continue to step 6 — don't abort
            } else {
                log.info("[FixFlowRunner] step5 done: exception reproduced");
            }

            // ---- Step 6: AI fix ----
            String methodSource = readMethodSource(worktreePath, throwPointSig);
            final String finalExceptionMsg = exceptionMsg;
            String filePath = sigToFilePath(throwPointSig);
            runStep(sid, turnId, "ai_fix",
                    Map.of("message", "Generating fix with AI...",
                            "throwPointSig", throwPointSig, "filePath", filePath),
                    () -> {
                        // TODO: entry params from capture data
                        String fixedSource = fixService.fix(throwPointSig, exceptionType, finalExceptionMsg, "{}", methodSource);
                        worktreeService.applyFix(worktreePath, filePath, fixedSource);
                        return Map.of("filePath", filePath);
                    });
            log.info("[FixFlowRunner] step6 done: fix applied to {}", filePath);

            // ---- Step 7: Run test pass ----
            // Lenient policy: pass check failure doesn't abort the flow either.
            boolean passed = runStep(sid, turnId, "run_pass",
                    Map.of("message", "Verifying fix passes tests...", "testFqn", testFqn),
                    () -> {
                        boolean p = reproService.runAndCheckPass(worktreePath, testFqn);
                        return Map.of("passed", p);
                    }).get("passed").equals(Boolean.TRUE);
            if (!passed) {
                String note = "Pass: fix did not pass the test (build/env failure likely — see maven output)";
                buildNotes.add(note);
                log.warn("[FixFlowRunner] step7: {}", note);
                // continue to commit — the fix is still applied to the worktree
            } else {
                log.info("[FixFlowRunner] step7 done: tests pass");
            }

            // ---- Step 8: Commit ----
            String commitHash = runStep(sid, turnId, "commit",
                    Map.of("message", "Committing fix...", "branchName", branchName),
                    () -> {
                        String hash = worktreeService.commit(branchName, worktreePath,
                                "fix: auto-fix for " + exceptionType + " in " + throwPointSig);
                        session.setCommitHash(hash);
                        return Map.of("commitHash", hash);
                    }).get("commitHash").toString();
            log.info("[FixFlowRunner] step8 done: commit={}", commitHash);

            // ---- Step 9: Done ----
            // Build verdict: SUCCESS if no build notes, otherwise SUCCESS_WITH_NOTES.
            // Both states keep the fix committed; the notes travel in the checkpoint
            // finalText so the model/user can decide whether to trust the unverified fix.
            session.setStatus(buildNotes.isEmpty() ? "SUCCESS" : "SUCCESS_UNVERIFIED");
            fixSessionRepository.update(session);
            String finalText = buildSummaryText(commitHash, branchName, filePath, exceptionType,
                    throwPointSig, buildNotes);
            pushCheckpoint(sid, turnId, finalText);
            log.info("[FixFlowRunner] step9 done: session={} status={}", session.getId(), session.getStatus());

        } catch (Exception e) {
            log.error("[FixFlowRunner] flow failed: {}", e.getMessage(), e);
            pushError(sid, turnId, "Fix flow failed: " + e.getMessage());
            markFailed(session, "ERROR");
        }
    }

    // ------------------------------------------------------------------
    // Step implementations
    // ------------------------------------------------------------------

    private Map<String, Object> stepLogRecognition(FixSession session) {
        ReportProjectInfo info = resolveReportProjectInfo(session.getReportId());
        String projectPath = info.projectPath;
        String stackTrace = info.stackTrace;
        // 用 report.logMessage 作为 errorMsg（FixOrchestrator 不再预先设置），
        // 否则 DAG 永远不会被调用，导致 throwPointSig/exceptionType 始终为 null。
        String errorMsg = session.getErrorMsg() != null && !session.getErrorMsg().isBlank()
                ? session.getErrorMsg()
                : (stackTrace != null && !stackTrace.isBlank() ? stackTrace : null);
        if (errorMsg != null) {
            try {
                Map<String, Object> result = logAnalysisOrchestrator.analyzeLog(
                        errorMsg,
                        stackTrace,
                        projectPath,
                        null,   // serviceName
                        null    // traceId
                );
                if (result != null) {
                    if (projectPath != null && result.get("projectPath") == null) {
                        result.put("projectPath", projectPath);
                    }
                    // DAG 把 exceptionType/throwPointSig 放在嵌套字段里（parsedError.errorType、
                    // keyFrames[0].fullSignature），FixFlowRunner 顶层取不到。这里展平到顶层，
                    // 让后续 step4 能直接 result.get("exceptionType") / get("throwPointSig")。
                    flattenDagOutputs(result);
                    return result;
                }
                return Map.of();
            } catch (Exception e) {
                log.warn("[FixFlowRunner] log analysis failed: {}", e.getMessage());
            }
        }
        // Fallback: use what's already in the session
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("exceptionType", session.getErrorMsg());
        fallback.put("throwPointSig", session.getThrowPointSig());
        if (projectPath != null) {
            fallback.put("projectPath", projectPath);
        }
        return fallback;
    }

    /**
     * 把 DAG 输出里嵌套的 exceptionType/throwPointSig 展平到顶层，让后续 step4 能直接取到。
     * - exceptionType ← parsedError.errorType（DAG 只在 parsedError 里写 errorType）
     * - throwPointSig ← keyFrames[0].fullSignature（DAG 用 className+"."+methodName 作为签名）
     */
    private void flattenDagOutputs(Map<String, Object> result) {
        if (result.get("exceptionType") == null) {
            Object parsedObj = result.get("parsedError");
            if (parsedObj instanceof Map<?, ?> parsed) {
                Object errType = parsed.get("errorType");
                if (errType instanceof String s && !s.isBlank() && !"Unknown".equals(s)) {
                    result.put("exceptionType", s);
                }
            }
        }
        if (result.get("throwPointSig") == null) {
            Object keyFramesObj = result.get("keyFrames");
            if (keyFramesObj instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> frame) {
                    Object sig = frame.get("fullSignature");
                    if (sig instanceof String s && !s.isBlank()) {
                        result.put("throwPointSig", s);
                    }
                }
            }
        }
    }

    /** 报告里抽出的项目信息：projectPath 和原始堆栈（用于在多仓库下定位 throwPointSig）。 */
    private static class ReportProjectInfo {
        final String projectPath;
        final String stackTrace;
        ReportProjectInfo(String projectPath, String stackTrace) {
            this.projectPath = projectPath;
            this.stackTrace = stackTrace;
        }
    }

    /**
     * 从 log_analysis_report 表读 projectPath（query_params.projectPath）
     * 和原始 stackTrace（log_stack_trace 列）。
     */
    private ReportProjectInfo resolveReportProjectInfo(String reportIdStr) {
        if (reportIdStr == null || reportIdStr.isBlank()) return new ReportProjectInfo(null, null);
        Long reportId;
        try {
            reportId = Long.parseLong(reportIdStr);
        } catch (NumberFormatException e) {
            log.warn("[FixFlowRunner] reportId 不是数字: {}", reportIdStr);
            return new ReportProjectInfo(null, null);
        }
        LogAnalysisRepository.LogAnalysisReportEntity report = logAnalysisRepository.findById(reportId);
        if (report == null) return new ReportProjectInfo(null, null);
        String pp = null;
        if (report.getQueryParams() != null) {
            Object obj = report.getQueryParams().get("projectPath");
            pp = obj instanceof String s && !s.isBlank() ? s : null;
        }
        return new ReportProjectInfo(pp, report.getLogStackTrace());
    }

    private TestGenInput buildTestGenInput(Map<String, Object> analysis,
                                           String throwPointSig,
                                           String exceptionType) {
        String methodName = throwPointSig.contains(".")
                ? throwPointSig.substring(throwPointSig.lastIndexOf('.') + 1)
                : throwPointSig;
        return TestGenInput.builder()
                .testMethodName("test" + capitalize(methodName) + capitalize(exceptionType))
                .testMethodSignature(throwPointSig)
                .exceptionType(exceptionType)
                .exceptionMessage(str(analysis.get("exceptionMessage"), null))
                .entryParams(Map.of())
                .spans(List.of())
                .callChain(List.of())
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void markFailed(FixSession session, String status) {
        session.setStatus(status);
        fixSessionRepository.update(session);
    }

    /**
     * Compose the final checkpoint text. If buildNotes is non-empty, the fix
     * was applied but could not be locally built/tested — surface that clearly
     * so the model/user can decide whether to trust the unverified fix.
     */
    private static String buildSummaryText(String commitHash, String branchName, String filePath,
                                           String exceptionType, String throwPointSig,
                                           List<String> buildNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fix applied. Commit: ").append(commitHash)
                .append(", branch: ").append(branchName)
                .append(", file: ").append(filePath)
                .append(", exception: ").append(exceptionType)
                .append(", signature: ").append(throwPointSig);
        if (!buildNotes.isEmpty()) {
            sb.append("\n\n⚠️ Local build/test could not be verified:\n");
            for (int i = 0; i < buildNotes.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(buildNotes.get(i)).append('\n');
            }
            sb.append("\nThe fix is committed but UNVERIFIED — review the diff before merging. ");
            sb.append("Common cause: target project's parent POM or dependencies cannot be ");
            sb.append("resolved from the configured maven repositories in this environment.");
        } else {
            sb.append("\n\n✓ Reproduced, fix applied, tests pass.");
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface StepBody {
        Map<String, Object> execute() throws Exception;
    }

    private Map<String, Object> runStep(long sessionId, String turnId,
                                       String toolName, Map<String, Object> input, StepBody body) {
        pushToolStart(sessionId, turnId, toolName, input);
        try {
            Map<String, Object> result = body.execute();
            pushToolResult(sessionId, turnId, toolName, result);
            return result;
        } catch (Exception e) {
            pushToolResult(sessionId, turnId, toolName,
                    Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            throw new RuntimeException("Step " + toolName + " failed", e);
        }
    }

    private void pushToolStart(long sessionId, String turnId, String toolName, Map<String, Object> input) {
        if (sessionId <= 0) return;
        // Persist full shape (turnId/toolName/input) so DB replay matches WS live events;
        // otherwise ChatMessageList skips these events for missing payload.turnId.
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("turnId", turnId);
        payloadMap.put("toolName", toolName);
        payloadMap.put("input", input);
        String payload = writePayload(payloadMap);
        AgentEvent ev = AgentEvent.toolUse(sessionId, 0, toolName, payload,
                idemKey(sessionId, toolName, "start", turnId));
        ev.setTurnId(turnId);
        try { agentEventRepository.append(ev); } catch (Exception e) {
            log.debug("[FixFlowRunner] persist tool_use_start failed: {}", e.getMessage());
        }
        wsPush(sessionId, wsEvent(ev, sessionId, () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "tool_use_start");
            m.put("turnId", turnId);
            m.put("toolName", toolName);
            m.put("input", input);
            return m;
        }));
    }

    private void pushToolResult(long sessionId, String turnId, String toolName, Map<String, Object> result) {
        if (sessionId <= 0) return;
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("turnId", turnId);
        payloadMap.put("toolName", toolName);
        payloadMap.put("result", result);
        String payload = writePayload(payloadMap);
        AgentEvent ev = AgentEvent.toolResult(sessionId, 0, toolName, payload,
                idemKey(sessionId, toolName, "result", turnId));
        ev.setTurnId(turnId);
        try { agentEventRepository.append(ev); } catch (Exception e) {
            log.debug("[FixFlowRunner] persist tool_result failed: {}", e.getMessage());
        }
        wsPush(sessionId, wsEvent(ev, sessionId, () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "tool_result");
            m.put("turnId", turnId);
            m.put("toolName", toolName);
            m.put("result", result);
            return m;
        }));
    }

    private void pushCheckpoint(long sessionId, String turnId, String finalText) {
        if (sessionId <= 0) return;
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("turnId", turnId);
        payloadMap.put("summary", "Fix flow completed");
        payloadMap.put("finalText", finalText);
        String payload = writePayload(payloadMap);
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .seq(0)
                .type(EventType.CHECKPOINT)
                .payload(payload)
                .idempotencyKey(idemKey(sessionId, "checkpoint", turnId))
                .turnId(turnId)
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        try { agentEventRepository.append(ev); } catch (Exception e) {
            log.debug("[FixFlowRunner] persist checkpoint failed: {}", e.getMessage());
        }
        wsPush(sessionId, wsEvent(ev, sessionId, () -> {
            Map<String, Object> m = new LinkedHashMap<>(payloadMap);
            m.put("type", "checkpoint");
            return m;
        }));
    }

    private void pushError(long sessionId, String turnId, String errorMsg) {
        if (sessionId <= 0) return;
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("turnId", turnId);
        payloadMap.put("error", errorMsg);
        String payload = writePayload(payloadMap);
        AgentEvent ev = AgentEvent.builder()
                .sessionId(sessionId)
                .seq(0)
                .type(EventType.ERROR)
                .payload(payload)
                .idempotencyKey(idemKey(sessionId, "error", turnId))
                .turnId(turnId)
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        try { agentEventRepository.append(ev); } catch (Exception e) {
            log.debug("[FixFlowRunner] persist error failed: {}", e.getMessage());
        }
        wsPush(sessionId, wsEvent(ev, sessionId, () -> {
            Map<String, Object> m = new LinkedHashMap<>(payloadMap);
            m.put("type", "error");
            return m;
        }));
    }

    private void wsPush(long sessionId, Map<String, Object> event) {
        try {
            wsHandler.pushEvent(sessionId, event);
        } catch (Exception e) {
            log.debug("[FixFlowRunner] ws push failed: {}", e.getMessage());
        }
    }

    @FunctionalInterface
    private interface WsEventBuilder {
        Map<String, Object> build();
    }

    private Map<String, Object> wsEvent(AgentEvent ev, long sessionId, WsEventBuilder builder) {
        Map<String, Object> enriched = new LinkedHashMap<>(builder.build());
        enriched.put("sessionId", sessionId);
        if (ev != null && ev.getId() != null) {
            enriched.put("eventId", ev.getId());
            enriched.put("seq", ev.getSeq());
            enriched.put("createdAt", ev.getCreatedAt());
        } else {
            enriched.put("createdAt", System.currentTimeMillis() / 1000L);
        }
        return enriched;
    }

    private String writePayload(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            log.debug("[FixFlowRunner] payload serialize failed: {}", e.getMessage());
            return "{}";
        }
    }

    private static String idemKey(long sessionId, String toolName, String suffix, String turnId) {
        return "fix-" + sessionId + "-" + turnId + "-" + toolName + "-" + suffix;
    }

    private static String idemKey(long sessionId, String kind, String turnId) {
        return "fix-" + sessionId + "-" + turnId + "-" + kind;
    }

    private static String str(Object o, String dflt) {
        if (o instanceof String s && !s.isBlank()) return s;
        return dflt;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String extractTestClassName(TestGenInput input) {
        String name = input.getTestMethodName();
        // e.g. "testDoStuffNullPointerException" -> "DoStuffNullPointerExceptionTest"
        // simpler: just use "ReproTest"
        return "ReproTest";
    }

    private static String extractTestPackage(TestGenInput input) {
        String sig = input.getTestMethodSignature();
        if (sig == null) return "com.huawei.hisi.fixengine.test";
        int lastDot = sig.lastIndexOf('.');
        if (lastDot > 0) {
            // "com.foo.Bar.method" -> package is from the class part
            String classPart = sig.substring(0, lastDot);
            int pkgDot = classPart.lastIndexOf('.');
            if (pkgDot > 0) {
                return classPart.substring(0, pkgDot);
            }
        }
        return "com.huawei.hisi.fixengine.test";
    }

    private String extractRepoPath(Map<String, Object> analysis, FixSession session) {
        Object pp = analysis.get("projectPath");
        if (pp instanceof String s && !s.isBlank()) {
            // Prefer throwPointSig from analysis (DAG-derived), fall back to session.
            String sig = str(analysis.get("throwPointSig"), session.getThrowPointSig());
            String resolved = resolveMultiRepoPath(s, sig);
            if (resolved != null) return resolved;
        }
        // Fallback to system property; refuse to silently default to user.dir
        // (which is the backend's CWD, not the target repo) — passing that to
        // WorktreeService would point git worktree add at the wrong repository.
        String configured = System.getProperty("hisi.fix.repo-path");
        if (configured != null && !configured.isBlank()) return configured;
        throw new IllegalStateException(
                "Cannot determine repoPath for fix session " + session.getId()
                + ": analysis.projectPath is null and -Dhisi.fix.repo-path not set");
    }

    /**
     * 探测仓库当前分支作为 worktree 的 base branch，兼容 master/main/develop 等任意默认分支。
     * 探测失败时退回 "master"（保持旧行为，不破坏已有链路）。
     */
    private String resolveBaseBranch(String repoPath) {
        String branch = worktreeService.currentBranch(repoPath);
        if (branch != null && !branch.isBlank()) {
            log.info("[FixFlowRunner] baseBranch={} (probed from {})", branch, repoPath);
            return branch;
        }
        log.warn("[FixFlowRunner] currentBranch probe failed for {}, fallback to master", repoPath);
        return "master";
    }

    /**
     * 当 projectPath 为逗号分隔的多个仓库路径时，根据 throwPointSig 推导出的源文件
     * 相对路径，在多仓库根下逐一探测，命中第一个含此文件的仓库即返回它。
     * 若只有一个仓库直接返回；找不到匹配仓库则返回 null（由调用方继续走 fallback 链）。
     */
    private String resolveMultiRepoPath(String projectPath, String throwPointSig) {
        List<String> repos = Arrays.stream(projectPath.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (repos.size() <= 1) return projectPath.trim();

        // 1) 如果 throwPointSig 已知，按它推导源文件相对路径并逐一探测
        if (throwPointSig != null && !throwPointSig.isBlank()) {
            String relative = sigToFilePath(throwPointSig);
            for (String repo : repos) {
                Path candidate = Path.of(repo, relative);
                if (Files.exists(candidate)) {
                    log.info("[FixFlowRunner] throwPointSig 命中仓库 {} (file={})", repo, relative);
                    return repo;
                }
            }
            log.warn("[FixFlowRunner] throwPointSig={} 在 {} 个仓库下都找不到 {}，尝试从 stack trace 推断",
                    throwPointSig, repos.size(), relative);
        }

        // 2) throwPointSig 未知或未命中：从 logStackTrace 中解析 at <FQN>(<file>:<line>)
        //    逐 frame 在多仓库根下探测源文件存在性，命中即返回该仓库。
        //    由于子工程嵌套（如 repo/hiapm-web/src/...），用 Files.walk 软探测
        //    能在子目录中找到目标 .java 文件的仓库根。
        String stackTrace = resolveReportProjectInfo(currentReportId).stackTrace;
        if (stackTrace == null || stackTrace.isBlank()) {
            log.warn("[FixFlowRunner] projectPath 是多仓库 ({}) 且 throwPointSig 与 stackTrace 均为空，无法选择",
                    repos.size());
            return null;
        }
        for (String fqn : extractStackFrameClasses(stackTrace)) {
            String relative = classFqnToRelativePath(fqn);
            for (String repo : repos) {
                String hit = probeRepoForJavaFile(repo, relative);
                if (hit != null) {
                    log.info("[FixFlowRunner] stack frame 命中仓库 {} (class={}, file={})",
                            repo, fqn, hit);
                    return repo;
                }
            }
        }
        log.warn("[FixFlowRunner] stack trace 中未找到命中任何仓库的源文件 (repos={})", repos.size());
        return null;
    }

    /** 当前正在处理的 reportId，由 run() 入口设置，供 resolveMultiRepoPath 读 stack trace 用。 */
    private String currentReportId;

    /** 从 Java stack trace 文本中抽出每个 frame 的类 FQN（保持顺序）。 */
    private static final Pattern STACK_FRAME_PATTERN =
            Pattern.compile("^\\s*at\\s+(\\w+(?:\\.\\w+)+)\\.[\\w$<>]+\\(");

    private static List<String> extractStackFrameClasses(String stackTrace) {
        List<String> result = new ArrayList<>();
        for (String line : stackTrace.split("\\r?\\n")) {
            Matcher m = STACK_FRAME_PATTERN.matcher(line);
            if (m.find()) {
                String fqn = m.group(1);
                if (!result.contains(fqn)) result.add(fqn);
            }
        }
        return result;
    }

    /** 类 FQN → 源文件相对路径（仅替换点为斜杠并加 .java 后缀，子包结构保留）。 */
    private static String classFqnToRelativePath(String classFqn) {
        return "src/main/java/" + classFqn.replace('.', '/') + ".java";
    }

    /**
     * 在仓库根下软探测目标 Java 文件，支持嵌套子工程（如 repo/hiapm-web/src/...）。
     * 命中则返回文件相对路径（含子工程前缀），否则返回 null。
     * 软探测：用 Files.walk 最多扫到目标文件名出现即停（最多 6 层深度限制成本）。
     */
    private static String probeRepoForJavaFile(String repoRoot, String relativePathTail) {
        Path repoPath = Paths.get(repoRoot);
        if (!Files.isDirectory(repoPath)) return null;
        // 先尝试直接拼接（标准 Maven 单工程）
        Path direct = repoPath.resolve(relativePathTail);
        if (Files.exists(direct)) return relativePathTail;
        // 取文件名，扫嵌套子工程
        String fileName = relativePathTail.substring(relativePathTail.lastIndexOf('/') + 1);
        // 取该 Java 文件所在子目录的尾部一段（如 "ctms/v2"）作为匹配锚点
        String[] tailParts = relativePathTail.split("/");
        String tailAnchor = tailParts.length >= 3
                ? tailParts[tailParts.length - 3] + "/" + tailParts[tailParts.length - 2] + "/" + fileName
                : fileName;
        int maxDepth = 12;
        try (Stream<Path> stream = Files.walk(repoPath, maxDepth)) {
            var iter = stream.iterator();
            while (iter.hasNext()) {
                Path p = iter.next();
                String s = p.toString().replace('\\', '/');
                if (s.endsWith(tailAnchor)) {
                    return repoPath.relativize(p).toString().replace('\\', '/');
                }
            }
        } catch (Exception ignored) {
            // 探测失败视为未命中
        }
        return null;
    }

    /**
     * Read the source of the method to be fixed.
     * 从 worktree 中读取源文件并提取目标方法体。
     */
    private String readMethodSource(String worktreePath, String throwPointSig) {
        if (throwPointSig == null || throwPointSig.isBlank()) {
            return "// empty throwPointSig";
        }
        String filePath = sigToFilePath(throwPointSig);
        Path sourcePath = Path.of(worktreePath, filePath);
        if (!Files.exists(sourcePath)) {
            log.warn("[FixFlowRunner] source file not found: {}", sourcePath);
            return "// source file not found: " + filePath;
        }
        try {
            String source = Files.readString(sourcePath);
            // 提取方法体：从方法签名到对应的右花括号
            String methodName = throwPointSig.substring(throwPointSig.lastIndexOf('.') + 1);
            return extractMethodBody(source, methodName);
        } catch (Exception e) {
            log.warn("[FixFlowRunner] failed to read source: {}", e.getMessage());
            return "// failed to read: " + e.getMessage();
        }
    }

    /**
     * 从源代码中提取指定方法的完整方法体。
     */
    private static String extractMethodBody(String source, String methodName) {
        // 查找方法签名行（支持 public/protected/private + 返回类型 + 方法名）
        String[] lines = source.split("\n");
        int startLine = -1;
        int braceCount = 0;
        boolean inMethod = false;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inMethod) {
                // 查找包含方法名的行（排除注释和字符串）
                String trimmed = line.trim();
                if (trimmed.contains(methodName + "(") &&
                    (trimmed.contains("public ") || trimmed.contains("private ") ||
                     trimmed.contains("protected ") || trimmed.contains("static "))) {
                    startLine = i;
                    inMethod = true;
                }
            }
            if (inMethod) {
                sb.append(line).append("\n");
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }
                if (braceCount == 0 && startLine != i) {
                    break;
                }
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? "// method not found: " + methodName : result;
    }

    /**
     * Convert a method signature like "com.foo.Bar.doStuff" to a file path.
     * TODO: proper resolution using KG
     */
    private static String sigToFilePath(String throwPointSig) {
        if (throwPointSig == null) return "src/main/java/Unknown.java";
        int lastDot = throwPointSig.lastIndexOf('.');
        if (lastDot > 0) {
            String classFqn = throwPointSig.substring(0, lastDot);
            return "src/main/java/" + classFqn.replace('.', '/') + ".java";
        }
        return "src/main/java/Unknown.java";
    }
}
