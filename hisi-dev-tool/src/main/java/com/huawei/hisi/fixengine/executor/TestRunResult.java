package com.huawei.hisi.fixengine.executor;

/**
 * Result of a Maven test run.
 *
 * @param exitCode  process exit code (0 = all tests passed)
 * @param output    combined stdout + stderr
 */
public record TestRunResult(int exitCode, String output) {

    /** True when Maven exited 0 (all tests passed). */
    public boolean isPassed() {
        return exitCode == 0;
    }

    /**
     * Check whether the target exception was reproduced in the test output.
     *
     * <p>Uses surefire output context anchoring: only matches exception type
     * in surefire report sections (Tests run: ... / FAILURE! / ERROR!) to
     * avoid false positives from unrelated log output.
     *
     * @param exceptionType    simple class name of the expected exception
     * @param exceptionMessage substring expected in the exception message (nullable)
     * @return true if the output contains the exception type in a test-failure context
     */
    public boolean isReproduced(String exceptionType, String exceptionMessage) {
        if (output == null || exceptionType == null) return false;

        // 确认异常出现在 surefire 测试失败上下文中
        // Surefire 输出格式: "Tests run: X, Failures: Y, Errors: Z" 或 "FAILURE!" / "BUILD FAILURE"
        boolean inTestFailureContext = output.contains("FAILURE!") ||
                output.contains("ERROR!") ||
                output.contains("BUILD FAILURE") ||
                output.contains("Tests run:");

        if (!inTestFailureContext) {
            // 降级：直接匹配（兼容非 Maven 环境）
            return output.contains(exceptionType);
        }

        // 在测试失败上下文中匹配异常类型
        boolean typeMatch = output.contains(exceptionType);
        if (exceptionMessage == null || exceptionMessage.isBlank()) {
            return typeMatch;
        }
        return typeMatch && output.contains(exceptionMessage);
    }
}
