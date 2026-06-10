package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.model.*;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;

import java.util.concurrent.Semaphore;
import com.huawei.hisi.model.FeignClientInfo;
import com.huawei.hisi.model.HttpCallInfo;
import com.huawei.hisi.model.MQEndpoint;
import com.huawei.hisi.model.ScanResult;
import com.huawei.hisi.scanner.FeignClientScanner;
import com.huawei.hisi.scanner.HttpCallScanner;
import com.huawei.hisi.scanner.MQEndpointScanner;
import com.huawei.hisi.scanner.ProxyClassScanner;
import com.huawei.hisi.knowledgegraph.scanner.MyBatisXmlScanner;
import com.huawei.hisi.knowledgegraph.scanner.JavaDataModelScanner;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import com.huawei.hisi.neo4j.model.DataModelNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.util.ProjectLanguageDetector;
import com.huawei.hisi.knowledgegraph.util.ProjectLanguageDetector.Language;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识图谱构建服务
 * 重构版：复用 CodeAnalysisCoreService 的核心方法
 * 保持与调用链分析一致的分析逻辑
 *
 * 存储策略：
 * - 所有图数据（方法节点、调用关系、入口点、接口实现、SQL 节点）→ Neo4j
 * - EXECUTES_SQL 关系：Method -> Sql（Mapper 调用时自动关联）
 */
@Service
@Slf4j
public class KnowledgeGraphBuilder {

    private final CodeAnalysisCoreService coreService;
    private final GlobalAnalysisCache globalCache;

    // Neo4j 存储服务（核心图数据）
    private final KnowledgeGraphStorageService storageService;

    // Neo4j SQL 节点 Repository（替代 PostgreSQL MyBatisSqlRepository）
    private final Neo4jSqlNodeRepository neo4jSqlNodeRepository;

    // Neo4j 方法节点 Repository（用于 IMPLEMENTS 关系验证）
    private final Neo4jMethodNodeRepository neo4jMethodNodeRepository;

    // Neo4j 入口点 Repository
    private final com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository;

    // Mapper 调用解析器
    private final MapperCallResolver mapperCallResolver;

    // 新增扫描器依赖
    private final FeignClientScanner feignClientScanner;
    private final MQEndpointScanner mqEndpointScanner;
    private final HttpCallScanner httpCallScanner;
    private final ProxyClassScanner proxyClassScanner;
    private final MyBatisXmlScanner myBatisXmlScanner;

    // 向量生成服务
    private final VectorGenerationService vectorGenerationService;

    // 向量生成任务 Repository (unified)
    private final GenerationTaskRepository generationTaskRepository;

    // Git 状态服务（用于增量更新）
    private final GitStatusService gitStatusService;

    // Python 知识图谱构建器
    private final PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder;

    // Neo4j 增量刷新 Checkpoint 仓库
    private final Neo4jGenerationCheckpointRepository checkpointRepository;

    // 数据模型扫描器
    private final JavaDataModelScanner javaDataModelScanner;

    // 全局信号量：同一时刻只允许一个项目执行知识图谱生成
    // 因为 GlobalAnalysisCache 是单例，并发生成会导致缓存互相覆盖
    private final Semaphore generationSemaphore = new Semaphore(1, true);

    // Neo4j 数据模型节点 Repository
    private final Neo4jDataModelNodeRepository neo4jDataModelNodeRepository;

    public KnowledgeGraphBuilder(
            CodeAnalysisCoreService coreService,
            GlobalAnalysisCache globalCache,
            @Qualifier("neo4jStorageService") KnowledgeGraphStorageService storageService,
            Neo4jSqlNodeRepository neo4jSqlNodeRepository,
            Neo4jMethodNodeRepository neo4jMethodNodeRepository,
            com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository,
            MapperCallResolver mapperCallResolver,
            FeignClientScanner feignClientScanner,
            MQEndpointScanner mqEndpointScanner,
            HttpCallScanner httpCallScanner,
            ProxyClassScanner proxyClassScanner,
            MyBatisXmlScanner myBatisXmlScanner,
            VectorGenerationService vectorGenerationService,
            GenerationTaskRepository generationTaskRepository,
            GitStatusService gitStatusService,
            PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder,
            Neo4jGenerationCheckpointRepository checkpointRepository,
            JavaDataModelScanner javaDataModelScanner,
            Neo4jDataModelNodeRepository neo4jDataModelNodeRepository) {
        this.coreService = coreService;
        this.globalCache = globalCache;
        this.storageService = storageService;
        this.neo4jSqlNodeRepository = neo4jSqlNodeRepository;
        this.neo4jMethodNodeRepository = neo4jMethodNodeRepository;
        this.neo4jEntryPointNodeRepository = neo4jEntryPointNodeRepository;
        this.mapperCallResolver = mapperCallResolver;
        this.feignClientScanner = feignClientScanner;
        this.mqEndpointScanner = mqEndpointScanner;
        this.httpCallScanner = httpCallScanner;
        this.proxyClassScanner = proxyClassScanner;
        this.myBatisXmlScanner = myBatisXmlScanner;
        this.vectorGenerationService = vectorGenerationService;
        this.generationTaskRepository = generationTaskRepository;
        this.gitStatusService = gitStatusService;
        this.pythonKnowledgeGraphBuilder = pythonKnowledgeGraphBuilder;
        this.checkpointRepository = checkpointRepository;
        this.javaDataModelScanner = javaDataModelScanner;
        this.neo4jDataModelNodeRepository = neo4jDataModelNodeRepository;
    }

    /**
     * 为项目构建知识图谱（使用默认屏蔽目录）
     */
    public Map<String, Object> buildKnowledgeGraph(String projectPath) {
        return buildKnowledgeGraph(projectPath, null);
    }

    /**
     * 为项目构建知识图谱，支持自定义屏蔽目录
     */
    public Map<String, Object> buildKnowledgeGraph(String projectPath, List<String> excludePaths) {
        if (!generationSemaphore.tryAcquire()) {
            throw new IllegalStateException("知识图谱生成任务正在执行中，请稍后再试（同一时刻仅允许一个项目生成）");
        }
        try {
            return doBuildKnowledgeGraph(projectPath, excludePaths);
        } finally {
            generationSemaphore.release();
        }
    }

    private Map<String, Object> doBuildKnowledgeGraph(String projectPath, List<String> excludePaths) {
        // 入口规范化：把反斜杠统一成正斜杠，并去尾斜杠，确保 Neo4j 节点的 projectPath
        // 始终使用规范形态。否则同一份代码用 \ 和 / 调两次会产生两套重复节点。
        String rawInput = projectPath;
        projectPath = com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils.normalizePath(projectPath);
        if (!java.util.Objects.equals(rawInput, projectPath)) {
            log.info("[KG Build] projectPath normalized: '{}' -> '{}'", rawInput, projectPath);
        }
        log.info("开始构建知识图谱: {} (excludePaths={})", projectPath, excludePaths);
        long startTime = System.currentTimeMillis();

        // 前置校验：必须能获取 git commit hash，否则增量刷新 checkpoint 无法保存
        String commitHash = gitStatusService.getCurrentCommitHash(projectPath);
        if (commitHash == null || commitHash.isBlank()) {
            throw new com.huawei.hisi.knowledgegraph.exception.NoGitCommitException(projectPath);
        }

        // 检测项目语言
        Language language = ProjectLanguageDetector.detectLanguage(projectPath);
        log.info("[KG Build] Detected language: {}", language);

        // 如果是Python项目，使用PythonKnowledgeGraphBuilder
        if (language == Language.PYTHON) {
            return buildPythonKnowledgeGraph(projectPath, excludePaths, startTime);
        }

        // 否则使用Java知识图谱构建（默认）
        return buildJavaKnowledgeGraph(projectPath, excludePaths, startTime);
    }

    /**
     * Build Python knowledge graph by delegating to PythonKnowledgeGraphBuilder.
     */
    private Map<String, Object> buildPythonKnowledgeGraph(String projectPath, List<String> excludePaths, long startTime) {
        log.info("[KG Build] Building Python knowledge graph...");

        // 1. 清理旧数据
        cleanOldData(projectPath);

        // 2. 构建Python知识图谱并保存到Neo4j
        try {
            pythonKnowledgeGraphBuilder.buildAndSave(projectPath, excludePaths);
        } catch (Exception e) {
            log.error("[KG Build] Failed to build Python knowledge graph", e);
            throw new RuntimeException("Python知识图谱构建失败: " + e.getMessage(), e);
        }

        // 3. 从Neo4j统计节点数量
        int methodNodeCount = (int) neo4jMethodNodeRepository.countByProjectPath(projectPath);
        int entryPointCount = (int) neo4jEntryPointNodeRepository.countByProjectPath(projectPath);
        int callRelationCount = (int) neo4jMethodNodeRepository.countCallRelationsByProjectPath(projectPath);

        long endTime = System.currentTimeMillis();

        Map<String, Object> result = new HashMap<>();
        result.put("methodNodeCount", methodNodeCount);
        result.put("callRelationCount", callRelationCount);
        result.put("entryPointCount", entryPointCount);
        result.put("interfaceImplCount", 0); // Python doesn't have interface impls yet
        result.put("myBatisMapperCount", 0); // No MyBatis in Python
        result.put("myBatisSqlCount", 0); // No MyBatis in Python
        result.put("costTimeMs", endTime - startTime);

        // 知识图谱生成完成后，异步启动向量生成
        log.info("知识图谱生成完成，启动向量生成: {}", projectPath);
        vectorGenerationService.startVectorGeneration(projectPath);

        // 保存生成日志（供增量更新使用）
        try {
            saveGenerationLog(projectPath, methodNodeCount, callRelationCount,
                entryPointCount, 0, startTime);
        } catch (Exception e) {
            log.error("保存生成日志/checkpoint 失败，增量刷新将不可用: {}", e.getMessage());
        }

        log.info("Python知识图谱构建完成: {}", result);
        return result;
    }

