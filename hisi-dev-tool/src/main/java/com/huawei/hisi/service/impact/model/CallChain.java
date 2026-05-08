package com.huawei.hisi.service.impact.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Call chain model representing a complete call path from entry point to the changed method.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallChain {

    /**
     * Unique chain identifier
     */
    private String chainId;

    /**
     * Entry point of the call chain (HTTP endpoint, MQ consumer, etc.)
     */
    private EntryPoint entryPoint;

    /**
     * List of callers in the chain (from entry point to changed method)
     */
    private List<ChainNode> nodes;

    /**
     * Depth of the call chain
     */
    private int depth;

    /**
     * Whether the chain contains async calls
     */
    private boolean containsAsyncCall;

    /**
     * Whether the chain crosses service boundaries (Feign calls)
     */
    private boolean crossesServiceBoundary;

    /**
     * Whether the chain contains MQ calls
     */
    private boolean containsMQCall;

    /**
     * Risk level of this call chain
     */
    private RiskLevel riskLevel;

    /**
     * Chain node representing a single method in the call chain
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChainNode {
        /**
         * Node order in the chain (0 = entry point)
         */
        private int order;

        /**
         * Full qualified class name
         */
        private String className;

        /**
         * Method name
         */
        private String methodName;

        /**
         * Method signature
         */
        private String methodSignature;

        /**
         * Call type: DIRECT/ASYNC/FEIGN/MQ/etc.
         */
        private CallType callType;

        /**
         * Source file path
         */
        private String filePath;

        /**
         * Line number of the call
         */
        private Integer lineNumber;
    }

    /**
     * Entry point model
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryPoint {
        /**
         * Entry point type
         */
        private EntryPointType type;

        /**
         * Full qualified class name
         */
        private String className;

        /**
         * Method name
         */
        private String methodName;

        /**
         * Associated URI (for HTTP endpoints)
         */
        private String uri;

        /**
         * Associated MQ topic/queue (for MQ endpoints)
         */
        private String mqEndpoint;

        /**
         * HTTP method (GET/POST/PUT/DELETE)
         */
        private String httpMethod;
    }

    /**
     * Entry point type enumeration
     */
    public enum EntryPointType {
        HTTP_ENDPOINT,
        MQ_CONSUMER,
        SCHEDULED_TASK,
        FEIGN_CLIENT,
        MANUAL_TRIGGER
    }

    /**
     * Call type enumeration
     */
    public enum CallType {
        /**
         * Direct method call
         */
        DIRECT,

        /**
         * Asynchronous call (CompletableFuture, @Async)
         */
        ASYNC,

        /**
         * Feign client call (cross-service)
         */
        FEIGN,

        /**
         * MQ producer call
         */
        MQ,

        /**
         * Reflection call
         */
        REFLECTION,

        /**
         * Proxy call (MyBatis, JPA)
         */
        PROXY
    }

    /**
     * Risk level enumeration
     */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}