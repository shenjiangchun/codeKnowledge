package com.huawei.hisi.service.intent;

import com.huawei.hisi.agent.event.AgentEventPublisher;
import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentEvent;
import com.huawei.hisi.agent.model.AgentResult;
import com.huawei.hisi.agent.orchestrator.AgentOrchestrator;
import com.huawei.hisi.agent.orchestrator.DiagnosisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 用户干预处理器
 * 处理用户在诊断过程中的主动干预
 *
 * 功能：
 * 1. 接收用户干预指令
 * 2. 管理干预状态
 * 3. 通知Agent任务管理器
 * 4. 干预后状态恢复
 * 5. WebSocket实时推送
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterventionHandler {

    private final DialogStateManager dialogStateManager;
    private final AgentEventPublisher eventPublisher;
    private final AgentOrchestrator agentOrchestrator;

    /**
     * 干预类型
     */
    public enum InterventionType {
        FOCUS_CHANGE,       // 改变分析焦点
        IGNORE_RESULT,      // 忽略某些结果
        ADD_CONTEXT,        // 添加上下文信息
        STOP_ANALYSIS,      // 停止分析
        RESTART_ANALYSIS,   // 重新分析
        ADJUST_SCOPE        // 调整分析范围
    }

    /**
     * 干预请求
     */
    @lombok.Data
    @lombok.Builder
    public static class InterventionRequest {
        private String sessionId;
        private InterventionType type;
        private String target;          // 干预目标（类名、方法名等）
        private String reason;          // 干预原因
        private Map<String, String> additionalInfo;
    }

    /**
     * 干预响应
     */
    @lombok.Data
    @lombok.Builder
    public static class InterventionResponse {
        private boolean success;
        private String message;
        private String newFocus;
        private long processingTimeMs;
    }

    /**
     * 处理用户干预
     *
     * @param request 干预请求
     * @return 干预响应
     */
    public InterventionResponse handleIntervention(InterventionRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Handling intervention: sessionId={}, type={}", request.getSessionId(), request.getType());

        // 获取对话上下文
        DialogContext context = dialogStateManager.getContext(request.getSessionId());
        if (context == null) {
            return InterventionResponse.builder()
                    .success(false)
                    .message("会话不存在或已过期")
                    .build();
        }

        // 发布干预事件
        publishInterventionEvent(request);

        // 根据干预类型处理
        InterventionResponse response = switch (request.getType()) {
            case FOCUS_CHANGE -> handleFocusChange(request, context);
            case IGNORE_RESULT -> handleIgnoreResult(request, context);
            case ADD_CONTEXT -> handleAddContext(request, context);
            case STOP_ANALYSIS -> handleStopAnalysis(request, context);
            case RESTART_ANALYSIS -> handleRestartAnalysis(request, context);
            case ADJUST_SCOPE -> handleAdjustScope(request, context);
        };

        response.setProcessingTimeMs(System.currentTimeMillis() - startTime);

        // 保存更新后的上下文
        dialogStateManager.saveContext(context);

        log.info("Intervention handled: type={}, success={}, time={}ms",
                request.getType(), response.isSuccess(), response.getProcessingTimeMs());

        return response;
    }

    /**
     * 异步处理干预
     */
    public CompletableFuture<InterventionResponse> handleInterventionAsync(InterventionRequest request) {
        return CompletableFuture.supplyAsync(() -> handleIntervention(request));
    }

    // ============== 具体干预处理方法 ==============

    /**
     * 处理焦点变更
     */
    private InterventionResponse handleFocusChange(InterventionRequest request, DialogContext context) {
        String newFocus = request.getTarget();

        // 更新上下文焦点
        context.updateEntities(Map.of("focusArea", newFocus));
        context.setTaskState(DialogContext.TaskState.ANALYZING);

        // 发布焦点变更事件
        eventPublisher.publishEventAsync(AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(context.getSessionId())
                .eventType(AgentEvent.EventType.AGENT_PROGRESS)
                .message("用户调整分析焦点: " + newFocus)
                .phase("干预处理")
                .build());

        return InterventionResponse.builder()
                .success(true)
                .message("已调整分析焦点到: " + newFocus)
                .newFocus(newFocus)
                .build();
    }

    /**
     * 处理忽略结果
     */
    private InterventionResponse handleIgnoreResult(InterventionRequest request, DialogContext context) {
        String ignoreTarget = request.getTarget();

        // 记录忽略信息
        @SuppressWarnings("unchecked")
        List<String> ignoredList = (List<String>) context.getEntity("ignoredTargets");
        if (ignoredList == null) {
            ignoredList = new ArrayList<>();
        }
        ignoredList.add(ignoreTarget);
        context.updateEntities(Map.of("ignoredTargets", ignoredList.toString()));

        return InterventionResponse.builder()
                .success(true)
                .message("已忽略: " + ignoreTarget)
                .build();
    }

    /**
     * 处理添加上下文
     */
    private InterventionResponse handleAddContext(InterventionRequest request, DialogContext context) {
        Map<String, String> additionalInfo = request.getAdditionalInfo();
        if (additionalInfo != null && !additionalInfo.isEmpty()) {
            context.updateEntities(additionalInfo);
        }

        return InterventionResponse.builder()
                .success(true)
                .message("已添加上下文信息")
                .build();
    }

    /**
     * 处理停止分析
     */
    private InterventionResponse handleStopAnalysis(InterventionRequest request, DialogContext context) {
        context.setTaskState(DialogContext.TaskState.IDLE);
        context.setLastConclusion("用户中断分析");

        // 发布停止事件
        eventPublisher.publishEventAsync(AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(context.getSessionId())
                .eventType(AgentEvent.EventType.ORCHESTRATION_END)
                .message("用户停止分析")
                .build());

        return InterventionResponse.builder()
                .success(true)
                .message("分析已停止")
                .build();
    }

    /**
     * 处理重新分析
     */
    private InterventionResponse handleRestartAnalysis(InterventionRequest request, DialogContext context) {
        // 重置上下文
        context.setTaskState(DialogContext.TaskState.IDLE);
        context.setLastConclusion(null);
        context.setLastIntent(null);
        context.getAnalyzedFiles().clear();

        // 保留已识别的实体
        String errorMessage = context.getCurrentErrorMessage();
        String stackTrace = context.getCurrentStackTrace();

        return InterventionResponse.builder()
                .success(true)
                .message("已重置分析状态，请重新描述问题")
                .build();
    }

    /**
     * 处理调整分析范围
     */
    private InterventionResponse handleAdjustScope(InterventionRequest request, DialogContext context) {
        String scope = request.getTarget();

        // 更新分析范围
        context.updateEntities(Map.of("analysisScope", scope));

        return InterventionResponse.builder()
                .success(true)
                .message("已调整分析范围: " + scope)
                .build();
    }

    /**
     * 发布干预事件
     */
    private void publishInterventionEvent(InterventionRequest request) {
        eventPublisher.publishEventAsync(AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .requestId(request.getSessionId())
                .eventType(AgentEvent.EventType.AGENT_PROGRESS)
                .agentType("INTERVENTION")
                .message("用户干预: " + request.getType() + " - " + request.getTarget())
                .phase("用户干预")
                .build());
    }

    /**
     * 检查是否可以接受干预
     */
    public boolean canAcceptIntervention(String sessionId) {
        DialogContext context = dialogStateManager.getContext(sessionId);
        if (context == null) {
            return false;
        }

        // 只有在分析中或等待澄清状态才能接受干预
        return context.getTaskState() == DialogContext.TaskState.ANALYZING
                || context.getTaskState() == DialogContext.TaskState.WAITING_CLARIFICATION;
    }

    /**
     * 获取当前可用的干预类型
     */
    public List<InterventionType> getAvailableInterventions(String sessionId) {
        DialogContext context = dialogStateManager.getContext(sessionId);
        if (context == null) {
            return Collections.emptyList();
        }

        List<InterventionType> available = new ArrayList<>();
        available.add(InterventionType.FOCUS_CHANGE);
        available.add(InterventionType.ADD_CONTEXT);

        if (context.getTaskState() == DialogContext.TaskState.ANALYZING) {
            available.add(InterventionType.STOP_ANALYSIS);
            available.add(InterventionType.ADJUST_SCOPE);
        }

        if (context.getLastConclusion() != null) {
            available.add(InterventionType.IGNORE_RESULT);
            available.add(InterventionType.RESTART_ANALYSIS);
        }

        return available;
    }
}