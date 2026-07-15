package com.huawei.hisi.fixengine.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Runs Maven builds via {@link ProcessBuilder}.
 * All methods block until the process completes.
 */
@Slf4j
@Component
public class MavenExecutor {

    private static final long DEFAULT_TIMEOUT_MINUTES = 10;

    /**
     * Run {@code mvn test} for a specific test class in the given module.
     *
     * @param worktreePath absolute path to the project root
     * @param testClass    fully-qualified test class name
     * @param module       Maven module (the {@code -pl} value); null to skip
     * @return structured result with exit code and combined output
     */
    public TestRunResult runTest(String worktreePath, String testClass, String module) {
        log.info("[MavenExecutor] running test={} module={} in {}", testClass, module, worktreePath);

        var cmd = new java.util.ArrayList<String>();
        // Windows 上 mvn 是 mvn.cmd 批处理；ProcessBuilder 不会自动走 PATHEXT，
        // 直接 "mvn" 会 CreateProcess error=2。显式选 mvn.cmd。
        cmd.add(System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn");
        cmd.add("test");
        cmd.add("-Dtest=" + testClass);
        if (module != null && !module.isBlank()) {
            cmd.add("-pl");
            cmd.add(module);
        }
        cmd.add("-q");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(worktreePath));
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = proc.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("[MavenExecutor] timed out after {} min", DEFAULT_TIMEOUT_MINUTES);
                return new TestRunResult(-1, "TIMEOUT after " + DEFAULT_TIMEOUT_MINUTES + " minutes\n" + output);
            }

            int exitCode = proc.exitValue();
            log.info("[MavenExecutor] exit={} output.len={}", exitCode, output.length());
            return new TestRunResult(exitCode, output.toString());
        } catch (Exception e) {
            log.error("[MavenExecutor] failed: {}", e.getMessage(), e);
            return new TestRunResult(-1, "EXECUTION_ERROR: " + e.getMessage());
        }
    }
}
