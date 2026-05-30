package com.huawei.hisi.mergeanalysis.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImpactResult {
    private List<AffectedEntryPoint> affectedEntryPoints;
    private List<CallChainEdge> callChainEdges;
    private String businessImpactSummary;
    private String riskLevel;

    @Data
    @Builder
    public static class AffectedEntryPoint {
        private String nodeId;
        private String entryType;
        private String httpMethod;
        private String urlPattern;
        private String className;
        private String methodName;
    }

    @Data
    @Builder
    public static class CallChainEdge {
        private String callerId;
        private String callerName;
        private String calleeId;
        private String calleeName;
        private String callType;
    }
}
