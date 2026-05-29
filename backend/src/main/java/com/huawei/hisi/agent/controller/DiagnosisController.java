package com.huawei.hisi.agent.controller;

import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentResult;
import com.huawei.hisi.agent.model.DiagnosisRequest;
import com.huawei.hisi.agent.model.DiagnosisResponse;
import com.huawei.hisi.agent.orchestrator.AgentOrchestrator;
import com.huawei.hisi.agent.orchestrator.DiagnosisResult;
import com.huawei.hisi.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent 诊断 API Controller
 * 提供多 Agent 协作诊断的 REST API
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/diagnosis")
@Validated
public class DiagnosisController {

    private final AgentOrchestrator agentOrchestrator;

    /**
     * 共享线程池用于 SSE 异步执行
     * 避免每次请求创建新线程池导致资源泄漏
     */
    private final ExecutorService sseExecutor;

    public DiagnosisController(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
        // 创建共享的守护线程池
        this.sseExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sse-diagnosis-executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 应用关闭时优雅关闭线程池
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SSE executor service...");
        sseExecutor.shutdown();
        try {
            if (!sseExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                sseExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行诊断
     * 编排多个 Agent 协作分析问题根因
     *
     * @param request 诊断请求
     * @return 诊断结果
     */
    @PostMapping("/analyze")
    public ApiResponse<DiagnosisResponse> diagnose(@Valid @RequestBody DiagnosisRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("Received diagnosis request: requestId={}, errorMessage={}", requestId, truncate(request.getErrorMessage(), 100));

        try {
            // 构建诊断上下文
            AgentContext context = AgentContext.builder()
                    .requestId(requestId)
                    .projectPath(request.getProjectPath())
                    .errorMessage(request.getErrorMessage())
                    .stackTrace(request.getStackTrace())
                    .logContent(request.getLogContent())
                    .traceId(request.getTraceId())
                    .entryPoint(request.getEntryPoint())
                    .build();

            // 执行诊断
            DiagnosisResult result = agentOrchestrator.diagnose(context);

            // 转换为响应格式
            DiagnosisResponse response = convertToResponse(result);

            log.info("Diagnosis completed: requestId={}, confidence={}, time={}ms", requestId, result.getOverallConfidence(), result.getTotalTimeMs());

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("Diagnosis failed: requestId={}, error={}", requestId, e.getMessage(), e);
            return ApiResponse.error("诊断失败: " + e.getMessage());
        }
    }

    /**
     * 异步诊断
     * 返回请求 ID，结果通过 WebSocket 推送
     *
     * @param request 诊断请求
     * @return 请求 ID
     */
    @PostMapping("/analyze/async")
    public ApiResponse<String> diagnoseAsync(@Valid @RequestBody DiagnosisRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("Received async diagnosis request: requestId={}", requestId);

        // 构建诊断上下文
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .projectPath(request.getProjectPath())
                .errorMessage(request.getErrorMessage())
                .stackTrace(request.getStackTrace())
                .logContent(request.getLogContent())
                .traceId(request.getTraceId())
                .entryPoint(request.getEntryPoint())
                .build();

        // 异步执行诊断
        agentOrchestrator.diagnoseAsync(context)
                .thenAccept(result -> log.info("Async diagnosis completed: requestId={}", requestId))
                .exceptionally(e -> {
                    log.error("Async diagnosis failed: requestId={}, error={}", requestId, e.getMessage());
                    return null;
                });

        return ApiResponse.success("诊断请求已提交: requestId=" + requestId + "，请通过 WebSocket 订阅结果");
    }

    /**
     * 流式诊断 (SSE)
     * 返回 Server-Sent Events 流，实时推送诊断结果
     *
     * @param request 诊断请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter diagnoseStream(@Valid @RequestBody DiagnosisRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("Received streaming diagnosis request: requestId={}, errorMessage={}", requestId, truncate(request.getErrorMessage(), 100));

        // 创建 SseEmitter，设置较长超时
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 构建诊断上下文
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .projectPath(request.getProjectPath())
                .errorMessage(request.getErrorMessage())
                .stackTrace(request.getStackTrace())
                .logContent(request.getLogContent())
                .traceId(request.getTraceId())
                .entryPoint(request.getEntryPoint())
                .sessionId(request.getSessionId())  // 支持会话ID
                .build();

        // 异步执行流式诊断（使用共享线程池）
        sseExecutor.execute(() -> {
            try {
                agentOrchestrator.diagnoseStream(context)
                        .doOnNext(result -> {
                            try {
                                // 发送 SSE 事件
                                emitter.send(SseEmitter.event()
                                        .name("agent-result")
                                        .data(result));
                            } catch (IOException e) {
                                log.error("Failed to send SSE event: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("complete")
                                        .data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                log.error("Failed to send completion event: {}", e.getMessage());
                            }
                        })
                        .doOnError(e -> {
                            log.error("Streaming diagnosis error: {}", e.getMessage(), e);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(e.getMessage()));
                            } catch (IOException ex) {
                                log.error("Failed to send error event: {}", ex.getMessage());
                            }
                            emitter.completeWithError(e);
                        })
                        .subscribe();

            } catch (Exception e) {
                log.error("Failed to start streaming diagnosis: {}", e.getMessage(), e);
                emitter.completeWithError(e);
            }
        });

        // 设置超时和完成回调（无需手动关闭共享线程池）
        emitter.onTimeout(() -> {
            log.warn("SSE connection timeout: requestId={}", requestId);
        });
        emitter.onCompletion(() -> {
            log.info("SSE connection closed: requestId={}", requestId);
        });
        emitter.onError(e -> {
            log.error("SSE connection error: requestId={}, error={}", requestId, e.getMessage());
        });

        return emitter;
    }

    /**
     * 流式诊断 (Reactive)
     * 返回 Reactive 流，适用于 WebFlux 环境
     *
     * @param request 诊断请求
     * @return AgentResult 流
     */
    @PostMapping(value = "/analyze/reactive", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public reactor.core.publisher.Flux<AgentResult> diagnoseReactive(@Valid @RequestBody DiagnosisRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("Received reactive diagnosis request: requestId={}", requestId);

        // 构建诊断上下文
        AgentContext context = AgentContext.builder()
                .requestId(requestId)
                .projectPath(request.getProjectPath())
                .errorMessage(request.getErrorMessage())
                .stackTrace(request.getStackTrace())
                .logContent(request.getLogContent())
                .traceId(request.getTraceId())
                .entryPoint(request.getEntryPoint())
                .sessionId(request.getSessionId())
                .build();

        // 返回流式结果
        return agentOrchestrator.diagnoseStream(context)
                .doOnNext(result -> log.debug("Sending reactive result: agentType={}", result.getAgentType()))
                .doOnComplete(() -> log.info("Reactive diagnosis completed: requestId={}", requestId))
                .doOnError(e -> log.error("Reactive diagnosis error: requestId={}, error={}", requestId, e.getMessage()));
    }

    /**
     * 获取已注册的 Agent 类型
     *
     * @return Agent 类型列表
     */
    @GetMapping("/agents")
    public ApiResponse<List<String>> getRegisteredAgents() {
        List<String> agentTypes = agentOrchestrator.getRegisteredAgentTypes();
        log.info("Registered agents: {}", agentTypes);
        return ApiResponse.success(agentTypes);
    }

    /**
     * 健康检查
     *
     * @return Agent 系统状态
     */
    @GetMapping("/health")
    public ApiResponse<AgentHealth> health() {
        AgentHealth health = new AgentHealth();
        health.setAgentCount(agentOrchestrator.getAgentCount());
        health.setAgentTypes(agentOrchestrator.getRegisteredAgentTypes());
        health.setStatus("UP");
        return ApiResponse.success(health);
    }

    /**
     * 转换诊断结果为响应格式
     */
    private DiagnosisResponse convertToResponse(DiagnosisResult result) {
        List<DiagnosisResponse.AgentSummary> agentSummaries = result.getAgentResults().stream()
                .map(ar -> DiagnosisResponse.AgentSummary.builder()
                        .type(ar.getAgentType())
                        .status(ar.getStatus().name())
                        .confidence(ar.getConfidence())
                        .executionTimeMs(ar.getExecutionTimeMs())
                        .conclusion(ar.getConclusion())
                        .build())
                .collect(Collectors.toList());

        return DiagnosisResponse.builder()
                .requestId(result.getRequestId())
                .success(result.hasValidConclusion())
                .conclusion(result.getPrimaryConclusion())
                .rootCause(result.getPrimaryRootCause())
                .confidence(result.getOverallConfidence())
                .affectedCode(result.getCombinedAffectedCode())
                .fixSuggestions(result.getCombinedFixSuggestions())
                .agents(agentSummaries)
                .executionTimeMs(result.getTotalTimeMs())
                .build();
    }

    /**
     * 截断字符串
     */
    private String truncate(String value, int maxLength) {
        if (value == null) return "null";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    /**
     * Agent 健康状态
     */
    @lombok.Data
    public static class AgentHealth {
        private int agentCount;
        private List<String> agentTypes;
        private String status;
    }
}