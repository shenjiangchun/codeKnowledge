package com.huawei.hisi.service.semantic;

import com.huawei.hisi.config.ExceptionInheritanceConfig;
import com.huawei.hisi.service.semantic.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 异常传播路径分析器
 *
 * 分析异常在代码中的传播路径，追踪异常可能的抛出来源，
 * 支持故障诊断中的异常定位和根因分析。
 */
@Slf4j
@Service
public class ExceptionPathAnalyzer {

    private final CodeKnowledgeGraph graph;
    private final ExceptionInheritanceConfig exceptionConfig;

    /**
     * 默认路径搜索深度
     */
    private static final int DEFAULT_SEARCH_DEPTH = 10;

    /**
     * 每次分析返回的最大路径数量
     */
    private static final int MAX_PATHS_COUNT = 20;

    /**
     * 路径概率衰减因子（每增加一层调用，概率衰减）
     */
    private static final double PROBABILITY_DECAY_FACTOR = 0.15;

    /**
     * try-catch块对异常传播的影响权重
     */
    private static final double TRY_CATCH_BLOCK_WEIGHT = 0.3;

    public ExceptionPathAnalyzer(CodeKnowledgeGraph graph, ExceptionInheritanceConfig exceptionConfig) {
        this.graph = graph;
        this.exceptionConfig = exceptionConfig;
    }

    /**
     * 分析异常传播路径
     *
     * @param exceptionType 异常类型全限定名（如 java.lang.NullPointerException）
     * @param location 异常发生位置（如 com.example.UserService.login）
     * @return 异常路径分析结果，包含所有可能的传播路径
     */
    public ExceptionPath analyzeExceptionPath(String exceptionType, String location) {
        log.info("开始分析异常传播路径: exceptionType={}, location={}", exceptionType, location);

        long startTime = System.currentTimeMillis();

        // 1. 从知识图谱查找异常节点
        Optional<ExceptionNode> exceptionNode = graph.findException(exceptionType);

        // 2. 查找可能的异常抛出源方法
        List<MethodNode> possibleSources = graph.findExceptionSources(exceptionType, location);

        // 3. 为每个源方法构建传播路径
        List<PropagationPath> propagationPaths = possibleSources.stream()
                .limit(MAX_PATHS_COUNT)
                .map(source -> buildPropagationPath(source, location, exceptionType))
                .filter(path -> path != null && path.getProbability() > 0)
                .sorted(Comparator.comparing(PropagationPath::getProbability).reversed())
                .collect(Collectors.toList());

        // 4. 如果知识图谱中没有数据，尝试基于异常类型推断
        if (propagationPaths.isEmpty()) {
            propagationPaths = inferPropagationPaths(exceptionType, location);
        }

        long endTime = System.currentTimeMillis();

        return ExceptionPath.builder()
                .exceptionType(exceptionType)
                .location(location)
                .propagationPaths(propagationPaths)
                .analyzedAt(endTime)
                .build();
    }

    /**
     * 构建单条异常传播路径
     *
     * @param source 抛出异常的源方法
     * @param targetLocation 目标位置
     * @param exceptionType 异常类型
     * @return 传播路径对象
     */
    private PropagationPath buildPropagationPath(MethodNode source, String targetLocation, String exceptionType) {
        // 使用图算法查找从源到目标的调用路径
        List<MethodNode> callChain = graph.findCallPath(source.getNodeId(), targetLocation);

        if (callChain.isEmpty()) {
            // 如果找不到正向路径，尝试反向查找
            callChain = graph.findReverseCallPath(targetLocation, source.getNodeId());
            if (callChain.isEmpty()) {
                // 无法确定调用链，仅保留源方法
                callChain = List.of(source);
            }
        }

        // 计算路径概率
        double probability = calculateProbability(callChain, source, exceptionType);

        // 获取行号信息
        Integer lineNumber = source.getStartLineNumber();

        // 检查路径中的try-catch块
        Boolean hasTryCatch = checkTryCatchInPath(callChain, exceptionType);
        String catchBehavior = determineCatchBehavior(callChain, exceptionType);

        // 构建调用链字符串列表
        List<String> callChainStrings = callChain.stream()
                .map(MethodNode::getMethodKey)
                .collect(Collectors.toList());

        return PropagationPath.builder()
                .sourceMethod(source.getMethodKey())
                .callChain(callChainStrings)
                .probability(probability)
                .lineNumber(lineNumber)
                .sourceClassName(source.getClassName())
                .sourceFileName(source.getFilePath())
                .hasTryCatch(hasTryCatch)
                .catchBehavior(catchBehavior)
                .build();
    }

