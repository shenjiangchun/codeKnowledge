package com.huawei.hisi.ram.registry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentManifest {

    private String agentId;
    private String version;
    private List<String> capabilities;
    private List<String> tags;
    private String inputContract;
    private String outputContract;
    private Model model;
    private double costHint;
    private boolean supportsHitl;
    private CircuitBreakerConfig circuitBreaker;
    private Endpoint endpoint;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Model {
        private String preferred;
        private String fallback;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CircuitBreakerConfig {
        private double failureRateThreshold;
        private int minimumCalls;
        private int cooldownSeconds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Endpoint {
        private String transport;
        private String handler;
    }
}
