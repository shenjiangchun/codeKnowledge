package com.huawei.hisi.agent.orchestrator;

import com.huawei.hisi.agent.DiagnosticAgent;
import com.huawei.hisi.agent.event.AgentEventPublisher;
import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentEvent;
import com.huawei.hisi.agent.model.AgentResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 编排服务
 * 管理多 Agent 协作诊断的执行流程
 *
 * 功能：
 * 1. Agent 注册与管理
 * 2. 执行计划生成（基于依赖和优先级）
 * 3. 并发执行 Agent
 * 4. 结果聚合与置信度加权
 * 5. WebSocket 事件推送
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private final AgentEventPublisher eventPublisher;
    private final List<DiagnosticAgent> agents;
    private final ExecutorService executorService;

    // 置信度阈值：低于此值的 Agent 结果将被标记为低置信度
    private static final double CONFIDENCE_THRESHOLD = 0.3;

    // 高置信度阈值：高于此值的 Agent 结果将作为主要结论
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.7;

    /**
     * 构造函数
     * 自动注入所有 DiagnosticAgent 实现
     *
     * @param eventPublisher 事件发布器
     * @param agents 所有 Agent 实现（Spring 自动注入）
     */
    public AgentOrchestrator(AgentEventPublisher eventPublisher, List<DiagnosticAgent> agents) {
        this.eventPublisher = eventPublisher;
        this.agents = agents != null ? agents : new ArrayList<>();

        // 创建专用的 Agent 执行线程池
        this.executorService = Executors.newFixedThreadPool(
                Math.max(4, this.agents.size()),
                r -> {
                    Thread t = new Thread(r, "agent-executor-" + r.hashCode());
                    t.setDaemon(true);
                    return t;
                }
        );

        // 按优先级排序 Agent
        this.agents.sort(Comparator.comparingInt(DiagnosticAgent::getPriority));

        log.info("AgentOrchestrator initialized with {} agents: {}", this.agents.size(),
                this.agents.stream().map(DiagnosticAgent::getAgentType).collect(Collectors.joining(", ")));
    }

    /**
     * 关闭线程池，释放资源
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down AgentOrchestrator executor service");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行诊断
     * 编排多个 Agent 协作完成诊断任务
     *
     * @param context 诊断上下文
     * @return 聚合后的诊断结果
     */
    public DiagnosisResult diagnose(AgentContext context) {
        String requestId = context.getRequestId();
        long startTime = System.currentTimeMillis();

        log.info("[{}] Starting multi-agent diagnosis", requestId);

        // 1. 发布编排开始事件
        eventPublisher.publishEventAsync(AgentEvent.orchestrationStart(requestId));
        eventPublisher.publishEventAsync(AgentEvent.requestReceived(requestId));

        // 2. 计算各 Agent 置信度，筛选可执行的 Agent
        Map<DiagnosticAgent, Double> agentConfidences = calculateConfidences(context);
        List<DiagnosticAgent> executableAgents = filterExecutableAgents(agentConfidences);

        if (executableAgents.isEmpty()) {
            log.warn("[{}] No agents available for this context", requestId);
            return DiagnosisResult.empty(requestId, "没有可用的诊断 Agent");
        }

        log.info("[{}] Selected {} agents for execution: {}", requestId, executableAgents.size(),
                executableAgents.stream().map(DiagnosticAgent::getAgentType).collect(Collectors.joining(", ")));

        // 3. 生成执行计划（考虑依赖关系）
        ExecutionPlan plan = generateExecutionPlan(executableAgents, agentConfidences);

        // 4. 按计划执行 Agent
        Map<String, AgentResult> resultsByAgent = executeAgents(plan, context);

        // 5. 聚合结果
        DiagnosisResult finalResult = aggregateResults(requestId, resultsByAgent, startTime);

        // 6. 发布编排结束事件
        eventPublisher.publishEventAsync(AgentEvent.orchestrationEnd(requestId));

        log.info("[{}] Diagnosis completed: totalAgents={}, successCount={}, totalTime={}ms",
                requestId, executableAgents.size(), finalResult.getSuccessCount(), finalResult.getTotalTimeMs());

        return finalResult;
    }

    /**
     * 异步执行诊断
     *
     * @param context 诊断上下文
     * @return 异步结果
     */
    public CompletableFuture<DiagnosisResult> diagnoseAsync(AgentContext context) {
        return CompletableFuture.supplyAsync(() -> diagnose(context), executorService);
    }

    /**
     * 流式执行诊断
     * 返回实时流式输出，适用于 SSE 场景
     *
     * 执行流程：
     * 1. 计算各 Agent 置信度，筛选可执行 Agent
     * 2. 按优先级和依赖关系排序
     * 3. 流式执行每个 Agent，合并输出流
     * 4. 最后发送聚合结果
     *
     * @param context 诊断上下文
     * @return 流式诊断结果
     */
    public Flux<AgentResult> diagnoseStream(AgentContext context) {
        String requestId = context.getRequestId();
        long startTime = System.currentTimeMillis();

        log.info("[{}] Starting streaming multi-agent diagnosis", requestId);

        // 1. 发布编排开始事件
        eventPublisher.publishEventAsync(AgentEvent.orchestrationStart(requestId));

        // 2. 计算各 Agent 置信度，筛选可执行的 Agent
        Map<DiagnosticAgent, Double> agentConfidences = calculateConfidences(context);
        List<DiagnosticAgent> executableAgents = filterExecutableAgents(agentConfidences);

        if (executableAgents.isEmpty()) {
            log.warn("[{}] No agents available for this context", requestId);
            return Flux.just(AgentResult.builder()
                    .agentType("ORCHESTRATOR")
                    .requestId(requestId)
                    .status(AgentResult.Status.SKIPPED)
                    .confidence(0.0)
                    .conclusion("没有可用的诊断 Agent")
                    .build());
        }

        log.info("[{}] Selected {} agents for streaming execution: {}", requestId, executableAgents.size(),
                executableAgents.stream().map(DiagnosticAgent::getAgentType).collect(Collectors.joining(", ")));

        // 3. 生成执行计划
        ExecutionPlan plan = generateExecutionPlan(executableAgents, agentConfidences);

        // 4. 收集所有 Agent 的流式输出
        List<Flux<AgentResult>> allStreams = new ArrayList<>();
        Map<String, AgentResult> completedResults = new ConcurrentHashMap<>();

        for (List<DiagnosticAgent> batch : plan.getBatches()) {
            for (DiagnosticAgent agent : batch) {
                // 为每个 Agent 创建流式执行
                Flux<AgentResult> agentStream = executeAgentStream(agent, context, completedResults, startTime);
                allStreams.add(agentStream);
            }
        }

        // 5. 合并所有流，并在最后发送聚合结果
        return Flux.concat(allStreams)
                .doOnNext(result -> {
                    // 收集完成的结果
                    if (result.getStatus() != null && !result.isStreaming()) {
                        completedResults.put(result.getAgentType(), result);
                        context.addPreviousResult(result);
                    }
                })
                .concatWith(Flux.defer(() -> {
                    // 发送最终的聚合结果
                    DiagnosisResult finalResult = aggregateResults(requestId, completedResults, startTime);
                    eventPublisher.publishEventAsync(AgentEvent.orchestrationEnd(requestId));

                    log.info("[{}] Streaming diagnosis completed: totalAgents={}, successCount={}, totalTime={}ms",
                            requestId, executableAgents.size(), finalResult.getSuccessCount(), finalResult.getTotalTimeMs());

                    // 将聚合结果转换为 AgentResult 格式
                    return Flux.just(AgentResult.builder()
                            .agentType("ORCHESTRATOR")
                            .requestId(requestId)
                            .status(finalResult.getSuccessCount() > 0 ? AgentResult.Status.SUCCESS : AgentResult.Status.PARTIAL)
                            .confidence(finalResult.getOverallConfidence())
                            .conclusion(finalResult.getPrimaryConclusion())
                            .rootCause(finalResult.getPrimaryRootCause())
                            .affectedCode(finalResult.getCombinedAffectedCode())
                            .fixSuggestions(finalResult.getCombinedFixSuggestions())
                            .executionTimeMs(finalResult.getTotalTimeMs())
                            .build());
                }));
    }

    /**
     * 流式执行单个 Agent
     */
    private Flux<AgentResult> executeAgentStream(DiagnosticAgent agent, AgentContext context,
                                                  Map<String, AgentResult> previousResults, long orchestrationStartTime) {
        String requestId = context.getRequestId();
        String agentType = agent.getAgentType();

        // 发布 Agent 启动事件
        eventPublisher.publishEventAsync(AgentEvent.agentStarted(requestId, agentType, agent.getAgentName()));

        long startTime = System.currentTimeMillis();

        return Flux.defer(() -> agent.executeStreaming(context))
                .doOnNext(result -> log.debug("[{}] Agent {} streaming chunk", requestId, agentType))
                .doOnComplete(() -> {
                    log.info("[{}] Agent {} streaming completed, time={}ms",
                            requestId, agentType, System.currentTimeMillis() - startTime);
                    eventPublisher.publishEventAsync(AgentEvent.agentCompleted(requestId, agentType, 0.8));
                })
                .onErrorResume(e -> {
                    log.error("[{}] Agent {} streaming failed: {}", requestId, agentType, e.getMessage(), e);
                    eventPublisher.publishEventAsync(AgentEvent.agentFailed(requestId, agentType, e.getMessage()));

                    // 返回失败结果
                    return Flux.just(AgentResult.builder()
                            .agentType(agentType)
                            .requestId(requestId)
                            .status(AgentResult.Status.FAILED)
                            .confidence(0.0)
                            .errorMessage("Agent 执行异常: " + e.getMessage())
                            .executionTimeMs(System.currentTimeMillis() - startTime)
                            .build());
                });
    }

    /**
     * 计算各 Agent 的置信度
     */
    private Map<DiagnosticAgent, Double> calculateConfidences(AgentContext context) {
        Map<DiagnosticAgent, Double> confidences = new HashMap<>();

        for (DiagnosticAgent agent : agents) {
            try {
                double confidence = agent.calculateConfidence(context);
                confidences.put(agent, confidence);
                log.debug("Agent {} confidence: {}", agent.getAgentType(), confidence);
            } catch (Exception e) {
                log.warn("Failed to calculate confidence for agent {}: {}", agent.getAgentType(), e.getMessage());
                confidences.put(agent, 0.0);
            }
        }

        return confidences;
    }

    /**
     * 筛选可执行的 Agent
     */
    private List<DiagnosticAgent> filterExecutableAgents(Map<DiagnosticAgent, Double> confidences) {
        return agents.stream()
                .filter(agent -> {
                    Double confidence = confidences.get(agent);
                    return confidence != null && !agent.canSkip(confidence);
                })
                .collect(Collectors.toList());
    }

    /**
     * 生成执行计划
     * 基于依赖关系和优先级确定执行顺序
     */
    private ExecutionPlan generateExecutionPlan(List<DiagnosticAgent> executableAgents, Map<DiagnosticAgent, Double> confidences) {
        ExecutionPlan plan = new ExecutionPlan();

        // 识别无依赖的 Agent（第一批执行）
        Set<String> executedTypes = new HashSet<>();

        while (executedTypes.size() < executableAgents.size()) {
            List<DiagnosticAgent> batch = new ArrayList<>();

            for (DiagnosticAgent agent : executableAgents) {
                if (executedTypes.contains(agent.getAgentType())) {
                    continue;  // 已执行
                }

                // 检查依赖是否都已执行
                List<String> dependencies = agent.getDependencies();
                boolean allDependenciesMet = dependencies.isEmpty() ||
                        dependencies.stream().allMatch(executedTypes::contains);

                if (allDependenciesMet) {
                    batch.add(agent);
                }
            }

            if (batch.isEmpty()) {
                // 存在循环依赖，强制添加剩余 Agent
                for (DiagnosticAgent agent : executableAgents) {
                    if (!executedTypes.contains(agent.getAgentType())) {
                        batch.add(agent);
                        log.warn("Agent {} has unmet dependencies, forcing execution", agent.getAgentType());
                    }
                }
            }

            plan.addBatch(batch);
            batch.forEach(agent -> executedTypes.add(agent.getAgentType()));
        }

        return plan;
    }

    /**
     * 执行 Agent 批次
     */
    private Map<String, AgentResult> executeAgents(ExecutionPlan plan, AgentContext context) {
        Map<String, AgentResult> results = new ConcurrentHashMap<>();
        String requestId = context.getRequestId();

        for (List<DiagnosticAgent> batch : plan.getBatches()) {
            // 批次内的 Agent 可以并发执行
            List<CompletableFuture<AgentResult>> futures = batch.stream()
                    .map(agent -> CompletableFuture.supplyAsync(() -> executeAgent(agent, context, results), executorService))
                    .collect(Collectors.toList());

            // 等待当前批次完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 收集当前批次结果
            for (int i = 0; i < futures.size(); i++) {
                AgentResult result = futures.get(i).join();
                if (result != null) {
                    results.put(batch.get(i).getAgentType(), result);

                    // 更新上下文，供后续 Agent 使用
                    context.addPreviousResult(result);
                }
            }
        }

        return results;
    }

    /**
     * 执行单个 Agent
     */
    private AgentResult executeAgent(DiagnosticAgent agent, AgentContext context, Map<String, AgentResult> previousResults) {
        String requestId = context.getRequestId();
        String agentType = agent.getAgentType();

        // 检查依赖结果
        for (String dependency : agent.getDependencies()) {
            AgentResult depResult = previousResults.get(dependency);
            if (depResult == null || !depResult.isSuccess()) {
                log.warn("[{}] Agent {} dependency {} not satisfied", requestId, agentType, dependency);
            }
        }

        // 发布 Agent 启动事件
        eventPublisher.publishEventAsync(AgentEvent.agentStarted(requestId, agentType, agent.getAgentName()));

        long startTime = System.currentTimeMillis();
        try {
            AgentResult result = agent.execute(context);

            // 发布完成事件
            eventPublisher.publishEventAsync(AgentEvent.agentCompleted(requestId, agentType, result.getConfidence()));

            log.info("[{}] Agent {} completed: status={}, confidence={}, time={}ms",
                    requestId, agentType, result.getStatus(), result.getConfidence(),
                    System.currentTimeMillis() - startTime);

            return result;

        } catch (Exception e) {
            log.error("[{}] Agent {} failed: {}", requestId, agentType, e.getMessage(), e);

            // 发布失败事件
            eventPublisher.publishEventAsync(AgentEvent.agentFailed(requestId, agentType, e.getMessage()));

            return AgentResult.builder()
                    .agentType(agentType)
                    .requestId(requestId)
                    .status(AgentResult.Status.FAILED)
                    .confidence(0.0)
                    .errorMessage("Agent 执行异常: " + e.getMessage())
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 聚合所有 Agent 结果
     */
    private DiagnosisResult aggregateResults(String requestId, Map<String, AgentResult> resultsByAgent, long startTime) {
        long totalTimeMs = System.currentTimeMillis() - startTime;

        // 统计成功/失败数量
        int successCount = 0;
        int failedCount = 0;

        // 收集所有结果
        List<AgentResult> allResults = new ArrayList<>(resultsByAgent.values());

        // 筛选高置信度结果
        List<AgentResult> highConfidenceResults = allResults.stream()
                .filter(r -> r.isSuccess() && r.getConfidence() >= HIGH_CONFIDENCE_THRESHOLD)
                .sorted((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()))  // 按置信度降序
                .collect(Collectors.toList());

        // 准备构建参数
        String primaryConclusion;
        String primaryRootCause;
        double primaryConfidence;
        String primaryAgentType = null;
        List<String> combinedFixSuggestions = new ArrayList<>();
        List<String> combinedAffectedCode = new ArrayList<>();

        // 生成最终结论
        if (!highConfidenceResults.isEmpty()) {
            AgentResult bestResult = highConfidenceResults.get(0);
            primaryConclusion = bestResult.getConclusion();
            primaryRootCause = bestResult.getRootCause();
            primaryConfidence = bestResult.getConfidence();
            primaryAgentType = bestResult.getAgentType();

            // 合并修复建议
            Set<String> allSuggestions = new LinkedHashSet<>();
            for (AgentResult result : highConfidenceResults) {
                allSuggestions.addAll(result.getFixSuggestions());
            }
            combinedFixSuggestions = new ArrayList<>(allSuggestions);

            // 合并受影响代码
            Set<String> allAffectedCode = new LinkedHashSet<>();
            for (AgentResult result : highConfidenceResults) {
                allAffectedCode.addAll(result.getAffectedCode());
            }
            combinedAffectedCode = new ArrayList<>(allAffectedCode);

        } else {
            // 没有高置信度结果，使用最高置信度的结果
            AgentResult bestResult = allResults.stream()
                    .filter(AgentResult::isSuccess)
                    .max(Comparator.comparingDouble(AgentResult::getConfidence))
                    .orElse(null);

            if (bestResult != null) {
                primaryConclusion = bestResult.getConclusion();
                primaryRootCause = bestResult.getRootCause();
                primaryConfidence = bestResult.getConfidence();
                primaryAgentType = bestResult.getAgentType();
            } else {
                primaryConclusion = "无法确定根因";
                primaryRootCause = "所有 Agent 执行失败或置信度过低";
                primaryConfidence = 0.0;
            }
        }

        // 统计计数
        for (AgentResult result : allResults) {
            if (result.isSuccess()) {
                successCount++;
            } else {
                failedCount++;
            }
        }

        // 计算综合置信度
        double overallConfidence = calculateOverallConfidence(allResults);

        return DiagnosisResult.builder()
                .requestId(requestId)
                .totalTimeMs(totalTimeMs)
                .agentResults(allResults)
                .primaryConclusion(primaryConclusion)
                .primaryRootCause(primaryRootCause)
                .primaryConfidence(primaryConfidence)
                .primaryAgentType(primaryAgentType)
                .combinedFixSuggestions(combinedFixSuggestions)
                .combinedAffectedCode(combinedAffectedCode)
                .successCount(successCount)
                .failedCount(failedCount)
                .overallConfidence(overallConfidence)
                .build();
    }

    /**
     * 计算综合置信度
     */
    private double calculateOverallConfidence(List<AgentResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }

        // 成功 Agent 的置信度加权平均
        double totalWeight = 0.0;
        double weightedSum = 0.0;

        for (AgentResult result : results) {
            if (result.isSuccess()) {
                // 高置信度 Agent 权重更大
                double weight = result.getConfidence() >= HIGH_CONFIDENCE_THRESHOLD ? 2.0 : 1.0;
                weightedSum += result.getConfidence() * weight;
                totalWeight += weight;
            }
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    /**
     * 获取已注册的 Agent 类型
     */
    public List<String> getRegisteredAgentTypes() {
        return agents.stream().map(DiagnosticAgent::getAgentType).collect(Collectors.toList());
    }

    /**
     * 获取 Agent 数量
     */
    public int getAgentCount() {
        return agents.size();
    }

    /**
     * 执行计划内部类
     */
    private static class ExecutionPlan {
        private final List<List<DiagnosticAgent>> batches = new ArrayList<>();

        void addBatch(List<DiagnosticAgent> batch) {
            batches.add(batch);
        }

        List<List<DiagnosticAgent>> getBatches() {
            return batches;
        }
    }
}