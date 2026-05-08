package com.huawei.hisi.service.impact.impl;

import com.huawei.hisi.service.impact.CoverageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default implementation of CoverageService
 *
 * Returns configurable default coverage score when actual coverage data is unavailable.
 * This implementation can be replaced by more sophisticated implementations that
 * integrate with JaCoCo, SonarQube, or other coverage tools.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class DefaultCoverageServiceImpl implements CoverageService {

    /**
     * Default coverage score when actual data is unavailable.
     * Configurable via application.yml: impact.default-coverage-score
     */
    @Value("${impact.default-coverage-score:50}")
    private int defaultCoverageScore;

    @Override
    public int getCoverageScore(String className) {
        log.debug("Getting coverage score for class: {}", className);
        // Default implementation returns configured default value
        // Future implementations can integrate with JaCoCo or SonarQube
        return defaultCoverageScore;
    }

    @Override
    public int getCoverageScore(String className, String methodName) {
        log.debug("Getting coverage score for method: {}.{}", className, methodName);
        // Default implementation returns configured default value
        // Future implementations can integrate with JaCoCo or SonarQube
        return defaultCoverageScore;
    }

    @Override
    public int getDefaultCoverageScore() {
        return defaultCoverageScore;
    }

    @Override
    public boolean hasCoverageData(String className) {
        // Default implementation always returns false as no actual data is available
        // Future implementations can check if coverage report exists
        return false;
    }
}