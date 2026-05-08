package com.huawei.hisi.service.intent;

import com.huawei.hisi.agent.model.AgentContext;
import com.huawei.hisi.agent.model.AgentResult;
import com.huawei.hisi.agent.orchestrator.AgentOrchestrator;
import com.huawei.hisi.agent.orchestrator.DiagnosisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自然语言诊断协调器
 * 将意图解析结果转换为Agent诊断任务
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NaturalLanguageDiagnosisCoordinator {

    private final AgentOrchestrator agentOrchestrator;
    private final DialogStateManager dialogStateManager;

    /**
     * 处理自然语言诊断请求
     *
     * @param userInput 用户输入
     * @param sessionId 会话ID（可选）
     * @return 诊断响应
     */
    public DiagnosisResponse processDiagnosis(String userInput, String sessionId) {
        long startTime = System.currentTimeMillis();

        // 1. 获取或创建对话上下文
        DialogContext context = dialogStateManager.getOrCreateContext(sessionId);

        // 2. 意图识别 (IntentParserService removed - using default intent)
        IntentResult intentResult = IntentResult.defaultResult(userInput);

        // 3. 更新对话上下文
        context.addUserMessage(userInput, intentResult.getIntent());
        context.updateEntities(intentResult.getEntities());
        context.setLastIntent(intentResult.getIntent());

        // 4. 如果需要澄清，返回澄清请求
        if (intentResult.isNeedClarification()) {
            return DiagnosisResponse.clarificationNeeded(
                    intentResult.getClarificationQuestions(),
                    context.getSessionId()
            );
        }

        // 5. 根据意图类型执行相应操作
        DiagnosisResponse response = executeByIntent(intentResult, context);

        // 6. 更新对话状态
        context.addAssistantMessage(response.getMessage());
        dialogStateManager.saveContext(context);

        response.setTotalTimeMs(System.currentTimeMillis() - startTime);
        log.info("Natural language diagnosis completed: intent={}, sessionId={}, time={}ms",
                intentResult.getIntent(), context.getSessionId(), response.getTotalTimeMs());

        return response;
    }

    /**
     * 流式处理诊断请求
     *
     * @param userInput 用户输入
     * @param sessionId 会话ID
     * @return 流式诊断结果
     */
    public Flux<AgentResult> processDiagnosisStream(String userInput, String sessionId) {
        DialogContext context = dialogStateManager.getOrCreateContext(sessionId);
        IntentResult intentResult = IntentResult.defaultResult(userInput);

        context.addUserMessage(userInput, intentResult.getIntent());
        context.updateEntities(intentResult.getEntities());
        context.setLastIntent(intentResult.getIntent());

        if (intentResult.isNeedClarification()) {
            // 返回澄清请求的流式包装
            return Flux.just(AgentResult.builder()
                    .agentType("INTENT_PARSER")
                    .sessionId(sessionId)
                    .status(AgentResult.Status.PARTIAL)
                    .conclusion("需要澄清: " + String.join("; ", intentResult.getClarificationQuestions()))
                    .build());
        }

        // 构建Agent上下文并执行流式诊断
        AgentContext agentContext = buildAgentContext(intentResult, context);
        return agentOrchestrator.diagnoseStream(agentContext)
                .doOnNext(result -> {
                    // 收集中间结果更新上下文
                    if (result.isSuccess() && result.getConclusion() != null) {
                        context.setLastConclusion(result.getConclusion());
                    }
                })
                .doOnComplete(() -> {
                    dialogStateManager.saveContext(context);
                });
    }

    /**
     * 根据意图执行相应操作
     */
    private DiagnosisResponse executeByIntent(IntentResult intentResult, DialogContext context) {
        switch (intentResult.getIntent()) {
            case DIAGNOSE_LOG:
                return executeDiagnosis(intentResult, context);
            case QUERY_CODE:
                return executeCodeQuery(intentResult, context);
            case EXPLAIN_ERROR:
                return executeErrorExplanation(intentResult, context);
            case INTERVENE:
                return handleIntervention(intentResult, context);
            case FOLLOW_UP:
                return handleFollowUp(intentResult, context);
            default:
                return DiagnosisResponse.unknownIntent(context.getSessionId());
        }
    }

    /**
     * 执行诊断
     */
    private DiagnosisResponse executeDiagnosis(IntentResult intentResult, DialogContext context) {
        String requestId = UUID.randomUUID().toString();

        // 构建Agent上下文
        AgentContext agentContext = buildAgentContext(intentResult, context);

        // 执行诊断
        DiagnosisResult diagnosisResult = agentOrchestrator.diagnose(agentContext);

        // 更新上下文
        context.setLastConclusion(diagnosisResult.getPrimaryConclusion());
        context.setTaskState(DialogContext.TaskState.COMPLETED);

        return DiagnosisResponse.fromDiagnosisResult(diagnosisResult, context.getSessionId());
    }

    /**
     * 执行代码查询（暂返回提示信息）
     */
    private DiagnosisResponse executeCodeQuery(IntentResult intentResult, DialogContext context) {
        // TODO: 实现代码查询逻辑
        String className = intentResult.getClassName();
        String methodName = intentResult.getMethodName();

        String message = String.format("正在查询代码: 类=%s, 方法=%s",
                className != null ? className : "未指定",
                methodName != null ? methodName : "未指定");

        return DiagnosisResponse.inProgress(message, context.getSessionId());
    }

    /**
     * 执行错误解释
     */
    private DiagnosisResponse executeErrorExplanation(IntentResult intentResult, DialogContext context) {
        String errorType = intentResult.getErrorType();
        if (errorType != null) {
            // 构建简化的诊断请求
            AgentContext agentContext = AgentContext.builder()
                    .requestId(UUID.randomUUID().toString())
                    .errorMessage("解释错误: " + errorType)
                    .sessionId(context.getSessionId())
                    .build();

            DiagnosisResult result = agentOrchestrator.diagnose(agentContext);
            return DiagnosisResponse.fromDiagnosisResult(result, context.getSessionId());
        }

        return DiagnosisResponse.needMoreInfo("请提供具体的错误类型", context.getSessionId());
    }

    /**
     * 处理用户干预
     */
    private DiagnosisResponse handleIntervention(IntentResult intentResult, DialogContext context) {
        // 更新关注领域
        String focusArea = intentResult.getFocusArea();
        if (focusArea != null) {
            context.updateEntities(intentResult.getEntities());
        }

        String message = "已更新分析方向，请继续描述您的需求。";
        return DiagnosisResponse.inProgress(message, context.getSessionId());
    }

    /**
     * 处理追问
     */
    private DiagnosisResponse handleFollowUp(IntentResult intentResult, DialogContext context) {
        // 基于上次结论进行追问处理
        String lastConclusion = context.getLastConclusion();
        if (lastConclusion != null) {
            // TODO: 实现追问处理逻辑
            return DiagnosisResponse.inProgress("正在深入分析上次结论...", context.getSessionId());
        }

        return DiagnosisResponse.needMoreInfo("没有之前的分析结论可供追问", context.getSessionId());
    }

    /**
     * 构建Agent上下文
     */
    private AgentContext buildAgentContext(IntentResult intentResult, DialogContext dialogContext) {
        // Convert Map<String, String> to Map<String, Object>
        Map<String, Object> attributes = new HashMap<>();
        if (intentResult.getEntities() != null) {
            intentResult.getEntities().forEach((k, v) -> attributes.put(k, v));
        }

        return AgentContext.builder()
                .requestId(UUID.randomUUID().toString())
                .sessionId(dialogContext.getSessionId())
                .projectPath(dialogContext.getProjectPath())
                .errorMessage(intentResult.getOriginalInput())
                .stackTrace(dialogContext.getCurrentStackTrace())
                .attributes(attributes)
                .build();
    }
}