    /**
     * 计算传播路径的概率
     *
     * 基于调用链深度、异常类型匹配度、try-catch等因素计算
     *
     * @param callChain 调用链
     * @param source 源方法
     * @param exceptionType 异常类型
     * @return 概率值（0.0-1.0）
     */
    private double calculateProbability(List<MethodNode> callChain, MethodNode source, String exceptionType) {
        // 基础概率：根据异常类型匹配度
        double baseProbability = calculateExceptionMatchProbability(source, exceptionType);

        // 根据调用链深度衰减
        int chainDepth = callChain.size();
        double depthFactor = Math.pow(1 - PROBABILITY_DECAY_FACTOR, chainDepth - 1);

        // 根据方法复杂度调整（复杂度高的方法更容易抛出异常）
        int complexity = source.getComplexity() != null ? source.getComplexity() : 1;
        double complexityFactor = 1.0 + Math.min(complexity / 10.0, 0.5);

        // 计算最终概率
        double probability = baseProbability * depthFactor * complexityFactor;

        // 确保概率在有效范围内
        return Math.max(0.0, Math.min(1.0, probability));
    }

    /**
     * 计算异常类型匹配概率
     */
    private double calculateExceptionMatchProbability(MethodNode method, String exceptionType) {
        List<String> thrownExceptions = method.getThrownExceptions();
        if (thrownExceptions == null || thrownExceptions.isEmpty()) {
            return 0.3; // 未声明异常，假设较低概率
        }

        // 完全匹配
        if (thrownExceptions.contains(exceptionType)) {
            return 1.0;
        }

        // 检查是否抛出父类异常
        for (String declared : thrownExceptions) {
            if (isExceptionSubtype(exceptionType, declared)) {
                return 0.8;
            }
        }

        // 检查是否抛出子类异常
        for (String declared : thrownExceptions) {
            if (isExceptionSubtype(declared, exceptionType)) {
                return 0.6;
            }
        }

        return 0.4; // 存在其他异常声明
    }

    /**
     * 判断异常继承关系（使用配置类）
     * TD-004修复：将硬编码的异常继承关系移至配置
     */
    private boolean isExceptionSubtype(String child, String parent) {
        return exceptionConfig.isSubtype(child, parent);
    }

    /**
     * 检查路径中是否包含try-catch块
     * TD-005修复：完善try-catch检测逻辑
     *
     * 检查内容：
     * 1. 检查路径中的方法是否包含catch块
     * 2. 检查catch块是否实际处理该异常类型
     * 3. 检查是否存在嵌套try-catch情况
     *
     * @param callChain 调用链
     * @param exceptionType 待检查的异常类型
     * @return true表示路径中存在能捕获该异常的try-catch块
     */
    private Boolean checkTryCatchInPath(List<MethodNode> callChain, String exceptionType) {
        if (callChain == null || callChain.isEmpty()) {
            return false;
        }

        for (MethodNode method : callChain) {
            // 检查方法是否有catch块捕获的异常信息
            List<String> caughtExceptions = method.getCaughtExceptions();
            if (caughtExceptions != null && !caughtExceptions.isEmpty()) {
                // 检查catch块是否能捕获该异常
                if (canCatchException(caughtExceptions, exceptionType)) {
                    log.debug("方法 {} 的catch块可以捕获异常 {}", method.getMethodKey(), exceptionType);
                    return true;
                }
            }

            // 检查是否存在嵌套try-catch
            if (method.isHasNestedTryCatch()) {
                log.debug("方法 {} 存在嵌套try-catch块", method.getMethodKey());
                // 嵌套try-catch可能影响异常传播，标记为有try-catch
                return true;
            }
        }

        return false;
    }