    /**
     * Build Java knowledge graph (existing logic).
     */
    private Map<String, Object> buildJavaKnowledgeGraph(String projectPath, List<String> excludePaths, long startTime) {
        log.info("[KG Build] Building Java knowledge graph...");

        // 1. 清理旧数据
        cleanOldData(projectPath);

        // 2. 构建类型解析器（复用调用链分析的逻辑）
        List<Path> sourceRoots = coreService.findSourceRoots(Paths.get(projectPath));
        CombinedTypeSolver solver = buildSolver(sourceRoots);
        globalCache.setTypeSolver(solver);
        JavaParser javaParser = coreService.createJavaParser(solver);

        // 3. 清空缓存（包括桥接缓存）
        // 注意：clearAll() 会清空 typeSolver，所以需要在之后重新设置
        globalCache.clearAll();
        globalCache.setTypeSolver(solver);  // 重新设置 TypeSolver

        // 4. 扫描所有Java文件
        List<File> javaFiles = coreService.findJavaFiles(projectPath, excludePaths);
        log.info("发现 {} 个Java文件", javaFiles.size());

        // ===== 按模块统计扫描到的文件 =====
        {
            final String normalizedPath = projectPath.replace('\\', '/');
            Map<String, Long> moduleFileCounts = javaFiles.stream()
                .collect(Collectors.groupingBy(f -> {
                    String rel = f.getAbsolutePath().replace('\\', '/')
                        .replace(normalizedPath + "/", "");
                    int firstSlash = rel.indexOf('/');
                    return firstSlash > 0 ? rel.substring(0, firstSlash) : "(root)";
                }, Collectors.counting()));
            log.info("[KG] 各模块文件数: {}", moduleFileCounts);
        }

        // 转换为 Path 列表供扫描器使用
        List<Path> javaFilePaths = javaFiles.stream()
            .map(File::toPath)
            .collect(Collectors.toList());

        // 5. 使用桥接扫描器扫描（填充 GlobalCache）
        scanBridgeEndpoints(javaFilePaths, projectPath);

        // 6. 扫描 MyBatis XML 文件（返回 Neo4j SqlNode 列表）
        MyBatisXmlScanner.Neo4jScanResult myBatisResult = myBatisXmlScanner.scanProjectForNeo4j(projectPath, excludePaths);
        log.info("MyBatis 扫描完成: {} Mapper, {} SQL",
            myBatisResult.getMapperCount(), myBatisResult.getSqlCount());

        // 7. 收集所有数据
        List<MethodNode> allMethodNodes = new ArrayList<>();
        List<Map<String, Object>> allCallRelations = new ArrayList<>();
        List<EntryPointNode> allEntryPoints = new ArrayList<>();
        Map<String, String> methodSignatureToNodeId = new HashMap<>();
        Map<String, String> methodFullKeyToNodeId = new HashMap<>();

        // 第一遍：扫描方法节点和入口点
        for (File javaFile : javaFiles) {
            CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
            if (cu == null) continue;

            String filePath = javaFile.getAbsolutePath();

            // 扫描方法节点
            List<MethodNode> methodNodes = scanMethodNodes(cu, filePath, projectPath);
            allMethodNodes.addAll(methodNodes);

            // 建立映射
            for (MethodNode node : methodNodes) {
                String key = node.getClassName() + "." + node.getMethodName();
                String fullKey = key + "." + signatureHash(node.getSignature());
                methodSignatureToNodeId.put(key, node.getNodeId());
                methodFullKeyToNodeId.put(fullKey, node.getNodeId());
            }

            // 扫描入口点（复用核心服务）
            List<EntryPointNode> entryPoints = createEntryPoints(cu, projectPath);
            allEntryPoints.addAll(entryPoints);

            // 构建接口-实现映射（复用核心服务）
            coreService.buildImplementationMap(cu);
        }

        // 第二遍：扫描调用关系（使用核心服务的复杂解析逻辑）
        for (File javaFile : javaFiles) {
            CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
            if (cu == null) continue;

            List<Map<String, Object>> relations = scanCallRelationsWithCoreService(cu, projectPath,
                methodSignatureToNodeId, methodFullKeyToNodeId, javaParser);
            allCallRelations.addAll(relations);
        }

        // 8. 识别桥接调用并增强调用关系
        List<Map<String, Object>> bridgeRelations = identifyBridgeCalls(allCallRelations, projectPath);
        allCallRelations.addAll(bridgeRelations);

        // 8.5 为接口继承关系生成合成 MethodNode（解决跨模块接口继承断链问题）
        List<MethodNode> syntheticNodes = synthesizeInheritedMethodNodes(allMethodNodes, projectPath);
        if (!syntheticNodes.isEmpty()) {
            log.info("[KG] 合成继承方法节点: {} 个（来自接口继承关系）", syntheticNodes.size());
            allMethodNodes.addAll(syntheticNodes);
            // 同步更新 methodSignatureToNodeId 映射
            for (MethodNode node : syntheticNodes) {
                String key = node.getClassName() + "." + node.getMethodName();
                String fullKey = key + "." + signatureHash(node.getSignature());
                methodSignatureToNodeId.putIfAbsent(key, node.getNodeId());
                methodFullKeyToNodeId.putIfAbsent(fullKey, node.getNodeId());
            }
        }

        // 9. 批量保存基础数据到 Neo4j
        log.info("[Neo4j] 保存方法节点: {}", allMethodNodes.size());
        storageService.saveMethodNodes(allMethodNodes);
        log.info("[Neo4j] 保存调用关系: {}", allCallRelations.size());
        storageService.saveCallRelations(allCallRelations);
        log.info("[Neo4j] 保存入口点: {}", allEntryPoints.size());
        storageService.saveEntryPoints(allEntryPoints);

        // 10. 保存接口-实现关系到 Neo4j
        {
            Map<String, Set<String>> implMapSummary = globalCache.getImplementationMap();
            Map<String, Set<String>> extendMapSummary = globalCache.getExtendMap();
            log.info("[KG] implementationMap 键数量: {}, extendMap 键数量: {}", implMapSummary.size(), extendMapSummary.size());

            // 汇总检查：有多少 interface/impl 对两端都有 MethodNode
            Set<String> methodNodeClassNames = allMethodNodes.stream()
                .map(MethodNode::getClassName)
                .collect(Collectors.toSet());
            long brokenPairs = 0;
            for (Map.Entry<String, Set<String>> e : implMapSummary.entrySet()) {
                boolean hasIface = methodNodeClassNames.contains(e.getKey());
                for (String implName : e.getValue()) {
                    if (!hasIface || !methodNodeClassNames.contains(implName)) {
                        brokenPairs++;
                    }
                }
            }
            if (brokenPairs > 0) {
                log.warn("[KG] IMPLEMENTS 潜在断链对数: {} (接口或实现类缺少 MethodNode)", brokenPairs);
            }
        }

        List<InterfaceImplementation> impls = convertFromGlobalCache(projectPath);
        log.info("[Neo4j] 保存接口实现关系: {}", impls.size());
        storageService.saveInterfaceImplementations(impls);

        // 10.1 保存类继承关系 EXTENDS
        List<ClassExtends> extendsRelations = buildExtendsRelations(projectPath);
        log.info("[Neo4j] 保存类继承关系: {}", extendsRelations.size());
        storageService.saveClassExtends(extendsRelations);

        // 10.2 保存方法重写关系 OVERRIDE
        List<MethodOverride> overrideRelations = buildOverrideRelations(projectPath, allMethodNodes);
        log.info("[Neo4j] 保存方法重写关系: {}", overrideRelations.size());
        storageService.saveMethodOverrides(overrideRelations);

        // 10.3 保存代理类关系 PROXY
        List<ProxyRelation> proxyRelations = buildProxyRelations(projectPath);
        log.info("[Neo4j] 保存代理类关系: {}", proxyRelations.size());
        storageService.saveProxyRelations(proxyRelations);

        // 验证实际创建的 IMPLEMENTS 边数量
        int actualCount = neo4jMethodNodeRepository.countImplementsRelations(projectPath);
        log.info("[Neo4j] IMPLEMENTS 关系实际数量: actualCount={}, inputCount={}", actualCount, impls.size());
        if (actualCount < impls.size()) {
            log.warn("[Neo4j] IMPLEMENTS 数量偏少: actual={}, input={}（部分接口/实现的方法名未匹配）", actualCount, impls.size());
        }

        // 10.5 基于 IMPLEMENTS 边物化 dispatch CALLS 边（使下游/上游遍历直接跟边走，无需 fallback）
        // 使用 MERGE + ON CREATE SET: 若代码分析阶段已有同方向 CALLS 边则保留原边，不覆盖
        long implDispatchCount = 0, implDispatchFeignCount = 0, feignBridgeCount = 0;
        try {
            neo4jMethodNodeRepository.createImplDispatchEdges(projectPath);
            neo4jMethodNodeRepository.createFeignBridgeEdges(projectPath);
            implDispatchCount = neo4jMethodNodeRepository.countCallsByType(projectPath, "IMPL_DISPATCH");
            implDispatchFeignCount = neo4jMethodNodeRepository.countCallsByType(projectPath, "IMPL_DISPATCH_FEIGN");
            feignBridgeCount = neo4jMethodNodeRepository.countCallsByType(projectPath, "FEIGN_BRIDGE");
            log.info("[Neo4j] Dispatch 边物化完成: IMPL_DISPATCH={}, IMPL_DISPATCH_FEIGN={}, FEIGN_BRIDGE={}",
                implDispatchCount, implDispatchFeignCount, feignBridgeCount);
        } catch (Exception e) {
            log.warn("[Neo4j] Dispatch 边物化异常（不影响基础调用链）: {}", e.getMessage());
        }

        // 11. 保存 SQL 节点到 Neo4j（使用 MERGE 幂等写入，避免 Duplicate key）
        List<SqlNode> sqlNodes = myBatisResult.getSqlNodes();
        if (!sqlNodes.isEmpty()) {
            log.info("[Neo4j] 保存 SQL 节点: {}", sqlNodes.size());
            List<Map<String, Object>> sqlNodeMaps = sqlNodes.stream()
                .map(s -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("nodeId", s.getNodeId());
                    map.put("sqlId", s.getSqlId());
                    map.put("statementType", s.getStatementType());
                    map.put("sqlStatement", s.getSqlStatement());
                    map.put("parameterType", s.getParameterType());
                    map.put("resultType", s.getResultType());
                    map.put("resultMap", s.getResultMap());
                    map.put("mapperInterface", s.getMapperInterface());
                    map.put("methodName", s.getMethodName());
                    map.put("xmlFilePath", s.getXmlFilePath());
                    map.put("projectPath", s.getProjectPath());
                    map.put("language", s.getLanguage());
                    map.put("framework", s.getFramework());
                    return map;
                })
                .toList();
            neo4jSqlNodeRepository.mergeAll(sqlNodeMaps);
        }

        // 12. 创建 EXECUTES_SQL 关系（Mapper 调用 -> SQL）
        List<Map<String, Object>> executesSqlRelations = buildExecutesSqlRelations(allCallRelations, sqlNodes, methodSignatureToNodeId);
        if (!executesSqlRelations.isEmpty()) {
            log.info("[Neo4j] 创建 EXECUTES_SQL 关系: {}", executesSqlRelations.size());
            neo4jSqlNodeRepository.createExecutesSqlRelations(executesSqlRelations);
        }

