package com.huawei.hisi.service.impact.impl;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.service.impact.StaticAnalysisEngine;
import com.huawei.hisi.service.impact.model.CallChain;
import com.huawei.hisi.service.impact.model.Caller;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementation of StaticAnalysisEngine for call chain analysis.
 *
 * Uses JavaParser for static code analysis and leverages GlobalAnalysisCache
 * for pre-scanned data.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaticAnalysisEngineImpl implements StaticAnalysisEngine {

    private final GlobalAnalysisCache globalCache;

    /**
     * Cache for parsed compilation units
     */
    private final Map<String, CompilationUnit> compilationUnitCache = new ConcurrentHashMap<>();

    /**
     * Cache for caller lookup (method signature -> callers)
     */
    private final Map<String, List<Caller>> callerCache = new ConcurrentHashMap<>();

    @Override
    public CallChain traceCallChain(String entryPoint, String targetMethod, int depth) {
        log.info("Tracing call chain from {} to {} (depth: {})", entryPoint, targetMethod, depth);

        // Parse entry point
        String[] entryParts = parseMethodSignature(entryPoint);
        String entryClassName = entryParts[0];
        String entryMethodName = entryParts[1];

        // Parse target method
        String[] targetParts = parseMethodSignature(targetMethod);
        String targetClassName = targetParts[0];
        String targetMethodName = targetParts[1];

        // Build chain nodes
        List<CallChain.ChainNode> nodes = new ArrayList<>();

        // Build entry point info
        CallChain.EntryPoint entryPointInfo = findEntryPointInfo(entryClassName, entryMethodName);

        // Trace from entry point to target
        boolean found = tracePath(entryClassName, entryMethodName, targetClassName, targetMethodName, nodes, depth);

        if (!found) {
            log.warn("Could not find path from {} to {}", entryPoint, targetMethod);
            return CallChain.builder()
                    .chainId(generateChainId())
                    .entryPoint(entryPointInfo)
                    .nodes(nodes)
                    .depth(0)
                    .build();
        }

        // Calculate chain properties
        boolean containsAsyncCall = nodes.stream().anyMatch(n -> n.getCallType() == CallChain.CallType.ASYNC);
        boolean crossesServiceBoundary = nodes.stream().anyMatch(n -> n.getCallType() == CallChain.CallType.FEIGN);
        boolean containsMQCall = nodes.stream().anyMatch(n -> n.getCallType() == CallChain.CallType.MQ);

        // Determine risk level based on chain properties
        CallChain.RiskLevel riskLevel = determineChainRiskLevel(nodes.size(), containsAsyncCall, crossesServiceBoundary);

        return CallChain.builder()
                .chainId(generateChainId())
                .entryPoint(entryPointInfo)
                .nodes(nodes)
                .depth(nodes.size())
                .containsAsyncCall(containsAsyncCall)
                .crossesServiceBoundary(crossesServiceBoundary)
                .containsMQCall(containsMQCall)
                .riskLevel(riskLevel)
                .build();
    }

    @Override
    public List<CallChain> traceAllCallChains(String targetMethod, int maxDepth) {
        log.info("Tracing all call chains to {} (max depth: {})", targetMethod, maxDepth);

        List<CallChain> allChains = new ArrayList<>();

        // Find all entry points that call the target method
        List<EntryPointInfo> entryPoints = findEntryPoints(targetMethod);

        for (EntryPointInfo entryPoint : entryPoints) {
            try {
                CallChain chain = traceCallChain(entryPoint.getMethodSignature(), targetMethod, maxDepth);
                if (chain.getDepth() > 0) {
                    allChains.add(chain);
                }
            } catch (Exception e) {
                log.warn("Failed to trace chain from {}: {}", entryPoint.getMethodSignature(), e.getMessage());
            }
        }

        return allChains;
    }

    @Override
    public Dependencies analyzeDependencies(String className) {
        log.info("Analyzing dependencies for: {}", className);

        Set<String> dependsOn = new HashSet<>();
        Set<String> dependedBy = new HashSet<>();
        Set<String> implementsInterfaces = new HashSet<>();
        String parentClass = null;

        // Check extend map for parent class
        Set<String> parents = globalCache.getExtendMap().get(className);
        if (parents != null) {
            for (String parent : parents) {
                if (!parent.contains(".")) {
                    // Likely a class rather than interface
                    parentClass = parent;
                } else {
                    implementsInterfaces.add(parent);
                }
            }
        }

        // Check implementation map for classes that depend on this class
        if (implementsInterfaces.isEmpty()) {
            Set<String> impls = globalCache.getImplementationMap().get(className);
            if (impls != null) {
                dependedBy.addAll(impls);
            }
        }

        // Parse class file to find dependencies (imports, method calls)
        Path filePath = globalCache.getBeanMap().get(className);
        if (filePath != null) {
            try {
                CompilationUnit cu = parseJavaFile(filePath);
                if (cu != null) {
                    // Extract imports
                    cu.getImports().forEach(importDecl -> {
                        dependsOn.add(importDecl.getNameAsString());
                    });

                    // Find method calls to other classes
                    cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
                        try {
                            String calledClass = methodCall.resolve().declaringType().getQualifiedName();
                            dependsOn.add(calledClass);
                            dependedBy.add(calledClass); // reverse relationship
                        } catch (Exception e) {
                            // Ignore unresolved calls
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("Failed to parse class {}: {}", className, e.getMessage());
            }
        }

        return Dependencies.builder()
                .className(className)
                .dependsOn(dependsOn)
                .dependedBy(dependedBy)
                .implementsInterfaces(implementsInterfaces)
                .parentClass(parentClass)
                .build();
    }

    @Override
    public List<Caller> findDirectCallers(String methodSignature) {
        log.info("Finding direct callers for: {}", methodSignature);

        // Check cache first
        if (callerCache.containsKey(methodSignature)) {
            return callerCache.get(methodSignature);
        }

        List<Caller> callers = new ArrayList<>();

        String[] parts = parseMethodSignature(methodSignature);
        String className = parts[0];
        String methodName = parts[1];

        // Scan all classes in beanMap for callers
        for (Map.Entry<String, Path> entry : globalCache.getBeanMap().entrySet()) {
            String candidateClass = entry.getKey();
            Path filePath = entry.getValue();

            try {
                List<Caller> foundCallers = findCallersInFile(filePath, candidateClass, className, methodName);
                callers.addAll(foundCallers);
            } catch (Exception e) {
                log.warn("Failed to scan file {}: {}", filePath, e.getMessage());
            }
        }

        // Cache the result
        callerCache.put(methodSignature, callers);

        log.info("Found {} direct callers for {}", callers.size(), methodSignature);
        return callers;
    }

    @Override
    public List<EntryPointInfo> findEntryPoints(String targetMethod) {
        log.info("Finding entry points for: {}", targetMethod);

        List<EntryPointInfo> entryPoints = new ArrayList<>();

        // First find all callers
        List<Caller> allCallers = findAllCallersRecursive(targetMethod, DEFAULT_TRACE_DEPTH);

        // Filter callers that are entry points
        for (Caller caller : allCallers) {
            if (caller.isEntryPoint()) {
                EntryPointInfo entryPoint = EntryPointInfo.builder()
                        .type(determineEntryPointType(caller))
                        .className(caller.getClassName())
                        .methodName(caller.getMethodName())
                        .methodSignature(caller.getMethodSignature())
                        .uri(caller.getAssociatedUri())
                        .mqEndpoint(caller.getAssociatedMQEndpoint())
                        .build();
                entryPoints.add(entryPoint);
            }
        }

        // Also check URI map for HTTP endpoints
        for (Map.Entry<String, MethodDeclaration> entry : globalCache.getUriMap().entrySet()) {
            String uri = entry.getKey();
            MethodDeclaration method = entry.getValue();

            try {
                // Check if this method eventually calls the target
                String callerClass = method.findCompilationUnit()
                        .flatMap(cu -> cu.findFirst(ClassOrInterfaceDeclaration.class))
                        .flatMap(c -> c.getFullyQualifiedName())
                        .orElse("");

                String callerMethod = method.getNameAsString();

                // Check if this entry point calls the target (via call chain)
                if (callsTargetMethod(callerClass, callerMethod, targetMethod, DEFAULT_TRACE_DEPTH)) {
                    EntryPointInfo entryPoint = EntryPointInfo.builder()
                            .type(CallChain.EntryPointType.HTTP_ENDPOINT)
                            .className(callerClass)
                            .methodName(callerMethod)
                            .methodSignature(callerClass + "." + callerMethod)
                            .uri(uri)
                            .build();
                    entryPoints.add(entryPoint);
                }
            } catch (Exception e) {
                log.warn("Failed to check URI {}: {}", uri, e.getMessage());
            }
        }

        // Check MQ endpoints
        for (Map.Entry<String, List<String>> entry : globalCache.getMqConsumerIndex().entrySet()) {
            String topic = entry.getKey();
            List<String> consumerMethods = entry.getValue();

            for (String consumerMethod : consumerMethods) {
                // Check if this consumer calls the target
                if (callsTargetMethodFromSignature(consumerMethod, targetMethod, DEFAULT_TRACE_DEPTH)) {
                    String[] parts = parseMethodSignature(consumerMethod);
                    EntryPointInfo entryPoint = EntryPointInfo.builder()
                            .type(CallChain.EntryPointType.MQ_CONSUMER)
                            .className(parts[0])
                            .methodName(parts[1])
                            .methodSignature(consumerMethod)
                            .mqEndpoint(topic)
                            .build();
                    entryPoints.add(entryPoint);
                }
            }
        }

        log.info("Found {} entry points for {}", entryPoints.size(), targetMethod);
        return entryPoints;
    }

    @Override
    public boolean methodExists(String methodSignature) {
        String[] parts = parseMethodSignature(methodSignature);
        String className = parts[0];

        // Check if class exists in beanMap
        Path filePath = globalCache.getBeanMap().get(className);
        if (filePath == null) {
            return false;
        }

        try {
            CompilationUnit cu = parseJavaFile(filePath);
            if (cu != null) {
                return cu.findAll(MethodDeclaration.class).stream()
                        .anyMatch(m -> m.getNameAsString().equals(parts[1]));
            }
        } catch (Exception e) {
            log.warn("Failed to check method existence: {}", e.getMessage());
        }

        return false;
    }

    @Override
    public List<String> getMethodsInClass(String className) {
        log.info("Getting methods in class: {}", className);

        List<String> methods = new ArrayList<>();

        Path filePath = globalCache.getBeanMap().get(className);
        if (filePath == null) {
            return methods;
        }

        try {
            CompilationUnit cu = parseJavaFile(filePath);
            if (cu != null) {
                cu.findAll(MethodDeclaration.class).forEach(m -> {
                    methods.add(className + "." + m.getNameAsString());
                });
            }
        } catch (Exception e) {
            log.warn("Failed to get methods for class {}: {}", className, e.getMessage());
        }

        return methods;
    }

    // ============================================================
    // Private Helper Methods
    // ============================================================

    private static final int DEFAULT_TRACE_DEPTH = 10;

    private String[] parseMethodSignature(String signature) {
        int lastDot = signature.lastIndexOf('.');
        if (lastDot < 0) {
            throw new IllegalArgumentException("Invalid method signature: " + signature);
        }
        return new String[]{signature.substring(0, lastDot), signature.substring(lastDot + 1)};
    }

    private CompilationUnit parseJavaFile(Path filePath) {
        // Check cache
        String cacheKey = filePath.toString();
        if (compilationUnitCache.containsKey(cacheKey)) {
            return compilationUnitCache.get(cacheKey);
        }

        try {
            // Set up symbol solver
            CombinedTypeSolver typeSolver = globalCache.getTypeSolver();
            if (typeSolver == null) {
                typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
            }
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
            ParserConfiguration config = new ParserConfiguration().setSymbolResolver(symbolSolver);
            JavaParser javaParser = new JavaParser(config);

            Optional<CompilationUnit> result = javaParser.parse(filePath).getResult();
            if (result.isPresent()) {
                compilationUnitCache.put(cacheKey, result.get());
                return result.get();
            }
        } catch (Exception e) {
            log.warn("Failed to parse file {}: {}", filePath, e.getMessage());
        }

        return null;
    }

    private List<Caller> findCallersInFile(Path filePath, String candidateClass, String targetClass, String targetMethod) {
        List<Caller> callers = new ArrayList<>();

        CompilationUnit cu = parseJavaFile(filePath);
        if (cu == null) {
            return callers;
        }

        // Find all method declarations
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            // Check if this method calls the target method
            method.findAll(MethodCallExpr.class).forEach(call -> {
                try {
                    String calledMethodName = call.getNameAsString();

                    // Check if called method matches target
                    if (calledMethodName.equals(targetMethod)) {
                        // Try to resolve the call to verify it's the correct class
                        try {
                            String resolvedClass = call.resolve().declaringType().getQualifiedName();
                            if (resolvedClass.equals(targetClass)) {
                                Caller caller = Caller.builder()
                                        .className(candidateClass)
                                        .methodName(method.getNameAsString())
                                        .methodSignature(candidateClass + "." + method.getNameAsString())
                                        .callLineNumber(call.getRange().map(r -> r.begin.line).orElse(null))
                                        .filePath(filePath.toString())
                                        .callerType(determineCallerType(candidateClass, method))
                                        .isEntryPoint(isEntryPoint(candidateClass, method))
                                        .associatedUri(findAssociatedUri(candidateClass, method.getNameAsString()))
                                        .associatedMQEndpoint(findAssociatedMQEndpoint(candidateClass, method.getNameAsString()))
                                        .build();
                                callers.add(caller);
                            }
                        } catch (Exception resolveEx) {
                            // If cannot resolve, add as potential caller
                            Caller caller = Caller.builder()
                                    .className(candidateClass)
                                    .methodName(method.getNameAsString())
                                    .methodSignature(candidateClass + "." + method.getNameAsString())
                                    .callLineNumber(call.getRange().map(r -> r.begin.line).orElse(null))
                                    .filePath(filePath.toString())
                                    .callerType(Caller.CallerType.UNKNOWN)
                                    .isEntryPoint(false)
                                    .build();
                            callers.add(caller);
                        }
                    }
                } catch (Exception e) {
                    // Ignore errors in individual call analysis
                }
            });
        });

        return callers;
    }

    private Caller.CallerType determineCallerType(String className, MethodDeclaration method) {
        // Check annotations to determine type
        if (method.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("GetMapping") ||
                a.getNameAsString().contains("PostMapping") ||
                a.getNameAsString().contains("RequestMapping") ||
                a.getNameAsString().contains("DeleteMapping") ||
                a.getNameAsString().contains("PutMapping"))) {
            return Caller.CallerType.CONTROLLER;
        }

        if (method.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("KafkaListener") ||
                a.getNameAsString().contains("RabbitListener") ||
                a.getNameAsString().contains("RocketMQMessageListener") ||
                a.getNameAsString().contains("JmsListener"))) {
            return Caller.CallerType.MQ_ENDPOINT;
        }

        if (method.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("Scheduled"))) {
            return Caller.CallerType.SCHEDULED;
        }

        // Check class name patterns
        if (className.contains("Controller")) {
            return Caller.CallerType.CONTROLLER;
        }
        if (className.contains("Service")) {
            return Caller.CallerType.SERVICE;
        }
        if (className.contains("Dao") || className.contains("Repository")) {
            return Caller.CallerType.DAO;
        }
        if (className.contains("Util") || className.contains("Helper")) {
            return Caller.CallerType.UTIL;
        }

        return Caller.CallerType.UNKNOWN;
    }

    private boolean isEntryPoint(String className, MethodDeclaration method) {
        return determineCallerType(className, method) == Caller.CallerType.CONTROLLER ||
               determineCallerType(className, method) == Caller.CallerType.MQ_ENDPOINT ||
               determineCallerType(className, method) == Caller.CallerType.SCHEDULED;
    }

    private String findAssociatedUri(String className, String methodName) {
        // Check URI map
        for (Map.Entry<String, MethodDeclaration> entry : globalCache.getUriMap().entrySet()) {
            MethodDeclaration method = entry.getValue();
            if (method.getNameAsString().equals(methodName)) {
                String methodClass = method.findCompilationUnit()
                        .flatMap(cu -> cu.findFirst(ClassOrInterfaceDeclaration.class))
                        .flatMap(c -> c.getFullyQualifiedName())
                        .orElse("");
                if (methodClass.equals(className)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private String findAssociatedMQEndpoint(String className, String methodName) {
        // Check MQ consumer index
        for (Map.Entry<String, List<String>> entry : globalCache.getMqConsumerIndex().entrySet()) {
            for (String consumerMethod : entry.getValue()) {
                if (consumerMethod.equals(className + "." + methodName)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private CallChain.EntryPoint findEntryPointInfo(String className, String methodName) {
        String uri = findAssociatedUri(className, methodName);
        String mqEndpoint = findAssociatedMQEndpoint(className, methodName);

        CallChain.EntryPointType type = CallChain.EntryPointType.MANUAL_TRIGGER;
        if (uri != null) {
            type = CallChain.EntryPointType.HTTP_ENDPOINT;
        } else if (mqEndpoint != null) {
            type = CallChain.EntryPointType.MQ_CONSUMER;
        }

        return CallChain.EntryPoint.builder()
                .type(type)
                .className(className)
                .methodName(methodName)
                .uri(uri)
                .mqEndpoint(mqEndpoint)
                .build();
    }

    private boolean tracePath(String fromClass, String fromMethod, String targetClass, String targetMethod,
                              List<CallChain.ChainNode> nodes, int maxDepth) {
        if (maxDepth <= 0) {
            return false;
        }

        // Add current node
        nodes.add(CallChain.ChainNode.builder()
                .order(nodes.size())
                .className(fromClass)
                .methodName(fromMethod)
                .methodSignature(fromClass + "." + fromMethod)
                .callType(CallChain.CallType.DIRECT)
                .build());

        // Check if we reached the target
        if (fromClass.equals(targetClass) && fromMethod.equals(targetMethod)) {
            return true;
        }

        // Find next callers
        Path filePath = globalCache.getBeanMap().get(fromClass);
        if (filePath == null) {
            return false;
        }

        CompilationUnit cu = parseJavaFile(filePath);
        if (cu == null) {
            return false;
        }

        // Find method declaration
        Optional<MethodDeclaration> methodOpt = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(fromMethod))
                .findFirst();

        if (methodOpt.isEmpty()) {
            return false;
        }

        MethodDeclaration method = methodOpt.get();

        // Check method calls in this method
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            try {
                String calledMethod = call.getNameAsString();

                // Try to resolve
                String calledClass = call.resolve().declaringType().getQualifiedName();

                // Recursively trace
                if (tracePath(calledClass, calledMethod, targetClass, targetMethod, nodes, maxDepth - 1)) {
                    return true;
                }

                // Remove the node added by recursive call if path not found
                if (!nodes.isEmpty() && nodes.get(nodes.size() - 1).getClassName().equals(calledClass)) {
                    nodes.remove(nodes.size() - 1);
                }
            } catch (Exception e) {
                // Ignore unresolved calls
            }
        }

        return false;
    }

    private List<Caller> findAllCallersRecursive(String targetMethod, int depth) {
        List<Caller> allCallers = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        findAllCallersRecursiveInternal(targetMethod, allCallers, visited, depth);

        return allCallers;
    }

    private void findAllCallersRecursiveInternal(String currentMethod, List<Caller> callers,
                                                  Set<String> visited, int remainingDepth) {
        if (remainingDepth <= 0 || visited.contains(currentMethod)) {
            return;
        }

        visited.add(currentMethod);

        List<Caller> directCallers = findDirectCallers(currentMethod);
        callers.addAll(directCallers);

        // Recursively find callers of callers
        for (Caller caller : directCallers) {
            findAllCallersRecursiveInternal(caller.getMethodSignature(), callers, visited, remainingDepth - 1);
        }
    }

    private boolean callsTargetMethod(String fromClass, String fromMethod, String targetMethod, int depth) {
        String[] parts = parseMethodSignature(targetMethod);
        String targetClass = parts[0];
        String targetMethodName = parts[1];

        List<CallChain.ChainNode> nodes = new ArrayList<>();
        return tracePath(fromClass, fromMethod, targetClass, targetMethodName, nodes, depth);
    }

    private boolean callsTargetMethodFromSignature(String fromSignature, String targetMethod, int depth) {
        String[] fromParts = parseMethodSignature(fromSignature);
        return callsTargetMethod(fromParts[0], fromParts[1], targetMethod, depth);
    }

    private CallChain.EntryPointType determineEntryPointType(Caller caller) {
        if (caller.getAssociatedUri() != null) {
            return CallChain.EntryPointType.HTTP_ENDPOINT;
        }
        if (caller.getAssociatedMQEndpoint() != null) {
            return CallChain.EntryPointType.MQ_CONSUMER;
        }
        if (caller.getCallerType() == Caller.CallerType.SCHEDULED) {
            return CallChain.EntryPointType.SCHEDULED_TASK;
        }
        return CallChain.EntryPointType.MANUAL_TRIGGER;
    }

    private CallChain.RiskLevel determineChainRiskLevel(int depth, boolean async, boolean crossService) {
        if (depth > 10 || crossService) {
            return CallChain.RiskLevel.HIGH;
        }
        if (depth > 5 || async) {
            return CallChain.RiskLevel.MEDIUM;
        }
        return CallChain.RiskLevel.LOW;
    }

    private String generateChainId() {
        return "CHAIN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}