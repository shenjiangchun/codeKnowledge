package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.util.ProjectPathResolver;
import com.huawei.hisi.knowledgegraph.model.BridgeRelation;
import com.huawei.hisi.knowledgegraph.model.BridgeStats;
import com.huawei.hisi.knowledgegraph.model.CallChainGraphResponse;
import com.huawei.hisi.knowledgegraph.model.CallCycleInfo;
import com.huawei.hisi.knowledgegraph.model.EntryPointType;
import com.huawei.hisi.knowledgegraph.model.GitStatus;
import com.huawei.hisi.knowledgegraph.model.GraphEdge;
import com.huawei.hisi.knowledgegraph.model.GraphNode;
import com.huawei.hisi.knowledgegraph.model.IncrementalUpdateResult;
import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.knowledgegraph.scanner.MyBatisXmlScanner;
import com.huawei.hisi.knowledgegraph.service.GitStatusService;
import com.huawei.hisi.knowledgegraph.service.IncrementalUpdateService;
import com.huawei.hisi.knowledgegraph.service.KnowledgeGraphBuilder;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.KnowledgeGraphTask;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.service.KnowledgeGraphTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 知识图谱 API 控制器
 * 增强版：支持调用链查询、深度信息、接口实现追踪、任务状态管理
 *
 * 数据存储：使用 Neo4j 作为主存储（包括 SQL 节点）
 */