        // 13. 扫描数据模型节点和 USES_MODEL 关系（独立 Pass 3，不影响已有逻辑）
        int dataModelCount = 0;
        int usesModelCount = 0;
        try {
            List<DataModelNode> dataModelNodes = new ArrayList<>();
            for (File javaFile : javaFiles) {
                CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
                if (cu == null) continue;
                dataModelNodes.addAll(javaDataModelScanner.scanDataModels(cu, javaFile.getAbsolutePath(), projectPath));
            }

            Set<String> dataModelClassNames = dataModelNodes.stream()
                .map(DataModelNode::getClassName).collect(Collectors.toSet());

            List<Map<String, Object>> usesModelRelations = new ArrayList<>();
            if (!dataModelClassNames.isEmpty()) {
                for (File javaFile : javaFiles) {
                    CompilationUnit cu = coreService.parseFile(javaFile, javaParser);
                    if (cu == null) continue;
                    usesModelRelations.addAll(
                        javaDataModelScanner.scanUsesModelRelations(cu, projectPath, dataModelClassNames, methodSignatureToNodeId));
                }

                neo4jDataModelNodeRepository.saveAll(dataModelNodes);
                if (!usesModelRelations.isEmpty()) {
                    neo4jDataModelNodeRepository.createUsesModelRelations(usesModelRelations);
                }
                dataModelCount = dataModelNodes.size();
                usesModelCount = usesModelRelations.size();
                log.info("[KG] 数据模型节点: {}, USES_MODEL 关系: {}", dataModelCount, usesModelCount);
            }
        } catch (Exception e) {
            log.warn("[KG] 数据模型扫描异常（不影响核心图谱）: {}", e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        result.put("methodNodeCount", allMethodNodes.size());
        result.put("callRelationCount", allCallRelations.size());
        result.put("entryPointCount", allEntryPoints.size());
        result.put("interfaceImplCount", impls.size());
        result.put("extendsCount", extendsRelations.size());
        result.put("overrideCount", overrideRelations.size());
        result.put("proxyCount", proxyRelations.size());
        result.put("myBatisMapperCount", myBatisResult.getMapperCount());
        result.put("myBatisSqlCount", myBatisResult.getSqlCount());
        result.put("dispatchEdges", Map.of(
            "IMPL_DISPATCH", implDispatchCount,
            "IMPL_DISPATCH_FEIGN", implDispatchFeignCount,
            "FEIGN_BRIDGE", feignBridgeCount
        ));
        result.put("costTimeMs", endTime - startTime);
        result.put("dataModelCount", dataModelCount);
        result.put("usesModelCount", usesModelCount);

        // 知识图谱生成完成后，异步启动向量生成
        log.info("知识图谱生成完成，启动向量生成: {}", projectPath);
        vectorGenerationService.startVectorGeneration(projectPath);

        // 保存生成日志（供增量更新使用）
        try {
            saveGenerationLog(projectPath, allMethodNodes.size(), allCallRelations.size(),
                allEntryPoints.size(), impls.size(), startTime);
        } catch (Exception e) {
            log.error("保存生成日志/checkpoint 失败，增量刷新将不可用: {}", e.getMessage());
        }

        log.info("知识图谱构建完成: {}", result);
        return result;
    }

    /**
     * 使用桥接扫描器扫描端点信息
     */
    private void scanBridgeEndpoints(List<Path> javaFilePaths, String projectPath) {
        log.info("开始扫描桥接端点...");

        // 扫描 FeignClient
        ScanResult<FeignClientInfo> feignResult = feignClientScanner.scanFiles(javaFilePaths, globalCache);
        log.info("FeignClient 扫描完成: {} 个端点", feignResult.getFoundCount());

        // 扫描 MQ 端点
        ScanResult<MQEndpoint> mqResult = mqEndpointScanner.scanFiles(javaFilePaths, globalCache);
        log.info("MQ 端点扫描完成: {} 个端点", mqResult.getFoundCount());

        // 扫描 HTTP 调用
        ScanResult<HttpCallInfo> httpResult = httpCallScanner.scanFiles(javaFilePaths, globalCache);
        log.info("HTTP 调用扫描完成: {} 个调用", httpResult.getFoundCount());

        // 扫描代理类（Mapper/Repository/AOP）
        ScanResult<com.huawei.hisi.model.ProxyMetadata> proxyResult = proxyClassScanner.scanFiles(javaFilePaths, globalCache);
        log.info("代理类扫描完成: {} 个代理", proxyResult.getFoundCount());
    }

    /**
     * 识别桥接调用（Mapper/JPA/Feign/MQ/HTTP）
     */
    private List<Map<String, Object>> identifyBridgeCalls(List<Map<String, Object>> existingRelations, String projectPath) {
        List<Map<String, Object>> bridgeRelations = new ArrayList<>();

        // 遍历现有调用关系，识别桥接类型
        for (Map<String, Object> relation : existingRelations) {
            String calleeId = (String) relation.get("calleeId");

            // 检查是否是 Mapper 调用
            if (mapperCallResolver.isMapperCall(extractInterfaceName(calleeId), globalCache)) {
                relation.put("bridgeType", "MAPPER");

                // 尝试获取 SQL ID
                String interfaceName = extractInterfaceName(calleeId);
                String methodName = extractMethodName(calleeId);
                Optional<SqlNode> sqlNode = mapperCallResolver.resolveMapperCall(
                    interfaceName, methodName, projectPath);
                sqlNode.ifPresent(node -> relation.put("sqlId", node.getSqlId()));
            }
            // 检查是否是 JPA Repository 调用
            else if (mapperCallResolver.isJpaRepositoryCall(extractInterfaceName(calleeId), globalCache)) {
                relation.put("bridgeType", "JPA");
                relation.put("callType", "JPA");
            }
        }

        // 从缓存中构建 MQ 桥接调用关系
        bridgeRelations.addAll(buildMqBridgeRelations(projectPath));

        // 从缓存中构建 Feign/HTTP 桥接调用关系
        bridgeRelations.addAll(buildHttpBridgeRelations(projectPath));

        return bridgeRelations;
    }

    /**
     * 构建 MQ 桥接调用关系
     */
    private List<Map<String, Object>> buildMqBridgeRelations(String projectPath) {
        List<Map<String, Object>> relations = new ArrayList<>();

        // 从生产者索引和消费者索引构建桥接关系
        Map<String, List<String>> producerIndex = globalCache.getMqProducerIndex();
        Map<String, List<String>> consumerIndex = globalCache.getMqConsumerIndex();

        // 遍历所有 topic，匹配生产者和消费者
        Set<String> allTopics = new HashSet<>();
        allTopics.addAll(producerIndex.keySet());
        allTopics.addAll(consumerIndex.keySet());

        for (String topic : allTopics) {
            List<String> producers = producerIndex.getOrDefault(topic, Collections.emptyList());
            List<String> consumers = consumerIndex.getOrDefault(topic, Collections.emptyList());

            // 为每个生产者-消费者对创建桥接调用关系
            for (String producer : producers) {
                for (String consumer : consumers) {
                    Map<String, Object> relation = new LinkedHashMap<>();
                    relation.put("callerId", producer);
                    relation.put("calleeId", consumer);
                    relation.put("callType", "MQ");
                    relation.put("bridgeType", "MQ");
                    relation.put("targetEndpoint", topic);
                    relations.add(relation);
                }
            }
        }

        return relations;
    }

    /**
     * 构建 HTTP/Feign 桥接调用关系
     */
    private List<Map<String, Object>> buildHttpBridgeRelations(String projectPath) {
        List<Map<String, Object>> relations = new ArrayList<>();

        // 从 Feign URI 索引构建桥接关系
        Map<String, String> feignUriIndex = globalCache.getFeignUriIndex();
        Map<String, String> restEndpointMap = globalCache.getRestEndpointMap();

        // 遍历 Feign URI，匹配 REST 端点
        for (Map.Entry<String, String> entry : feignUriIndex.entrySet()) {
            String uriKey = entry.getKey();
            String feignMethod = entry.getValue();

            // 查找匹配的 REST 端点
            if (restEndpointMap.containsKey(uriKey)) {
                String handlerMethod = restEndpointMap.get(uriKey);

                Map<String, Object> relation = new LinkedHashMap<>();
                relation.put("callerId", feignMethod);
                relation.put("calleeId", handlerMethod);
                relation.put("callType", "FEIGN");
                relation.put("bridgeType", "FEIGN");
                relation.put("targetEndpoint", uriKey);

                // 解析 URI key 获取服务名
                String[] parts = uriKey.split("\\|");
                if (parts.length > 0) {
                    relation.put("targetService", parts[0]);
                }

                relations.add(relation);
            }
        }

        return relations;
    }

    /**
     * 构建 EXECUTES_SQL 关系
     * 当方法调用 Mapper 时，创建从 Method 到 SqlNode 的关系
     */
    private List<Map<String, Object>> buildExecutesSqlRelations(
            List<Map<String, Object>> allCallRelations,
            List<SqlNode> sqlNodes,
            Map<String, String> methodSignatureToNodeId) {

        List<Map<String, Object>> relations = new ArrayList<>();

        // 构建 sqlId -> SqlNode 映射
        Map<String, SqlNode> sqlIdToNode = new HashMap<>();
        for (SqlNode node : sqlNodes) {
            sqlIdToNode.put(node.getSqlId(), node);
        }

        // 遍历调用关系，找到 Mapper 调用
        for (Map<String, Object> relation : allCallRelations) {
            String bridgeType = (String) relation.get("bridgeType");
            String sqlId = (String) relation.get("sqlId");
            String callerId = (String) relation.get("callerId");

            // 只有 Mapper 调用且有关联 SQL ID 时才创建关系
            if ("MAPPER".equals(bridgeType) && sqlId != null && callerId != null) {
                // 确认 SQL 节点存在
                SqlNode sqlNode = sqlIdToNode.get(sqlId);
                if (sqlNode != null) {
                    Map<String, Object> execRelation = new LinkedHashMap<>();
                    execRelation.put("methodNodeId", callerId);
                    execRelation.put("sqlNodeId", sqlNode.getNodeId());  // 使用 nodeId 而不是 sqlId
                    execRelation.put("callLine", relation.get("callLine"));
                    relations.add(execRelation);
                }
            }
        }

        return relations;
    }

    /**
     * 从方法 ID 提取接口名
     */
    private String extractInterfaceName(String methodId) {
        if (methodId == null) return "";
        int lastDot = methodId.lastIndexOf(".");
        return lastDot > 0 ? methodId.substring(0, lastDot) : methodId;
    }

    /**
     * 从方法 ID 提取方法名
     */
    private String extractMethodName(String methodId) {
        if (methodId == null) return "";
        int lastDot = methodId.lastIndexOf(".");
        return lastDot > 0 ? methodId.substring(lastDot + 1) : methodId;
    }

    /**
     * 从方法 ID 提取类名
     */
    private String extractClassName(String methodId) {
        if (methodId == null) return "";
        int lastDot = methodId.lastIndexOf(".");
        return lastDot > 0 ? methodId.substring(0, lastDot) : methodId;
    }

    /**
     * 扫描方法节点
     * 包括普通方法、静态方法、构造方法
     */
    private List<MethodNode> scanMethodNodes(CompilationUnit cu, String filePath, String projectPath) {
        List<MethodNode> nodes = new ArrayList<>();

        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ?
                clazz.getNameAsString() :
                packageName + "." + clazz.getNameAsString();

            // 扫描普通方法
            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                // nodeId 格式: projectPath:className.methodName.签名hash
                // 确保全局唯一，同时冗余存储 projectPath、className、methodName
                String methodId = className + "." + method.getNameAsString() + "." +
                    signatureHash(method.getSignature().toString());
                String nodeId = projectPath + ":" + methodId;

                MethodNode node = MethodNode.builder()
                    .nodeId(nodeId)
                    .className(className)
                    .methodName(method.getNameAsString())
                    .signature(method.getSignature().toString())
                    .filePath(filePath)
                    .startLine(method.getBegin().map(p -> p.line).orElse(0))
                    .endLine(method.getEnd().map(p -> p.line).orElse(0))
                    .complexity(calculateComplexity(method))
                    .methodBody(coreService.compressMethodBody(method))
                    .projectPath(projectPath)
                    .serviceName(extractServiceName(className, projectPath))
                    .build();

                nodes.add(node);
            });

