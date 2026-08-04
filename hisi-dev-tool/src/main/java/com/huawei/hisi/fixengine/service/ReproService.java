package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.executor.MavenExecutor;
import com.huawei.hisi.fixengine.executor.TestRunResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs reproducibility and pass verification tests via Maven.
 */
@Slf4j
@Service
public class ReproService {

    private final MavenExecutor mavenExecutor;

    public ReproService(MavenExecutor mavenExecutor) {
        this.mavenExecutor = mavenExecutor;
    }

    /**
     * Run the reproducibility test up to {@code maxRounds} times, checking that
     * the target exception is thrown.
     *
     * @param worktreePath   project root
     * @param testClassName  fully-qualified test class to run
     * @param exceptionType  expected exception simple name
     * @param exceptionMsg   expected exception message substring (nullable)
     * @param maxRounds      maximum attempts
     * @return true if the exception was reproduced in at least one round
     */
    public boolean runAndCheckRepro(String worktreePath,
                                    String testClassName,
                                    String exceptionType,
                                    String exceptionMsg,
                                    int maxRounds) {
        for (int round = 1; round <= maxRounds; round++) {
            log.info("[ReproService] repro round {}/{} for {}", round, maxRounds, testClassName);
            TestRunResult result = mavenExecutor.runTest(worktreePath, testClassName, null);

            if (result.isReproduced(exceptionType, exceptionMsg)) {
                log.info("[ReproService] exception reproduced on round {}", round);
                return true;
            }

            if (result.isPassed()) {
                log.warn("[ReproService] test passed without reproducing exception on round {}", round);
                // test passed but didn't reproduce — still counts as failure for repro
            }
        }
        log.warn("[ReproService] failed to reproduce after {} rounds", maxRounds);
        return false;
    }

    /**
     * Run the test and verify it passes (used after applying the fix).
     *
     * @param worktreePath  project root
     * @param testClassName fully-qualified test class
     * @return true if the test passes (exit code 0)
     */
    public boolean runAndCheckPass(String worktreePath, String testClassName) {
        log.info("[ReproService] checking test passes for {}", testClassName);
        TestRunResult result = mavenExecutor.runTest(worktreePath, testClassName, null);

        if (result.isPassed()) {
            log.info("[ReproService] test passed");
            return true;
        }

        log.warn("[ReproService] test failed exit={} output.len={}",
                result.exitCode(), result.output().length());
        return false;
    }
}
