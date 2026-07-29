package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.service.AIAnalysisPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 分析提示词生成控制器
 * 从 Neo4j 知识图谱拉取完整数据，组装为结构化富提示词，
 * 供前端创建 workspace session 后发送到 Claude CLI 终端
 *
 * 混合模式架构：
 * - 复杂场景（调用链分析、影响分析、日志分析）：后端从 Neo4j 组装数据
 * - 简单场景（代码审查）：前端直接组装
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-analysis")
@RequiredArgsConstructor
public class AIAnalysisController {

    private final AIAnalysisPromptService aiAnalysisPromptService;

    /**
     * 生成调用链分析提示词
     * POST /api/ai-analysis/call-chain/prompt
     *
     * 从 Neo4j 拉取入口点的完整调用链（nodes + edges + signatures + method body + SQL）
     * 前端拿到 prompt 后创建 workspace session 发送到 Claude CLI 终端
     *
     * @deprecated 请使用 {@link AgentChatController} 统一端点 (POST /api/chat/call-chain-analysis)
     * @param request { entryKey: "GET /api/orders", projectPath: "/path/to/project" }
     * @return 组装好的富提示词
     */
    @Deprecated(since = "5.0", forRemoval = true)
    @PostMapping("/call-chain/prompt")
    public ApiResponse<Map<String, Object>> buildCallChainPrompt(@RequestBody Map<String, String> request) {
        String entryKey = request.get("entryKey");
        String projectPath = request.get("projectPath");

        if (entryKey == null || entryKey.isBlank()) {
            return ApiResponse.error(400, "entryKey is required");
        }
        if (projectPath == null || projectPath.isBlank()) {
            return ApiResponse.error(400, "projectPath is required");
        }

        try {
            String prompt = aiAnalysisPromptService.buildCallChainAnalysisPrompt(entryKey, projectPath);
            return ApiResponse.success(Map.of(
                    "prompt", prompt,
                    "scene", "call-chain-analysis",
                    "entryKey", entryKey,
                    "promptLength", prompt.length()
            ));
        } catch (Exception e) {
            log.error("[AI Analysis] 调用链分析 prompt 生成失败: {}", e.getMessage(), e);
            return ApiResponse.error("调用链分析 prompt 生成失败: " + e.getMessage());
        }
    }

    /**
     * 生成影响分析提示词
     * POST /api/ai-analysis/impact/prompt
     *
     * 从 Neo4j 拉取目标方法的上下游调用关系、受影响入口点等数据
     *
     * @param request { className: "OrderService", methodName: "createOrder", projectPath: "/path" }
     * @return 组装好的富提示词
     */
    @PostMapping("/impact/prompt")
    public ApiResponse<Map<String, Object>> buildImpactPrompt(@RequestBody Map<String, String> request) {
        String className = request.get("className");
        String methodName = request.get("methodName");
        String projectPath = request.get("projectPath");

        if (className == null || className.isBlank()) {
            return ApiResponse.error(400, "className is required");
        }
        if (methodName == null || methodName.isBlank()) {
            return ApiResponse.error(400, "methodName is required");
        }
        if (projectPath == null || projectPath.isBlank()) {
            return ApiResponse.error(400, "projectPath is required");
        }

        try {
            String prompt = aiAnalysisPromptService.buildImpactAnalysisPrompt(className, methodName, projectPath);
            return ApiResponse.success(Map.of(
                    "prompt", prompt,
                    "scene", "impact-analysis",
                    "className", className,
                    "methodName", methodName,
                    "promptLength", prompt.length()
            ));
        } catch (Exception e) {
            log.error("[AI Analysis] 影响分析 prompt 生成失败: {}", e.getMessage(), e);
            return ApiResponse.error("影响分析 prompt 生成失败: " + e.getMessage());
        }
    }

    /**
     * 生成日志/异常分析提示词
     * POST /api/ai-analysis/log/prompt
     *
     * 从堆栈信息中提取类名，关联知识图谱中的代码上下文
     *
     * @param request { errorMessage, errorType, stackTrace, projectPath }
     * @return 组装好的富提示词
     */
    @PostMapping("/log/prompt")
    public ApiResponse<Map<String, Object>> buildLogPrompt(@RequestBody Map<String, String> request) {
        String errorMessage = request.get("errorMessage");
        String errorType = request.get("errorType");
        String stackTrace = request.get("stackTrace");
        String projectPath = request.get("projectPath");

        if ((errorMessage == null || errorMessage.isBlank()) && (stackTrace == null || stackTrace.isBlank())) {
            return ApiResponse.error(400, "errorMessage or stackTrace is required");
        }

        try {
            String prompt = aiAnalysisPromptService.buildLogAnalysisPrompt(
                    errorMessage, errorType, stackTrace, projectPath);
            return ApiResponse.success(Map.of(
                    "prompt", prompt,
                    "scene", "log-analysis",
                    "promptLength", prompt.length()
            ));
        } catch (Exception e) {
            log.error("[AI Analysis] 日志分析 prompt 生成失败: {}", e.getMessage(), e);
            return ApiResponse.error("日志分析 prompt 生成失败: " + e.getMessage());
        }
    }

    /**
     * 生成方法级分析提示词
     * POST /api/ai-analysis/method/prompt
     *
     * 简单场景：从 Neo4j 获取单个方法的代码和上下游信息
     *
     * @param request { nodeId: "xxx", projectPath: "/path" }
     * @return 组装好的提示词
     */
    @PostMapping("/method/prompt")
    public ApiResponse<Map<String, Object>> buildMethodPrompt(@RequestBody Map<String, String> request) {
        String nodeId = request.get("nodeId");
        String projectPath = request.get("projectPath");

        if (nodeId == null || nodeId.isBlank()) {
            return ApiResponse.error(400, "nodeId is required");
        }

        try {
            String prompt = aiAnalysisPromptService.buildMethodAnalysisPrompt(nodeId, projectPath);
            return ApiResponse.success(Map.of(
                    "prompt", prompt,
                    "scene", "method-analysis",
                    "nodeId", nodeId,
                    "promptLength", prompt.length()
            ));
        } catch (Exception e) {
            log.error("[AI Analysis] 方法分析 prompt 生成失败: {}", e.getMessage(), e);
            return ApiResponse.error("方法分析 prompt 生成失败: " + e.getMessage());
        }
    }
}
