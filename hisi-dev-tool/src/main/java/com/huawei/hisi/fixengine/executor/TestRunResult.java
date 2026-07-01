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
     * @param exceptionType    simple class name of the expected exception
     * @param exceptionMessage substring expected in the exception message (nullable)
     * @return true if the output contains the exception type and (optionally) message
     */
    public boolean isReproduced(String exceptionType, String exceptionMessage) {
        if (output == null) return false;
        boolean typeMatch = output.contains(exceptionType);
        if (exceptionMessage == null || exceptionMessage.isBlank()) {
            return typeMatch;
        }
        return typeMatch && output.contains(exceptionMessage);
    }
}