@RestController
@RequestMapping("/api/knowledge-graph")
@Slf4j
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphBuilder knowledgeGraphBuilder;
    private final KnowledgeGraphTaskService taskService;

    // Neo4j Driver (for raw Cypher queries)
    private final Driver neo4jDriver;

    // Neo4j Repository (主数据源)
    private final Neo4jMethodNodeRepository neo4jMethodNodeRepository;
    private final Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository;
    private final Neo4jSqlNodeRepository neo4jSqlNodeRepository;

    private final GitStatusService gitStatusService;
    private final IncrementalUpdateService incrementalUpdateService;
    private final GenerationTaskRepository generationTaskRepository;

    // MyBatis Scanner
    private final MyBatisXmlScanner myBatisXmlScanner;

    // ============================================================
    // 任务管理接口（异步生成）
    // ============================================================

    /**
     * 启动知识图谱生成任务（异步）
     * POST /api/knowledge-graph/tasks/generate?projectPath=xxx
     */
    @PostMapping("/tasks/generate")
    public ResponseEntity<?> startTask(
            @RequestParam String projectPath,
            @RequestParam(required = false) String excludePaths) {
        projectPath = normalizePath(projectPath);
        List<String> excludeList = null;
        if (excludePaths != null && !excludePaths.isBlank()) {
            excludeList = Arrays.stream(excludePaths.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        try {
            KnowledgeGraphTask task = taskService.startTask(projectPath, excludeList);
            return ResponseEntity.ok(task);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "INVALID_PROJECT");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            String message = e.getMessage();

            // 区分 Git 校验失败和任务正在运行
            if (message != null && (message.contains("未提交") || message.contains("未推送"))) {
                error.put("error", "GIT_CHECK_FAILED");
                error.put("message", message);
                return ResponseEntity.status(400).body(error);
            } else {
                error.put("error", "TASK_RUNNING");
                error.put("message", message);
                KnowledgeGraphTask existingTask = taskService.getLatestTask(projectPath);
                if (existingTask != null) {
                    error.put("runningTask", existingTask);
                }
                return ResponseEntity.status(409).body(error);
            }
        }
    }

    /**
     * 批量查询任务状态
     * GET /api/knowledge-graph/tasks/status?projectPaths=D:/path1,D:/path2
     */
    @GetMapping("/tasks/status")
    public ResponseEntity<List<KnowledgeGraphTask>> getTaskStatus(
            @RequestParam(required = false) String projectPaths) {

        List<String> pathList = null;
        if (projectPaths != null && !projectPaths.trim().isEmpty()) {
            pathList = Arrays.asList(projectPaths.split(","));
        }

        List<KnowledgeGraphTask> tasks = taskService.getTaskStatus(pathList);
        return ResponseEntity.ok(tasks);
    }

    /**
     * 获取单个项目的最新任务
     * GET /api/knowledge-graph/tasks/latest?projectPath=xxx
     */
    @GetMapping("/tasks/latest")
    public ResponseEntity<KnowledgeGraphTask> getLatestTask(@RequestParam String projectPath) {
        projectPath = normalizePath(projectPath);
        KnowledgeGraphTask task = taskService.getLatestTask(projectPath);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(task);
    }

    // ============================================================
    // 项目与类查询接口（替代旧 callchain 接口）
    // ============================================================

    /**
     * 获取所有已生成知识图谱的项目路径列表
     * GET /api/knowledge-graph/projects
     * 替代旧的 /api/callchain/projects 接口
     */
    @GetMapping("/projects")
    public ApiResponse<List<String>> getProjects() {
        List<String> projects = neo4jMethodNodeRepository.findDistinctProjectPaths();
        return ApiResponse.success(projects);
    }

    /**
     * 获取项目下的所有类名列表
     * GET /api/knowledge-graph/classes?projectPath=xxx
     * 替代旧的 /api/callchain/classes 接口
     */
    @GetMapping("/classes")
    public ApiResponse<List<String>> getClasses(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        List<String> classes = neo4jMethodNodeRepository.findDistinctClassNamesByProjectPaths(paths);
        return ApiResponse.success(classes);
    }

    // ============================================================
    // Git 状态与增量更新接口
    // ============================================================

    /**
     * 获取项目 Git 状态
     * GET /api/knowledge-graph/git-status?projectPath=xxx
     *
     * @param projectPath 项目路径
     * @return Git 状态信息
     */
    @GetMapping("/git-status")
    public ResponseEntity<Map<String, Object>> getGitStatus(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "projectPath or projectPaths required"));
        }
        String resolvedPath = paths.get(0);
        Map<String, Object> result = new HashMap<>();

        try {
            // 获取 Git 状态
            GitStatus gitStatus = gitStatusService.getGitStatus(resolvedPath);
            result.put("isClean", gitStatus.isClean());
            result.put("commitHash", gitStatus.getCommitHash());
            result.put("branch", gitStatus.getBranch());
            result.put("hasUncommittedChanges", gitStatus.isHasUncommittedChanges());
            result.put("hasUnpushedCommits", gitStatus.isHasUnpushedCommits());
            result.put("unpushedCommitCount", gitStatus.getUnpushedCommitCount());

            // 检查是否有历史记录
            boolean hasHistory = gitStatus.getCommitHash() != null;
            result.put("hasHistory", hasHistory);

            // 获取上次生成的 commit hash (stored in errorMessage field of KG_LOG tasks)
            String lastGeneratedCommit = null;
            Optional<GenerationTask> lastLog = generationTaskRepository.findLatestByProjectPathAndType(resolvedPath, "KG_LOG");
            if (lastLog.isPresent()) {
                lastGeneratedCommit = lastLog.get().getErrorMessage();
            }
            result.put("lastGeneratedCommit", lastGeneratedCommit);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取 Git 状态失败: {}", resolvedPath, e);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 执行增量生成
     * POST /api/knowledge-graph/incremental
     *
     * @param request 请求体，包含 projectPath
     * @return 增量更新结果
     */
    @PostMapping("/incremental")
    public ResponseEntity<Map<String, Object>> incrementalGenerate(@RequestBody Map<String, String> request) {
        String projectPath = request.get("projectPath");
        Map<String, Object> result = new HashMap<>();

        if (projectPath == null || projectPath.isEmpty()) {
            result.put("success", false);
            result.put("error", "项目路径不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            IncrementalUpdateResult updateResult = incrementalUpdateService.incrementalUpdate(projectPath);

            result.put("success", updateResult.isSuccess());
            result.put("newMethods", updateResult.getNewMethods());
            result.put("updatedMethods", updateResult.getUpdatedMethods());
            result.put("deletedMethods", updateResult.getDeletedMethods());
            result.put("costTimeMs", updateResult.getCostTimeMs());
            result.put("oldCommitHash", updateResult.getOldCommitHash());
            result.put("newCommitHash", updateResult.getNewCommitHash());
            result.put("branch", updateResult.getBranch());
            result.put("totalMethods", updateResult.getTotalMethods());
            result.put("changedFiles", updateResult.getChangedFiles());

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("增量生成参数错误: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (IllegalStateException e) {
            log.warn("增量生成状态错误: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("needsFullGeneration", true);
            return ResponseEntity.status(409).body(result);
        } catch (Exception e) {
            log.error("增量生成失败: {}", projectPath, e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ============================================================
    // 同步生成接口（保留，供简单场景使用）
    // ============================================================

    /**
     * 为项目生成知识图谱（同步，阻塞请求）
     */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, String> request) {
        String projectPath = request.get("projectPath");
        if (projectPath == null || projectPath.isEmpty()) {
            return ApiResponse.error(400, "项目路径不能为空");
        }
        // 入口规范化（与 startTask 保持一致），避免再产生分隔符不一致的脏数据。
        projectPath = normalizePath(projectPath);

        try {
            Map<String, Object> result = knowledgeGraphBuilder.buildKnowledgeGraph(projectPath);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("生成知识图谱失败", e);
            return ApiResponse.error(500, "生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取知识图谱状态（包含任务状态）
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error("projectPath or projectPaths is required");
        }
        log.info("[KG Status] Query for paths={}", paths);

        // 获取数据统计 - 使用 Neo4j（IN 批量查询）
        int methodNodeCount = (int) neo4jMethodNodeRepository.countByProjectPaths(paths);
        int callRelationCount = (int) neo4jMethodNodeRepository.countCallRelationsByProjectPaths(paths);
        int entryPointCount = (int) neo4jEntryPointNodeRepository.countByProjectPaths(paths);
        log.info("[KG Status] Neo4j IN-match counts: methods={}, relations={}, entryPoints={}",
            methodNodeCount, callRelationCount, entryPointCount);

        // 如果 Neo4j 数据为空，列出数据库中的 projectPath
        if (methodNodeCount == 0 && entryPointCount == 0) {
            try (Session session = neo4jDriver.session()) {
                var pathsResult = session.run(
                    "MATCH (n) WHERE n.projectPath IS NOT NULL RETURN DISTINCT n.projectPath as path, labels(n) as labels LIMIT 5"
                );
                log.info("[KG Status] Neo4j has data with projectPath:");
                while (pathsResult.hasNext()) {
                    var rec = pathsResult.next();
                    log.info("[KG Status]   path='{}', labels={}", rec.get("path").asString(""), rec.get("labels").asList(v -> v.asString()));
                }
            }
        }

        // Neo4j 数据：接口实现数量（IN 查询）
        int interfaceImplCount;
        try (Session neoSession = neo4jDriver.session()) {
            interfaceImplCount = neoSession.run(
                "MATCH (c)-[:IMPLEMENTS]->(i) WHERE c.projectPath IN $paths RETURN count(c) AS cnt",
                Map.of("paths", paths)
            ).single().get("cnt").asInt();
        }
        // 使用 Neo4j 计算调用链覆盖的方法数量（IN 批量查询）
        long callChainCount = neo4jMethodNodeRepository.countReachableMethodsFromEntryPointsByProjectPaths(paths);
        // 使用 Neo4j 统计入口点数量
        long entryCount = neo4jEntryPointNodeRepository.countByProjectPaths(paths);
        log.info("[KG Status] Neo4j counts: interfaceImpls={}", interfaceImplCount);
        log.info("[KG Status] Neo4j callChain coverage: reachableMethods={}, entryPoints={}",
            callChainCount, entryCount);

        // 获取任务状态（取第一个路径的最新任务）
        String primaryPath = paths.get(0);
        KnowledgeGraphTask latestTask = taskService.getLatestTask(primaryPath);

        // 判断生成状态
        String status = "not_generated";
        if (latestTask != null) {
            status = latestTask.getStatus().toLowerCase(); // PENDING, RUNNING, COMPLETED, FAILED -> lowercase
        } else if (methodNodeCount > 0 || callRelationCount > 0 || entryPointCount > 0) {
            status = "generated";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("projectPath", primaryPath);
        result.put("status", status);
        result.put("methodNodeCount", methodNodeCount);
        result.put("callRelationCount", callRelationCount);
        result.put("entryPointCount", entryPointCount);
        result.put("interfaceImplCount", interfaceImplCount);
        result.put("callChainCount", callChainCount);
        result.put("entryCount", entryCount);

        // 添加任务信息
        if (latestTask != null) {
            result.put("taskId", latestTask.getId());
            result.put("taskStatus", latestTask.getStatus());
            result.put("startTime", latestTask.getStartTime());
            result.put("endTime", latestTask.getEndTime());
            result.put("costTimeMs", latestTask.getCostTimeMs());
            result.put("errorMessage", latestTask.getErrorMessage());
        }

        return ApiResponse.success(result);
    }

    /**
     * 批量查询多个项目的知识图谱状态
     * GET /api/knowledge-graph/status/batch?projectPaths=D:/path1&projectPaths=D:/path2
     *
     * 返回 List 形式，便于前端按数组迭代；每个元素自带 projectPath 字段。
     * projectPaths 为空或缺失时返回空列表（避免 MissingServletRequestParameterException）。
     */
    @GetMapping("/status/batch")
    public ApiResponse<List<Map<String, Object>>> getBatchStatus(
            @RequestParam(required = false) List<String> projectPaths) {
        List<Map<String, Object>> batchResult = new ArrayList<>();
        if (projectPaths == null || projectPaths.isEmpty()) {
            log.info("[KG Batch Status] projectPaths empty, returning empty list");
            return ApiResponse.success(batchResult);
        }
        for (String path : projectPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalized = ProjectPathResolver.normalize(path);
            try {
                ApiResponse<Map<String, Object>> single = getStatus(normalized, null);
                Map<String, Object> data = single.getData();
                if (data == null) {
                    data = new HashMap<>();
                }
                // 确保返回值带 projectPath，便于前端按 path 索引
                data.putIfAbsent("projectPath", normalized);
                batchResult.add(data);
            } catch (Exception e) {
                log.warn("[KG Batch Status] Failed to query status for path={}: {}", normalized, e.getMessage());
                Map<String, Object> errorEntry = new HashMap<>();
                errorEntry.put("projectPath", normalized);
                errorEntry.put("status", "error");
                errorEntry.put("error", e.getMessage());
                batchResult.add(errorEntry);
            }
        }
        return ApiResponse.success(batchResult);
    }

    /**
     * 查询方法的调用者
     */
    /**
     * 查询方法的所有上游信息（合并接口）
     * 返回: { rootEntries: [...], directCallers: [...] }
     * - rootEntries: 沿调用链向上回溯到的根入口点（HTTP/MQ/EVENT/SCHEDULED/LIFECYCLE/FEIGN 等）
     * - directCallers: 直接调用当前方法的一层调用方（含 callType/callLine）
     * GET /api/knowledge-graph/root-entries
     */
    @GetMapping("/root-entries")
    public ApiResponse<Map<String, Object>> getRootEntries(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        log.info("[KG RootEntries] Searching for method: className={}, methodName={}, projectPaths={}",
            className, methodName, paths);

        List<Map<String, Object>> rootEntries = new ArrayList<>();
        List<Map<String, Object>> directCallers = new ArrayList<>();

        // 先尝试精确匹配
        List<MethodNode> nodes = neo4jMethodNodeRepository.findByProjectPathsAndClassName(paths, className);
        log.info("[KG RootEntries] Found {} nodes with exact className match", nodes.size());

        // 查找节点的通用方法
        MethodNode targetNode = nodes.stream()
            .filter(n -> n.getMethodName().equals(methodName))
            .findFirst()
            .orElse(null);

        // 如果没找到，尝试通过 className + methodName 组合查找
        if (targetNode == null) {
            log.info("[KG RootEntries] Exact match failed, trying className+methodName search");
            List<MethodNode> combinedNodes = neo4jMethodNodeRepository.findByProjectPathsAndClassNameAndMethodName(paths, className, methodName);
            if (!combinedNodes.isEmpty()) {
                targetNode = combinedNodes.get(0);
                log.info("[KG RootEntries] Found by className+methodName: {}", targetNode.getNodeId());
            }
        }

        // 如果还是没找到，尝试类名模糊匹配
        if (targetNode == null) {
            log.info("[KG RootEntries] Still not found, trying fuzzy className search");
            List<MethodNode> fuzzyNodes = neo4jMethodNodeRepository.findByProjectPathsAndClassNameContaining(paths, className);
            log.info("[KG RootEntries] Found {} nodes with fuzzy className match", fuzzyNodes.size());
            targetNode = fuzzyNodes.stream()
                .filter(n -> n.getMethodName().equals(methodName))
                .findFirst()
                .orElse(null);
        }

        if (targetNode == null) {
            log.warn("[KG RootEntries] Method not found: {}#{}", className, methodName);
            // 返回空结果而不是 404，让前端可以显示没有找到的状态
            Map<String, Object> result = new HashMap<>();
            result.put("rootEntries", rootEntries);
            result.put("directCallers", directCallers);
            return ApiResponse.success(result);
        }

        log.info("[KG RootEntries] Found method: nodeId={}, projectPath={}", targetNode.getNodeId(), targetNode.getProjectPath());

        Set<String> seenEntryIds = new HashSet<>();
        Set<String> seenCallerIds = new HashSet<>();

        // 1. 根入口点
        List<Neo4jMethodNodeRepository.EntryPointInfo> entries =
            neo4jMethodNodeRepository.findEntryPointsCallingMethodByPaths(targetNode.getNodeId(), paths);
        for (Neo4jMethodNodeRepository.EntryPointInfo entry : entries) {
            if (seenEntryIds.add(entry.entryId())) {
                Map<String, Object> info = new HashMap<>();
                info.put("entryId", entry.entryId());
                info.put("entryType", entry.entryType());
                info.put("entryKey", entry.entryKey());
                rootEntries.add(info);
            }
        }

        // 2. 直接调用方
        // dispatch 边已物化为 CALLS，findCallersWithRelation 会自动返回
        // FEIGN_BRIDGE caller（feign→local 反向），无需额外 Feign 穿透
        List<Neo4jMethodNodeRepository.CallerWithRelation> relations =
            neo4jMethodNodeRepository.findCallersWithRelation(targetNode.getNodeId());
        for (Neo4jMethodNodeRepository.CallerWithRelation relation : relations) {
            if (seenCallerIds.add(relation.callerId())) {
                Map<String, Object> callerInfo = new HashMap<>();
                callerInfo.put("callerId", relation.callerId());
                callerInfo.put("callerClassName", relation.callerClassName());
                callerInfo.put("callerMethodName", relation.callerMethodName());
                callerInfo.put("callType", relation.callType());
                callerInfo.put("callLine", relation.callLine());
                directCallers.add(callerInfo);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("rootEntries", rootEntries);
        result.put("directCallers", directCallers);

        log.info("[KG RootEntries] {}#{} -> {} entries, {} callers",
            className, methodName, rootEntries.size(), directCallers.size());
        return ApiResponse.success(result);
    }

    /**
     * 查询方法的完整下游调用树（递归，含 depth）
     * 内部解析 className+methodName → nodeId，复用 buildDownstreamGraph
     * GET /api/knowledge-graph/callees-tree
     */
    @GetMapping("/callees-tree")
    public ApiResponse<CallChainGraphResponse> getCalleesTree(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {

        // 标准化单个 projectPath
        if (projectPath != null) {
            projectPath = normalizePath(projectPath);
        }

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        log.info("[KG CalleesTree] Searching for method: className={}, methodName={}, projectPaths={}",
            className, methodName, paths);

        // 先尝试精确匹配
        List<MethodNode> nodes = neo4jMethodNodeRepository.findByProjectPathsAndClassName(paths, className);
        log.info("[KG CalleesTree] Found {} nodes with exact className match", nodes.size());

        MethodNode startNode = nodes.stream()
            .filter(n -> n.getMethodName().equals(methodName))
            .findFirst()
            .orElse(null);

        // 如果没找到，尝试通过 className + methodName 组合查找（支持模糊类名）
        if (startNode == null) {
            log.info("[KG CalleesTree] Exact match failed, trying className+methodName search");
            List<MethodNode> combinedNodes = neo4jMethodNodeRepository.findByProjectPathsAndClassNameAndMethodName(paths, className, methodName);
            if (!combinedNodes.isEmpty()) {
                startNode = combinedNodes.get(0);
                log.info("[KG CalleesTree] Found by className+methodName: {}", startNode.getNodeId());
            }
        }

        // 如果还是没找到，尝试类名模糊匹配
        if (startNode == null) {
            log.info("[KG CalleesTree] Still not found, trying fuzzy className search");
            List<MethodNode> fuzzyNodes = neo4jMethodNodeRepository.findByProjectPathsAndClassNameContaining(paths, className);
            log.info("[KG CalleesTree] Found {} nodes with fuzzy className match", fuzzyNodes.size());
            startNode = fuzzyNodes.stream()
                .filter(n -> n.getMethodName().equals(methodName))
                .findFirst()
                .orElse(null);
        }

        // 如果还是没找到，尝试通过 EntryPoint 查找
        if (startNode == null) {
            log.info("[KG CalleesTree] Still not found, trying to find via EntryPoint");
            List<EntryPointNode> entryPoints = neo4jEntryPointNodeRepository.findByProjectPaths(paths);
            for (EntryPointNode ep : entryPoints) {
                if (ep.getMethodNodeId() != null) {
                    // 检查是否匹配 className.methodName
                    if (ep.getMethodNodeId().contains(className) && ep.getMethodNodeId().contains(methodName)) {
                        Optional<MethodNode> foundByEntry = neo4jMethodNodeRepository.findByNodeId(ep.getMethodNodeId());
                        if (foundByEntry.isPresent()) {
                            startNode = foundByEntry.get();
                            log.info("[KG CalleesTree] Found method via EntryPoint: nodeId={}, entryKey={}",
                                startNode.getNodeId(), ep.getEntryKey());
                            break;
                        }
                    }
                }
            }
        }

        // 如果还是没找到，尝试通过方法名模糊搜索（不限制类名）
        if (startNode == null) {
            log.info("[KG CalleesTree] Still not found, trying fuzzy methodName search");
            List<MethodNode> methodNodes = neo4jMethodNodeRepository.findByProjectPaths(paths);
            startNode = methodNodes.stream()
                .filter(n -> n.getMethodName().equals(methodName))
                .findFirst()
                .orElse(null);
            if (startNode != null) {
                log.info("[KG CalleesTree] Found method by fuzzy methodName search: nodeId={}, className={}",
                    startNode.getNodeId(), startNode.getClassName());
            }
        }

        if (startNode == null) {
            log.warn("[KG CalleesTree] Method not found: {}#{}", className, methodName);
            // 输出诊断信息
            List<MethodNode> allNodes = neo4jMethodNodeRepository.findByProjectPaths(paths);
            log.info("[KG CalleesTree] Total {} method nodes in project, some samples: {}", allNodes.size(),
                allNodes.stream().limit(10).map(n -> n.getClassName() + "#" + n.getMethodName()).collect(Collectors.toList()));
            // 返回空图而不是 404，让前端可以显示没有找到的状态
            CallChainGraphResponse emptyResponse = CallChainGraphResponse.builder()
                .entryId(null)
                .entryType("METHOD")
                .entryKey(className + "." + methodName)
                .maxDepth(0)
                .totalNodes(0)
                .nodes(new ArrayList<>())
                .edges(new ArrayList<>())
                .cycles(new ArrayList<>())
                .cycleCount(0)
                .nodesInCycle(new HashSet<>())
                .build();
            return ApiResponse.success(emptyResponse);
        }

        log.info("[KG CalleesTree] Found method: nodeId={}, projectPath={}", startNode.getNodeId(), startNode.getProjectPath());

        String resolvedPath = startNode.getProjectPath();
        List<GraphNode> graphNodes = new ArrayList<>();
        List<GraphEdge> graphEdges = new ArrayList<>();
        Set<String> visitedNodes = new HashSet<>();
        Set<String> nodesInCycle = new HashSet<>();
        List<CallCycleInfo> cycles = new ArrayList<>();

        buildDownstreamGraph(startNode.getNodeId(), resolvedPath, 0, maxDepth,
            visitedNodes, graphNodes, graphEdges, nodesInCycle, cycles);

        CallChainGraphResponse response = CallChainGraphResponse.builder()
            .entryId(startNode.getNodeId())
            .entryType("METHOD")
            .entryKey(className + "." + methodName)
            .maxDepth(graphNodes.stream().mapToInt(n -> n.getDepth() != null ? n.getDepth() : 0).max().orElse(0))
            .totalNodes(graphNodes.size())
            .nodes(graphNodes)
            .edges(graphEdges)
            .cycles(cycles)
            .cycleCount(cycles.size())
            .nodesInCycle(nodesInCycle)
            .build();

        log.info("[KG CalleesTree] {}#{} -> {} nodes, maxDepth={}",
            className, methodName, graphNodes.size(), response.getMaxDepth());
        return ApiResponse.success(response);
    }

    /**
     * 查询入口点列表
     */
    @GetMapping("/entry-points")
    public ApiResponse<List<Map<String, Object>>> getEntryPoints(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(required = false) String entryType) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        List<Map<String, Object>> entryPoints = new ArrayList<>();
        List<com.huawei.hisi.neo4j.model.EntryPointNode> neo4jEntryPoints;
        if (entryType != null && !entryType.isEmpty()) {
            neo4jEntryPoints = neo4jEntryPointNodeRepository.findByProjectPathsAndEntryType(paths, entryType);
        } else {
            neo4jEntryPoints = neo4jEntryPointNodeRepository.findByProjectPaths(paths);
        }

        for (var ep : neo4jEntryPoints) {
            Map<String, Object> map = new HashMap<>();
            map.put("nodeId", ep.getEntryId());
            map.put("entryType", ep.getEntryType());
            map.put("entryKey", ep.getEntryKey());
            map.put("entryInfo", ep.getEntryInfo());
            map.put("projectPath", ep.getProjectPath());
            entryPoints.add(map);
        }

        return ApiResponse.success(entryPoints);
    }

    // ============================================================
    // 新增 API：调用链查询（替代调用链分析功能）
    // ============================================================

    /**
     * 根据入口标识查询完整调用链（如 URI）
     * 使用 Neo4j 图遍历替代预计算的调用链表
     */
    @GetMapping("/call-chain/by-key")
    public ApiResponse<Map<String, Object>> getCallChainByKey(
            @RequestParam String entryKey,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        String resolvedPath = paths.get(0);

        // 使用 Neo4j 图遍历获取调用链节点
        int maxDepth = 50;
        List<Neo4jMethodNodeRepository.GraphTraversalResult> traversalNodes =
            getCallChainNodes(entryKey, resolvedPath, maxDepth);

        if (traversalNodes.isEmpty()) {
            return ApiResponse.error(404, "未找到入口: " + entryKey);
        }

        // 使用 Neo4j 图遍历获取调用链边
        List<Neo4jMethodNodeRepository.GraphEdgeResult> traversalEdges =
            getCallChainEdges(entryKey, resolvedPath, maxDepth);

        log.info("[KG CallChain] entryKey={}, nodes={}, edges={}", entryKey, traversalNodes.size(), traversalEdges.size());

        // 构建链路视图
        Map<String, Object> result = buildChainViewFromNeo4j(traversalNodes, traversalEdges, entryKey, resolvedPath);
        return ApiResponse.success(result);
    }

    /**
     * 根据入口类型查询所有调用链
     * 使用 Neo4j 入口点查询 + 图遍历
     */
    @GetMapping("/call-chain/by-type")
    public ApiResponse<List<Map<String, Object>>> getCallChainsByType(
            @RequestParam String entryType,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int maxDepth = 50;

        List<EntryPointNode> entryPoints = neo4jEntryPointNodeRepository
            .findByProjectPathsAndEntryType(paths, entryType);

        for (EntryPointNode ep : entryPoints) {
            List<Neo4jMethodNodeRepository.GraphTraversalResult> traversalNodes =
                getCallChainNodes(ep.getEntryKey(), ep.getProjectPath(), maxDepth);
            List<Neo4jMethodNodeRepository.GraphEdgeResult> traversalEdges =
                getCallChainEdges(ep.getEntryKey(), ep.getProjectPath(), maxDepth);

            if (!traversalNodes.isEmpty()) {
                results.add(buildChainViewFromNeo4j(traversalNodes, traversalEdges,
                    ep.getEntryKey(), ep.getProjectPath()));
            }
        }

        return ApiResponse.success(results);
    }

    /**
     * 反向查询：哪些入口会调用指定方法
     * 使用 Neo4j 反向图遍历
     */
    @GetMapping("/call-chain/affecting")
    public ApiResponse<List<Map<String, Object>>> getCallChainsAffecting(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        List<Map<String, Object>> results = new ArrayList<>();

        log.info("[KG Affecting] Query: className={}, methodName={}, projectPaths={}", className, methodName, paths);

        List<MethodNode> nodes = neo4jMethodNodeRepository.findByProjectPathsAndClassName(paths, className);

        for (MethodNode node : nodes) {
            if (node.getMethodName().equals(methodName)) {
                // dispatch 边已物化为 CALLS，findEntryPointsCallingMethodByPaths
                // 通过 (entry)-[:CALLS*]->(target) 自动穿透 FEIGN_BRIDGE 边，无需额外反向查找
                List<Neo4jMethodNodeRepository.EntryPointInfo> callingEntries =
                    neo4jMethodNodeRepository.findEntryPointsCallingMethodByPaths(node.getNodeId(), paths);

                int maxDepth = 50;
                for (Neo4jMethodNodeRepository.EntryPointInfo entry : callingEntries) {
                    List<Neo4jMethodNodeRepository.GraphTraversalResult> traversalNodes =
                        getCallChainNodes(entry.entryKey(), node.getProjectPath(), maxDepth);
                    List<Neo4jMethodNodeRepository.GraphEdgeResult> traversalEdges =
                        getCallChainEdges(entry.entryKey(), node.getProjectPath(), maxDepth);

                    if (!traversalNodes.isEmpty()) {
                        results.add(buildChainViewFromNeo4j(traversalNodes, traversalEdges,
                            entry.entryKey(), node.getProjectPath()));
                    }
                }
            }
        }

        log.info("[KG Affecting] Returning {} results", results.size());
        return ApiResponse.success(results);
    }

    /**
     * 查询接口的所有实现类
     */
    @GetMapping("/implementations")
    public ApiResponse<List<String>> getImplementations(
            @RequestParam String interfaceName,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        List<String> implementations = neo4jMethodNodeRepository.findImplementationsByInterface(interfaceName, paths);
        return ApiResponse.success(implementations);
    }

    // ============================================================
    // 新增 API：向下调用链查询、DAG图数据、环检测
    // ============================================================

    /**
     * 向下调用链查询
     * GET /api/knowledge-graph/call-chain/downstream
     * 参数: nodeId (必填), projectPath (必填), maxDepth (可选，默认10)
     */
    @GetMapping("/call-chain/downstream")
    public ApiResponse<CallChainGraphResponse> getDownstreamCallChain(
            @RequestParam String nodeId,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        String resolvedPath = paths.get(0);

        log.info("[KG Downstream] Query: nodeId={}, projectPath={}, maxDepth={}", nodeId, resolvedPath, maxDepth);

        // 查找起始节点 - 使用 Neo4j
        Optional<MethodNode> startNodeOpt = neo4jMethodNodeRepository.findByNodeId(nodeId);
        if (startNodeOpt.isEmpty()) {
            return ApiResponse.error(404, "未找到方法节点: " + nodeId);
        }

        MethodNode startNode = startNodeOpt.get();

        // 构建调用链图
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> visitedNodes = new HashSet<>();
        Set<String> nodesInCycle = new HashSet<>();
        List<CallCycleInfo> cycles = new ArrayList<>();

        // 递归遍历调用链
        buildDownstreamGraph(nodeId, resolvedPath, 0, maxDepth, visitedNodes, nodes, edges, nodesInCycle, cycles);

        // 构建响应
        CallChainGraphResponse response = CallChainGraphResponse.builder()
            .entryId(nodeId)
            .entryType("METHOD")
            .entryKey(startNode.getClassName() + "." + startNode.getMethodName())
            .maxDepth(nodes.stream().mapToInt(n -> n.getDepth() != null ? n.getDepth() : 0).max().orElse(0))
            .totalNodes(nodes.size())
            .nodes(nodes)
            .edges(edges)
            .cycles(cycles)
            .cycleCount(cycles.size())
            .nodesInCycle(nodesInCycle)
            .build();

        return ApiResponse.success(response);
    }

    /**
     * DAG图数据查询
     * GET /api/knowledge-graph/call-chain/graph
     * 使用Neo4j原生图遍历，性能优化
     */
    @GetMapping("/call-chain/graph")
    public ApiResponse<CallChainGraphResponse> getCallChainGraph(
            @RequestParam String entryKey,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(defaultValue = "true") boolean includeCycles) {
        long startTime = System.currentTimeMillis();
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        String resolvedPath = paths.get(0);

        log.info("[KG Graph] Query: entryKey={}, projectPath={}, includeCycles={}", entryKey, resolvedPath, includeCycles);

        // 使用Neo4j原生图遍历（单次查询获取所有节点）
        int maxDepth = 50; // 可配置的最大深度

        // 使用原生 Neo4j Driver 执行查询（变长路径不支持参数绑定）
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, GraphNode> nodeMap = new HashMap<>();

        try (Session session = neo4jDriver.session()) {
            // 首先诊断：检查EntryPoint是否存在
            var diagResult = session.run(
                "MATCH (ep:EntryPoint) WHERE ep.entryKey = $entryKey RETURN ep.entryKey, ep.projectPath, ep.methodNodeId LIMIT 5",
                Map.of("entryKey", entryKey)
            );
            log.info("[KG Graph] Diagnostics - EntryPoints with entryKey={}", entryKey);
            boolean foundEp = false;
            while (diagResult.hasNext()) {
                var rec = diagResult.next();
                log.info("[KG Graph] Found EntryPoint: projectPath={}, methodNodeId={}",
                    rec.get("projectPath").asString(),
                    rec.get("methodNodeId").asString(""));
                foundEp = true;
            }
            if (!foundEp) {
                // 直接列出数据库中所有 EntryPoint 的 entryKey 和 projectPath
                var allEpResult = session.run(
                    "MATCH (ep:EntryPoint) RETURN ep.entryKey as entryKey, ep.projectPath as projectPath LIMIT 10"
                );
                log.info("[KG Graph] === All EntryPoints in DB (first 10) ===");
                Set<String> dbProjectPaths = new HashSet<>();
                while (allEpResult.hasNext()) {
                    var rec = allEpResult.next();
                    String dbPath = rec.get("projectPath").asString("");
                    dbProjectPaths.add(dbPath);
                    log.info("[KG Graph]   entryKey='{}', projectPath='{}'",
                        rec.get("entryKey").asString(""),
                        dbPath);
                }

                // 统计总数
                var countResult = session.run("MATCH (ep:EntryPoint) RETURN count(ep) as total");
                if (countResult.hasNext()) {
                    log.info("[KG Graph] Total EntryPoint count: {}", countResult.next().get("total").asLong());
                }

                log.warn("[KG Graph] === PATH MISMATCH ===");
                log.warn("[KG Graph] Requested projectPath: '{}'", resolvedPath);
                log.warn("[KG Graph] DB has projectPaths: {}", dbProjectPaths);
                log.warn("[KG Graph] Please regenerate knowledge graph with the correct project path!");
            }

            // 查询节点 - 使用聚合避免重复（每个节点只取最小深度）
            String nodeQuery = """
                MATCH (ep:EntryPoint {entryKey: $entryKey, projectPath: $projectPath})
                WITH ep.methodNodeId as entryMethodId
                MATCH (entry:Method {nodeId: entryMethodId})
                MATCH path = (entry)-[:CALLS*0..%d]->(m:Method)
                WITH m, min(length(path)) as depth
                RETURN m.nodeId as nodeId, m.className as className,
                       m.methodName as methodName, m.signature as signature,
                       m.filePath as filePath, m.startLine as startLine,
                       m.description as description, depth
                ORDER BY depth, nodeId
                """.formatted(maxDepth);

            var nodeResult = session.run(nodeQuery, Map.of(
                "entryKey", entryKey,
                "projectPath", resolvedPath
            ));

            while (nodeResult.hasNext()) {
                Record record = nodeResult.next();
                String nodeId = record.get("nodeId").asString();
                // 避免重复添加
                if (nodeMap.containsKey(nodeId)) {
                    continue;
                }
                GraphNode graphNode = GraphNode.builder()
                    .id(nodeId)
                    .name(record.get("methodName").asString(""))
                    .className(record.get("className").asString(""))
                    .depth(record.get("depth").asInt(0))  // 临时深度，后面会重新计算
                    .inCycle(false)
                    .signature(record.get("signature").asString(""))
                    .filePath(record.get("filePath").asString(""))
                    .startLine(record.get("startLine").asInt(0))
                    .description(record.get("description").isNull() ? null : record.get("description").asString())
                    .build();
                nodes.add(graphNode);
                nodeMap.put(nodeId, graphNode);
            }

            if (nodes.isEmpty()) {
                return ApiResponse.error(404, "未找到入口: " + entryKey);
            }

            // 查询边 - 使用 DISTINCT 去重
            String edgeQuery = """
                MATCH (ep:EntryPoint {entryKey: $entryKey, projectPath: $projectPath})
                WITH ep.methodNodeId as entryMethodId
                MATCH (entry:Method {nodeId: entryMethodId})
                MATCH path = (entry)-[:CALLS*1..%d]->(method:Method)
                UNWIND relationships(path) as r
                RETURN DISTINCT startNode(r).nodeId as sourceId,
                       endNode(r).nodeId as targetId,
                       r.callType as callType, r.callLine as callLine
                """.formatted(maxDepth);

            var edgeResult = session.run(edgeQuery, Map.of(
                "entryKey", entryKey,
                "projectPath", resolvedPath
            ));

            while (edgeResult.hasNext()) {
                Record record = edgeResult.next();
                String sourceId = record.get("sourceId").asString();
                String targetId = record.get("targetId").asString();
                if (sourceId != null && targetId != null) {
                    GraphEdge edge = GraphEdge.builder()
                        .source(sourceId)
                        .target(targetId)
                        .callType(record.get("callType").asString("DIRECT"))
                        .callLine(record.get("callLine").asInt(0))
                        .isCycleEdge(false)
                        .build();
                    edges.add(edge);
                }
            }
        }

        // 使用 BFS 重新计算深度
        Map<String, Integer> correctDepth = new HashMap<>();
        if (!nodes.isEmpty()) {
            // 找到入口节点（原始深度为0的节点）
            String entryNodeId = nodes.get(0).getId();
            for (GraphNode node : nodes) {
                if (node.getDepth() == 0) {
                    entryNodeId = node.getId();
                    break;
                }
            }

            // 构建邻接表
            Map<String, List<String>> adjacencyList = new HashMap<>();
            for (GraphEdge edge : edges) {
                adjacencyList.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
            }

            // BFS 计算深度
            java.util.Queue<String> queue = new java.util.LinkedList<>();
            queue.offer(entryNodeId);
            correctDepth.put(entryNodeId, 0);

            while (!queue.isEmpty()) {
                String current = queue.poll();
                int currentDepth = correctDepth.get(current);

                List<String> callees = adjacencyList.get(current);
                if (callees != null) {
                    for (String callee : callees) {
                        if (!correctDepth.containsKey(callee)) {
                            correctDepth.put(callee, currentDepth + 1);
                            queue.offer(callee);
                        }
                    }
                }
            }

            // 更新节点的深度
            for (GraphNode node : nodes) {
                Integer depth = correctDepth.get(node.getId());
                if (depth != null) {
                    node.setDepth(depth);
                }
            }

            // 统计深度分布
            Map<Integer, Long> depthDist = nodes.stream()
                .collect(java.util.stream.Collectors.groupingBy(GraphNode::getDepth, java.util.stream.Collectors.counting()));
            log.info("[KG Graph] BFS depths: total={}, distribution={}", correctDepth.size(), depthDist);
        }

        // 检测环
        Set<String> nodesInCycle = new HashSet<>();
        List<CallCycleInfo> cycles = new ArrayList<>();
        if (includeCycles) {
            detectCyclesInGraph(nodes, edges, cycles, nodesInCycle);
        }

        long costTime = System.currentTimeMillis() - startTime;
        log.info("[KG Graph] Complete: nodes={}, edges={}, cycles={}, cost={}ms (native Neo4j traversal)",
                nodes.size(), edges.size(), cycles.size(), costTime);

        // 构建响应
        GraphNode firstNode = nodes.isEmpty() ? null : nodes.get(0);
        CallChainGraphResponse response = CallChainGraphResponse.builder()
            .entryId(firstNode != null ? firstNode.getId() : null)
            .entryType("HTTP") // 从EntryPoint获取
            .entryKey(entryKey)
            .maxDepth(nodes.stream().mapToInt(GraphNode::getDepth).max().orElse(0))
            .totalNodes(nodes.size())
            .nodes(nodes)
            .edges(edges)
            .cycles(cycles)
            .cycleCount(cycles.size())
            .nodesInCycle(nodesInCycle)
            .build();

        return ApiResponse.success(response);
    }

    /**
     * 环检测
     * GET /api/knowledge-graph/cycles/detect
     * 参数: projectPath (必填), entryKey (可选), nodeId (可选)
     */
    @GetMapping("/cycles/detect")
    public ApiResponse<Map<String, Object>> detectCycles(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(required = false) String entryKey,
            @RequestParam(required = false) String nodeId) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        String resolvedPath = paths.get(0);

        log.info("[KG Cycles] Detect: projectPath={}, entryKey={}, nodeId={}", resolvedPath, entryKey, nodeId);

        List<Neo4jMethodNodeRepository.CallRelationWithNodes> relations;
        Set<String> targetNodes = new HashSet<>();

        // 根据过滤条件获取调用关系 - 使用 Neo4j
        if (entryKey != null && !entryKey.isEmpty()) {
            // 根据入口Key过滤 - 使用 Neo4j 图遍历获取节点
            int maxDepth = 50;
            List<Neo4jMethodNodeRepository.GraphTraversalResult> traversalNodes =
                getCallChainNodes(entryKey, resolvedPath, maxDepth);
            Set<String> entryNodeIds = traversalNodes.stream()
                .map(Neo4jMethodNodeRepository.GraphTraversalResult::nodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            targetNodes.addAll(entryNodeIds);
            relations = neo4jMethodNodeRepository.findAllCallRelationsByProjectPath(resolvedPath).stream()
                .filter(r -> entryNodeIds.contains(r.callerId()) || entryNodeIds.contains(r.calleeId()))
                .collect(Collectors.toList());
        } else if (nodeId != null && !nodeId.isEmpty()) {
            // 根据节点ID过滤
            targetNodes.add(nodeId);
            List<Neo4jMethodNodeRepository.CalleeWithRelation> outgoing = neo4jMethodNodeRepository.findCalleesWithRelation(nodeId);
            List<Neo4jMethodNodeRepository.CallerWithRelation> incoming = neo4jMethodNodeRepository.findCallersWithRelation(nodeId);
            relations = new ArrayList<>();
            // 转换为 CallRelationWithNodes
            for (Neo4jMethodNodeRepository.CalleeWithRelation r : outgoing) {
                relations.add(new Neo4jMethodNodeRepository.CallRelationWithNodes(
                    nodeId, null, null,
                    r.calleeId(), r.calleeClassName(), r.calleeMethodName(),
                    r.callType(), r.callLine(), r.bridgeType(), r.sqlId(), r.targetService(), r.targetEndpoint()
                ));
                targetNodes.add(r.calleeId());
            }
            for (Neo4jMethodNodeRepository.CallerWithRelation r : incoming) {
                relations.add(new Neo4jMethodNodeRepository.CallRelationWithNodes(
                    r.callerId(), r.callerClassName(), r.callerMethodName(),
                    nodeId, null, null,
                    r.callType(), r.callLine(), r.bridgeType(), r.sqlId(), r.targetService(), r.targetEndpoint()
                ));
                targetNodes.add(r.callerId());
            }
        } else {
            // 获取项目所有调用关系 - 使用 Neo4j
            relations = neo4jMethodNodeRepository.findAllCallRelationsByProjectPath(resolvedPath);
        }

        // 构建图并检测环
        List<CallCycleInfo> cycles = new ArrayList<>();
        Set<String> affectedMethods = new HashSet<>();

        detectCyclesFromNeo4jRelations(relations, cycles, affectedMethods);

        Map<String, Object> result = new HashMap<>();
        result.put("totalCycles", cycles.size());
        result.put("cycles", cycles);
        result.put("affectedMethods", new ArrayList<>(affectedMethods));

        return ApiResponse.success(result);
    }

    // ============================================================
    // 私有辅助方法
    // ============================================================

    /**
     * 递归构建向下调用链图
     */
    private void buildDownstreamGraph(String nodeId, String projectPath, int currentDepth, int maxDepth,
                                      Set<String> visitedNodes, List<GraphNode> nodes, List<GraphEdge> edges,
                                      Set<String> nodesInCycle, List<CallCycleInfo> cycles) {
        if (currentDepth > maxDepth || visitedNodes.contains(nodeId)) {
            // 检测到环
            if (visitedNodes.contains(nodeId)) {
                nodesInCycle.add(nodeId);
            }
            return;
        }

        visitedNodes.add(nodeId);

        // 获取方法节点信息 - 使用 Neo4j
        Optional<MethodNode> methodOpt = neo4jMethodNodeRepository.findByNodeId(nodeId);
        if (methodOpt.isEmpty()) {
            return;
        }

        MethodNode method = methodOpt.get();

        // 添加节点
        GraphNode graphNode = GraphNode.builder()
            .id(nodeId)
            .name(method.getMethodName())
            .className(method.getClassName())
            .depth(currentDepth)
            .inCycle(nodesInCycle.contains(nodeId))
            .signature(method.getSignature())
            .filePath(method.getFilePath())
            .startLine(method.getStartLine())
            .description(method.getDescription())
            .build();
        nodes.add(graphNode);

        // 获取调用关系 - 使用 Neo4j
        // dispatch 边（IMPL_DISPATCH / FEIGN_BRIDGE）已在 KG 生成阶段物化为 CALLS 边，
        // 所以 findCalleesWithRelation 直接返回所有下游关系，无需 IMPLEMENTS fallback。
        List<Neo4jMethodNodeRepository.CalleeWithRelation> relations = neo4jMethodNodeRepository.findCalleesWithRelation(nodeId);

        for (Neo4jMethodNodeRepository.CalleeWithRelation relation : relations) {
            // 添加边
            GraphEdge edge = GraphEdge.builder()
                .source(nodeId)
                .target(relation.calleeId())
                .callType(relation.callType())
                .callLine(relation.callLine())
                .isCycleEdge(false)
                .build();
            edges.add(edge);

            // 递归处理被调用方
            buildDownstreamGraph(relation.calleeId(), projectPath, currentDepth + 1, maxDepth,
                visitedNodes, nodes, edges, nodesInCycle, cycles);
        }
    }

    /**
     * 递归构建上游调用图（反向遍历 CALLS 边）
     * 镜像 buildDownstreamGraph，方向相反：从 nodeId 向上追溯调用者
     */
    private void buildUpstreamGraph(String nodeId, String projectPath, int currentDepth, int maxDepth,
                                    Set<String> visitedNodes, List<GraphNode> nodes, List<GraphEdge> edges) {
        if (currentDepth > maxDepth || visitedNodes.contains(nodeId)) {
            return;
        }

        visitedNodes.add(nodeId);

        Optional<MethodNode> methodOpt = neo4jMethodNodeRepository.findByNodeId(nodeId);
        if (methodOpt.isEmpty()) {
            return;
        }

        MethodNode method = methodOpt.get();

        // 上游节点深度用负值表示（bridge 点为 0，上游为 -1, -2, ...）
        GraphNode graphNode = GraphNode.builder()
            .id(nodeId)
            .name(method.getMethodName())
            .className(method.getClassName())
            .depth(-currentDepth)
            .inCycle(false)
            .signature(method.getSignature())
            .filePath(method.getFilePath())
            .startLine(method.getStartLine())
            .description(method.getDescription())
            .build();
        nodes.add(graphNode);

        // 向上追溯：查询谁调用了当前节点
        List<Neo4jMethodNodeRepository.CallerWithRelation> callers =
            neo4jMethodNodeRepository.findCallersWithRelation(nodeId);

        for (Neo4jMethodNodeRepository.CallerWithRelation caller : callers) {
            // 边方向仍然保持 caller → callee（正向）
            GraphEdge edge = GraphEdge.builder()
                .source(caller.callerId())
                .target(nodeId)
                .callType(caller.callType())
                .callLine(caller.callLine())
                .isCycleEdge(false)
                .build();
            edges.add(edge);

            // 递归向上
            buildUpstreamGraph(caller.callerId(), projectPath, currentDepth + 1, maxDepth,
                visitedNodes, nodes, edges);
        }
    }

    /**
     * 从方法节点ID构建GraphNode
     */
    private GraphNode buildGraphNodeFromMethod(String nodeId, int depth, String projectPath) {
        Optional<MethodNode> methodOpt = neo4jMethodNodeRepository.findByNodeId(nodeId);
        if (methodOpt.isEmpty()) {
            return null;
        }

        MethodNode method = methodOpt.get();
        return GraphNode.builder()
            .id(nodeId)
            .name(method.getMethodName())
            .className(method.getClassName())
            .depth(depth)
            .inCycle(false)
            .signature(method.getSignature())
            .filePath(method.getFilePath())
            .startLine(method.getStartLine())
            .description(method.getDescription())
            .build();
    }

    /**
     * 从缓存构建GraphNode（高性能版本）
     * 避免对每个节点单独查询Neo4j
     */
    private GraphNode buildGraphNodeFromCache(String nodeId, int depth, Map<String, MethodNode> methodMap) {
        MethodNode method = methodMap.get(nodeId);
        if (method == null) {
            return null;
        }

        return GraphNode.builder()
            .id(nodeId)
            .name(method.getMethodName())
            .className(method.getClassName())
            .depth(depth)
            .inCycle(false)
            .signature(method.getSignature())
            .filePath(method.getFilePath())
            .startLine(method.getStartLine())
            .description(method.getDescription())
            .build();
    }

    /**
     * 在图中检测环
     */
    private void detectCyclesInGraph(List<GraphNode> nodes, List<GraphEdge> edges,
                                     List<CallCycleInfo> cycles, Set<String> nodesInCycle) {
        // 构建邻接表
        Map<String, List<String>> adjList = new HashMap<>();
        for (GraphNode node : nodes) {
            adjList.putIfAbsent(node.getId(), new ArrayList<>());
        }
        for (GraphEdge edge : edges) {
            adjList.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
        }

        // 使用DFS检测环
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        List<String> currentPath = new ArrayList<>();

        for (GraphNode node : nodes) {
            if (!visited.contains(node.getId())) {
                dfsDetectCycle(node.getId(), adjList, visited, recursionStack, currentPath, cycles, nodesInCycle);
            }
        }
    }

    /**
     * DFS检测环
     */
    private void dfsDetectCycle(String node, Map<String, List<String>> adjList,
                                Set<String> visited, Set<String> recursionStack,
                                List<String> currentPath, List<CallCycleInfo> cycles,
                                Set<String> nodesInCycle) {
        visited.add(node);
        recursionStack.add(node);
        currentPath.add(node);

        List<String> neighbors = adjList.getOrDefault(node, Collections.emptyList());
        for (String neighbor : neighbors) {
            if (recursionStack.contains(neighbor)) {
                // 发现环
                int cycleStartIndex = currentPath.indexOf(neighbor);
                List<String> cyclePath = new ArrayList<>(currentPath.subList(cycleStartIndex, currentPath.size()));
                cyclePath.add(neighbor); // 闭合环

                CallCycleInfo cycleInfo = CallCycleInfo.builder()
                    .cycleId("cycle_" + System.nanoTime())
                    .cyclePath(cyclePath)
                    .startNodeId(neighbor)
                    .cycleLength(cyclePath.size())
                    .build();
                cycles.add(cycleInfo);

                // 标记环中节点
                nodesInCycle.addAll(cyclePath);
            } else if (!visited.contains(neighbor)) {
                dfsDetectCycle(neighbor, adjList, visited, recursionStack, currentPath, cycles, nodesInCycle);
            }
        }

        currentPath.remove(currentPath.size() - 1);
        recursionStack.remove(node);
    }

    /**
     * 从 Neo4j 调用关系中检测环
     */
    private void detectCyclesFromNeo4jRelations(List<Neo4jMethodNodeRepository.CallRelationWithNodes> relations,
                                                List<CallCycleInfo> cycles, Set<String> affectedMethods) {
        // 构建邻接表
        Map<String, List<String>> adjList = new HashMap<>();
        for (Neo4jMethodNodeRepository.CallRelationWithNodes relation : relations) {
            adjList.computeIfAbsent(relation.callerId(), k -> new ArrayList<>())
                .add(relation.calleeId());
        }

        // 使用DFS检测环
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        List<String> currentPath = new ArrayList<>();

        for (String node : adjList.keySet()) {
            if (!visited.contains(node)) {
                dfsDetectCycle(node, adjList, visited, recursionStack, currentPath, cycles, affectedMethods);
            }
        }
    }

    /**
     * 查询类实现的所有接口
     */
    @GetMapping("/interfaces")
    public ApiResponse<List<String>> getInterfaces(
            @RequestParam String className,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        List<String> interfaces;
        try (Session neoSession = neo4jDriver.session()) {
            interfaces = neoSession.run(
                "MATCH (c)-[:IMPLEMENTS]->(i) WHERE c.name = $className AND c.projectPath IN $paths RETURN DISTINCT i.name AS name",
                Map.of("className", className, "paths", paths)
            ).list(record -> record.get("name").asString());
        }
        return ApiResponse.success(interfaces);
    }

    /**
     * 查询方法详情（包含方法体）
     */
    @GetMapping("/method/detail")
    public ApiResponse<Map<String, Object>> getMethodDetail(
            @RequestParam String nodeId,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        return neo4jMethodNodeRepository.findByNodeId(nodeId)
            .map(node -> {
                Map<String, Object> result = new HashMap<>();
                result.put("nodeId", node.getNodeId());
                result.put("className", node.getClassName());
                result.put("methodName", node.getMethodName());
                result.put("signature", node.getSignature());
                result.put("filePath", node.getFilePath());
                result.put("startLine", node.getStartLine());
                result.put("endLine", node.getEndLine());
                result.put("complexity", node.getComplexity());
                result.put("thrownExceptions", node.getThrownExceptions());
                result.put("caughtExceptions", node.getCaughtExceptions());
                result.put("methodBody", node.getMethodBody());
                result.put("projectPath", node.getProjectPath());
                result.put("description", node.getDescription());
                return ApiResponse.success(result);
            })
            .orElse(ApiResponse.error(404, "未找到方法: " + nodeId));
    }

    /**
     * 按类名查询所有方法
     */
    @GetMapping("/method/by-class")
    public ApiResponse<List<Map<String, Object>>> getMethodsByClass(
            @RequestParam String className,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {

        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        List<MethodNode> nodes = neo4jMethodNodeRepository.findByProjectPathsAndClassName(paths, className);
        for (MethodNode node : nodes) {
            Map<String, Object> map = new HashMap<>();
            map.put("nodeId", node.getNodeId());
            map.put("className", node.getClassName());
            map.put("methodName", node.getMethodName());
            map.put("signature", node.getSignature());
            map.put("startLine", node.getStartLine());
            map.put("endLine", node.getEndLine());
            map.put("filePath", node.getFilePath());
            map.put("complexity", node.getComplexity());
            map.put("description", node.getDescription());
            results.add(map);
        }
        return ApiResponse.success(results);
    }

    // ============================================================
    // MyBatis 相关 API
    // ============================================================

    /**
     * 扫描项目的 MyBatis XML 文件
     * POST /api/knowledge-graph/mybatis/scan
     */
    @PostMapping("/mybatis/scan")
    public ApiResponse<Map<String, Object>> scanMyBatisXml(@RequestBody Map<String, String> request) {
        String projectPath = request.get("projectPath");
        if (projectPath == null || projectPath.isEmpty()) {
            throw new IllegalArgumentException("项目路径不能为空");
        }

        projectPath = normalizePath(projectPath);

        try {
            log.info("[MyBatis Scan] Starting scan for project: {}", projectPath);

            // 删除旧数据（Neo4j）
            neo4jSqlNodeRepository.deleteExecutesSqlRelationsByProjectPath(projectPath);
            neo4jSqlNodeRepository.deleteByProjectPath(projectPath);

            // 执行扫描（返回 Neo4j SqlNode 列表）
            MyBatisXmlScanner.Neo4jScanResult result = myBatisXmlScanner.scanProjectForNeo4j(projectPath);

            // 保存到 Neo4j
            if (!result.getSqlNodes().isEmpty()) {
                neo4jSqlNodeRepository.saveAll(result.getSqlNodes());
                log.info("[MyBatis Scan] Saved {} SQL nodes to Neo4j", result.getSqlNodes().size());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("mapperCount", result.getMapperCount());
            response.put("sqlCount", result.getSqlCount());
            response.put("errors", result.getErrors());

            log.info("[MyBatis Scan] Completed: {} mappers, {} SQL statements",
                    result.getMapperCount(), result.getSqlCount());
            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("[MyBatis Scan] Failed to scan project: {}", projectPath, e);
            return ApiResponse.error(500, "扫描失败: " + e.getMessage());
        }
    }

    /**
     * 获取 Mapper 列表
     * GET /api/knowledge-graph/mybatis/mappers
     * 重构版：从 Neo4j 查询
     */
    @GetMapping("/mybatis/mappers")
    public ApiResponse<List<String>> getMyBatisMappers(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        try {
            List<String> mapperInterfaces = neo4jSqlNodeRepository.findDistinctMapperInterfacesByProjectPaths(paths);
            return ApiResponse.success(mapperInterfaces);
        } catch (Exception e) {
            log.error("[MyBatis Mappers] Failed to get mappers", e);
            throw new RuntimeException("查询 Mapper 列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 SQL 语句列表
     * GET /api/knowledge-graph/mybatis/sql
     * 重构版：从 Neo4j 查询
     */
    @GetMapping("/mybatis/sql")
    public ApiResponse<List<SqlNode>> getMyBatisSqlList(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(required = false) String mapperInterface,
            @RequestParam(required = false) String statementType) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        try {
            List<SqlNode> sqlNodes;
            if (mapperInterface != null && !mapperInterface.isEmpty()) {
                sqlNodes = neo4jSqlNodeRepository.findByMapperInterfaceAndProjectPaths(paths, mapperInterface);
            } else if (statementType != null && !statementType.isEmpty()) {
                sqlNodes = neo4jSqlNodeRepository.findByStatementTypeAndProjectPaths(paths, statementType);
            } else {
                sqlNodes = neo4jSqlNodeRepository.findByProjectPaths(paths);
            }
            return ApiResponse.success(sqlNodes);
        } catch (Exception e) {
            log.error("[MyBatis SQL] Failed to get SQL list", e);
            throw new RuntimeException("查询 SQL 列表失败: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // 桥接关系查询 API
    // ============================================================

    /**
     * 获取方法的所有桥接关系
     * GET /api/knowledge-graph/call-chain/{nodeId}/bridges
     * 查询指定方法涉及的所有桥接调用（Mapper/MQ/Feign/HTTP等）
     */
    @GetMapping("/call-chain/{nodeId}/bridges")
    public ApiResponse<List<BridgeRelation>> getMethodBridges(
            @PathVariable String nodeId,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        String resolvedPath = paths.isEmpty() ? "" : paths.get(0);

        log.info("[KG Bridges] Query bridges for nodeId: {}, projectPath: {}", nodeId, resolvedPath);

        // 查找方法节点 - 使用 Neo4j
        Optional<MethodNode> methodOpt = neo4jMethodNodeRepository.findByNodeId(nodeId);
        if (methodOpt.isEmpty()) {
            return ApiResponse.error(404, "未找到方法节点: " + nodeId);
        }

        MethodNode method = methodOpt.get();
        List<BridgeRelation> bridges = new ArrayList<>();

        // 获取该方法作为调用方的所有调用关系 - 使用 Neo4j
        List<Neo4jMethodNodeRepository.CalleeWithRelation> outgoingCalls = neo4jMethodNodeRepository.findCalleesWithRelation(nodeId);

        for (Neo4jMethodNodeRepository.CalleeWithRelation relation : outgoingCalls) {
            // 只处理桥接类型的调用
            if (relation.bridgeType() != null && !"DIRECT".equals(relation.bridgeType())) {
                BridgeRelation bridge = buildBridgeRelationFromNeo4j(relation, method, resolvedPath);
                bridges.add(bridge);
            }
        }

        log.info("[KG Bridges] Found {} bridges for nodeId: {}", bridges.size(), nodeId);
        return ApiResponse.success(bridges);
    }

    /**
     * 获取 Mapper 的 SQL 信息
     * GET /api/knowledge-graph/mapper/{mapperInterface}/sql
     * 查询指定 Mapper 接口的所有 SQL 语句
     */
    @GetMapping("/mapper/{mapperInterface}/sql")
    public ApiResponse<List<SqlNode>> getMapperSql(
            @PathVariable String mapperInterface,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        String resolvedPath = paths.get(0);

        log.info("[KG Mapper SQL] Query SQL for mapper: {}, projectPath: {}", mapperInterface, resolvedPath);

        try {
            // URL 解码 mapperInterface（可能包含包路径）
            String decodedMapperInterface = java.net.URLDecoder.decode(mapperInterface, "UTF-8");

            List<SqlNode> sqlNodes = neo4jSqlNodeRepository.findByMapperInterfaceAndProjectPath(
                    decodedMapperInterface, resolvedPath);

            log.info("[KG Mapper SQL] Found {} SQL statements for mapper: {}", sqlNodes.size(), decodedMapperInterface);
            return ApiResponse.success(sqlNodes);
        } catch (Exception e) {
            log.error("[KG Mapper SQL] Failed to query SQL for mapper: {}", mapperInterface, e);
            throw new RuntimeException("查询 Mapper SQL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 FeignClient 完整调用链
     * GET /api/knowledge-graph/feign/{serviceName}/call-chain
     * 从 Feign bridge 两端分别展开：上游追溯到入口点，下游追溯到叶子节点
     * 返回完整的 CallChainGraphResponse（可直接用 ChainChart 渲染）
     */
    @GetMapping("/feign/{serviceName}/call-chain")
    public ApiResponse<CallChainGraphResponse> getFeignCallChain(
            @PathVariable String serviceName,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        String resolvedPath = paths.get(0);

        log.info("[KG Feign] Query full call chain for service: {}", serviceName);

        try {
            String decodedServiceName = java.net.URLDecoder.decode(serviceName, "UTF-8");

            // 1. 找到所有 FEIGN bridge 边
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> feignCalls =
                neo4jMethodNodeRepository.findByBridgeTypeAndProjectPaths(paths, "FEIGN");

            // 按 serviceName 过滤
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> matchedBridges = feignCalls.stream()
                .filter(call -> decodedServiceName.equals(call.targetService()))
                .collect(Collectors.toList());

            if (matchedBridges.isEmpty()) {
                log.info("[KG Feign] No Feign bridges found for service: {}", decodedServiceName);
                return ApiResponse.success(CallChainGraphResponse.builder()
                    .entryId("")
                    .entryType("FEIGN_CLIENT")
                    .entryKey(decodedServiceName)
                    .maxDepth(0)
                    .totalNodes(0)
                    .nodes(new ArrayList<>())
                    .edges(new ArrayList<>())
                    .cycles(new ArrayList<>())
                    .cycleCount(0)
                    .nodesInCycle(new HashSet<>())
                    .build());
            }

            // 2. 合并所有 bridge 的上游 + 下游图
            List<GraphNode> allNodes = new ArrayList<>();
            List<GraphEdge> allEdges = new ArrayList<>();
            Set<String> globalVisited = new HashSet<>();
            Set<String> nodesInCycle = new HashSet<>();
            List<CallCycleInfo> cycles = new ArrayList<>();

            for (Neo4jMethodNodeRepository.CallRelationWithNodes bridge : matchedBridges) {
                // 上游：从 FeignClient 方法向上追溯
                buildUpstreamGraph(bridge.callerId(), resolvedPath, 0, maxDepth,
                    globalVisited, allNodes, allEdges);

                // 下游：从 ServiceImpl 方法向下追溯
                buildDownstreamGraph(bridge.calleeId(), resolvedPath, 0, maxDepth,
                    globalVisited, allNodes, allEdges, nodesInCycle, cycles);

                // 确保 bridge 边本身存在
                boolean bridgeEdgeExists = allEdges.stream()
                    .anyMatch(e -> e.getSource().equals(bridge.callerId()) && e.getTarget().equals(bridge.calleeId()));
                if (!bridgeEdgeExists) {
                    allEdges.add(GraphEdge.builder()
                        .source(bridge.callerId())
                        .target(bridge.calleeId())
                        .callType(bridge.callType())
                        .callLine(bridge.callLine() != null ? bridge.callLine() : 0)
                        .isCycleEdge(false)
                        .build());
                }
            }

            // 3. 重新标准化深度（找到最小深度作为偏移量，让所有深度 >= 0）
            int minDepth = allNodes.stream()
                .mapToInt(n -> n.getDepth() != null ? n.getDepth() : 0)
                .min().orElse(0);
            if (minDepth < 0) {
                for (GraphNode node : allNodes) {
                    node.setDepth((node.getDepth() != null ? node.getDepth() : 0) - minDepth);
                }
            }

            int actualMaxDepth = allNodes.stream()
                .mapToInt(n -> n.getDepth() != null ? n.getDepth() : 0)
                .max().orElse(0);

            CallChainGraphResponse response = CallChainGraphResponse.builder()
                .entryId(matchedBridges.get(0).callerId())
                .entryType("FEIGN_CLIENT")
                .entryKey(decodedServiceName)
                .maxDepth(actualMaxDepth)
                .totalNodes(allNodes.size())
                .nodes(allNodes)
                .edges(allEdges)
                .cycles(cycles)
                .cycleCount(cycles.size())
                .nodesInCycle(nodesInCycle)
                .build();

            log.info("[KG Feign] Full chain for service {}: {} nodes, {} edges, {} bridges",
                decodedServiceName, allNodes.size(), allEdges.size(), matchedBridges.size());

            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("[KG Feign] Failed to query call chain for service: {}", serviceName, e);
            return ApiResponse.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 获取 MQ 完整调用链
     * GET /api/knowledge-graph/mq/{topic}/call-chain
     * 从 MQ bridge 两端分别展开：上游追溯到入口点，下游追溯到叶子节点
     * 返回完整的 CallChainGraphResponse
     */
    @GetMapping("/mq/{topic}/call-chain")
    public ApiResponse<CallChainGraphResponse> getMQCallChain(
            @PathVariable String topic,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths,
            @RequestParam(defaultValue = "10") int maxDepth) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }
        String resolvedPath = paths.get(0);

        log.info("[KG MQ] Query full call chain for topic: {}", topic);

        try {
            String decodedTopic = java.net.URLDecoder.decode(topic, "UTF-8");

            // 1. 找到所有 MQ bridge 边
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> mqCalls =
                neo4jMethodNodeRepository.findByBridgeTypeAndProjectPaths(paths, "MQ");

            // 按 topic 过滤
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> matchedBridges = mqCalls.stream()
                .filter(call -> decodedTopic.equals(call.targetEndpoint()))
                .collect(Collectors.toList());

            if (matchedBridges.isEmpty()) {
                log.info("[KG MQ] No MQ bridges found for topic: {}", decodedTopic);
                return ApiResponse.success(CallChainGraphResponse.builder()
                    .entryId("")
                    .entryType("MQ_LISTENER")
                    .entryKey(decodedTopic)
                    .maxDepth(0)
                    .totalNodes(0)
                    .nodes(new ArrayList<>())
                    .edges(new ArrayList<>())
                    .cycles(new ArrayList<>())
                    .cycleCount(0)
                    .nodesInCycle(new HashSet<>())
                    .build());
            }

            // 2. 合并所有 bridge 的上游 + 下游图
            List<GraphNode> allNodes = new ArrayList<>();
            List<GraphEdge> allEdges = new ArrayList<>();
            Set<String> globalVisited = new HashSet<>();
            Set<String> nodesInCycle = new HashSet<>();
            List<CallCycleInfo> cycles = new ArrayList<>();

            for (Neo4jMethodNodeRepository.CallRelationWithNodes bridge : matchedBridges) {
                // 上游：从 Producer 方法向上追溯
                buildUpstreamGraph(bridge.callerId(), resolvedPath, 0, maxDepth,
                    globalVisited, allNodes, allEdges);

                // 下游：从 Consumer 方法向下追溯
                buildDownstreamGraph(bridge.calleeId(), resolvedPath, 0, maxDepth,
                    globalVisited, allNodes, allEdges, nodesInCycle, cycles);

                // 确保 bridge 边本身存在
                boolean bridgeEdgeExists = allEdges.stream()
                    .anyMatch(e -> e.getSource().equals(bridge.callerId()) && e.getTarget().equals(bridge.calleeId()));
                if (!bridgeEdgeExists) {
                    allEdges.add(GraphEdge.builder()
                        .source(bridge.callerId())
                        .target(bridge.calleeId())
                        .callType(bridge.callType())
                        .callLine(bridge.callLine() != null ? bridge.callLine() : 0)
                        .isCycleEdge(false)
                        .build());
                }
            }

            // 3. 标准化深度
            int minDepth = allNodes.stream()
                .mapToInt(n -> n.getDepth() != null ? n.getDepth() : 0)
                .min().orElse(0);
            if (minDepth < 0) {
                for (GraphNode node : allNodes) {
                    node.setDepth((node.getDepth() != null ? node.getDepth() : 0) - minDepth);
                }
            }

            int actualMaxDepth = allNodes.stream()
                .mapToInt(n -> n.getDepth() != null ? n.getDepth() : 0)
                .max().orElse(0);

            CallChainGraphResponse response = CallChainGraphResponse.builder()
                .entryId(matchedBridges.get(0).callerId())
                .entryType("MQ_LISTENER")
                .entryKey(decodedTopic)
                .maxDepth(actualMaxDepth)
                .totalNodes(allNodes.size())
                .nodes(allNodes)
                .edges(allEdges)
                .cycles(cycles)
                .cycleCount(cycles.size())
                .nodesInCycle(nodesInCycle)
                .build();

            log.info("[KG MQ] Full chain for topic {}: {} nodes, {} edges, {} bridges",
                decodedTopic, allNodes.size(), allEdges.size(), matchedBridges.size());

            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("[KG MQ] Failed to query call chain for topic: {}", topic, e);
            return ApiResponse.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 获取桥接统计信息
     * GET /api/knowledge-graph/bridge-stats
     * 获取项目中各类桥接关系的统计信息
     */
    @GetMapping("/bridge-stats")
    public ApiResponse<BridgeStats> getBridgeStats(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        log.info("[KG Bridge Stats] Query bridge stats for projects: {}", paths);

        try {
            // 获取各类统计数据 - 使用 Neo4j 批量查询
            int totalCallRelations = (int) neo4jMethodNodeRepository.countCallRelationsByProjectPaths(paths);
            int mapperCallCount = (int) neo4jMethodNodeRepository.countByBridgeTypeAndProjectPaths(paths, "MAPPER");
            int feignCallCount = (int) neo4jMethodNodeRepository.countByBridgeTypeAndProjectPaths(paths, "FEIGN");
            int httpCallCount = (int) neo4jMethodNodeRepository.countByBridgeTypeAndProjectPaths(paths, "HTTP");
            int mqCallCount = (int) neo4jMethodNodeRepository.countByBridgeTypeAndProjectPaths(paths, "MQ");
            int jpaCallCount = (int) neo4jMethodNodeRepository.countByBridgeTypeAndProjectPaths(paths, "JPA");
            int aspectCallCount = (int) neo4jMethodNodeRepository.countByBridgeTypeAndProjectPaths(paths, "ASPECT");

            long myBatisSqlCount = neo4jSqlNodeRepository.countByProjectPaths(paths);
            int myBatisMapperCount = (int) neo4jSqlNodeRepository.countDistinctMapperInterfacesByProjectPaths(paths);

            // 计算总桥接数（非 DIRECT 调用）
            int totalBridges = mapperCallCount + feignCallCount + httpCallCount + mqCallCount + jpaCallCount + aspectCallCount;

            // 跳转率计算方式变更：基于有 targetService/targetEndpoint 的调用
            int jumpableCount = feignCallCount + mqCallCount + httpCallCount;
            double jumpableRate = totalBridges > 0 ? (double) jumpableCount / totalBridges : 0.0;

            // 构建各类型统计
            Map<String, Integer> bridgeTypeCounts = new java.util.HashMap<>();
            bridgeTypeCounts.put("MAPPER", mapperCallCount);
            bridgeTypeCounts.put("FEIGN", feignCallCount);
            bridgeTypeCounts.put("HTTP", httpCallCount);
            bridgeTypeCounts.put("MQ", mqCallCount);
            bridgeTypeCounts.put("JPA", jpaCallCount);
            bridgeTypeCounts.put("ASPECT", aspectCallCount);

            // 统计外部服务调用 - 使用 Neo4j 批量查询
            Map<String, Integer> externalServiceCalls = new java.util.HashMap<>();
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> feignCalls = neo4jMethodNodeRepository.findByBridgeTypeAndProjectPaths(paths, "FEIGN");
            for (Neo4jMethodNodeRepository.CallRelationWithNodes call : feignCalls) {
                if (call.targetService() != null) {
                    externalServiceCalls.merge(call.targetService(), 1, Integer::sum);
                }
            }

            // 统计 MQ Topic 调用 - 使用 Neo4j 批量查询
            Map<String, Integer> mqTopicCalls = new java.util.HashMap<>();
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> mqCalls = neo4jMethodNodeRepository.findByBridgeTypeAndProjectPaths(paths, "MQ");
            for (Neo4jMethodNodeRepository.CallRelationWithNodes call : mqCalls) {
                if (call.targetEndpoint() != null) {
                    mqTopicCalls.merge(call.targetEndpoint(), 1, Integer::sum);
                }
            }

            BridgeStats stats = BridgeStats.builder()
                    .projectPath(String.join(",", paths))
                    .totalCallRelations(totalCallRelations)
                    .totalBridges(totalBridges)
                    .bridgeTypeCounts(bridgeTypeCounts)
                    .mapperCallCount(mapperCallCount)
                    .feignCallCount(feignCallCount)
                    .httpCallCount(httpCallCount)
                    .mqCallCount(mqCallCount)
                    .jpaCallCount(jpaCallCount)
                    .aspectCallCount(aspectCallCount)
                    .myBatisSqlCount((int) myBatisSqlCount)
                    .myBatisMapperCount(myBatisMapperCount)
                    .jumpableRate(jumpableRate)
                    .externalServiceCalls(externalServiceCalls)
                    .mqTopicCalls(mqTopicCalls)
                    .build();

            log.info("[KG Bridge Stats] Stats calculated: totalBridges={}, mapper={}, feign={}, mq={}",
                    totalBridges, mapperCallCount, feignCallCount, mqCallCount);

            return ApiResponse.success(stats);
        } catch (Exception e) {
            log.error("[KG Bridge Stats] Failed to calculate bridge stats for projects: {}", paths, e);
            return ApiResponse.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 按类型查询桥接关系列表
     * GET /api/knowledge-graph/bridges/by-type
     */
    @GetMapping("/bridges/by-type")
    public ApiResponse<List<Map<String, Object>>> getBridgesByType(
            @RequestParam String bridgeType,
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) List<String> projectPaths) {
        List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
        if (paths.isEmpty()) {
            return ApiResponse.error(400, "projectPath or projectPaths required");
        }

        log.info("[KG Bridges] Query bridges by type: {} for projects: {}", bridgeType, paths);

        try {
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> calls =
                    neo4jMethodNodeRepository.findByBridgeTypeAndProjectPaths(paths, bridgeType);

            List<Map<String, Object>> result = new ArrayList<>();
            for (Neo4jMethodNodeRepository.CallRelationWithNodes call : calls) {
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("callerClassName", call.callerClassName());
                row.put("callerMethodName", call.callerMethodName());
                row.put("calleeClassName", call.calleeClassName());
                row.put("calleeMethodName", call.calleeMethodName());
                row.put("bridgeType", call.bridgeType());
                row.put("callLine", call.callLine());
                row.put("targetService", call.targetService());
                row.put("targetEndpoint", call.targetEndpoint());
                row.put("sqlId", call.sqlId());
                result.add(row);
            }

            log.info("[KG Bridges] Found {} bridges of type: {}", result.size(), bridgeType);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("[KG Bridges] Failed to query bridges by type: {}", bridgeType, e);
            return ApiResponse.error(500, "查询桥接关系失败: " + e.getMessage());
        }
    }

    /**
     * 构建桥接关系对象
     */
    /**
     * 从 Neo4j 关系数据构建 BridgeRelation
     */
    private BridgeRelation buildBridgeRelationFromNeo4j(Neo4jMethodNodeRepository.CalleeWithRelation relation,
                                                         MethodNode sourceMethod, String projectPath) {
        BridgeRelation.BridgeRelationBuilder builder = BridgeRelation.builder()
                .sourceNodeId(sourceMethod.getNodeId())
                .sourceClassName(sourceMethod.getClassName())
                .sourceMethodName(sourceMethod.getMethodName())
                .bridgeType(relation.bridgeType())
                .callLine(relation.callLine());

        List<BridgeRelation.BridgeTargetDetail> targetDetails = new ArrayList<>();

        // 获取被调用方法信息
        neo4jMethodNodeRepository.findByNodeId(relation.calleeId()).ifPresent(callee -> {
            BridgeRelation.BridgeTargetDetail detail = BridgeRelation.BridgeTargetDetail.builder()
                    .targetType("METHOD")
                    .targetNodeId(callee.getNodeId())
                    .targetClassName(callee.getClassName())
                    .targetMethodName(callee.getMethodName())
                    .build();
            targetDetails.add(detail);
        });

        builder.targetDetails(targetDetails);
        builder.jumpable(!targetDetails.isEmpty());

        return builder.build();
    }

    /**
     * 规范化路径格式（将反斜杠转换为正斜杠）
     * 确保与 Neo4j 中存储的路径格式一致
     */
    private String normalizePath(String path) {
        return com.huawei.hisi.utils.PathUtils.normalize(path);
    }

    /**
     * 使用 Neo4j Driver 执行图遍历获取调用链节点
     * 注意：变长路径不支持参数绑定，必须使用字符串拼接
     * 使用 min(length(path)) 对每个节点只保留最小深度，避免重复
     */
    private List<Neo4jMethodNodeRepository.GraphTraversalResult> getCallChainNodes(String entryKey, String projectPath, int maxDepth) {
        List<Neo4jMethodNodeRepository.GraphTraversalResult> results = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            // 使用聚合查询，每个 nodeId 只返回一次（取最小深度）
            String query = """
                MATCH (ep:EntryPoint {entryKey: $entryKey, projectPath: $projectPath})
                WITH ep.methodNodeId as entryMethodId
                MATCH (entry:Method {nodeId: entryMethodId})
                MATCH path = (entry)-[:CALLS*0..%d]->(m:Method)
                WITH m, min(length(path)) as depth
                RETURN m.nodeId as nodeId, m.className as className,
                       m.methodName as methodName, m.signature as signature,
                       m.filePath as filePath, m.startLine as startLine,
                       m.description as description, depth
                ORDER BY depth, nodeId
                """.formatted(maxDepth);

            var result = session.run(query, Map.of(
                "entryKey", entryKey,
                "projectPath", projectPath
            ));

            while (result.hasNext()) {
                Record record = result.next();
                results.add(new Neo4jMethodNodeRepository.GraphTraversalResult(
                    record.get("nodeId").asString(""),
                    record.get("className").asString(""),
                    record.get("methodName").asString(""),
                    record.get("signature").asString(""),
                    record.get("filePath").asString(""),
                    record.get("startLine").asInt(0),
                    record.get("depth").asInt(0),
                    record.get("description").isNull() ? null : record.get("description").asString()
                ));
            }
        }

        return results;
    }

    /**
     * 使用 Neo4j Driver 执行图遍历获取调用链边
     * 注意：变长路径不支持参数绑定，必须使用字符串拼接
     */
    private List<Neo4jMethodNodeRepository.GraphEdgeResult> getCallChainEdges(String entryKey, String projectPath, int maxDepth) {
        List<Neo4jMethodNodeRepository.GraphEdgeResult> results = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            String query = """
                MATCH (ep:EntryPoint {entryKey: $entryKey, projectPath: $projectPath})
                WITH ep.methodNodeId as entryMethodId
                MATCH (entry:Method {nodeId: entryMethodId})
                MATCH path = (entry)-[:CALLS*1..%d]->(method:Method)
                UNWIND relationships(path) as r
                RETURN DISTINCT startNode(r).nodeId as sourceId,
                       endNode(r).nodeId as targetId,
                       r.callType as callType, r.callLine as callLine
                """.formatted(maxDepth);

            var result = session.run(query, Map.of(
                "entryKey", entryKey,
                "projectPath", projectPath
            ));

            while (result.hasNext()) {
                Record record = result.next();
                results.add(new Neo4jMethodNodeRepository.GraphEdgeResult(
                    record.get("sourceId").asString(""),
                    record.get("targetId").asString(""),
                    record.get("callType").asString(""),
                    record.get("callLine").asInt(0)
                ));
            }
        }

        return results;
    }

    /**
     * 从 Neo4j 图遍历结果构建调用链视图
     * 返回格式兼容前端 CallChainView 接口
     */
    private Map<String, Object> buildChainViewFromNeo4j(
            List<Neo4jMethodNodeRepository.GraphTraversalResult> nodes,
            List<Neo4jMethodNodeRepository.GraphEdgeResult> edges,
            String entryKey,
            String projectPath) {

        Map<String, Object> result = new HashMap<>();
        result.put("entryKey", entryKey);
        result.put("projectPath", projectPath);

        // 构建 nodeId -> node 映射
        Map<String, Neo4jMethodNodeRepository.GraphTraversalResult> nodeMap = new HashMap<>();
        for (Neo4jMethodNodeRepository.GraphTraversalResult node : nodes) {
            nodeMap.put(node.nodeId(), node);
        }

        // 构建邻接表：caller -> list of callees
        Map<String, List<String>> adjacencyList = new HashMap<>();
        Map<String, String> callerMap = new HashMap<>();

        for (Neo4jMethodNodeRepository.GraphEdgeResult edge : edges) {
            adjacencyList.computeIfAbsent(edge.sourceId(), k -> new ArrayList<>()).add(edge.targetId());
        }

        // 从入口点开始 BFS 计算正确的深度
        // 入口点是深度0的节点（nodes中depth=0的那个）
        String entryNodeId = null;
        for (Neo4jMethodNodeRepository.GraphTraversalResult node : nodes) {
            if (node.depth() == 0) {
                entryNodeId = node.nodeId();
                break;
            }
        }

        // 使用 BFS 重新计算深度和 callerId
        Map<String, Integer> correctDepth = new HashMap<>();
        if (entryNodeId != null) {
            java.util.Queue<String> queue = new java.util.LinkedList<>();
            queue.offer(entryNodeId);
            correctDepth.put(entryNodeId, 0);

            while (!queue.isEmpty()) {
                String current = queue.poll();
                int currentDepth = correctDepth.get(current);

                List<String> callees = adjacencyList.get(current);
                if (callees != null) {
                    for (String callee : callees) {
                        if (!correctDepth.containsKey(callee)) {
                            correctDepth.put(callee, currentDepth + 1);
                            callerMap.put(callee, current);
                            queue.offer(callee);
                        }
                    }
                }
            }
        }

        // 统计每个深度的节点数量
        Map<Integer, Integer> depthDistribution = new HashMap<>();
        for (Integer d : correctDepth.values()) {
            depthDistribution.merge(d, 1, Integer::sum);
        }
        log.info("[KG Build] BFS depths calculated: entryNodeId={}, total nodes={}, depth distribution={}",
            entryNodeId, correctDepth.size(), depthDistribution);

        // 查询入口点信息
        List<EntryPointNode> entryPoints = neo4jEntryPointNodeRepository
            .findByProjectPathAndEntryKey(projectPath, entryKey);
        if (!entryPoints.isEmpty()) {
            EntryPointNode ep = entryPoints.get(0);
            result.put("entryId", ep.getEntryId());
            result.put("entryType", ep.getEntryType());
        } else {
            result.put("entryId", entryKey.hashCode() + "");
            result.put("entryType", "HTTP");
        }

        // 按深度分组构建 chain（使用 BFS 计算的正确深度）
        Map<Integer, List<Map<String, Object>>> chain = new HashMap<>();
        int maxDepth = 0;
        int reachableNodes = 0;

        for (Neo4jMethodNodeRepository.GraphTraversalResult node : nodes) {
            // 使用 BFS 计算的深度，如果没有则跳过（不可达节点）
            Integer depth = correctDepth.get(node.nodeId());
            if (depth == null) {
                continue; // 跳过不可达节点
            }
            reachableNodes++;
            maxDepth = Math.max(maxDepth, depth);

            Map<String, Object> nodeData = new HashMap<>();
            nodeData.put("nodeId", node.nodeId());
            nodeData.put("className", node.className());
            nodeData.put("methodName", node.methodName());
            nodeData.put("signature", node.signature());
            nodeData.put("filePath", node.filePath());
            nodeData.put("startLine", node.startLine());
            nodeData.put("endLine", node.startLine()); // 没有 endLine，用 startLine 代替
            nodeData.put("depth", depth);
            nodeData.put("description", node.description());
            nodeData.put("callerId", callerMap.get(node.nodeId()));
            nodeData.put("callPath", new ArrayList<String>()); // 简化处理
            nodeData.put("complexity", 1); // 默认值
            nodeData.put("methodBody", ""); // 简化处理
            nodeData.put("thrownExceptions", new ArrayList<String>()); // 简化处理

            chain.computeIfAbsent(depth, k -> new ArrayList<>()).add(nodeData);
        }

        log.info("[KG Build] Reachable nodes: {}/{}", reachableNodes, nodes.size());
        log.info("[KG Build] Final maxDepth={}, totalNodes={}", maxDepth, reachableNodes);

        result.put("chain", chain);
        result.put("maxDepth", maxDepth);
        result.put("totalNodes", reachableNodes);

        // 同时保留 nodes 和 edges 用于图展示（也使用正确深度）
        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (Neo4jMethodNodeRepository.GraphTraversalResult node : nodes) {
            Integer depth = correctDepth.get(node.nodeId());
            if (depth == null) {
                continue; // 跳过不可达节点
            }
            Map<String, Object> nodeData = new HashMap<>();
            nodeData.put("nodeId", node.nodeId());
            nodeData.put("className", node.className());
            nodeData.put("methodName", node.methodName());
            nodeData.put("signature", node.signature());
            nodeData.put("filePath", node.filePath());
            nodeData.put("startLine", node.startLine());
            nodeData.put("depth", depth);
            nodeData.put("description", node.description());
            nodeList.add(nodeData);
        }
        result.put("nodes", nodeList);

        // 只保留可达节点之间的边
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (Neo4jMethodNodeRepository.GraphEdgeResult edge : edges) {
            // 只添加两端都在可达节点中的边
            if (correctDepth.containsKey(edge.sourceId()) && correctDepth.containsKey(edge.targetId())) {
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", edge.sourceId());
                edgeMap.put("target", edge.targetId());
                edgeMap.put("callType", edge.callType());
                edgeMap.put("callLine", edge.callLine());
                edgeList.add(edgeMap);
            }
        }
        result.put("edges", edgeList);
        result.put("totalEdges", edgeList.size());

        return result;
    }
}
