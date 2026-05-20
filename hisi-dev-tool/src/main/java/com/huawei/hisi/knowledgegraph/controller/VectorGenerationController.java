package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.model.VectorGenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.knowledgegraph.service.VectorGenerationService;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Arrays;

/**
 * 向量生成状态 API 控制器
 * Uses unified GenerationTaskRepository with task_type = "VECTOR".
 */
@RestController
@RequestMapping("/api/vector-generation")
@Slf4j
@RequiredArgsConstructor
public class VectorGenerationController {

    private static final String TASK_TYPE = "VECTOR";

    private final GenerationTaskRepository taskRepository;
    private final VectorGenerationService vectorGenerationService;
    private final Neo4jMethodNodeRepository neo4jMethodNodeRepository;
    private final EmbeddingService embeddingService;

    @GetMapping("/test-embedding")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testEmbedding(
            @RequestParam(defaultValue = "测试文本") String text) {
        try {
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("inputText", text);
            result.put("available", embeddingService.isEmbeddingAvailable());
            result.put("dimension", embeddingService.getEmbeddingDimension());

            float[] embedding = embeddingService.generateEmbedding(text);
            result.put("embeddingDimension", embedding != null ? embedding.length : null);
            result.put("embeddingSample", embedding != null && embedding.length >= 5 ?
                Arrays.toString(Arrays.copyOf(embedding, 5)) : null);

            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("测试embedding失败", e);
            return ResponseEntity.ok(ApiResponse.error(500, "测试失败: " + e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<VectorGenerationTask>> getStatus(
            @RequestParam String projectPath) {
        projectPath = normalizePath(projectPath);
        VectorGenerationTask task = vectorGenerationService.getTaskStatus(projectPath);
        return ResponseEntity.ok(ApiResponse.success(task));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<String>> startGeneration(
            @RequestParam String projectPath) {
        projectPath = normalizePath(projectPath);
        try {
            vectorGenerationService.startVectorGeneration(projectPath);
            return ResponseEntity.ok(ApiResponse.success("向量生成任务已启动"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(500, "向量生成服务不可用: " + e.getMessage()));
        }
    }

    @PostMapping("/regenerate")
    public ResponseEntity<ApiResponse<String>> regenerateAll(
            @RequestParam String projectPath) {
        projectPath = normalizePath(projectPath);
        try {
            vectorGenerationService.regenerateAll(projectPath);
            return ResponseEntity.ok(ApiResponse.success("全量重新生成任务已启动"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(500, "全量重新生成失败: " + e.getMessage()));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getStats(
            @RequestParam String projectPath) {
        projectPath = normalizePath(projectPath);
        long totalMethods = neo4jMethodNodeRepository.countByProjectPath(projectPath);
        long withDescription = neo4jMethodNodeRepository.countWithDescription(projectPath);
        long withEmbedding = neo4jMethodNodeRepository.countWithDescriptionEmbedding(projectPath);

        java.util.Map<String, Object> stats = java.util.Map.of(
            "totalMethods", totalMethods,
            "withDescription", withDescription,
            "withEmbedding", withEmbedding,
            "pendingDescription", totalMethods - withDescription,
            "pendingEmbedding", totalMethods - withEmbedding
        );

        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/status/batch")
    public ResponseEntity<ApiResponse<java.util.List<VectorGenerationTask>>> getStatusBatch(
            @RequestParam(required = false) String projectPaths) {
        java.util.List<VectorGenerationTask> tasks = new java.util.ArrayList<>();
        if (projectPaths == null || projectPaths.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(tasks));
        }
        String[] paths = projectPaths.split(",");

        for (String path : paths) {
            String normalizedPath = normalizePath(path.trim());
            if (!normalizedPath.isEmpty()) {
                VectorGenerationTask task = vectorGenerationService.getTaskStatus(normalizedPath);
                if (task != null) {
                    tasks.add(task);
                }
            }
        }

        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    /**
     * 查询缺失描述向量的方法数量及预览列表
     * GET /api/vector-generation/missing?projectPath=xxx&limit=50
     */
    @GetMapping("/missing")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getMissing(
            @RequestParam String projectPath,
            @RequestParam(defaultValue = "50") int limit) {
        projectPath = normalizePath(projectPath);
        long totalMethods = neo4jMethodNodeRepository.countByProjectPath(projectPath);
        long missingCount = neo4jMethodNodeRepository.countMissingDescriptionEmbedding(projectPath);
        java.util.List<java.util.Map<String, Object>> preview =
                neo4jMethodNodeRepository.findMissingDescriptionEmbedding(projectPath, Math.min(limit, 100));

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("totalMethods", totalMethods);
        result.put("missingCount", missingCount);
        result.put("generatedCount", totalMethods - missingCount);
        result.put("preview", preview);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 补齐缺失向量（仅处理 descriptionEmbedding 为 null 的方法）
     * POST /api/vector-generation/refresh-missing?projectPath=xxx
     * 等价于重新调用 startVectorGeneration，但语义更清晰：只补齐，不清空。
     */
    @PostMapping("/refresh-missing")
    public ResponseEntity<ApiResponse<String>> refreshMissing(
            @RequestParam String projectPath) {
        projectPath = normalizePath(projectPath);
        long missingCount = neo4jMethodNodeRepository.countMissingDescriptionEmbedding(projectPath);
        if (missingCount == 0) {
            return ResponseEntity.ok(ApiResponse.success("所有方法已有向量，无需补齐"));
        }
        try {
            vectorGenerationService.startVectorGeneration(projectPath);
            return ResponseEntity.ok(ApiResponse.success("补齐任务已启动，待处理 " + missingCount + " 个方法"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(500, "补齐任务启动失败: " + e.getMessage()));
        }
    }

    private String normalizePath(String path) {
        return com.huawei.hisi.utils.PathUtils.normalize(path);
    }
}
