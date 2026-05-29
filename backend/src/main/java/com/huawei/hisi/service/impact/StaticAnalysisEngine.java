package com.huawei.hisi.service.impact;

import com.huawei.hisi.service.impact.model.CallChain;
import com.huawei.hisi.service.impact.model.Caller;

import java.util.List;
import java.util.Set;

/**
 * Static analysis engine interface for call chain analysis.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface StaticAnalysisEngine {

    /**
     * Trace call chain from entry point to target method.
     *
     * Traces all call paths from the entry point (HTTP endpoint, MQ consumer, etc.)
     * to the specified target method.
     *
     * @param entryPoint entry point method signature (className.methodName)
     * @param targetMethod target method signature to trace to
     * @param depth maximum tracing depth
     * @return call chain from entry point to target method
     */
    CallChain traceCallChain(String entryPoint, String targetMethod, int depth);

    /**
     * Trace all call chains reaching the target method.
     *
     * Finds all entry points that eventually call the target method
     * and builds complete call chains for each.
     *
     * @param targetMethod target method signature
     * @param maxDepth maximum tracing depth
     * @return list of all call chains reaching the target
     */
    List<CallChain> traceAllCallChains(String targetMethod, int maxDepth);

    /**
     * Analyze dependencies for a class.
     *
     * Returns all classes that the specified class depends on.
     *
     * @param className full qualified class name
     * @return dependency analysis result
     */
    Dependencies analyzeDependencies(String className);

    /**
     * Find all direct callers of a method.
     *
     * Searches the codebase for methods that directly invoke
     * the specified method.
     *
     * @param methodSignature method signature (className.methodName)
     * @return list of direct callers
     */
    List<Caller> findDirectCallers(String methodSignature);

    /**
     * Find all entry points that call a target method.
     *
     * Entry points include:
     * - REST controller endpoints
     * - MQ consumer endpoints
     * - Scheduled tasks
     * - Feign client implementations
     *
     * @param targetMethod target method signature
     * @return list of entry points
     */
    List<EntryPointInfo> findEntryPoints(String targetMethod);

    /**
     * Check if a method exists in the analyzed codebase.
     *
     * @param methodSignature method signature to check
     * @return true if method exists
     */
    boolean methodExists(String methodSignature);

    /**
     * Get all methods in a class.
     *
     * @param className full qualified class name
     * @return list of method signatures in the class
     */
    List<String> getMethodsInClass(String className);

    /**
     * Dependencies model.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class Dependencies {
        /**
         * Class name analyzed
         */
        private String className;

        /**
         * Classes this class depends on (imports, calls)
         */
        private Set<String> dependsOn;

        /**
         * Classes that depend on this class
         */
        private Set<String> dependedBy;

        /**
         * Interfaces implemented by this class
         */
        private Set<String> implementsInterfaces;

        /**
         * Parent class (if extends)
         */
        private String parentClass;
    }

    /**
     * Entry point info model.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class EntryPointInfo {
        /**
         * Entry point type
         */
        private CallChain.EntryPointType type;

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
         * Associated URI (for HTTP endpoints)
         */
        private String uri;

        /**
         * HTTP method (GET/POST/PUT/DELETE)
         */
        private String httpMethod;

        /**
         * Associated MQ endpoint (topic/queue)
         */
        private String mqEndpoint;
    }
}