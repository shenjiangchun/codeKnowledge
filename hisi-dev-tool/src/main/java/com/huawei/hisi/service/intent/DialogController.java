package com.huawei.hisi.service.intent;

import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import reactor.core.publisher.Flux;

/**
 * 自然语言对话 Controller
 * 提供自然语言诊断入口的 REST API
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/dialog")
@RequiredArgsConstructor
public class DialogController {

    private final NaturalLanguageDiagnosisCoordinator coordinator;
    private final DialogStateManager dialogStateManager;
    private final InterventionHandler interventionHandler;

    /**
     * SSE 执行线程池
     */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-dialog-executor");
        t.setDaemon(true);
        return t;
    });

    /**
     * 自然语言诊断请求 DTO
     */
    @lombok.Data
    public static class DialogRequest {
        /**
         * 用户输入的自然语言文本
         */
        @NotBlank(message = "用户输入不能为空")
        private String userInput;

        /**
         * 会话ID（可选，用于多轮对话）
         */
        private String sessionId;

        /**
         * 项目路径（可选）
         */
        private String projectPath;
    }

    /**
     * 处理自然语言诊断请求
     *
     * @param request 诊断请求
     * @return 诊断响应
     */
    @PostMapping("/diagnose")
    public ApiResponse<DiagnosisResponse> diagnose(@Valid @RequestBody DialogRequest request) {
        log.info("Received dialog diagnosis request: input={}, sessionId={}",
                truncate(request.getUserInput(), 50), request.getSessionId());

        try {
            DiagnosisResponse response = coordinator.processDiagnosis(
                    request.getUserInput(),
                    request.getSessionId()
            );

            // 如果是新会话，返回sessionId
            if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
                log.info("Created new session: {}", response.getSessionId());
            }

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("Dialog diagnosis failed: {}", e.getMessage(), e);
            return ApiResponse.error("诊断失败: " + e.getMessage());
        }
    }

    /**
     * 流式诊断 (SSE)
     *
     * @param request 诊断请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/diagnose/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter diagnoseStream(@Valid @RequestBody DialogRequest request) {
        String sessionId = request.getSessionId();
        log.info("Received streaming dialog request: input={}, sessionId={}",
                truncate(request.getUserInput(), 50), sessionId);

        // 创建 SSE Emitter
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        sseExecutor.execute(() -> {
            try {
                coordinator.processDiagnosisStream(request.getUserInput(), sessionId)
                        .doOnNext(result -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("agent-result")
                                        .data(result));
                            } catch (Exception e) {
                                log.error("Failed to send SSE event: {}", e.getMessage());
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("complete")
                                        .data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("Failed to send completion event: {}", e.getMessage());
                            }
                        })
                        .doOnError(e -> {
                            log.error("Streaming diagnosis error: {}", e.getMessage(), e);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(e.getMessage()));
                            } catch (Exception ex) {
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

        emitter.onTimeout(() -> log.warn("SSE connection timeout"));
        emitter.onCompletion(() -> log.info("SSE connection closed"));

        return emitter;
    }

    /**
     * 流式诊断 (Reactive)
     *
     * @param request 诊断请求
     * @return AgentResult 流
     */
    @PostMapping(value = "/diagnose/reactive", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<com.huawei.hisi.agent.model.AgentResult> diagnoseReactive(
            @Valid @RequestBody DialogRequest request) {
        log.info("Received reactive dialog request: input={}", truncate(request.getUserInput(), 50));

        return coordinator.processDiagnosisStream(request.getUserInput(), request.getSessionId());
    }

    /**
     * 获取对话上下文
     *
     * @param sessionId 会话ID
     * @return 对话上下文摘要
     */
    @GetMapping("/context/{sessionId}")
    public ApiResponse<DialogContextSummary> getContext(@PathVariable String sessionId) {
        log.info("Getting dialog context: sessionId={}", sessionId);

        DialogContext context = dialogStateManager.getContext(sessionId);
        if (context == null) {
            return ApiResponse.error("会话不存在或已过期");
        }

        return ApiResponse.success(toSummary(context));
    }

    /**
     * 清除对话上下文
     *
     * @param sessionId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/context/{sessionId}")
    public ApiResponse<String> clearContext(@PathVariable String sessionId) {
        log.info("Clearing dialog context: sessionId={}", sessionId);

        dialogStateManager.removeContext(sessionId);
        return ApiResponse.success("会话已清除");
    }

    /**
     * 获取活跃会话数量
     */
    @GetMapping("/sessions/count")
    public ApiResponse<Integer> getActiveSessionCount() {
        return ApiResponse.success(dialogStateManager.getActiveSessionCount());
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ApiResponse<DialogHealth> health() {
        DialogHealth health = new DialogHealth();
        health.setActiveSessions(dialogStateManager.getActiveSessionCount());
        health.setStatus("UP");
        return ApiResponse.success(health);
    }

    // ============== 用户干预 API ==============

    /**
     * 干预请求 DTO
     */
    @lombok.Data
    public static class InterventionRequestDto {
        @NotBlank(message = "会话ID不能为空")
        private String sessionId;

        @NotBlank(message = "干预类型不能为空")
        private String interventionType;

        private String target;
        private String reason;
        private java.util.Map<String, String> additionalInfo;
    }

    /**
     * 处理用户干预
     *
     * @param request 干预请求
     * @return 干预响应
     */
    @PostMapping("/intervention")
    public ApiResponse<InterventionHandler.InterventionResponse> handleIntervention(
            @Valid @RequestBody InterventionRequestDto request) {
        log.info("Received intervention request: sessionId={}, type={}",
                request.getSessionId(), request.getInterventionType());

        try {
            InterventionHandler.InterventionRequest interventionRequest = InterventionHandler.InterventionRequest.builder()
                    .sessionId(request.getSessionId())
                    .type(InterventionHandler.InterventionType.valueOf(request.getInterventionType().toUpperCase()))
                    .target(request.getTarget())
                    .reason(request.getReason())
                    .additionalInfo(request.getAdditionalInfo())
                    .build();

            InterventionHandler.InterventionResponse response = interventionHandler.handleIntervention(interventionRequest);

            return ApiResponse.success(response);

        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的干预类型: " + request.getInterventionType());
        } catch (Exception e) {
            log.error("Intervention handling failed: {}", e.getMessage(), e);
            return ApiResponse.error("干预处理失败: " + e.getMessage());
        }
    }

    /**
     * 获取可用的干预类型
     *
     * @param sessionId 会话ID
     * @return 可用干预类型列表
     */
    @GetMapping("/intervention/available/{sessionId}")
    public ApiResponse<java.util.List<String>> getAvailableInterventions(@PathVariable String sessionId) {
        java.util.List<InterventionHandler.InterventionType> types =
                interventionHandler.getAvailableInterventions(sessionId);

        java.util.List<String> typeNames = types.stream()
                .map(Enum::name)
                .toList();

        return ApiResponse.success(typeNames);
    }

    /**
     * 检查是否可以接受干预
     *
     * @param sessionId 会话ID
     * @return 是否可以接受干预
     */
    @GetMapping("/intervention/can-accept/{sessionId}")
    public ApiResponse<Boolean> canAcceptIntervention(@PathVariable String sessionId) {
        return ApiResponse.success(interventionHandler.canAcceptIntervention(sessionId));
    }

    // ============== 辅助方法 ==============

    private String truncate(String value, int maxLength) {
        if (value == null) return "null";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private DialogContextSummary toSummary(DialogContext context) {
        return DialogContextSummary.builder()
                .sessionId(context.getSessionId())
                .messageCount(context.getMessages() != null ? context.getMessages().size() : 0)
                .taskState(context.getTaskState() != null ? context.getTaskState().name() : "IDLE")
                .lastIntent(context.getLastIntent() != null ? context.getLastIntent().name() : null)
                .lastConclusion(context.getLastConclusion())
                .build();
    }

    // ============== 内部类 ==============

    @lombok.Data
    @lombok.Builder
    public static class DialogContextSummary {
        private String sessionId;
        private int messageCount;
        private String taskState;
        private String lastIntent;
        private String lastConclusion;
    }

    @lombok.Data
    public static class DialogHealth {
        private int activeSessions;
        private String status;
    }
}