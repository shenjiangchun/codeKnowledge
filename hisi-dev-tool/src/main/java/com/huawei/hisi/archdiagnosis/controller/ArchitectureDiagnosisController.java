package com.huawei.hisi.archdiagnosis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.archdiagnosis.domain.VerticalDomainNamer;
import com.huawei.hisi.archdiagnosis.model.ArchDiagnosisSnapshot;
import com.huawei.hisi.archdiagnosis.repository.ArchDiagnosisSnapshotRepository;
import com.huawei.hisi.archdiagnosis.service.ArchDiagnosisReportService;
import com.huawei.hisi.archdiagnosis.service.ArchReportMarkdownExporter;
import com.huawei.hisi.knowledgegraph.util.ProjectPathResolver;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 架构诊断独立核心能力：报告式诊断 + 可视看板。
 *
 * <p>所有端点均为只读 GET（受 {@code AdminOnlyInterceptor} 放行约束），同步一次返回。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/architecture-diagnosis")
@RequiredArgsConstructor
public class ArchitectureDiagnosisController {

    private final ArchDiagnosisReportService reportService;
    private final ArchDiagnosisSnapshotRepository snapshotRepository;
    private final ArchReportMarkdownExporter markdownExporter;
    private final VerticalDomainNamer verticalDomainNamer;
    private final ObjectMapper objectMapper;

    /** 单一报告端点：四层诊断报告（健康分 + 问题清单 + 各章数据）。persist=false 为实时查询，不落快照。 */
    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> getReport(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, defaultValue = "true") boolean persist) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            return ApiResponse.error(400, "projectPaths required");
        }
        try {
            // 「实时生成/刷新」：清空垂直领域命名缓存，重算命名（AI 深度分析/导出路径复用缓存）
            verticalDomainNamer.clearCache();
            return ApiResponse.success(reportService.generate(projectPaths, language, persist));
        } catch (Exception e) {
            log.error("[ArchDiagnosis] 报告生成失败", e);
            return ApiResponse.error(500, "Report generation failed: " + e.getMessage());
        }
    }

    /** 快照历史查询：单项目 scope 的健康总分 + 高/中/低趋势（不回传 reportJson）。 */
    @GetMapping("/snapshots")
    public ApiResponse<Map<String, Object>> getSnapshots(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String projectPath,
            @RequestParam(defaultValue = "20") int limit) {
        String resolvedScope = scope != null ? ProjectPathResolver.normalize(scope) : ProjectPathResolver.normalize(projectPath);
        if (resolvedScope == null || resolvedScope.isBlank()) {
            return ApiResponse.error(400, "scope or projectPath required");
        }
        List<ArchDiagnosisSnapshot> snapshots = snapshotRepository.listTrendByScope(resolvedScope, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ArchDiagnosisSnapshot s : snapshots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("scope", s.getScope());
            item.put("healthScore", s.getHealthScore());
            item.put("grade", s.getGrade());
            item.put("highCount", s.getHighCount());
            item.put("mediumCount", s.getMediumCount());
            item.put("lowCount", s.getLowCount());
            item.put("createdAt", s.getCreatedAt());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshots", items);
        result.put("total", items.size());
        return ApiResponse.success(result);
    }

    /** 快照报告体：按 id 返回完整报告体 JSON（供前端「查看历史」与「进入页面展示最新快照」）。 */
    @GetMapping("/report/snapshot")
    public ApiResponse<Map<String, Object>> getSnapshotReport(@RequestParam Long snapshotId) {
        if (snapshotId == null) {
            return ApiResponse.error(400, "snapshotId required");
        }
        ArchDiagnosisSnapshot snapshot = snapshotRepository.findById(snapshotId);
        if (snapshot == null) {
            return ApiResponse.error(404, "Snapshot not found: " + snapshotId);
        }
        return ApiResponse.success(deserializeReport(snapshot.getReportJson()));
    }

    /**
     * 报告导出为 Markdown：
     * 支持 {@code projectPaths} 即时生成，或 {@code snapshotId} 从历史快照还原。
     */
    @GetMapping("/report/export")
    public ResponseEntity<?> exportReport(
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(required = false) Long snapshotId,
            @RequestParam(required = false) String language) {
        Map<String, Object> report;
        String filename;
        if (snapshotId != null) {
            ArchDiagnosisSnapshot snapshot = snapshotRepository.findById(snapshotId);
            if (snapshot == null) {
                return ResponseEntity.badRequest().body("Snapshot not found: " + snapshotId);
            }
            report = deserializeReport(snapshot.getReportJson());
            filename = "arch-diagnosis-snapshot-" + snapshotId + ".md";
        } else {
            if (projectPaths == null || projectPaths.isEmpty()) {
                return ResponseEntity.badRequest().body("projectPaths or snapshotId required");
            }
            report = reportService.generate(projectPaths, language, false);
            filename = "arch-diagnosis.md";
        }

        String markdown = markdownExporter.toMarkdown(report);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
            .body(markdown.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeReport(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("[ArchDiagnosis] 反序列化快照报告体失败: {}", e.getMessage());
            return Map.of();
        }
    }
}