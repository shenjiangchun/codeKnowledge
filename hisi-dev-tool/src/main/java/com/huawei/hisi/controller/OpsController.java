package com.huawei.hisi.controller;

import com.huawei.hisi.model.*;
import com.huawei.hisi.service.OpsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 运维接口控制器
 * 提供健康检查、影响分析、接口文档生成等运维能力
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsService opsService;

    /**
     * 服务健康检查
     * GET /api/ops/health
     *
     * @return 健康状态
     */
    @GetMapping("/health")
    public ApiResponse<HealthStatus> health() {
        HealthStatus status = opsService.checkHealth();
        return ApiResponse.success(status);
    }

    /**
     * 影响范围分析
     * POST /api/ops/analysis/impact
     *
     * @param request 分析请求
     * @return 影响分析结果
     */
    @PostMapping("/analysis/impact")
    public ApiResponse<ImpactAnalysisResponse> analyzeImpact(
            @Valid @RequestBody ImpactAnalysisRequest request) {
        ImpactAnalysisResponse response = opsService.analyzeImpact(request);
        return ApiResponse.success(response);
    }

    /**
     * 生成接口文档
     * GET /api/ops/docs/interface?uri=/api/log/analyze
     *
     * @param uri 接口 URI
     * @return 接口文档
     */
    @GetMapping("/docs/interface")
    public ApiResponse<Map<String, Object>> generateInterfaceDoc(
            @RequestParam String uri) {
        Map<String, Object> doc = opsService.generateInterfaceDoc(uri);
        return ApiResponse.success(doc);
    }

    /**
     * 下载错误日志
     * POST /api/ops/logs/download
     *
     * @param request 请求参数 (service, timeRange, level)
     * @return 日志列表
     */
    @PostMapping("/logs/download")
    public ApiResponse<Map<String, Object>> downloadLogs(
            @RequestBody Map<String, String> request) {
        String service = request.get("service");
        String timeRange = request.get("timeRange");
        String level = request.getOrDefault("level", "ERROR");

        Map<String, Object> logs = opsService.downloadErrorLogs(service, timeRange, level);
        return ApiResponse.success(logs);
    }
}