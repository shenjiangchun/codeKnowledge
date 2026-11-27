package com.huawei.hisi.mergeanalysis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 需要能被 Jackson 反序列化（节点重跑时从事件表读回），原因见 {@link DiffResult}。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestScopeResult {
    private List<TestCaseGroup> groups;
    private List<String> regressionSuggestions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestCaseGroup {
        private String entryPointName;
        private String urlPattern;
        private String urlRoot;
        private int coveredEntryCount;
        private String coveredMethods;
        private String riskLevel;
        private List<TestCase> testCases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestCase {
        private String description;
        private String riskLevel;
        private String reason;
    }
}
