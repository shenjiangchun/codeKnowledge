package com.huawei.hisi.service.impact.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Test case model representing a recommended test case for the changed code.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCase {

    /**
     * Unique test case identifier
     */
    private String testCaseId;

    /**
     * Test case name
     */
    private String name;

    /**
     * Test case description
     */
    private String description;

    /**
     * Test type: UNIT/INTEGRATION/API/E2E
     */
    private TestType testType;

    /**
     * Priority level
     */
    private Priority priority;

    /**
     * Target method to test
     */
    private String targetMethod;

    /**
     * Target class to test
     */
    private String targetClass;

    /**
     * Test scenario description
     */
    private String scenario;

    /**
     * Input parameters description
     */
    private List<TestInput> inputs;

    /**
     * Expected output description
     */
    private String expectedOutput;

    /**
     * Preconditions for the test
     */
    private List<String> preconditions;

    /**
     * Test steps
     */
    private List<TestStep> steps;

    /**
     * Assertions to verify
     */
    private List<String> assertions;

    /**
     * Mock requirements
     */
    private List<MockRequirement> mocks;

    /**
     * Related call chain for integration tests
     */
    private String relatedCallChainId;

    /**
     * Estimated test coverage contribution
     */
    private int coverageScore;

    /**
     * Whether this test case already exists
     */
    private boolean existingTest;

    /**
     * Test type enumeration
     */
    public enum TestType {
        /**
         * Unit test for single method
         */
        UNIT,

        /**
         * Integration test for multiple components
         */
        INTEGRATION,

        /**
         * API test for HTTP endpoints
         */
        API,

        /**
         * End-to-end test
         */
        E2E,

        /**
         * Performance test
         */
        PERFORMANCE
    }

    /**
     * Priority enumeration
     */
    public enum Priority {
        P0,  // Must have
        P1,  // Should have
        P2,  // Nice to have
        P3   // Optional
    }

    /**
     * Test input model
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestInput {
        private String parameterName;
        private String parameterType;
        private Object value;
        private String description;
        private boolean isBoundaryValue;
    }

    /**
     * Test step model
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestStep {
        private int order;
        private String action;
        private String description;
    }

    /**
     * Mock requirement model
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MockRequirement {
        private String targetClass;
        private String targetMethod;
        private String mockBehavior;
        private boolean required;
    }
}