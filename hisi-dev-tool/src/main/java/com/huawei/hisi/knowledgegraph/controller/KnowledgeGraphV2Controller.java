package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.model.BridgeStats;
import com.huawei.hisi.knowledgegraph.model.CallChainGraphResponse;
import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.model.ServiceEntryGroup;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱 V2 API —— 所有查询接口统一使用 projectPaths（必填），不再接受单个 projectPath。
 * 委托给 V1 控制器执行实际逻辑（projectPath 传 null）。
 */
@RestController
@RequestMapping("/api/v2/knowledge-graph")
@RequiredArgsConstructor
public class KnowledgeGraphV2Controller {

    private final KnowledgeGraphController v1;
    private final Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository;
    private final Neo4jApiClientNodeRepository neo4jApiClientNodeRepository;
    private final GenerationController generationController;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus(
            @RequestParam List<String> projectPaths) {
        return v1.getStatus(null, projectPaths);
    }

    @GetMapping("/status/batch")
    public ApiResponse<List<Map<String, Object>>> getBatchStatus(
            @RequestParam List<String> projectPaths) {
        return v1.getBatchStatus(projectPaths);
    }

    @GetMapping("/classes")
    public ApiResponse<Map<String, Object>> getClasses(
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String keyword) {
        return v1.getClasses(null, projectPaths, page, pageSize, keyword);
    }

    @GetMapping("/git-status")
    public ResponseEntity<Map<String, Object>> getGitStatus(
            @RequestParam List<String> projectPaths) {
        return v1.getGitStatus(null, projectPaths);
    }

    @GetMapping("/root-entries")
    public ApiResponse<Map<String, Object>> getRootEntries(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam List<String> projectPaths) {
        return v1.getRootEntries(className, methodName, null, projectPaths);
    }

    @GetMapping("/callees-tree")
    public ApiResponse<CallChainGraphResponse> getCalleesTree(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {
        return v1.getCalleesTree(className, methodName, null, projectPaths, maxDepth);
    }

    @GetMapping("/entry-types")
    public ApiResponse<List<String>> getDistinctEntryTypes(
            @RequestParam List<String> projectPaths) {
        return v1.getDistinctEntryTypes(null, projectPaths);
    }

    @GetMapping("/entry-points")
    public ApiResponse<Map<String, Object>> getEntryPoints(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String entryType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return v1.getEntryPoints(null, projectPaths, entryType, page, pageSize);
    }

    @GetMapping("/call-chain/by-key")
    public ApiResponse<Map<String, Object>> getCallChainByKey(
            @RequestParam String entryKey,
            @RequestParam List<String> projectPaths) {
        return v1.getCallChainByKey(entryKey, null, projectPaths);
    }

    @GetMapping("/call-chain/graph")
    public ApiResponse<CallChainGraphResponse> getCallChainGraph(
            @RequestParam String entryKey,
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "true") boolean includeCycles,
            @RequestParam(defaultValue = "50") int maxDepth) {
        return v1.getCallChainGraph(entryKey, null, projectPaths, includeCycles, maxDepth);
    }

