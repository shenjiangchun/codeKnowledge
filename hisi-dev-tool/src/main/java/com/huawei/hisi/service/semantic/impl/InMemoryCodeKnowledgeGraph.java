package com.huawei.hisi.service.semantic.impl;

import com.huawei.hisi.service.semantic.CodeKnowledgeGraph;
import com.huawei.hisi.service.semantic.model.ExceptionNode;
import com.huawei.hisi.service.semantic.model.MethodNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 代码知识图谱内存实现
 *
 * 使用内存Map实现的简易知识图谱，用于异常传播路径分析。
 * 适用于中小型项目的快速分析场景。
 */
@Component
public class InMemoryCodeKnowledgeGraph implements CodeKnowledgeGraph {

    /**
     * 方法节点存储：nodeId -> MethodNode
     */
    private final Map<String, MethodNode> methodNodes = new ConcurrentHashMap<>();

    /**
     * 方法键索引：className.methodName -> nodeId
     */
    private final Map<String, String> methodKeyIndex = new ConcurrentHashMap<>();

    /**
     * 异常节点存储：exceptionType -> ExceptionNode
     */
    private final Map<String, ExceptionNode> exceptionNodes = new ConcurrentHashMap<>();

    /**
     * 方法调用关系：callerNodeId -> Set<calleeNodeId>
     */
    private final Map<String, Set<String>> callRelations = new ConcurrentHashMap<>();

    /**
     * 反向调用关系：calleeNodeId -> Set<callerNodeId>
     */
    private final Map<String, Set<String>> reverseCallRelations = new ConcurrentHashMap<>();

    /**
     * 方法抛出异常关系：methodNodeId -> Set<exceptionType>
     */
    private final Map<String, Set<String>> throwsRelations = new ConcurrentHashMap<>();

    /**
     * 异常被方法抛出的反向关系：exceptionType -> Set<methodNodeId>
     */
    private final Map<String, Set<String>> exceptionSources = new ConcurrentHashMap<>();

