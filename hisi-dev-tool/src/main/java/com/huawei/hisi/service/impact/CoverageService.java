package com.huawei.hisi.service.impact;

/**
 * Coverage Service Interface
 *
 * Provides test coverage data for risk assessment.
 * Implementations can integrate with coverage tools (JaCoCo, SonarQube, etc.)
 * or use default values when coverage data is unavailable.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface CoverageService {

    /**
     * Get test coverage score for a specific class
     *
     * @param className the fully qualified class name
     * @return coverage score (0-100), or default value if not available
     */
    int getCoverageScore(String className);

    /**
     * Get test coverage score for a specific method
     *
     * @param className the fully qualified class name
     * @param methodName the method name
     * @return coverage score (0-100), or default value if not available
     */
    int getCoverageScore(String className, String methodName);

    /**
     * Get the default coverage score used when actual data is unavailable
     *
     * @return default coverage score (0-100)
     */
    int getDefaultCoverageScore();

    /**
     * Check if coverage data is available for a class
     *
     * @param className the fully qualified class name
     * @return true if coverage data is available
     */
    boolean hasCoverageData(String className);
}