package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.executor.MavenExecutor;
import com.huawei.hisi.fixengine.executor.TestRunResult;
import com.huawei.hisi.fixengine.model.FixSession;
import com.huawei.hisi.fixengine.model.TestGenInput;
import com.huawei.hisi.fixengine.repository.FixSessionRepository;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagOrchestrator;
import com.huawei.hisi.ram.chat.RamChatWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the 9-step fix pipeline for a {@link FixSession}.
 *
 * <p>Each step pushes a progress message via WebSocket. Steps that depend on
 * unimplemented integrations (KG, capture data) are stubbed with TODO comments.
 */
@Slf4j
@Service
public class FixFlowRunner {

    private static final int MAX_REPRO_ROUNDS = 3;

    private final LogAnalysisDagOrchestrator logAnalysisOrchestrator;
    private final WorktreeService worktreeService;
    private final TestGenService testGenService;
    private final ReproService reproService;
    private final FixService fixService;
    private final MavenExecutor mavenExecutor;
    private final FixSessionRepository fixSessionRepository;
    private final RamChatWebSocketHandler wsHandler;

    public FixFlowRunner(LogAnalysisDagOrchestrator logAnalysisOrchestrator,
                         WorktreeService worktreeService,
                         TestGenService testGenService,
                         ReproService reproService,
                         FixService fixService,
                         MavenExecutor mavenExecutor,
                         FixSessionRepository fixSessionRepository,
                         RamChatWebSocketHandler wsHandler) {
        this.logAnalysisOrchestrator = logAnalysisOrchestrator;
        this.worktreeService = worktreeService;
        this.testGenService = testGenService;
        this.reproService = reproService;
        this.fixService = fixService;
        this.mavenExecutor = mavenExecutor;
        this.fixSessionRepository = fixSessionRepository;
        this.wsHandler = wsHandler;
    }