    @GetMapping("/method/search")
    public ApiResponse<List<Map<String, Object>>> searchMethods(
            @RequestParam String keyword,
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "50") int limit) {
        return v1.searchMethods(keyword, null, projectPaths, limit);
    }

    @GetMapping("/method/detail")
    public ApiResponse<Map<String, Object>> getMethodDetail(
            @RequestParam String nodeId,
            @RequestParam List<String> projectPaths) {
        return v1.getMethodDetail(nodeId, null, projectPaths);
    }

    @GetMapping("/method/by-class")
    public ApiResponse<List<Map<String, Object>>> getMethodsByClass(
            @RequestParam String className,
            @RequestParam List<String> projectPaths) {
        return v1.getMethodsByClass(className, null, projectPaths);
    }

    @GetMapping("/bridge-stats")
    public ApiResponse<BridgeStats> getBridgeStats(
            @RequestParam List<String> projectPaths) {
        return v1.getBridgeStats(null, projectPaths);
    }

    @GetMapping("/bridges/by-type")
    public ApiResponse<List<Map<String, Object>>> getBridgesByType(
            @RequestParam String bridgeType,
            @RequestParam List<String> projectPaths) {
        return v1.getBridgesByType(bridgeType, null, projectPaths);
    }

    @GetMapping("/projects")
    public ApiResponse<List<String>> getProjects() {
        return v1.getProjects();
    }

    /**
     * 按 serviceName 聚合查询入口点（支持分页）
     */
    @GetMapping("/entry-points/grouped")
    public ApiResponse<Map<String, Object>> getEntryPointsGrouped(
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        long skip = (long) (page - 1) * pageSize;
        List<ServiceEntryGroup> groups = neo4jEntryPointNodeRepository
                .findByProjectPathsGroupedByServiceNamePaged(projectPaths, skip, pageSize);
        long total = neo4jEntryPointNodeRepository.countServiceNamesByProjectPaths(projectPaths);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", groups);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return ApiResponse.success(result);
    }

    // ==================== 聚合视图端点（multi-perspective-platform Phase 2） ====================

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> getDashboard(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String language) {
        return v1.getDashboard(null, projectPaths, language);
    }

    @GetMapping("/dsm")
    public ApiResponse<Map<String, Object>> getDsm(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, defaultValue = "package") String level) {
        return v1.getDsm(null, projectPaths, language, level);
    }

    @GetMapping("/dsm/drill-down")
    public ApiResponse<Map<String, Object>> getDsmDrillDown(
            @RequestParam List<String> projectPaths,
            @RequestParam List<String> modules) {
        return v1.getDsmDrillDown(null, projectPaths, modules);
    }

    @PostMapping("/architecture-analysis")
    public ApiResponse<Map<String, Object>> runArchitectureAnalysis(
            @RequestParam List<String> projectPaths) {
        return v1.runArchitectureAnalysis(null, projectPaths);
    }

    @GetMapping("/architecture-analysis/status")
    public ApiResponse<List<GenerationTask>> getArchitectureAnalysisStatus(
            @RequestParam List<String> projectPaths) {
        return v1.getArchitectureAnalysisStatus(null, projectPaths);
    }

    @GetMapping("/package-cycles")
    public ApiResponse<Map<String, Object>> getPackageCycles(
            @RequestParam List<String> projectPaths) {
        return v1.getPackageCycles(null, projectPaths);
    }

    @GetMapping("/package-dependencies")
    public ApiResponse<Map<String, Object>> getPackageDependencies(
            @RequestParam List<String> projectPaths) {
        return v1.getPackageDependencies(null, projectPaths);
    }

    @GetMapping("/class-layer-violations")
    public ApiResponse<Map<String, Object>> getClassLayerViolations(
            @RequestParam List<String> projectPaths) {
        return v1.getClassLayerViolations(null, projectPaths);
    }

    @GetMapping("/layer-domain-matrix")
    public ApiResponse<Map<String, Object>> getLayerDomainMatrix(
            @RequestParam List<String> projectPaths) {
        return v1.getLayerDomainMatrix(null, projectPaths);
    }

    @GetMapping("/class-ego-net")
    public ApiResponse<Map<String, Object>> getClassEgoNet(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) List<String> packages) {
        return v1.getClassEgoNet(null, projectPaths, packages);
    }

    @PostMapping("/test-suggestions")
    public ApiResponse<Map<String, Object>> generateTestSuggestions(
            @RequestParam String nodeId,
            @RequestParam List<String> projectPaths) {
        return generationController.generateTestSuggestions(nodeId, null, projectPaths);
    }

    @PostMapping("/refactor-suggestions")
    public ApiResponse<Map<String, Object>> generateRefactorSuggestions(
            @RequestParam String moduleName,
            @RequestParam List<String> projectPaths) {
        return generationController.generateRefactorSuggestions(moduleName, null, projectPaths);
    }

    /**
     * 查某后端接口（EntryPoint）的前端调用方列表（沿 INVOKES_API 边反向）。
     * 供 MCP 工具 kg_api_consumers 直接调用。
     */
    @GetMapping("/api-consumers")
    public ApiResponse<Map<String, Object>> getApiConsumers(@RequestParam String entryId) {
        List<Map<String, Object>> consumers = neo4jApiClientNodeRepository.findApiConsumersByEntryId(entryId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entryId", entryId);
        result.put("consumers", consumers);
        return ApiResponse.success(result);
    }

    /**
     * 查某前端 ApiClient 调用的后端接口列表（沿 INVOKES_API 边正向）。
     * 供 MCP 工具 kg_backend_deps 直接调用。
     */
    @GetMapping("/backend-deps")
    public ApiResponse<Map<String, Object>> getBackendDeps(@RequestParam String apiClientId) {
        List<Map<String, Object>> deps = neo4jApiClientNodeRepository.findBackendDepsByApiClientId(apiClientId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiClientId", apiClientId);
        result.put("deps", deps);
        return ApiResponse.success(result);
    }
}