    /**
     * 判断给定的catch块是否能捕获指定的异常类型
     *
     * @param caughtExceptions catch块声明的异常类型列表
     * @param exceptionType 待捕获的异常类型
     * @return true表示可以捕获
     */
    private boolean canCatchException(List<String> caughtExceptions, String exceptionType) {
        for (String caughtType : caughtExceptions) {
            // catch (Throwable) 能捕获所有异常
            if ("java.lang.Throwable".equals(caughtType)) {
                return true;
            }

            // catch (Exception) 能捕获所有Exception及其子类
            if ("java.lang.Exception".equals(caughtType)) {
                if (!exceptionConfig.isSubtype(exceptionType, "java.lang.Error")) {
                    return true;
                }
            }

            // catch (RuntimeException) 能捕获所有RuntimeException及其子类
            if ("java.lang.RuntimeException".equals(caughtType)) {
                if (exceptionConfig.isSubtype(exceptionType, "java.lang.RuntimeException")) {
                    return true;
                }
            }

            // catch (具体异常类型) - 精确匹配或子类匹配
            if (caughtType.equals(exceptionType) || exceptionConfig.isSubtype(exceptionType, caughtType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 确定catch块的处理行为
     */
    private String determineCatchBehavior(List<MethodNode> callChain, String exceptionType) {
        // 简化实现：根据方法分类推断
        for (MethodNode method : callChain) {
            if (method.getCategory() == MethodCategory.EXCEPTION_HANDLER) {
                return "HANDLE";
            }
        }
        return null;
    }

    /**
     * 当知识图谱数据不足时，基于异常类型推断传播路径
     *
     * @param exceptionType 异常类型
     * @param location 发生位置
     * @return 推断的传播路径列表
     */
    private List<PropagationPath> inferPropagationPaths(String exceptionType, String location) {
        log.debug("知识图谱数据不足，开始推断传播路径");

        List<PropagationPath> inferredPaths = new ArrayList<>();

        // 常见异常类型的典型抛出场景推断
        Map<String, List<String>> typicalSources = getTypicalExceptionSources();

        List<String> sources = typicalSources.getOrDefault(exceptionType, List.of());

        for (String typicalSource : sources) {
            PropagationPath path = PropagationPath.builder()
                    .sourceMethod(typicalSource)
                    .callChain(List.of(typicalSource, location))
                    .probability(0.5) // 推断路径概率较低
                    .hasTryCatch(false)
                    .build();
            inferredPaths.add(path);
        }

        // 添加当前位置作为直接抛出源的路径
        PropagationPath directPath = PropagationPath.builder()
                .sourceMethod(location)
                .callChain(List.of(location))
                .probability(0.7)
                .hasTryCatch(false)
                .build();
        inferredPaths.add(directPath);

        return inferredPaths.stream()
                .sorted(Comparator.comparing(PropagationPath::getProbability).reversed())
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * 获取常见异常类型的典型抛出源方法
     */
    private Map<String, List<String>> getTypicalExceptionSources() {
        Map<String, List<String>> sources = new HashMap<>();

        sources.put("java.lang.NullPointerException",
                List.of("get", "toString", "equals", "hashCode", "valueOf", "parse"));

        sources.put("java.lang.IllegalArgumentException",
                List.of("validate", "check", "verify", "parse", "convert"));

        sources.put("java.lang.IndexOutOfBoundsException",
                List.of("get", "substring", "charAt", "remove", "add"));

        sources.put("java.io.IOException",
                List.of("read", "write", "open", "close", "connect", "send"));

        sources.put("java.sql.SQLException",
                List.of("executeQuery", "executeUpdate", "getConnection", "commit", "rollback"));

        sources.put("java.lang.ClassCastException",
                List.of("cast", "convert", "valueOf", "parse"));

        return sources;
    }

    /**
     * 批量分析多个异常的传播路径
     *
     * @param exceptionTypes 异常类型列表
     * @param location 共同位置
     * @return 异常路径分析结果列表
     */
    public List<ExceptionPath> analyzeBatchExceptionPaths(List<String> exceptionTypes, String location) {
        return exceptionTypes.stream()
                .map(exceptionType -> analyzeExceptionPath(exceptionType, location))
                .collect(Collectors.toList());
    }

    /**
     * 获取Top N高概率的异常来源方法
     *
     * @param exceptionType 异常类型
     * @param location 发生位置
     * @param n 返回数量
     * @return Top N源方法列表
     */
    public List<String> getTopExceptionSources(String exceptionType, String location, int n) {
        ExceptionPath pathResult = analyzeExceptionPath(exceptionType, location);
        return pathResult.getTopPaths(n).stream()
                .map(PropagationPath::getSourceMethod)
                .collect(Collectors.toList());
    }

    /**
     * 检查异常传播路径分析器是否可用
     *
     * @return true表示分析器已就绪
     */
    public boolean isReady() {
        return graph != null && !graph.isEmpty();
    }

    /**
     * 获取分析器的状态信息
     *
     * @return 状态信息字符串
     */
    public String getStatusInfo() {
        return String.format("ExceptionPathAnalyzer状态: 方法节点数=%d, 异常节点数=%d, 是否就绪=%s",
                graph.getMethodCount(),
                graph.getExceptionCount(),
                isReady() ? "是" : "否");
    }
}