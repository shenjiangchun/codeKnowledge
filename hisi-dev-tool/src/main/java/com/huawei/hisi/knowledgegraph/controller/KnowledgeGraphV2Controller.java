package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.model.BridgeRelation;
import com.huawei.hisi.knowledgegraph.model.BridgeStats;
import com.huawei.hisi.knowledgegraph.model.CallChainGraphResponse;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.model.ServiceEntryGroup;
import com.huawei.hisi.neo4j.model.SqlNode;
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

    @GetMapping("/call-chain/by-type")
    public ApiResponse<List<Map<String, Object>>> getCallChainsByType(
            @RequestParam String entryType,
            @RequestParam List<String> projectPaths) {
        return v1.getCallChainsByType(entryType, null, projectPaths);
    }

    @GetMapping("/call-chain/affecting")
    public ApiResponse<List<Map<String, Object>>> getCallChainsAffecting(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam List<String> projectPaths) {
        return v1.getCallChainsAffecting(className, methodName, null, projectPaths);
    }

    @GetMapping("/implementations")
    public ApiResponse<List<String>> getImplementations(
            @RequestParam String interfaceName,
            @RequestParam List<String> projectPaths) {
        return v1.getImplementations(interfaceName, null, projectPaths);
    }

    @GetMapping("/call-chain/downstream")
    public ApiResponse<CallChainGraphResponse> getDownstreamCallChain(
            @RequestParam String nodeId,
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {
        return v1.getDownstreamCallChain(nodeId, null, projectPaths, maxDepth);
    }

    @GetMapping("/call-chain/graph")
    public ApiResponse<CallChainGraphResponse> getCallChainGraph(
            @RequestParam String entryKey,
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "true") boolean includeCycles,
            @RequestParam(defaultValue = "50") int maxDepth) {
        return v1.getCallChainGraph(entryKey, null, projectPaths, includeCycles, maxDepth);
    }

    @GetMapping("/cycles/detect")
    public ApiResponse<Map<String, Object>> detectCycles(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String entryKey,
            @RequestParam(required = false) String nodeId) {
        return v1.detectCycles(null, projectPaths, entryKey, nodeId);
    }

    @GetMapping("/interfaces")
    public ApiResponse<List<String>> getInterfaces(
            @RequestParam String className,
            @RequestParam List<String> projectPaths) {
        return v1.getInterfaces(className, null, projectPaths);
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

    @GetMapping("/mybatis/mappers")
    public ApiResponse<List<String>> getMyBatisMappers(
            @RequestParam List<String> projectPaths) {
        return v1.getMyBatisMappers(null, projectPaths);
    }

    @GetMapping("/mybatis/sql")
    public ApiResponse<List<SqlNode>> getMyBatisSqlList(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String mapperInterface,
            @RequestParam(required = false) String statementType) {
        return v1.getMyBatisSqlList(null, projectPaths, mapperInterface, statementType);
    }

    @GetMapping("/call-chain/{nodeId}/bridges")
    public ApiResponse<List<BridgeRelation>> getMethodBridges(
            @PathVariable String nodeId,
            @RequestParam List<String> projectPaths) {
        return v1.getMethodBridges(nodeId, null, projectPaths);
    }

    @GetMapping("/mapper/{mapperInterface}/sql")
    public ApiResponse<List<SqlNode>> getMapperSql(
            @PathVariable String mapperInterface,
            @RequestParam List<String> projectPaths) {
        return v1.getMapperSql(mapperInterface, null, projectPaths);
    }

    @GetMapping("/feign/{serviceName}/call-chain")
    public ApiResponse<CallChainGraphResponse> getFeignCallChain(
            @PathVariable String serviceName,
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {
        return v1.getFeignCallChain(serviceName, null, projectPaths, maxDepth);
    }

    @GetMapping("/mq/{topic}/call-chain")
    public ApiResponse<CallChainGraphResponse> getMQCallChain(
            @PathVariable String topic,
            @RequestParam List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {
        return v1.getMQCallChain(topic, null, projectPaths, maxDepth);
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

    @GetMapping("/hotspots")
    public ApiResponse<Map<String, Object>> getHotspots(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String language,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return v1.getHotspots(null, projectPaths, language, limit);
    }

    @GetMapping("/domains")
    public ApiResponse<Map<String, Object>> getDomains(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String language) {
        return v1.getDomains(null, projectPaths, language);
    }

    @GetMapping("/domains/{domainId}/classes")
    public ApiResponse<Map<String, Object>> getDomainClasses(
            @PathVariable String domainId,
            @RequestParam List<String> projectPaths) {
        return v1.getDomainClasses(domainId, null, projectPaths);
    }

    @PostMapping("/architecture-analysis")
    public ApiResponse<Map<String, Object>> runArchitectureAnalysis(
            @RequestParam List<String> projectPaths) {
        return v1.runArchitectureAnalysis(null, projectPaths);
    }

    @GetMapping("/service-topology")
    public ApiResponse<Map<String, Object>> getServiceTopology(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) String language) {
        return v1.getServiceTopology(null, projectPaths, language);
    }

    @GetMapping("/blast-radius/{nodeId}")
    public ApiResponse<Map<String, Object>> getBlastRadius(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "5") int maxDepth,
            @RequestParam List<String> projectPaths) {
        return v1.getBlastRadius(nodeId, maxDepth, null, projectPaths);
    }

    @GetMapping("/module-dependency-graph")
    public ApiResponse<Map<String, Object>> getModuleDependencyGraph(
            @RequestParam List<String> projectPaths,
            @RequestParam String sourceModule,
            @RequestParam String targetModule) {
        return v1.getModuleDependencyGraph(null, projectPaths, sourceModule, targetModule);
    }

    @GetMapping("/domain-dependency-graph")
    public ApiResponse<Map<String, Object>> getDomainDependencyGraph(
            @RequestParam List<String> projectPaths,
            @RequestParam String sourceDomain,
            @RequestParam String targetDomain) {
        return v1.getDomainDependencyGraph(null, projectPaths, sourceDomain, targetDomain);
    }

    @GetMapping("/build-modules")
    public ApiResponse<Map<String, Object>> getBuildModules(
            @RequestParam List<String> projectPaths) {
        return v1.getBuildModules(null, projectPaths);
    }

    @GetMapping("/build-module-cycles")
    public ApiResponse<Map<String, Object>> getBuildModuleCycles(
            @RequestParam List<String> projectPaths) {
        return v1.getBuildModuleCycles(null, projectPaths);
    }

    @GetMapping("/build-module-layer-violations")
    public ApiResponse<Map<String, Object>> getBuildModuleLayerViolations(
            @RequestParam List<String> projectPaths) {
        return v1.getBuildModuleLayerViolations(null, projectPaths);
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

    @GetMapping("/module-cycles")
    public ApiResponse<Map<String, Object>> getModuleCycles(
            @RequestParam List<String> projectPaths) {
        return v1.getModuleCycles(null, projectPaths);
    }

    @GetMapping("/class-layer-violations")
    public ApiResponse<Map<String, Object>> getClassLayerViolations(
            @RequestParam List<String> projectPaths) {
        return v1.getClassLayerViolations(null, projectPaths);
    }

    @GetMapping("/class-dependencies")
    public ApiResponse<Map<String, Object>> getClassDependencies(
            @RequestParam List<String> projectPaths,
            @RequestParam(required = false) List<String> packages) {
        return v1.getClassDependencies(null, projectPaths, packages);
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
}
