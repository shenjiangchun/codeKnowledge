package com.huawei.hisi.fixengine.service;

import com.huawei.hisi.fixengine.agent.TestGenAgent;
import com.huawei.hisi.fixengine.model.TestGenInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thin service layer over {@link TestGenAgent}.
 * Handles the iterative test-fix loop (max 3 rounds).
 */
@Slf4j
@Service
public class TestGenService {

    private static final int MAX_FIX_ROUNDS = 3;

    private final TestGenAgent testGenAgent;

    public TestGenService(TestGenAgent testGenAgent) {
        this.testGenAgent = testGenAgent;
    }

    /**
     * Generate a test and iteratively fix compilation/runtime errors.
     *
     * @param input        test-generation context
     * @param compileCheck function that returns null if compilation succeeds, or an error message
     * @return the final test source code
     */
    public String generate(TestGenInput input, java.util.function.Function<String, String> compileCheck) {
        String testCode = testGenAgent.generate(input);

        for (int round = 1; round <= MAX_FIX_ROUNDS; round++) {
            String error = compileCheck.apply(testCode);
            if (error == null || error.isBlank()) {
                log.info("[TestGenService] test compiles after {} generation round(s)", round);
                return testCode;
            }
            log.info("[TestGenService] round {}/{} fix needed, error.len={}",
                    round, MAX_FIX_ROUNDS, error.length());
            testCode = testGenAgent.fixTest(testCode, error);
        }

        log.warn("[TestGenService] test still has issues after {} rounds, returning as-is", MAX_FIX_ROUNDS);
        return testCode;
    }

    /**
     * Generate a test without compile checking.
     */
    public String generate(TestGenInput input) {
        return testGenAgent.generate(input);
    }
}
