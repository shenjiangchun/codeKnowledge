package com.huawei.hisi.mergeanalysis.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TestScopeResult {
    private List<TestCaseGroup> groups;
    private List<String> regressionSuggestions;

    @Data
    @Builder
    public static class TestCaseGroup {
        private String entryPointName;
        private String urlPattern;
        private String riskLevel;
        private List<TestCase> testCases;
    }

    @Data
    @Builder
    public static class TestCase {
        private String description;
        private String riskLevel;
        private String reason;
    }
}