            // 扫描构造方法
            clazz.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class).forEach(ctor -> {
                String methodId = className + ".<init>." +
                    signatureHash(ctor.getSignature().toString());
                String nodeId = projectPath + ":" + methodId;

                MethodNode node = MethodNode.builder()
                    .nodeId(nodeId)
                    .className(className)
                    .methodName("<init>")
                    .signature(ctor.getSignature().toString())
                    .filePath(filePath)
                    .startLine(ctor.getBegin().map(p -> p.line).orElse(0))
                    .endLine(ctor.getEnd().map(p -> p.line).orElse(0))
                    .complexity(1)
                    .methodBody("")
                    .projectPath(projectPath)
                    .serviceName(extractServiceName(className, projectPath))
                    .build();

                nodes.add(node);
            });
        });

        // Scan enum methods
        cu.findAll(com.github.javaparser.ast.body.EnumDeclaration.class).forEach(enumDecl -> {
            String className = packageName.isEmpty() ?
                enumDecl.getNameAsString() :
                packageName + "." + enumDecl.getNameAsString();

            enumDecl.findAll(MethodDeclaration.class).forEach(method -> {
                String methodId = className + "." + method.getNameAsString() + "." +
                    signatureHash(method.getSignature().toString());
                String nodeId = projectPath + ":" + methodId;

                MethodNode node = MethodNode.builder()
                    .nodeId(nodeId)
                    .className(className)
                    .methodName(method.getNameAsString())
                    .signature(method.getSignature().toString())
                    .filePath(filePath)
                    .startLine(method.getBegin().map(p -> p.line).orElse(0))
                    .endLine(method.getEnd().map(p -> p.line).orElse(0))
                    .complexity(calculateComplexity(method))
                    .methodBody(coreService.compressMethodBody(method))
                    .projectPath(projectPath)
                    .serviceName(extractServiceName(className, projectPath))
                    .build();

                nodes.add(node);
            });
        });

        return nodes;
    }

    /**
     * 为接口继承关系生成合成 MethodNode
     *
     * 问题场景: interface B extends interface A（如 FeignClient extends API interface）
     * - A 有方法 m1, m2, m3 → 有 MethodNode(className=A, methodName=m1/m2/m3)
     * - B 继承了 A 的方法，但源文件中没有重新声明 → 没有 MethodNode(className=B, methodName=m1/m2/m3)
     * - IMPLEMENTS Cypher 要求 iface.methodName = impl.methodName → 匹配失败 → 断链
     *
     * 修复: 遍历 implementationMap，找出每对 interface→impl 关系中，
     * 父接口有但子接口/实现类缺少的方法，为缺少的方法创建合成 MethodNode。
     */
    private List<MethodNode> synthesizeInheritedMethodNodes(List<MethodNode> existingNodes, String projectPath) {
        List<MethodNode> syntheticNodes = new ArrayList<>();

        // 建立 className -> List<MethodNode> 索引（保留所有重载方法）
        Map<String, List<MethodNode>> classMethodNodes = new HashMap<>();
        // 建立 className -> Set<nodeId后缀> 用于去重检测（methodName.signatureHash）
        Map<String, Set<String>> classMethodIds = new HashMap<>();

        for (MethodNode node : existingNodes) {
            classMethodNodes
                .computeIfAbsent(node.getClassName(), k -> new ArrayList<>())
                .add(node);
            String sigHash = node.getSignature() != null
                ? signatureHash(node.getSignature()) : "0";
            classMethodIds
                .computeIfAbsent(node.getClassName(), k -> new HashSet<>())
                .add(node.getMethodName() + "." + sigHash);
        }

        Map<String, Set<String>> implMap = globalCache.getImplementationMap();
        Set<String> syntheticNodeIds = new HashSet<>();

        // 多轮迭代（fixed-point），确保多层继承链 A→B→C 也能正确级联
        boolean changed = true;
        int round = 0;
        while (changed) {
            changed = false;
            round++;
            if (round > 10) {
                log.warn("[KG] 合成 MethodNode 迭代超过10轮，可能存在循环继承，终止");
                break;
            }

            for (Map.Entry<String, Set<String>> entry : implMap.entrySet()) {
                String interfaceName = entry.getKey();
                List<MethodNode> interfaceNodes = classMethodNodes.get(interfaceName);

                // 跳过没有 MethodNode 的接口
                if (interfaceNodes == null || interfaceNodes.isEmpty()) {
                    continue;
                }

                for (String implName : entry.getValue()) {
                    Set<String> existingImplIds = classMethodIds.getOrDefault(implName, Set.of());

                    for (MethodNode template : interfaceNodes) {
                        String sigHash = template.getSignature() != null
                            ? signatureHash(template.getSignature().toString()) : "0";
                        String methodIdSuffix = template.getMethodName() + "." + sigHash;

                        // 实现类已有同名同签名的方法，跳过
                        if (existingImplIds.contains(methodIdSuffix)) {
                            continue;
                        }

                        // 生成合成 MethodNode
                        String syntheticId = projectPath + ":" + implName + "." + methodIdSuffix;

                        if (syntheticNodeIds.contains(syntheticId)) {
                            continue;
                        }
                        syntheticNodeIds.add(syntheticId);

                        MethodNode syntheticNode = MethodNode.builder()
                            .nodeId(syntheticId)
                            .className(implName)
                            .methodName(template.getMethodName())
                            .signature(template.getSignature())
                            .filePath(template.getFilePath()) // 指向父接口的源文件
                            .startLine(template.getStartLine())
                            .endLine(template.getEndLine())
                            .complexity(template.getComplexity())
                            .methodBody(template.getMethodBody())
                            .language(template.getLanguage())
                            .framework(template.getFramework())
                            .projectPath(projectPath)
                            .serviceName(extractServiceName(implName, projectPath))
                            .build();

                        syntheticNodes.add(syntheticNode);
                        changed = true;

                        // 更新索引，使后续继承链也能级联
                        classMethodNodes
                            .computeIfAbsent(implName, k -> new ArrayList<>())
                            .add(syntheticNode);
                        classMethodIds
                            .computeIfAbsent(implName, k -> new HashSet<>())
                            .add(methodIdSuffix);
                    }
                }
            }
        }

        if (!syntheticNodes.isEmpty()) {
            Map<String, Long> synthByClass = syntheticNodes.stream()
                .collect(Collectors.groupingBy(MethodNode::getClassName, Collectors.counting()));
            log.info("[KG] 合成 MethodNode 分布 ({}轮): {}", round, synthByClass);
        }

        return syntheticNodes;
    }

    /**
     * 使用核心服务扫描调用关系
     * 复用 CodeAnalysisCoreService.findMethodCallTargets 的复杂解析逻辑
     * 增强：支持静态方法调用、构造方法调用
     */
    private List<Map<String, Object>> scanCallRelationsWithCoreService(
            CompilationUnit cu, String projectPath,
            Map<String, String> methodSignatureToNodeId,
            Map<String, String> methodFullKeyToNodeId,
            JavaParser javaParser) {

        List<Map<String, Object>> relations = new ArrayList<>();

        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ?
                clazz.getNameAsString() :
                packageName + "." + clazz.getNameAsString();

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                String callerKey = className + "." + method.getNameAsString();
                String callerFullKey = callerKey + "." + signatureHash(method.getSignature().toString());
                String callerNodeId = methodFullKeyToNodeId.getOrDefault(callerFullKey,
                    methodSignatureToNodeId.get(callerKey));
                if (callerNodeId == null) return;

                final String finalCallerId = callerNodeId;

                // 1. 扫描所有方法调用（包括静态方法）
                method.findAll(MethodCallExpr.class).forEach(call -> {
                    // 使用核心服务的复杂解析逻辑
                    List<MethodDeclaration> targets = coreService.findMethodCallTargets(
                        call, clazz, method, javaParser);

                    for (MethodDeclaration target : targets) {
                        if (target.getNameAsString().startsWith("no match:")) {
                            continue; // 跳过未匹配的方法
                        }

                        // 获取目标方法的类名
                        String targetClassName = getMethodClassName(target);
                        String targetMethodKey = targetClassName + "." + target.getNameAsString();
                        String targetFullKey = targetMethodKey + "." + signatureHash(target.getSignature().toString());
                        String calleeNodeId = methodFullKeyToNodeId.getOrDefault(targetFullKey,
                            methodSignatureToNodeId.get(targetMethodKey));

                        if (calleeNodeId != null && !calleeNodeId.equals(finalCallerId)) {
                            // Proxy-aware call type: check @Async/@Transactional on target
                            String callType = coreService.detectProxyCallType(target);
                            if ("DIRECT".equals(callType)) {
                                callType = determineCallType(call);
                            }
                            Map<String, Object> relation = new LinkedHashMap<>();
                            relation.put("callerId", finalCallerId);
                            relation.put("calleeId", calleeNodeId);
                            relation.put("callType", callType);
                            relation.put("callLine", call.getBegin().map(p -> p.line).orElse(0));
                            relations.add(relation);
                        }
                    }

                    // 增强：尝试解析静态方法调用（如 ApiResponse.success()）
                    // 条件：targets为空，或者所有targets都是"no match"
                    boolean shouldTryStatic = targets.isEmpty() ||
                        targets.stream().allMatch(t -> t.getNameAsString().startsWith("no match:"));

                    // 额外条件：如果scope是类名格式，也尝试静态方法解析
                    if (!shouldTryStatic && call.getScope().isPresent()) {
                        String scopeStr = call.getScope().get().toString();
                        if (scopeStr.matches("([A-Z][a-zA-Z0-9]*\\.)*[A-Z][a-zA-Z0-9]*")) {
                            shouldTryStatic = true;
                        }
                    }

                    boolean staticResolved = false;
                    if (shouldTryStatic) {
                        Map<String, Object> staticRelation = resolveStaticMethodCall(call, finalCallerId,
                            methodSignatureToNodeId, projectPath);
                        if (staticRelation != null) {
                            relations.add(staticRelation);
                            staticResolved = true;
                        }
                    }

                    // 增强Fallback：字段调用兜底 — 当 findMethodCallTargets 和静态解析都未匹配时，
                    // 通过字段声明解析类型名，直接在 methodSignatureToNodeId 中查找接口方法。
                    // 覆盖场景：MyBatis Mapper、Feign Client 等无实现类的接口方法调用，
                    // TypeSolver 无法解析到目标方法时，至少保证建立与接口方法的 CALLS 关系。
                    boolean primaryResolved = !targets.isEmpty() &&
                        targets.stream().anyMatch(t -> !t.getNameAsString().startsWith("no match:"));
                    if (!primaryResolved && !staticResolved && call.getScope().isPresent()) {
                        String scopeStr = call.getScope().get().toString().trim();
                        // 仅处理简单字段引用（小写字母开头的标识符，排除 this/super 和链式调用）
                        if (scopeStr.matches("[a-z][a-zA-Z0-9]*") && !"this".equals(scopeStr) && !"super".equals(scopeStr)) {
                            Map<String, Object> inferredRelation = resolveInferredFieldCall(
                                call, clazz, scopeStr, finalCallerId, methodSignatureToNodeId);
                            if (inferredRelation != null) {
                                relations.add(inferredRelation);
                            }
                        }
                    }
                });

                // 2. 扫描构造调用: new ClassName(...)
                method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).forEach(newExpr -> {
                    String targetClassName;
                    try {
                        targetClassName = newExpr.getType().resolve().describe();
                    } catch (Exception e) {
                        // Fallback to simple name if symbol resolution fails
                        targetClassName = newExpr.getType().getNameAsString();
                    }

                    // Look up constructor method node: className.<init>
                    String constructorKey = targetClassName + ".<init>";
                    String calleeNodeId = methodSignatureToNodeId.get(constructorKey);
                    if (calleeNodeId == null) {
                        // Try by simple name as fallback
                        String simpleName = newExpr.getType().getNameAsString();
                        for (Map.Entry<String, String> entry : methodSignatureToNodeId.entrySet()) {
                            if (entry.getKey().startsWith(simpleName + ".<init>")) {
                                calleeNodeId = entry.getValue();
                                break;
                            }
                        }
                        if (calleeNodeId == null) return;
                    }

                    if (!calleeNodeId.equals(finalCallerId)) {
                        Map<String, Object> relation = new LinkedHashMap<>();
                        relation.put("callerId", finalCallerId);
                        relation.put("calleeId", calleeNodeId);
                        relation.put("callType", "DIRECT");
                        relation.put("callLine", newExpr.getBegin().map(p -> p.line).orElse(0));
                        relations.add(relation);
                    }
                });
            });
        });

        // Scan enum method calls
        // Note: findMethodCallTargets requires ClassOrInterfaceDeclaration, so for enum methods
        // we rely on static method resolution only. Full call resolution for enums would require
        // extending CodeAnalysisCoreService.findMethodCallTargets to accept TypeDeclaration.
        cu.findAll(com.github.javaparser.ast.body.EnumDeclaration.class).forEach(enumDecl -> {
            String className = packageName.isEmpty() ?
                enumDecl.getNameAsString() :
                packageName + "." + enumDecl.getNameAsString();

            enumDecl.findAll(MethodDeclaration.class).forEach(method -> {
                String callerKey = className + "." + method.getNameAsString();
                String callerFullKey = callerKey + "." + signatureHash(method.getSignature().toString());
                String callerNodeId = methodFullKeyToNodeId.getOrDefault(callerFullKey,
                    methodSignatureToNodeId.get(callerKey));
                if (callerNodeId == null) return;

                final String finalCallerId = callerNodeId;

                method.findAll(MethodCallExpr.class).forEach(call -> {
                    // For enum methods, skip findMethodCallTargets (requires ClassOrInterfaceDeclaration)
                    // and go straight to static method resolution
                    Map<String, Object> staticRelation = resolveStaticMethodCall(call, finalCallerId,
                        methodSignatureToNodeId, projectPath);
                    if (staticRelation != null) {
                        relations.add(staticRelation);
                    }
                });

                // Scan constructor calls within enum methods
                method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).forEach(newExpr -> {
                    String targetClassName;
                    try {
                        targetClassName = newExpr.getType().resolve().describe();
                    } catch (Exception e) {
                        targetClassName = newExpr.getType().getNameAsString();
                    }

                    String constructorKey = targetClassName + ".<init>";
                    String calleeNodeId = methodSignatureToNodeId.get(constructorKey);
                    if (calleeNodeId == null) {
                        String simpleName = newExpr.getType().getNameAsString();
                        for (Map.Entry<String, String> entry : methodSignatureToNodeId.entrySet()) {
                            if (entry.getKey().startsWith(simpleName + ".<init>")) {
                                calleeNodeId = entry.getValue();
                                break;
                            }
                        }
                        if (calleeNodeId == null) return;
                    }

                    if (!calleeNodeId.equals(finalCallerId)) {
                        Map<String, Object> relation = new LinkedHashMap<>();
                        relation.put("callerId", finalCallerId);
                        relation.put("calleeId", calleeNodeId);
                        relation.put("callType", "DIRECT");
                        relation.put("callLine", newExpr.getBegin().map(p -> p.line).orElse(0));
                        relations.add(relation);
                    }
                });
            });
        });

        return relations;
    }

    /**
     * 解析静态方法调用
     * 处理类似 ApiResponse.success()、LocalDateTime.now() 等静态方法
     */
    private Map<String, Object> resolveStaticMethodCall(MethodCallExpr call, String callerId,
            Map<String, String> methodSignatureToNodeId, String projectPath) {

        if (!call.getScope().isPresent()) {
            return null;
        }

        String scope = call.getScope().get().toString();
        String methodName = call.getNameAsString();

        // 检查是否是静态方法调用模式（类名.方法名）
        // 支持格式: ApiResponse, com.example.ApiResponse, ApiResponse.Builder
        if (!scope.matches("([A-Z][a-zA-Z0-9]*\\.)*[A-Z][a-zA-Z0-9]*") &&
            !scope.matches("[A-Z][a-zA-Z0-9]*")) {
            return null;
        }

        // 过滤 Lombok builder 模式调用（如 Xxx.builder()、Xxx.builder().xxx()）
        if ("builder".equals(methodName) || methodName.startsWith("build")) {
            return null;
        }

        // 过滤常见的非项目静态调用（日志、工具类等）
        if (scope.equals("log") || scope.equals("LOG") || scope.equals("logger") ||
            scope.equals("Collections") || scope.equals("Arrays") || scope.equals("Objects") ||
            scope.equals("Math") || scope.equals("System") || scope.equals("String") ||
            scope.equals("Integer") || scope.equals("Long") || scope.equals("Double") ||
            scope.equals("Boolean") || scope.equals("List") || scope.equals("Map") ||
            scope.equals("Set") || scope.equals("Optional") || scope.equals("Stream") ||
            scope.equals("Paths") || scope.equals("Files") || scope.equals("Thread") ||
            scope.equals("LocalDate") || scope.equals("LocalDateTime") ||
            scope.equals("Collectors") || scope.equals("Assertions") ||
            scope.equals("assertThat") || scope.equals("Mockito") ||
            scope.equals("when") || scope.equals("verify") ||
            scope.endsWith("Assert") || scope.endsWith("Logger") ||
            scope.endsWith("Log") || scope.endsWith("Logs")) {
            return null;
        }

        // 提取简单类名（最后一个部分）
        String simpleClassName = scope.contains(".") ?
            scope.substring(scope.lastIndexOf(".") + 1) : scope;

        // 尝试多种类名格式匹配
        List<String> possibleClassNames = new ArrayList<>();
        possibleClassNames.add(simpleClassName);  // 简单类名优先
        possibleClassNames.add(scope);             // 完整scope

        // 在已扫描的方法节点中查找匹配的完整类名
        for (String key : methodSignatureToNodeId.keySet()) {
            if (key.endsWith("." + methodName)) {
                String keyClass = key.substring(0, key.lastIndexOf("." + methodName));
                // 检查类名是否匹配
                if (keyClass.endsWith("." + simpleClassName) || keyClass.equals(simpleClassName)) {
                    if (!possibleClassNames.contains(keyClass)) {
                        possibleClassNames.add(keyClass);
                    }
                }
            }
        }

        for (String className : possibleClassNames) {
            // 尝试匹配方法签名
            String targetMethodKey = className + "." + methodName;
            String calleeNodeId = methodSignatureToNodeId.get(targetMethodKey);

            if (calleeNodeId != null && !calleeNodeId.equals(callerId)) {
                Map<String, Object> relation = new LinkedHashMap<>();
                relation.put("callerId", callerId);
                relation.put("calleeId", calleeNodeId);
                relation.put("callType", "STATIC");
                relation.put("callLine", call.getBegin().map(p -> p.line).orElse(0));
                return relation;
            }
        }

        return null;
    }

    /**
     * 字段调用兜底解析 — 当 findMethodCallTargets 和静态解析都失败时，
     * 通过字段声明获取类型名，直接在 methodSignatureToNodeId 中查找接口方法节点。
     *
     * 覆盖场景：MyBatis Mapper、Feign Client 等无 Java 实现类的接口方法调用，
     * TypeSolver 无法解析到目标方法时，至少保证建立与接口方法的 CALLS 关系，
     * 而不是静默丢弃调用边。
     */
    private Map<String, Object> resolveInferredFieldCall(
            MethodCallExpr call, com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz,
            String scopeName, String callerId,
            Map<String, String> methodSignatureToNodeId) {

        String methodName = call.getNameAsString();

        // 从类的直接字段声明中查找 scope 对应的类型（不递归内部类，避免同名字段误匹配）
        for (com.github.javaparser.ast.body.FieldDeclaration field : clazz.getFields()) {
            for (com.github.javaparser.ast.body.VariableDeclarator var : field.getVariables()) {
                if (!var.getNameAsString().equals(scopeName)) continue;

                String simpleTypeName = var.getType().asString();
                // 去掉泛型参数：List<Xxx> → List, Mapper<X,Y> → Mapper
                String baseTypeName = simpleTypeName.replaceAll("<.*>", "");

                // 解析全限定名：优先从 import 查找
                String fullTypeName = resolveFieldTypeNameFromImports(baseTypeName, clazz);

                // 按优先级尝试：全限定名 → 简单名（fullTypeName 可能为 null）
                for (String typeName : new String[]{fullTypeName, baseTypeName}) {
                    if (typeName == null) continue;
                    String targetMethodKey = typeName + "." + methodName;
                    String calleeNodeId = methodSignatureToNodeId.get(targetMethodKey);
                    if (calleeNodeId != null && !calleeNodeId.equals(callerId)) {
                        log.info("[InferredCall] 字段调用兜底命中: scope={}.{}() → type={}, calleeNodeId={}",
                            scopeName, methodName, typeName, calleeNodeId);
                        Map<String, Object> relation = new LinkedHashMap<>();
                        relation.put("callerId", callerId);
                        relation.put("calleeId", calleeNodeId);
                        relation.put("callType", "INFERRED");
                        relation.put("callLine", call.getBegin().map(p -> p.line).orElse(0));
                        return relation;
                    }
                }
                break; // 字段名唯一，找到后无需继续
            }
        }

        return null;
    }

    /**
     * 从 CompilationUnit 的 import 声明中解析字段类型的全限定名
     */
    private String resolveFieldTypeNameFromImports(String simpleTypeName, com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz) {
        return clazz.findCompilationUnit()
            .map(cu -> cu.getImports().stream()
                .filter(imp -> imp.getNameAsString().endsWith("." + simpleTypeName))
                .map(imp -> imp.getNameAsString())
                .findFirst()
                .orElse(null))
            .orElse(null);
    }

    /**
     * 创建入口点（从 CompilationUnit 解析）
     * 支持多种入口类型：HTTP、MQ、定时任务、事件监听等
     */
    private List<EntryPointNode> createEntryPoints(CompilationUnit cu, String projectPath) {
        List<EntryPointNode> entryPoints = new ArrayList<>();

        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ?
                clazz.getNameAsString() :
                packageName + "." + clazz.getNameAsString();

            // 提取类级别的 URI 路径（来自 @RequestMapping）
            String classLevelPath = extractClassLevelPath(clazz);

            // 检查类级 @FeignClient 注解
            boolean isFeignClient = clazz.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("FeignClient"));

            // 如果是 FeignClient 接口，提取 serviceName 和 basePath
            final String feignServiceName;
            final String feignBasePath;
            if (isFeignClient) {
                feignServiceName = extractFeignServiceName(clazz);
                String requestMappingPath = extractClassLevelPath(clazz);
                String feignPathAttr = extractFeignPath(clazz);
                if (feignPathAttr != null && !feignPathAttr.isEmpty()) {
                    feignBasePath = combinePaths(feignPathAttr, requestMappingPath);
                } else {
                    feignBasePath = requestMappingPath;
                }
            } else {
                feignServiceName = null;
                feignBasePath = "";
            }

            // 扫描所有方法
            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                // methodNodeId 必须与 MethodNode 的 nodeId 格式一致: projectPath:className.methodName.hash
                String methodId = className + "." + method.getNameAsString() + "." +
                    signatureHash(method.getSignature().toString());
                String nodeId = projectPath + ":" + methodId;

                // FeignClient 接口的方法 → 注册为 FEIGN_CLIENT 入口点
                if (isFeignClient) {
                    String httpMethod = "GET"; // default
                    String methodPath = "";

                    // 从方法的 HTTP 映射注解提取 httpMethod 和 path
                    for (com.github.javaparser.ast.expr.AnnotationExpr ann : method.getAnnotations()) {
                        String annName = ann.getNameAsString();
                        if (annName.equals("GetMapping") || annName.equals("PostMapping") ||
                            annName.equals("PutMapping") || annName.equals("DeleteMapping") ||
                            annName.equals("PatchMapping") || annName.equals("RequestMapping")) {
                            httpMethod = extractHttpMethod(annName);
                            methodPath = extractMethodLevelPath(ann);
                            break;
                        }
                    }

                    String fullPath = combinePaths(feignBasePath, methodPath);
                    if (fullPath.isEmpty()) {
                        fullPath = "/" + method.getNameAsString(); // fallback: use method name as path
                    }

                    String entryKey = feignServiceName + "|" + httpMethod + " " + fullPath;
                    String entryId = projectPath + ":FEIGN_" + className + "." + method.getNameAsString();

                    EntryPointNode entry = EntryPointNode.builder()
                        .entryId(entryId)
                        .entryType(EntryPointNode.TYPE_FEIGN_CLIENT)
                        .entryKey(entryKey)
                        .entryInfo(buildEntryInfo(className, method))
                        .projectPath(projectPath)
                        .methodNodeId(nodeId)
                        .serviceName(feignServiceName)
                        .build();
                    entryPoints.add(entry);

                    log.debug("[KG EntryPoint] FEIGN_CLIENT: {}.{} -> service={}, key={}",
                        className, method.getNameAsString(), feignServiceName, entryKey);
                    return; // skip the rest of method annotation checks for this method
                }

                // 1. HTTP 入口点 - RequestMapping 系列
                for (com.github.javaparser.ast.expr.AnnotationExpr annotation : method.getAnnotations()) {
                    String annotationName = annotation.getNameAsString();

                    if (annotationName.equals("RequestMapping") ||
                        annotationName.equals("GetMapping") ||
                        annotationName.equals("PostMapping") ||
                        annotationName.equals("PutMapping") ||
                        annotationName.equals("DeleteMapping") ||
                        annotationName.equals("PatchMapping")) {

                        // 提取方法级别的路径
                        String methodLevelPath = extractMethodLevelPath(annotation);
                        // 拼接类级别和方法级别路径
                        String fullPath = combinePaths(classLevelPath, methodLevelPath);
                        // 提取 HTTP 方法
                        String httpMethod = extractHttpMethod(annotationName);

                        String entryKey = httpMethod + " " + fullPath;

                        // entryId 格式: projectPath:HTTP_方法标识，确保全局唯一
                        // 同时冗余存储 projectPath、entryKey
                        String entryId = projectPath + ":HTTP_" + className + "." + method.getNameAsString();

                        EntryPointNode entry = EntryPointNode.builder()
                            .entryId(entryId)
                            .entryType(EntryPointNode.TYPE_HTTP)
                            .entryKey(entryKey)
                            .entryInfo(buildEntryInfo(className, method))
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .build();

                        entryPoints.add(entry);
                        log.debug("发现HTTP入口点: {} -> {}", entryKey, nodeId);
                    }

                    // 2. 定时任务入口点
                    else if (annotationName.equals("Scheduled")) {
                        String cron = extractScheduledCron(annotation);
                        String entryKey = "SCHEDULED:" + className + "." + method.getNameAsString();
                        if (cron != null && !cron.isEmpty()) {
                            entryKey = entryKey + ":" + cron;
                        }

                        String entryId = projectPath + ":SCHEDULED_" + className + "." + method.getNameAsString();

                        EntryPointNode entry = EntryPointNode.builder()
                            .entryId(entryId)
                            .entryType(EntryPointNode.TYPE_SCHEDULED)
                            .entryKey(entryKey)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .build();

                        entryPoints.add(entry);
                        log.debug("发现定时任务入口点: {} -> {}", entryKey, nodeId);
                    }

                    // 3. MQ 消费者入口点
                    else if (annotationName.equals("RabbitListener") ||
                             annotationName.equals("KafkaListener") ||
                             annotationName.equals("RocketMQMessageListener")) {

                        String topic = extractTopicFromAnnotation(annotation);
                        String entryKey = "MQ:" + className + "." + method.getNameAsString() + ":" + topic;

                        String entryId = projectPath + ":MQ_" + className + "." + method.getNameAsString();

                        EntryPointNode entry = EntryPointNode.builder()
                            .entryId(entryId)
                            .entryType(EntryPointNode.TYPE_MQ_CONSUMER)
                            .entryKey(entryKey)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .build();

                        entryPoints.add(entry);
                        log.debug("发现MQ入口点: {} -> {}", entryKey, nodeId);
                    }

                    // 4. 事件监听入口点
                    else if (annotationName.equals("EventListener") ||
                             annotationName.equals("TransactionalEventListener")) {

                        String eventType = extractEventType(annotation);
                        String entryKey = "EVENT:" + eventType;

                        String entryId = projectPath + ":EVENT_" + className + "." + method.getNameAsString();

                        EntryPointNode entry = EntryPointNode.builder()
                            .entryId(entryId)
                            .entryType("EVENT")
                            .entryKey(entryKey)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .build();

                        entryPoints.add(entry);
                        log.debug("发现事件监听入口点: {} -> {}", entryKey, nodeId);
                    }

                    // 5. 生命周期入口点
                    else if (annotationName.equals("PostConstruct") ||
                             annotationName.equals("PreDestroy") ||
                             annotationName.equals("AfterConstruct")) {

                        String entryKey = "LIFECYCLE:" + className + "." + method.getNameAsString();

                        String entryId = projectPath + ":LIFECYCLE_" + className + "." + method.getNameAsString();

                        EntryPointNode entry = EntryPointNode.builder()
                            .entryId(entryId)
                            .entryType("LIFECYCLE")
                            .entryKey(entryKey)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .build();

                        entryPoints.add(entry);
                        log.debug("发现生命周期入口点: {} -> {}", entryKey, nodeId);
                    }

                    // 6. RPC 服务入口点
                    else if (annotationName.equals("DubboService") ||
                             annotationName.equals("GrpcService") ||
                             annotationName.equals("RpcService")) {

                        String entryKey = "RPC:" + className + "." + method.getNameAsString();

                        String entryId = projectPath + ":RPC_" + className + "." + method.getNameAsString();

                        EntryPointNode entry = EntryPointNode.builder()
                            .entryId(entryId)
                            .entryType(EntryPointNode.TYPE_GRPC)
                            .entryKey(entryKey)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .build();

                        entryPoints.add(entry);
                        log.debug("发现RPC入口点: {} -> {}", entryKey, nodeId);
                    }
                }
            });
        });

        if (!entryPoints.isEmpty()) {
            log.info("从文件解析到 {} 个入口点", entryPoints.size());
        }
        return entryPoints;
    }

    /**
     * 构建入口点的 entryInfo JSON，包含方法签名、参数信息和返回类型。
     * JSON 格式:
     * {
     *   "className": "com.example.UserController",
     *   "methodName": "createUser",
     *   "returnType": "ResponseEntity",
     *   "parameters": [
     *     {"name":"user","type":"UserDto","annotations":["RequestBody"]},
     *     {"name":"id","type":"Long","annotations":["PathVariable"]}
     *   ]
     * }
     */
    private String buildEntryInfo(String className, MethodDeclaration method) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"className\":\"").append(escapeJson(className)).append("\"");
            sb.append(",\"methodName\":\"").append(escapeJson(method.getNameAsString())).append("\"");
            sb.append(",\"returnType\":\"").append(escapeJson(method.getType().asString())).append("\"");

            sb.append(",\"parameters\":[");
            var params = method.getParameters();
            for (int i = 0; i < params.size(); i++) {
                var param = params.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"name\":\"").append(escapeJson(param.getNameAsString())).append("\"");
                sb.append(",\"type\":\"").append(escapeJson(param.getType().asString())).append("\"");

                // Collect Spring MVC parameter annotations
                sb.append(",\"annotations\":[");
                var annotations = param.getAnnotations();
                boolean first = true;
                for (var ann : annotations) {
                    String annName = ann.getNameAsString();
                    if (!first) sb.append(",");
                    sb.append("\"").append(escapeJson(annName)).append("\"");
                    first = false;
                }
                sb.append("]");

                // Extract annotation value (e.g., @PathVariable("userId") → "userId")
                for (var ann : annotations) {
                    String annName = ann.getNameAsString();
                    if (annName.equals("PathVariable") || annName.equals("RequestParam")
                        || annName.equals("RequestHeader") || annName.equals("CookieValue")) {
                        String val = extractAnnotationValue(ann);
                        if (val != null && !val.isEmpty()) {
                            sb.append(",\"aliasName\":\"").append(escapeJson(val)).append("\"");
                        }
                        // Extract defaultValue and required for @RequestParam
                        if (annName.equals("RequestParam")) {
                            String defaultVal = extractAnnotationAttribute(ann, "defaultValue");
                            if (defaultVal != null) {
                                sb.append(",\"defaultValue\":\"").append(escapeJson(defaultVal)).append("\"");
                            }
                            String required = extractAnnotationAttribute(ann, "required");
                            if (required != null) {
                                sb.append(",\"required\":").append(required);
                            }
                        }
                        break;
                    }
                }

                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to build entryInfo for {}.{}: {}", className, method.getNameAsString(), e.getMessage());
            return "{\"className\":\"" + escapeJson(className) + "\",\"methodName\":\"" + escapeJson(method.getNameAsString()) + "\",\"parameters\":[]}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 从注解中提取 value 属性值
     * 支持 @PathVariable("id") 和 @PathVariable(value = "id")
     */
    private String extractAnnotationValue(com.github.javaparser.ast.expr.AnnotationExpr ann) {
        if (ann instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr single) {
            return single.getMemberValue().toString().replace("\"", "");
        }
        if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("name")) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return null;
    }

    /**
     * 从注解中提取指定属性的值
     */
    private String extractAnnotationAttribute(com.github.javaparser.ast.expr.AnnotationExpr ann, String attrName) {
        if (ann instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(attrName)) {
                    String val = pair.getValue().toString().replace("\"", "");
                    // Filter out Java default sentinel values
                    if (val.equals("\\n\\t\\t\\n\\t\\t\\n\\uE000\\uE001\\uE002\\n\\t\\t\\t\\t\\n")) {
                        return null;
                    }
                    return val;
                }
            }
        }
        return null;
    }
    private String extractClassLevelPath(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz) {
        for (com.github.javaparser.ast.expr.AnnotationExpr annotation : clazz.getAnnotations()) {
            if (annotation.getNameAsString().equals("RequestMapping")) {
                return extractPathFromAnnotation(annotation);
            }
        }
        return "";
    }

    /**
     * 提取方法级别的 URI 路径
     */
    private String extractMethodLevelPath(com.github.javaparser.ast.expr.AnnotationExpr annotation) {
        return extractPathFromAnnotation(annotation);
    }

    /**
     * 从注解提取路径值
     */
    private String extractPathFromAnnotation(com.github.javaparser.ast.expr.AnnotationExpr annotation) {
        String path = "";

        // 尝试从注解中提取路径值
        if (annotation instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr) {
            com.github.javaparser.ast.expr.SingleMemberAnnotationExpr singleMember =
                (com.github.javaparser.ast.expr.SingleMemberAnnotationExpr) annotation;
            path = singleMember.getMemberValue().toString();
            // 移除引号
            path = path.replace("\"", "");
        } else if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr) {
            com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnnotation =
                (com.github.javaparser.ast.expr.NormalAnnotationExpr) annotation;
            for (com.github.javaparser.ast.expr.MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    path = pair.getValue().toString().replace("\"", "");
                    break;
                }
            }
        }

        return path;
    }

    /**
     * 拼接类级别和方法级别的路径
     */
    private String combinePaths(String classPath, String methodPath) {
        classPath = classPath == null ? "" : classPath;
        methodPath = methodPath == null ? "" : methodPath;

        // 移除首尾斜杠以便统一处理
        classPath = classPath.replaceAll("^/+|/+$", "");
        methodPath = methodPath.replaceAll("^/+|/+$", "");

        if (classPath.isEmpty() && methodPath.isEmpty()) {
            return "/";
        } else if (classPath.isEmpty()) {
            return "/" + methodPath;
        } else if (methodPath.isEmpty()) {
            return "/" + classPath;
        } else {
            return "/" + classPath + "/" + methodPath;
        }
    }

    /**
     * 根据注解名提取 HTTP 方法
     */
    private String extractHttpMethod(String annotationName) {
        return switch (annotationName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> "GET"; // RequestMapping 默认 GET
        };
    }

    /**
     * 从 @FeignClient 注解提取 serviceName (name/value 属性)
     */
    private String extractFeignServiceName(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz) {
        for (com.github.javaparser.ast.expr.AnnotationExpr annotation : clazz.getAnnotations()) {
            if (!annotation.getNameAsString().equals("FeignClient")) continue;

            if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnnotation) {
                for (var pair : normalAnnotation.getPairs()) {
                    if (pair.getNameAsString().equals("name") || pair.getNameAsString().equals("value")) {
                        String value = pair.getValue().toString().replaceAll("\"", "").trim();
                        if (!value.isEmpty()) return value;
                    }
                }
            } else if (annotation instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr singleAnnotation) {
                String value = singleAnnotation.getMemberValue().toString().replaceAll("\"", "").trim();
                if (!value.isEmpty()) return value;
            }
        }
        return clazz.getNameAsString(); // fallback: use class name
    }

    /**
     * 从 @FeignClient 注解提取 path 属性
     */
    private String extractFeignPath(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration clazz) {
        for (com.github.javaparser.ast.expr.AnnotationExpr annotation : clazz.getAnnotations()) {
            if (!annotation.getNameAsString().equals("FeignClient")) continue;

            if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnnotation) {
                for (var pair : normalAnnotation.getPairs()) {
                    if (pair.getNameAsString().equals("path")) {
                        String value = pair.getValue().toString().replaceAll("\"", "").trim();
                        // Handle array syntax: {"value"} → value
                        if (value.startsWith("{") && value.endsWith("}")) {
                            value = value.substring(1, value.length() - 1).trim();
                            if (value.contains(",")) value = value.split(",")[0].trim();
                            value = value.replaceAll("\"", "").trim();
                        }
                        return value;
                    }
                }
            }
        }
        return "";
    }

    /**
     * 从注解提取 Topic
     */
    private String extractTopicFromAnnotation(com.github.javaparser.ast.expr.AnnotationExpr annotation) {
        if (annotation instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr) {
            com.github.javaparser.ast.expr.SingleMemberAnnotationExpr singleMember =
                (com.github.javaparser.ast.expr.SingleMemberAnnotationExpr) annotation;
            return singleMember.getMemberValue().toString().replace("\"", "");
        } else if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr) {
            com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnnotation =
                (com.github.javaparser.ast.expr.NormalAnnotationExpr) annotation;
            for (com.github.javaparser.ast.expr.MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("queues") ||
                    pair.getNameAsString().equals("topics") ||
                    pair.getNameAsString().equals("value")) {
                    return pair.getValue().toString().replace("\"", "").replace("{", "").replace("}", "");
                }
            }
        }
        return annotation.toString();
    }

    /**
     * 从 @Scheduled 注解提取 cron 表达式
     */
    private String extractScheduledCron(com.github.javaparser.ast.expr.AnnotationExpr annotation) {
        if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr) {
            com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnnotation =
                (com.github.javaparser.ast.expr.NormalAnnotationExpr) annotation;
            for (com.github.javaparser.ast.expr.MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("cron")) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return null;
    }

    /**
     * 从事件监听注解提取事件类型
     */
    private String extractEventType(com.github.javaparser.ast.expr.AnnotationExpr annotation) {
        if (annotation instanceof com.github.javaparser.ast.expr.SingleMemberAnnotationExpr) {
            com.github.javaparser.ast.expr.SingleMemberAnnotationExpr singleMember =
                (com.github.javaparser.ast.expr.SingleMemberAnnotationExpr) annotation;
            return singleMember.getMemberValue().toString().replace("\"", "");
        } else if (annotation instanceof com.github.javaparser.ast.expr.NormalAnnotationExpr) {
            com.github.javaparser.ast.expr.NormalAnnotationExpr normalAnnotation =
                (com.github.javaparser.ast.expr.NormalAnnotationExpr) annotation;
            for (com.github.javaparser.ast.expr.MemberValuePair pair : normalAnnotation.getPairs()) {
                if (pair.getNameAsString().equals("classes") || pair.getNameAsString().equals("value")) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        }
        return "UnknownEvent";
    }

    /**
     * 从 GlobalCache 转换接口-实现关系到专用表
     */
    private List<InterfaceImplementation> convertFromGlobalCache(String projectPath) {
        List<InterfaceImplementation> impls = new ArrayList<>();

        // 1. 直接的 implements 关系
        globalCache.getImplementationMap().forEach((interfaceName, implNames) -> {
            for (String implName : implNames) {
                String implType = globalCache.getProxyIndex().containsKey(implName) ? "FEIGN_PROXY" : "LOCAL";
                impls.add(InterfaceImplementation.builder()
                    .interfaceName(interfaceName)
                    .implementationName(implName)
                    .projectPath(projectPath)
                    .implType(implType)
                    .build());
            }
        });

        // 2. 通过 extendMap 传递 implements 关系
        // 场景: class B extends A, A implements Interface → B 也 IMPLEMENTS Interface
        Map<String, Set<String>> extendMap = globalCache.getExtendMap();
        Map<String, Set<String>> implMap = globalCache.getImplementationMap();

        Set<String> processedExtends = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : extendMap.entrySet()) {
            String childClass = entry.getKey();
            Set<String> ancestorInterfaces = resolveAncestorInterfaces(childClass, extendMap, implMap, processedExtends);
            for (String ifaceName : ancestorInterfaces) {
                // 检查是否已经有直接的 implements 关系
                Set<String> directImpls = implMap.getOrDefault(ifaceName, Set.of());
                if (!directImpls.contains(childClass)) {
                    String implType = globalCache.getProxyIndex().containsKey(childClass) ? "FEIGN_PROXY" : "LOCAL";
                    impls.add(InterfaceImplementation.builder()
                        .interfaceName(ifaceName)
                        .implementationName(childClass)
                        .projectPath(projectPath)
                        .implType(implType)
                        .build());
                }
            }
        }

        int directCount = globalCache.getImplementationMap().values().stream().mapToInt(Set::size).sum();
        log.info("[KG] convertFromGlobalCache: directImpls={}, totalWithInherited={}", directCount, impls.size());

        return impls;
    }

    /**
     * BFS 沿继承链向上追溯，收集祖先类直接实现的所有接口
     */
    private Set<String> resolveAncestorInterfaces(
            String className,
            Map<String, Set<String>> extendMap,
            Map<String, Set<String>> implMap,
            Set<String> visited) {

        Set<String> interfaces = new HashSet<>();
        if (visited.contains(className)) {
            return interfaces; // 防止循环继承
        }
        visited.add(className);

        Set<String> parents = extendMap.getOrDefault(className, Set.of());
        for (String parent : parents) {
            // 父类直接实现的接口（作为 implMap 中的 key，value 包含 parent）
            for (Map.Entry<String, Set<String>> implEntry : implMap.entrySet()) {
                if (implEntry.getValue().contains(parent)) {
                    interfaces.add(implEntry.getKey());
                }
            }
            // 递归追溯祖父类
            interfaces.addAll(resolveAncestorInterfaces(parent, extendMap, implMap, visited));
        }

        return interfaces;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 保存知识图谱生成日志（供增量更新使用）
     */
    private void saveGenerationLog(String projectPath, int methodCount, int callRelationCount,
                                   int entryPointCount, int implCount, long startTime) {
        // Normalize path to ensure consistency
        String normalizedProjectPath = com.huawei.hisi.knowledgegraph.util.ProjectPathResolver.normalize(projectPath);

        long costTimeMs = System.currentTimeMillis() - startTime;
        long nowEpoch = java.time.Instant.now().getEpochSecond();
        long startEpoch = nowEpoch - (costTimeMs / 1000);

        // Retrieve current commit hash for incremental update support
        String commitHash = null;
        try {
            com.huawei.hisi.knowledgegraph.model.GitStatus gitStatus =
                gitStatusService.getGitStatus(normalizedProjectPath);
            commitHash = gitStatus.getCommitHash();
        } catch (Exception e) {
            this.log.warn("获取 Git commit hash 失败: {}", e.getMessage());
        }

        // 1. 保存 SQLite 生成日志（best-effort，失败不影响主流程）
        try {
            GenerationTask logTask = GenerationTask.builder()
                .taskType("KG_LOG")
                .projectPath(normalizedProjectPath)
                .status("COMPLETED")
                .totalCount(methodCount)
                .progress(methodCount)
                .successCount(methodCount)
                .failCount(0)
                .errorMessage(commitHash)
                .startedAt(startEpoch)
                .finishedAt(nowEpoch)
                .build();

            generationTaskRepository.insert(logTask);
            this.log.info("知识图谱生成日志已保存: projectPath={}, commitHash={}", normalizedProjectPath, commitHash);
        } catch (Exception e) {
            this.log.warn("保存知识图谱生成日志失败（不影响增量刷新）: {}", e.getMessage());
        }

        // 2. 保存 Neo4j checkpoint（失败必须可见，否则增量刷新会报 409）
        String branch = null;
        try {
            branch = gitStatusService.getCurrentBranch(normalizedProjectPath);
        } catch (Exception ge) {
            this.log.debug("获取分支失败，checkpoint 将以 null 分支保存: {}", ge.getMessage());
        }
        String effectiveCommit = commitHash != null ? commitHash : "NO_COMMIT";
        checkpointRepository.upsertCheckpoint(normalizedProjectPath, effectiveCommit, branch);
        this.log.info("增量刷新 checkpoint 已保存: projectPath={}, commit={}, branch={}",
                normalizedProjectPath, effectiveCommit, branch);
    }

    private void cleanOldData(String projectPath) {
        // 1. 清理 Neo4j 核心图数据（方法节点、入口点、调用关系、DataModel、向量和描述）
        log.info("[Neo4j] 清理旧数据: {}", projectPath);
        storageService.cleanProjectData(projectPath);

        // 2. 清理 Neo4j SQL 节点和 EXECUTES_SQL 关系
        log.info("[Neo4j] 清理 SQL 节点和 EXECUTES_SQL 关系: {}", projectPath);
        neo4jSqlNodeRepository.deleteExecutesSqlRelationsByProjectPath(projectPath);
        long sqlDeleted;
        int sqlTotal = 0;
        do {
            sqlDeleted = neo4jSqlNodeRepository.deleteByProjectPathBatch(projectPath, 2000);
            sqlTotal += sqlDeleted;
        } while (sqlDeleted > 0);
        log.info("[Neo4j] 分批删除 SQL 节点完成: projectPath={}, 共删除 {} 个", projectPath, sqlTotal);

        // 3. 清理向量生成任务状态
        log.info("[SQLite] 清理生成任务状态: {}", projectPath);
        generationTaskRepository.deleteByProjectPath(projectPath);
    }

    private CombinedTypeSolver buildSolver(List<Path> sourceRoots) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver());
        for (Path root : sourceRoots) {
            solver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver(root));
        }
        return solver;
    }

    private int calculateComplexity(MethodDeclaration method) {
        int complexity = 1;
        complexity += method.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.DoStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.CatchClause.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.SwitchEntry.class).size();
        complexity += method.findAll(com.github.javaparser.ast.expr.ConditionalExpr.class).size();
        complexity += (int) method.findAll(com.github.javaparser.ast.expr.BinaryExpr.class).stream()
            .filter(b -> b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.AND
                      || b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.OR)
            .count();
        return complexity;
    }

    private String getMethodClassName(MethodDeclaration method) {
        return method.findCompilationUnit()
            .map(cu -> {
                String pkg = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString() + ".")
                    .orElse("");
                return method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .map(clazz -> pkg + clazz.getNameAsString())
                    .orElse("Unknown");
            })
            .orElse("Unknown");
    }

    /**
     * 从类名提取服务名
     * 结合项目路径生成唯一 serviceName，避免不同项目同名类冲突
     * 例如: hisi-devtool + com.example.service.UserServiceImpl -> hisi-devtool:User
     */
    private String extractServiceName(String className, String projectPath) {
        if (className == null || className.isEmpty()) {
            return "unknown";
        }

        // 1. 从类名提取核心名称
        int lastDot = className.lastIndexOf('.');
        String simpleClassName = lastDot > 0 ? className.substring(lastDot + 1) : className;

        // 2. 移除常见后缀
        String coreName = simpleClassName.replaceAll("(Controller|Service|Impl|Repository|Handler|Endpoint)$", "");

        // 3. 结合项目路径生成唯一 serviceName
        String projectShortName = extractProjectShortName(projectPath);
        return projectShortName + ":" + coreName;
    }

    /**
     * 从项目路径提取短名称
     * 例如: /path/to/hisi-dev-tool -> hisi-devtool
     */
    private String extractProjectShortName(String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) return "default";
        Path p = Paths.get(projectPath);
        String name = p.getFileName() != null ? p.getFileName().toString() : "default";
        // 移除常见前缀后缀，保持简洁
        return name.replaceAll("(^hisi-|-dev-tool$|-backend$|-service$|-api$)", "");
    }

    /**
     * 判断调用类型
     * 支持类型：
     * - STATIC: 静态方法调用 (ClassName.methodName)
     * - DIRECT: 直接实例方法调用 (methodName 或 this.methodName 或 variable.methodName)
     * - CONSTRUCTOR: 构造方法调用 (new ClassName())
     * - INTERFACE: 接口方法调用 (待增强)
     * - VIRTUAL: 虚方法调用 (待增强)
     * - LAMBDA: Lambda表达式调用 (待增强)
     */
    private String determineCallType(MethodCallExpr call) {
        // 1. 检查是否是构造方法调用 (new ClassName())
        // 构造方法调用在JavaParser中通过 ObjectCreationExpr 表示
        // 方法调用表达式不包含构造方法，这里保留扩展点

        // 2. 检查是否在Lambda表达式中
        if (call.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).isPresent()) {
            return "LAMBDA";
        }

        // 3. 检查是否有调用者（scope）
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();

            // 静态方法调用：调用者是大写字母开头的类名
            // 例如：Utils.staticMethod(), Collections.emptyList()
            if (scope.matches("([A-Z][a-zA-Z0-9]*\\.)+[A-Z][a-zA-Z0-9]*") ||
                scope.matches("[A-Z][a-zA-Z0-9]*")) {
                return "STATIC";
            }

            // this 调用：this.methodName()
            if ("this".equals(scope)) {
                return "DIRECT";
            }

            // super 调用：super.methodName() - 这是虚方法调用
            if ("super".equals(scope)) {
                return "VIRTUAL";
            }

            // 变量调用：variable.methodName()
            // 可能是接口调用或虚方法调用，简化处理返回 DIRECT
            // 后续可通过类型解析确定具体类型
            if (scope.matches("[a-z][a-zA-Z0-9]*")) {
                return "DIRECT";
            }
        }

        // 4. 无调用者的方法调用（当前类的方法）
        return "DIRECT";
    }

    // ============================================================
    // 类继承关系、方法重写关系、代理关系构建方法
    // ============================================================

    /**
     * 从 GlobalCache 构建类继承关系 EXTENDS
     */
    private List<ClassExtends> buildExtendsRelations(String projectPath) {
        List<ClassExtends> relations = new ArrayList<>();

        Map<String, Set<String>> extendMap = globalCache.getExtendMap();
        for (Map.Entry<String, Set<String>> entry : extendMap.entrySet()) {
            String subclass = entry.getKey();
            for (String superclass : entry.getValue()) {
                relations.add(ClassExtends.builder()
                    .subclass(subclass)
                    .superclass(superclass)
                    .projectPath(projectPath)
                    .build());
            }
        }

        return relations;
    }

    /**
     * 构建方法重写关系 OVERRIDE
     * 遍历所有类，找出子类重写父类的方法
     */
    private List<MethodOverride> buildOverrideRelations(String projectPath, List<MethodNode> allMethodNodes) {
        List<MethodOverride> relations = new ArrayList<>();

        Map<String, Set<String>> extendMap = globalCache.getExtendMap();

        // 建立 className -> methodNames 索引
        Map<String, Set<String>> classMethods = new HashMap<>();
        for (MethodNode node : allMethodNodes) {
            classMethods
                .computeIfAbsent(node.getClassName(), k -> new HashSet<>())
                .add(node.getMethodName());
        }

        // 遍历继承关系，查找子类重写父类方法的情况
        for (Map.Entry<String, Set<String>> entry : extendMap.entrySet()) {
            String subclass = entry.getKey();
            Set<String> subclassMethods = classMethods.getOrDefault(subclass, Set.of());

            for (String superclass : entry.getValue()) {
                Set<String> superclassMethods = classMethods.getOrDefault(superclass, Set.of());

                // 找出子类和父类都有的方法（可能重写）
                for (String methodName : subclassMethods) {
                    if (superclassMethods.contains(methodName)) {
                        relations.add(MethodOverride.builder()
                            .subclass(subclass)
                            .superclass(superclass)
                            .methodName(methodName)
                            .projectPath(projectPath)
                            .build());
                    }
                }
            }
        }

        return relations;
    }

    /**
     * 从 GlobalCache 构建代理类关系 PROXY
     */
    private List<ProxyRelation> buildProxyRelations(String projectPath) {
        List<ProxyRelation> relations = new ArrayList<>();

        Map<String, String> proxyIndex = globalCache.getProxyIndex();
        Map<String, Set<String>> implMap = globalCache.getImplementationMap();

        for (Map.Entry<String, String> entry : proxyIndex.entrySet()) {
            String proxyClass = entry.getKey();
            String proxyType = entry.getValue();

            // 查找这个代理类实现的接口，作为 targetClass
            String targetClass = null;
            for (Map.Entry<String, Set<String>> implEntry : implMap.entrySet()) {
                if (implEntry.getValue().contains(proxyClass)) {
                    targetClass = implEntry.getKey();
                    break;
                }
            }

            // 如果没有找到接口，尝试从 extendMap 查找父类
            if (targetClass == null) {
                Set<String> parents = globalCache.getExtendMap().getOrDefault(proxyClass, Set.of());
                if (!parents.isEmpty()) {
                    targetClass = parents.iterator().next();
                }
            }

            // 如果还是没找到，跳过这个代理类（不创建自引用关系）
            if (targetClass == null || targetClass.equals(proxyClass)) {
                continue;
            }

            relations.add(ProxyRelation.builder()
                .proxyClass(proxyClass)
                .targetClass(targetClass)
                .proxyType(proxyType)
                .projectPath(projectPath)
                .build());
        }

        return relations;
    }

    /**
     * 计算方法签名的稳定 hash（基于签名字符串，不依赖 AST 对象身份）
     * 解决两次独立 parseFile 导致 getSignature().hashCode() 不一致的问题
     */
    private static String signatureHash(String signature) {
        return Integer.toHexString(signature.hashCode());
    }
}