    /**
     * Run the full 9-step fix pipeline.
     *
     * @param session the fix session (must already be persisted)
     */
    public void run(FixSession session) {
        // chatSessionId 现为 String，需解析为 long 给 pushWs 使用
        long sid = 0L;
        if (session.getChatSessionId() != null) {
            try { sid = Long.parseLong(session.getChatSessionId()); } catch (NumberFormatException ignored) {}
        }

        try {
            // ---- Step 1: Log recognition ----
            pushWs(sid, "log_recognition", "Analyzing log to extract exception details...");
            Map<String, Object> analysisResult = stepLogRecognition(session);
            String exceptionType = str(analysisResult.get("exceptionType"), session.getErrorMsg());
            String throwPointSig = str(analysisResult.get("throwPointSig"), session.getThrowPointSig());
            log.info("[FixFlowRunner] step1 done: exceptionType={} throwPointSig={}", exceptionType, throwPointSig);

            // ---- Step 2: KG search ----
            pushWs(sid, "kg_search", "Searching knowledge graph for target method...");
            // TODO: integrate with KG hybrid_search to find method location
            log.info("[FixFlowRunner] step2: KG search not yet integrated, using throwPointSig={}", throwPointSig);

            // ---- Step 3: Create worktree ----
            pushWs(sid, "create_worktree", "Creating git worktree...");
            String repoPath = extractRepoPath(analysisResult, session);
            String branchName = session.getBranchName();
            String worktreePath = worktreeService.createWorktree(branchName, repoPath, "master");
            session.setWorktreePath(worktreePath);
            fixSessionRepository.update(session);
            log.info("[FixFlowRunner] step3 done: worktree={}", worktreePath);

            // ---- Step 4: AI generate test（带编译检查，最多 3 轮修复） ----
            pushWs(sid, "generate_test", "Generating reproduction test...");
            TestGenInput testInput = buildTestGenInput(analysisResult, throwPointSig, exceptionType);
            String testClassName = extractTestClassName(testInput);
            String testPackage = extractTestPackage(testInput);
            String testFqn = testPackage + "." + testClassName;

            // 使用 compileCheck 重载：生成 → 写文件 → 编译 → 失败则 AI 修复 → 重试
            String testCode = testGenService.generate(testInput, code -> {
                worktreeService.writeTestFile(worktreePath, testPackage, testClassName, code);
                TestRunResult result = mavenExecutor.runTest(worktreePath, testFqn, null);
                return result.isPassed() ? null : result.output();
            });
            worktreeService.writeTestFile(worktreePath, testPackage, testClassName, testCode);
            log.info("[FixFlowRunner] step4 done: testClass={}", testClassName);

            // ---- Step 5: Run repro test ----
            pushWs(sid, "run_repro", "Running reproducibility test (" + MAX_REPRO_ROUNDS + " rounds)...");
            String exceptionMsg = str(analysisResult.get("exceptionMessage"), null);
            boolean reproduced = reproService.runAndCheckRepro(
                    worktreePath, testPackage + "." + testClassName,
                    exceptionType, exceptionMsg, MAX_REPRO_ROUNDS);
            if (!reproduced) {
                pushWs(sid, "repro_failed", "Could not reproduce the exception after " + MAX_REPRO_ROUNDS + " rounds");
                markFailed(session, "REPRO_FAILED");
                return;
            }
            log.info("[FixFlowRunner] step5 done: exception reproduced");

            // ---- Step 6: AI fix ----
            pushWs(sid, "ai_fix", "Generating fix with AI...");
            String methodSource = readMethodSource(worktreePath, throwPointSig);
            // TODO: entry params from capture data
            String fixedSource = fixService.fix(throwPointSig, exceptionType, exceptionMsg, "{}", methodSource);
            String filePath = sigToFilePath(throwPointSig);
            worktreeService.applyFix(worktreePath, filePath, fixedSource);
            log.info("[FixFlowRunner] step6 done: fix applied to {}", filePath);

            // ---- Step 7: Run test pass ----
            pushWs(sid, "run_pass", "Verifying fix passes tests...");
            boolean passed = reproService.runAndCheckPass(
                    worktreePath, testPackage + "." + testClassName);
            if (!passed) {
                pushWs(sid, "pass_failed", "Fix did not pass the test");
                markFailed(session, "TEST_NOT_PASSED");
                return;
            }
            log.info("[FixFlowRunner] step7 done: tests pass");

            // ---- Step 8: Commit ----
            pushWs(sid, "commit", "Committing fix...");
            String commitHash = worktreeService.commit(branchName, worktreePath,
                    "fix: auto-fix for " + exceptionType + " in " + throwPointSig);
            session.setCommitHash(commitHash);
            log.info("[FixFlowRunner] step8 done: commit={}", commitHash);

            // ---- Step 9: Done ----
            pushWs(sid, "done", "Fix completed successfully");
            session.setStatus("SUCCESS");
            fixSessionRepository.update(session);
            log.info("[FixFlowRunner] step9 done: session={}", session.getId());

        } catch (Exception e) {
            log.error("[FixFlowRunner] flow failed: {}", e.getMessage(), e);
            pushWs(sid, "error", "Fix flow failed: " + e.getMessage());
            markFailed(session, "ERROR");
        }
    }

    // ------------------------------------------------------------------
    // Step implementations
    // ------------------------------------------------------------------

    private Map<String, Object> stepLogRecognition(FixSession session) {
        // Try full log analysis if we have error details
        if (session.getErrorMsg() != null && !session.getErrorMsg().isBlank()) {
            try {
                Map<String, Object> result = logAnalysisOrchestrator.analyzeLog(
                        session.getErrorMsg(),
                        null,   // stackTrace — not available from FixSession alone
                        null,   // projectPath
                        null,   // serviceName
                        null    // traceId
                );
                return result != null ? result : Map.of();
            } catch (Exception e) {
                log.warn("[FixFlowRunner] log analysis failed: {}", e.getMessage());
            }
        }
        // Fallback: use what's already in the session
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("exceptionType", session.getErrorMsg());
        fallback.put("throwPointSig", session.getThrowPointSig());
        return fallback;
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

    private void pushWs(long sessionId, String step, String message) {
        if (sessionId <= 0) return;
        try {
            wsHandler.pushEvent(sessionId, Map.of(
                    "type", "fix_progress",
                    "step", step,
                    "message", message,
                    "createdAt", System.currentTimeMillis() / 1000L
            ));
        } catch (Exception e) {
            log.debug("[FixFlowRunner] ws push failed: {}", e.getMessage());
        }
    }

    private void markFailed(FixSession session, String status) {
        session.setStatus(status);
        fixSessionRepository.update(session);
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

    private static String extractRepoPath(Map<String, Object> analysis, FixSession session) {
        Object pp = analysis.get("projectPath");
        if (pp instanceof String s && !s.isBlank()) return s;
        // TODO: lookup from report or config
        return System.getProperty("user.dir");
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
