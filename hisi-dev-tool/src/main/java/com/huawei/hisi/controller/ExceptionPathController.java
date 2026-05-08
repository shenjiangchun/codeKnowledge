package com.huawei.hisi.controller;

import com.huawei.hisi.service.semantic.ExceptionPathAnalyzer;
import com.huawei.hisi.service.semantic.model.ExceptionPath;
import com.huawei.hisi.service.semantic.model.PropagationPath;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 异常传播分析API控制器
 *
 * 提供异常传播路径分析的REST API接口。
 */
@RestController
@RequestMapping("/api/semantic/exception")
public class ExceptionPathController {

    private final ExceptionPathAnalyzer exceptionPathAnalyzer;

    public ExceptionPathController(ExceptionPathAnalyzer exceptionPathAnalyzer) {
        this.exceptionPathAnalyzer = exceptionPathAnalyzer;
    }

    /**
     * 分析单个异常的传播路径
     *
     * @param exceptionType 异常类型全限定名
     * @param location 异常发生位置
     * @return 异常传播路径分析结果
     */
    @GetMapping("/analyze")
    public ResponseEntity<ExceptionPath> analyzeException(
            @RequestParam String exceptionType,
            @RequestParam String location) {
        ExceptionPath result = exceptionPathAnalyzer.analyzeExceptionPath(exceptionType, location);
        return ResponseEntity.ok(result);
    }

    /**
     * 批量分析多个异常的传播路径
     *
     * @param request 包含异常类型列表和位置信息
     * @return 批量分析结果
     */
    @PostMapping("/analyze/batch")
    public ResponseEntity<List<ExceptionPath>> analyzeBatchExceptions(
            @RequestBody BatchAnalysisRequest request) {
        List<ExceptionPath> results = exceptionPathAnalyzer.analyzeBatchExceptionPaths(
                request.getExceptionTypes(),
                request.getLocation());
        return ResponseEntity.ok(results);
    }

    /**
     * 获取Top N异常来源方法
     *
     * @param exceptionType 异常类型
     * @param location 发生位置
     * @param topN 返回数量（默认3）
     * @return Top N源方法列表
     */
    @GetMapping("/top-sources")
    public ResponseEntity<Map<String, Object>> getTopSources(
            @RequestParam String exceptionType,
            @RequestParam String location,
            @RequestParam(defaultValue = "3") int topN) {
        List<String> topSources = exceptionPathAnalyzer.getTopExceptionSources(
                exceptionType, location, topN);

        ExceptionPath fullAnalysis = exceptionPathAnalyzer.analyzeExceptionPath(exceptionType, location);
        List<PropagationPath> topPaths = fullAnalysis.getTopPaths(topN);

        return ResponseEntity.ok(Map.of(
                "exceptionType", exceptionType,
                "location", location,
                "topSources", topSources,
                "topPaths", topPaths,
                "hitProbability", calculateHitProbability(topPaths)
        ));
    }

    /**
     * 获取分析器状态
     *
     * @return 分析器状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "ready", exceptionPathAnalyzer.isReady(),
                "statusInfo", exceptionPathAnalyzer.getStatusInfo()
        ));
    }

    /**
     * 批量分析请求DTO
     */
    public static class BatchAnalysisRequest {
        private List<String> exceptionTypes;
        private String location;

        public List<String> getExceptionTypes() {
            return exceptionTypes;
        }

        public void setExceptionTypes(List<String> exceptionTypes) {
            this.exceptionTypes = exceptionTypes;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }

    /**
     * 计算Top N命中率概率
     */
    private double calculateHitProbability(List<PropagationPath> topPaths) {
        if (topPaths == null || topPaths.isEmpty()) {
            return 0.0;
        }
        return topPaths.stream()
                .mapToDouble(PropagationPath::getProbability)
                .average()
                .orElse(0.0);
    }
}