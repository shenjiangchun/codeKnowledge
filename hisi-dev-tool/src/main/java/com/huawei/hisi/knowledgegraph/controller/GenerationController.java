package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.knowledgegraph.util.ProjectPathResolver;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 生成中心控制器 — 测试建议 & 重构建议
 * 使用 extractionChatClient（无记忆 advisor 的 anthropic 中转 deepseek）via Spring AI 结构化输出，
 * 注入图谱数据（爆炸半径/热点/DSM 违规）作为 prompt context。
 * 结构化输出依赖 .entity() 强制（anthropic 走 tool use），避免文本解析脆弱性。
 */
@RestController
@RequestMapping("/api/knowledge-graph")
@Slf4j
public class GenerationController {

    private final ChatClient extractionChatClient;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final KnowledgeGraphController kgController;

    public GenerationController(
            @Qualifier("extractionChatClient") ChatClient extractionChatClient,
            Neo4jMethodNodeRepository methodNodeRepository,
            KnowledgeGraphController kgController) {
        this.extractionChatClient = extractionChatClient;
        this.methodNodeRepository = methodNodeRepository;
        this.kgController = kgController;
    }

    /** 测试类型枚举（结构化输出约束：schema 生成 enum，模型只能返回这些值） */
    public enum TestType { UNIT, INTEGRATION, EXCEPTION, BOUNDARY }
    /** 优先级枚举（结构化输出约束） */
    public enum Priority { HIGH, MEDIUM, LOW }

    /** 测试建议条目（结构化输出目标） */
    @JsonClassDescription("测试建议：scenario 场景描述一句话，type 测试类型，priority 优先级")
    public record TestSuggestion(String scenario, TestType type, Priority priority) {}
    /** 重构建议条目（结构化输出目标） */
    @JsonClassDescription("重构建议：issue 问题描述一句话，direction 重构方向，impact 影响范围估计，priority 优先级")
    public record RefactorSuggestion(String issue, String direction, String impact, Priority priority) {}

    @PostMapping("/test-suggestions")
    public ApiResponse<Map<String, Object>> generateTestSuggestions(
            @RequestParam String nodeId,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) return ApiResponse.error(400, "projectPath or projectPaths required");

        var node = methodNodeRepository.findByNodeId(nodeId);
        if (node.isEmpty()) return ApiResponse.error(404, "Method not found: " + nodeId);
        var m = node.get();

        // 获取爆炸半径数据作为 context
        var blast = kgController.getBlastRadius(nodeId, 5, null, paths);
        String blastContext = "爆炸半径摘要: " + (blast.isSuccess() ? String.valueOf(blast.getData()) : "不可用");

        String prompt =
            "你是一个架构分析专家。基于以下方法信息和爆炸半径数据，生成 3-5 条测试建议。\n" +
            "每条建议应包含：scenario（场景描述一句话）、type（测试类型 UNIT/INTEGRATION/EXCEPTION/BOUNDARY）、priority（优先级 HIGH/MEDIUM/LOW）。\n\n" +
            "方法: " + m.getClassName() + "." + m.getMethodName() + "(" + m.getSignature() + ")\n" +
            "描述: " + (m.getDescription() != null ? m.getDescription() : "无") + "\n" +
            "文件: " + m.getFilePath() + "\n" +
            "复杂度: " + m.getComplexity() + "\n\n" +
            blastContext;

        try {
            List<TestSuggestion> testCases = extractionChatClient.prompt().user(prompt).call()
                .entity(new ParameterizedTypeReference<List<TestSuggestion>>() {});
            Map<String, Object> resp = new HashMap<>();
            resp.put("nodeId", nodeId);
            resp.put("testCases", testCases == null ? List.of() : testCases);
            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.warn("[GenCenter] Claude test suggestions failed: {}", e.getMessage());
            return ApiResponse.error("LLM 生成测试建议失败: " + e.getMessage());
        }
    }

    @PostMapping("/refactor-suggestions")
    public ApiResponse<Map<String, Object>> generateRefactorSuggestions(
            @RequestParam String moduleName,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) return ApiResponse.error(400, "projectPath or projectPaths required");

        // 获取 DSM 和热点数据作为 context
        var dsm = kgController.getDsm(null, paths, null, "package");
        var hotspots = kgController.getHotspots(null, paths, null, 10);
        String ctx = "DSM: " + (dsm.isSuccess() ? dsm.getData() : "不可用")
            + "\nHotspots: " + (hotspots.isSuccess() ? hotspots.getData() : "不可用");

        String prompt =
            "你是一个架构重构专家。基于模块\"" + moduleName + "\"及其 DSM 矩阵和热点数据，生成 3-5 条重构建议。\n" +
            "每条建议包含：issue（问题描述一句话）、direction（重构方向，如:拆分类/提取接口/解耦依赖/消除循环依赖）、\n" +
            "impact（影响范围估计）、priority（优先级 HIGH/MEDIUM/LOW）。\n\n" +
            ctx;

        try {
            List<RefactorSuggestion> suggestions = extractionChatClient.prompt().user(prompt).call()
                .entity(new ParameterizedTypeReference<List<RefactorSuggestion>>() {});
            Map<String, Object> resp = new HashMap<>();
            resp.put("moduleName", moduleName);
            resp.put("suggestions", suggestions == null ? List.of() : suggestions);
            return ApiResponse.success(resp);
        } catch (Exception e) {
            log.warn("[GenCenter] Claude refactor suggestions failed: {}", e.getMessage());
            return ApiResponse.error("LLM 生成重构建议失败: " + e.getMessage());
        }
    }
}