    @Override
    public Optional<MethodNode> findMethod(String className, String methodName) {
        String key = className + "." + methodName;
        String nodeId = methodKeyIndex.get(key);
        if (nodeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(methodNodes.get(nodeId));
    }

    @Override
    public Optional<MethodNode> findMethodById(String nodeId) {
        return Optional.ofNullable(methodNodes.get(nodeId));
    }

    @Override
    public Optional<MethodNode> findMethodBySignature(String fullSignature) {
        // 简化实现：按className.methodName查找
        return methodNodes.values().stream()
                .filter(m -> m.getMethodKey().equals(fullSignature) ||
                        fullSignature.contains(m.getMethodKey()))
                .findFirst();
    }

    @Override
    public Optional<ExceptionNode> findException(String exceptionType) {
        return Optional.ofNullable(exceptionNodes.get(exceptionType));
    }

    @Override
    public List<MethodNode> findMethodsThrowingException(String exceptionType) {
        Set<String> methodNodeIds = exceptionSources.get(exceptionType);
        if (methodNodeIds == null || methodNodeIds.isEmpty()) {
            return List.of();
        }
        return methodNodeIds.stream()
                .map(methodNodes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<MethodNode> findExceptionSources(String exceptionType, String location) {
        List<MethodNode> allSources = findMethodsThrowingException(exceptionType);

        // 基于位置相关性排序：与当前位置调用链更近的方法优先
        Optional<MethodNode> locationMethod = findMethodBySignature(location);
        if (locationMethod.isEmpty()) {
            return allSources;
        }

        String locationNodeId = locationMethod.get().getNodeId();

        // 计算每个源方法到当前位置的距离
        return allSources.stream()
                .sorted((m1, m2) -> {
                    int d1 = calculateDistance(m1.getNodeId(), locationNodeId);
                    int d2 = calculateDistance(m2.getNodeId(), locationNodeId);
                    return Integer.compare(d1, d2);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<MethodNode> findCallers(String methodNodeId, int depth) {
        if (depth <= 0) {
            return List.of();
        }

        Set<String> callerIds = reverseCallRelations.get(methodNodeId);
        if (callerIds == null || callerIds.isEmpty()) {
            return List.of();
        }

        List<MethodNode> result = callerIds.stream()
                .map(methodNodes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 递归查找更深层调用者
        if (depth > 1) {
            for (String callerId : callerIds) {
                result.addAll(findCallers(callerId, depth - 1));
            }
        }

        return result;
    }

    @Override
    public List<MethodNode> findCallees(String methodNodeId, int depth) {
        if (depth <= 0) {
            return List.of();
        }

        Set<String> calleeIds = callRelations.get(methodNodeId);
        if (calleeIds == null || calleeIds.isEmpty()) {
            return List.of();
        }

        List<MethodNode> result = calleeIds.stream()
                .map(methodNodes::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 递归查找更深层被调用方法
        if (depth > 1) {
            for (String calleeId : calleeIds) {
                result.addAll(findCallees(calleeId, depth - 1));
            }
        }

        return result;
    }

    @Override
    public List<MethodNode> findCallPath(String sourceNodeId, String targetLocation) {
        Optional<MethodNode> targetMethod = findMethodBySignature(targetLocation);
        if (targetMethod.isEmpty()) {
            return List.of();
        }

        String targetNodeId = targetMethod.get().getNodeId();

        // 使用BFS查找从源到目标的路径
        return bfsPath(sourceNodeId, targetNodeId);
    }

    @Override
    public List<MethodNode> findReverseCallPath(String targetLocation, String sourceNodeId) {
        List<MethodNode> forwardPath = findCallPath(sourceNodeId, targetLocation);
        // 反转路径顺序
        List<MethodNode> reversePath = new ArrayList<>(forwardPath);
        Collections.reverse(reversePath);
        return reversePath;
    }

    @Override
    public void addMethod(MethodNode methodNode) {
        String nodeId = methodNode.getNodeId();
        methodNodes.put(nodeId, methodNode);
        methodKeyIndex.put(methodNode.getMethodKey(), nodeId);
    }

    @Override
    public void addException(ExceptionNode exceptionNode) {
        String exceptionType = exceptionNode.getExceptionType();
        exceptionNodes.put(exceptionType, exceptionNode);
    }

    @Override
    public void addCallRelation(String callerNodeId, String calleeNodeId) {
        callRelations.computeIfAbsent(callerNodeId, k -> new HashSet<>()).add(calleeNodeId);
        reverseCallRelations.computeIfAbsent(calleeNodeId, k -> new HashSet<>()).add(callerNodeId);
    }

    @Override
    public void addThrowsRelation(String methodNodeId, String exceptionType) {
        throwsRelations.computeIfAbsent(methodNodeId, k -> new HashSet<>()).add(exceptionType);
        exceptionSources.computeIfAbsent(exceptionType, k -> new HashSet<>()).add(methodNodeId);
    }

    @Override
    public int getMethodCount() {
        return methodNodes.size();
    }

    @Override
    public int getExceptionCount() {
        return exceptionNodes.size();
    }

    @Override
    public void clear() {
        methodNodes.clear();
        methodKeyIndex.clear();
        exceptionNodes.clear();
        callRelations.clear();
        reverseCallRelations.clear();
        throwsRelations.clear();
        exceptionSources.clear();
    }

    @Override
    public boolean isEmpty() {
        return methodNodes.isEmpty();
    }

    /**
     * 计算两个方法节点之间的距离（最短路径长度）
     */
    private int calculateDistance(String fromNodeId, String toNodeId) {
        if (fromNodeId.equals(toNodeId)) {
            return 0;
        }

        // BFS查找最短路径
        Map<String, Integer> distances = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(fromNodeId);
        distances.put(fromNodeId, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDistance = distances.get(current);

            Set<String> callees = callRelations.get(current);
            if (callees != null) {
                for (String callee : callees) {
                    if (!distances.containsKey(callee)) {
                        distances.put(callee, currentDistance + 1);
                        if (callee.equals(toNodeId)) {
                            return currentDistance + 1;
                        }
                        queue.add(callee);
                    }
                }
            }

            Set<String> callers = reverseCallRelations.get(current);
            if (callers != null) {
                for (String caller : callers) {
                    if (!distances.containsKey(caller)) {
                        distances.put(caller, currentDistance + 1);
                        if (caller.equals(toNodeId)) {
                            return currentDistance + 1;
                        }
                        queue.add(caller);
                    }
                }
            }
        }

        return Integer.MAX_VALUE; // 无路径可达
    }

    /**
     * BFS查找路径
     */
    private List<MethodNode> bfsPath(String startNodeId, String endNodeId) {
        if (startNodeId.equals(endNodeId)) {
            return List.of(methodNodes.get(startNodeId));
        }

        Map<String, String> parentMap = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(startNodeId);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            Set<String> callees = callRelations.get(current);
            if (callees != null) {
                for (String callee : callees) {
                    if (!visited.contains(callee)) {
                        visited.add(callee);
                        parentMap.put(callee, current);

                        if (callee.equals(endNodeId)) {
                            return buildPath(parentMap, endNodeId);
                        }
                        queue.add(callee);
                    }
                }
            }
        }

        return List.of(); // 无路径
    }

    /**
     * 从父节点映射构建路径
     */
    private List<MethodNode> buildPath(Map<String, String> parentMap, String endNodeId) {
        List<MethodNode> path = new ArrayList<>();
        String current = endNodeId;

        while (current != null) {
            MethodNode node = methodNodes.get(current);
            if (node != null) {
                path.add(node);
            }
            current = parentMap.get(current);
        }

        Collections.reverse(path);
        return path;
    }
}