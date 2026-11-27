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
public class ImpactResult {
    private List<AffectedEntryPoint> affectedEntryPoints;
    private List<CallChainEdge> callChainEdges;
    private String businessImpactSummary;
    private String riskLevel;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallChainEdge {
        private String callerId;
        private String callerName;
        private String calleeId;
        private String calleeName;
        private String callType;
    }
}
