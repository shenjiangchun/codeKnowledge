package com.huawei.hisi.ram.kg.impl;

import com.huawei.hisi.knowledgegraph.model.BridgeStats;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.service.HybridSearchService;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.kg.dto.SqlMapping;
import com.huawei.hisi.utils.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Embedded implementation of {@link KgMcpClient} that calls internal Neo4j
 * services directly, bypassing the external MCP HTTP endpoint.
 *
 * <p>This is the sole {@code KgMcpClient} implementation — it requires a running
 * Neo4j instance (configured via {@code neo4j.uri}).</p>
 */
@Component
@ConditionalOnProperty(name = "neo4j.uri")
public class DirectKgClient implements KgMcpClient {

    private static final Logger log = LoggerFactory.getLogger(DirectKgClient.class);

    private final HybridSearchService hybridSearchService;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointRepository;
    private final Neo4jSqlNodeRepository sqlNodeRepository;

    public DirectKgClient(HybridSearchService hybridSearchService,
                          Neo4jMethodNodeRepository methodNodeRepository,
                          Neo4jEntryPointNodeRepository entryPointRepository,
                          Neo4jSqlNodeRepository sqlNodeRepository) {
        this.hybridSearchService = hybridSearchService;
        this.methodNodeRepository = methodNodeRepository;
        this.entryPointRepository = entryPointRepository;
        this.sqlNodeRepository = sqlNodeRepository;
        log.info("DirectKgClient initialised — using embedded Neo4j services");
    }

    // ─────────────────────── KgMcpClient methods ───────────────────────

    @Override
    public List<Seed> hybridSearch(String query, String projectPath, int limit) {
        String normPath = PathUtils.normalize(projectPath);
        return doHybridSearch(query, List.of(normPath), limit);
    }

    @Override
    public List<Seed> hybridSearch(String query, List<String> projectPaths, int limit) {
        List<String> normPaths = normalizePaths(projectPaths);
        return doHybridSearch(query, normPaths, limit);
    }

    private List<Seed> doHybridSearch(String query, List<String> normPaths, int limit) {
        try {
            String firstPath = normPaths.isEmpty() ? "" : normPaths.get(0);
            SearchResult result = hybridSearchService.hybridSearch(
                    query, firstPath, normPaths, null, limit, 0);
            if (result == null || result.getResults() == null) {
                return Collections.emptyList();
            }
            return result.getResults().stream()
                    .limit(limit > 0 ? limit : Long.MAX_VALUE)
                    .map(m -> new Seed(
                            m.getNodeId(),
                            0.0,
                            m.getDescription() != null ? m.getDescription() : m.getClassName() + "#" + m.getMethodName()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("hybridSearch failed for query='{}', projectPaths={}: {}", query, normPaths, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 类级语义检索：走 searchType=CLASS，结果从 items（nodeType=Class）转为 Seed */
    private List<Seed> doClassSearch(String query, List<String> normPaths, int limit) {
        try {
            String firstPath = normPaths.isEmpty() ? "" : normPaths.get(0);
            SearchResult result = hybridSearchService.hybridSearch(
                    query, firstPath, normPaths, null, limit, 0, "CLASS");
            if (result == null || result.getItems() == null) {
                return Collections.emptyList();
            }
            return result.getItems().stream()
                    .filter(i -> "Class".equals(i.getNodeType()))
                    .limit(limit > 0 ? limit : Long.MAX_VALUE)
                    .map(i -> new Seed(
                            i.getClassName(),  // 类检索的 seed 用 className 作标识
                            0.0,
                            i.getDescription() != null ? i.getDescription() : i.getClassName()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("classSearch failed for query='{}', projectPaths={}: {}", query, normPaths, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Seed> classSearch(String query, String projectPath, int limit) {
        String normPath = PathUtils.normalize(projectPath);
        return doClassSearch(query, List.of(normPath), limit);
    }

    @Override
    public List<Seed> classSearch(String query, List<String> projectPaths, int limit) {
        return doClassSearch(query, normalizePaths(projectPaths), limit);
    }

    @Override
    public List<Seed> representativeMethod(String className, String projectPath, int limit) {
        return representativeMethod(className, List.of(PathUtils.normalize(projectPath)), limit);
    }

    @Override
    public List<Seed> representativeMethod(String className, List<String> projectPaths, int limit) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty() || className == null || className.isBlank()) {
            return Collections.emptyList();
        }
        try {
            // 查该类所有方法，按入度降序取代表方法
            List<Seed> seeds = new ArrayList<>();
            for (String path : normPaths) {
                List<MethodNode> methods = methodNodeRepository.findByProjectPathAndClassName(path, className);
                methods.stream()
                    .sorted((a, b) -> Integer.compare(
                        b.getInDegree() != null ? b.getInDegree() : 0,
                        a.getInDegree() != null ? a.getInDegree() : 0))
                    .limit(limit > 0 ? limit : 1)
                    .forEach(m -> seeds.add(new Seed(
                        m.getNodeId(),
                        0.0,
                        m.getDescription() != null ? m.getDescription() : m.getMethodName())));
            }
            return seeds;
        } catch (Exception e) {
            log.warn("representativeMethod failed for className='{}': {}", className, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Entry> entryPoints(String projectPath, String entryType) {
        String normPath = PathUtils.normalize(projectPath);
        return entryPoints(List.of(normPath), entryType);
    }

    @Override
    public List<Entry> entryPoints(List<String> projectPaths, String entryType) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) return Collections.emptyList();
        try {
            List<EntryPointNode> nodes;
            if (entryType == null || "ALL".equalsIgnoreCase(entryType)) {
                nodes = entryPointRepository.findByProjectPaths(normPaths);
            } else {
                nodes = entryPointRepository.findByProjectPathsAndEntryType(normPaths, entryType);
            }
            return nodes.stream().map(this::toEntry).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("entryPoints failed for projectPaths={}: {}", normPaths, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BridgeStats bridgeStats(String projectPath) {
        return bridgeStats(List.of(projectPath));
    }

    @Override
    public BridgeStats bridgeStats(List<String> projectPaths) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) {
            return BridgeStats.builder().build();
        }
        try {
            int totalCallRelations = (int) methodNodeRepository.countCallRelationsByProjectPaths(normPaths);
            int mapperCallCount = (int) methodNodeRepository.countByBridgeTypeAndProjectPaths(normPaths, "MAPPER");
            int feignCallCount = (int) methodNodeRepository.countByBridgeTypeAndProjectPaths(normPaths, "FEIGN");
            int httpCallCount = (int) methodNodeRepository.countByBridgeTypeAndProjectPaths(normPaths, "HTTP");
            int mqCallCount = (int) methodNodeRepository.countByBridgeTypeAndProjectPaths(normPaths, "MQ");
            int jpaCallCount = (int) methodNodeRepository.countByBridgeTypeAndProjectPaths(normPaths, "JPA");
            int aspectCallCount = (int) methodNodeRepository.countByBridgeTypeAndProjectPaths(normPaths, "ASPECT");

            long myBatisSqlCount = sqlNodeRepository.countByProjectPaths(normPaths);
            int myBatisMapperCount = (int) sqlNodeRepository.countDistinctMapperInterfacesByProjectPaths(normPaths);

            int totalBridges = mapperCallCount + feignCallCount + httpCallCount + mqCallCount + jpaCallCount + aspectCallCount;
            int jumpableCount = feignCallCount + mqCallCount + httpCallCount;
            double jumpableRate = totalBridges > 0 ? (double) jumpableCount / totalBridges : 0.0;

            Map<String, Integer> bridgeTypeCounts = new HashMap<>();
            bridgeTypeCounts.put("MAPPER", mapperCallCount);
            bridgeTypeCounts.put("FEIGN", feignCallCount);
            bridgeTypeCounts.put("HTTP", httpCallCount);
            bridgeTypeCounts.put("MQ", mqCallCount);
            bridgeTypeCounts.put("JPA", jpaCallCount);
            bridgeTypeCounts.put("ASPECT", aspectCallCount);

            Map<String, Integer> externalServiceCalls = new HashMap<>();
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> feignCalls =
                    methodNodeRepository.findByBridgeTypeAndProjectPaths(normPaths, "FEIGN");
            for (var call : feignCalls) {
                if (call.targetService() != null) {
                    externalServiceCalls.merge(call.targetService(), 1, Integer::sum);
                }
            }

            Map<String, Integer> mqTopicCalls = new HashMap<>();
            List<Neo4jMethodNodeRepository.CallRelationWithNodes> mqCalls =
                    methodNodeRepository.findByBridgeTypeAndProjectPaths(normPaths, "MQ");
            for (var call : mqCalls) {
                if (call.targetEndpoint() != null) {
                    mqTopicCalls.merge(call.targetEndpoint(), 1, Integer::sum);
                }
            }

            return BridgeStats.builder()
                    .projectPath(String.join(",", normPaths))
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
        } catch (Exception e) {
            log.warn("bridgeStats failed for projectPaths={}: {}", normPaths, e.getMessage());
            return BridgeStats.builder().build();
        }
    }

    @Override
    public List<Impl> implementations(String interfaceName, String projectPath) {
        return implementations(interfaceName, List.of(PathUtils.normalize(projectPath)));
    }

    @Override
    public List<Impl> implementations(String interfaceName, List<String> projectPaths) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) return Collections.emptyList();
        try {
            if (interfaceName != null && interfaceName.contains(":")) {
                List<String> implNodeIds = methodNodeRepository.findImplementationMethodsByInterfaceMethod(interfaceName);
                if (!implNodeIds.isEmpty()) {
                    final String ifaceName = interfaceName;
                    return implNodeIds.stream()
                            .map(implId -> new Impl(implId, null, ifaceName))
                            .collect(Collectors.toList());
                }
                String extracted = extractClassNameFromNodeId(interfaceName);
                if (extracted != null) {
                    interfaceName = extracted;
                } else {
                    return Collections.emptyList();
                }
            }
            final String resolvedName = interfaceName;
            List<String> implClassNames = methodNodeRepository.findImplementationsByInterface(resolvedName, normPaths);
            return implClassNames.stream()
                    .map(className -> new Impl(null, className, resolvedName))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("implementations failed for interface='{}': {}", interfaceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public CallTreeNode calleesTree(String className, String methodName, String projectPath, int maxDepth) {
        return calleesTree(className, methodName, List.of(PathUtils.normalize(projectPath)), maxDepth);
    }

    @Override
    public CallTreeNode calleesTree(String className, String methodName, List<String> projectPaths, int maxDepth) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) {
            return new CallTreeNode(null, className, methodName, 0, List.of());
        }
        try {
            MethodNode root = resolveMethod(className, methodName, normPaths);
            if (root == null) {
                log.debug("calleesTree: no method found for className='{}' methodName='{}' in {}",
                        className, methodName, normPaths);
                return new CallTreeNode(null, className, methodName, 0, List.of());
            }
            return buildCallTree(root, maxDepth, 0, new HashSet<>());
        } catch (Exception e) {
            log.warn("calleesTree failed for {}#{}: {}", className, methodName, e.getMessage());
            return new CallTreeNode(null, className, methodName, 0, List.of());
        }
    }

    @Override
    public List<Entry> rootEntries(String className, String methodName, String projectPath) {
        return rootEntries(className, methodName, List.of(PathUtils.normalize(projectPath)));
    }

    @Override
    public List<Entry> rootEntries(String className, String methodName, List<String> projectPaths) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) return Collections.emptyList();
        try {
            // Wildcard: find all entry points whose call chain reaches the target class
            if ("*".equals(methodName)) {
                List<Entry> all = new ArrayList<>();
                for (String p : normPaths) {
                    List<EntryPointNode> eps = entryPointRepository.findEntryPointsAffectingClass(p, className, 10);
                    eps.forEach(ep -> all.add(toEntry(ep)));
                }
                return dedupEntries(all);
            }

            MethodNode target = resolveMethod(className, methodName, normPaths);
            if (target == null) return Collections.emptyList();
            List<MethodNode> callers = methodNodeRepository.findCallersUpToDepth(target.getNodeId(), 10);

            List<Entry> entries = new ArrayList<>();
            for (String p : normPaths) {
                for (MethodNode caller : callers) {
                    List<EntryPointNode> eps = entryPointRepository.findByProjectPathAndMethodNodeId(p, caller.getNodeId());
                    eps.forEach(ep -> entries.add(toEntry(ep)));
                }
                List<EntryPointNode> selfEps = entryPointRepository.findByProjectPathAndMethodNodeId(p, target.getNodeId());
                selfEps.forEach(ep -> entries.add(toEntry(ep)));
            }
            return dedupEntries(entries);
        } catch (Exception e) {
            log.warn("rootEntries failed for {}#{}: {}", className, methodName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Entry> affecting(String className, String methodName, String projectPath, int maxDepth) {
        return affecting(className, methodName, List.of(PathUtils.normalize(projectPath)), maxDepth);
    }

    @Override
    public List<Entry> affecting(String className, String methodName, List<String> projectPaths, int maxDepth) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) return Collections.emptyList();
        try {
            // Wildcard: find all upstream callers for the entire class
            if ("*".equals(methodName)) {
                List<MethodNode> callers = new ArrayList<>();
                for (String p : normPaths) {
                    callers.addAll(methodNodeRepository.findCallersUpToDepthByClassName(p, className, maxDepth));
                }
                if (log.isDebugEnabled()) {
                    log.debug("affecting: className='{}' wildcard maxDepth={} → {} upstream callers",
                            className, maxDepth, callers.size());
                }
                return dedupMethodNodes(callers);
            }

            MethodNode target = resolveMethod(className, methodName, normPaths);
            if (target == null) {
                log.debug("affecting: resolveMethod returned null for className='{}' methodName='{}' paths='{}'",
                        className, methodName, normPaths);
                return Collections.emptyList();
            }
            List<MethodNode> callers = methodNodeRepository.findCallersUpToDepth(target.getNodeId(), maxDepth);
            if (log.isDebugEnabled()) {
                log.debug("affecting: nodeId='{}' maxDepth={} → {} upstream callers",
                        target.getNodeId(), maxDepth, callers.size());
            }
            return dedupMethodNodes(callers);
        } catch (Exception e) {
            log.warn("affecting failed for {}#{}: {}", className, methodName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Entry> downstream(String nodeId, String projectPath, int maxDepth) {
        return downstream(nodeId, List.of(PathUtils.normalize(projectPath)), maxDepth);
    }

    @Override
    public List<Entry> downstream(String nodeId, List<String> projectPaths, int maxDepth) {
        // nodeId-based query is project-agnostic; projectPaths reserved for future scope filtering
        try {
            List<MethodNode> callees = methodNodeRepository.findCalleesUpToDepth(nodeId, maxDepth);
            return callees.stream().map(this::toEntry).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("downstream failed for nodeId='{}': {}", nodeId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Bridge> feignChain(String serviceName, String projectPath) {
        return feignChain(serviceName, List.of(PathUtils.normalize(projectPath)));
    }

    @Override
    public List<Bridge> feignChain(String serviceName, List<String> projectPaths) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) return Collections.emptyList();
        try {
            List<EntryPointNode> feignEntries = entryPointRepository.findByProjectPathsAndEntryType(normPaths, "FEIGN_CLIENT");
            return feignEntries.stream()
                    .filter(ep -> serviceName.equals(ep.getServiceName()))
                    .map(ep -> new Bridge(ep.getMethodNodeId(), "FEIGN", ep.getEntryKey()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("feignChain failed for service='{}': {}", serviceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Bridge> mqChain(String topic, String projectPath) {
        return mqChain(topic, List.of(PathUtils.normalize(projectPath)));
    }

    @Override
    public List<Bridge> mqChain(String topic, List<String> projectPaths) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) return Collections.emptyList();
        try {
            List<EntryPointNode> mqEntries = entryPointRepository.findByProjectPathsAndEntryType(normPaths, "MQ_CONSUMER");
            return mqEntries.stream()
                    .filter(ep -> ep.getEntryKey() != null && ep.getEntryKey().contains(topic))
                    .map(ep -> new Bridge(ep.getMethodNodeId(), "MQ", ep.getEntryKey()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("mqChain failed for topic='{}': {}", topic, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Bridge> bridges(String nodeId, String projectPath) {
        return bridges(nodeId, List.of(PathUtils.normalize(projectPath)));
    }

    @Override
    public List<Bridge> bridges(String nodeId, List<String> projectPaths) {
        // nodeId-based query is project-agnostic
        try {
            List<Bridge> result = new ArrayList<>();
            try {
                List<Neo4jMethodNodeRepository.FeignBridgeTarget> feignTargets =
                        methodNodeRepository.findFeignBridgeTargets(nodeId);
                for (var ft : feignTargets) {
                    result.add(new Bridge(ft.implNodeId(), "FEIGN", ft.ifaceNodeId()));
                }
            } catch (Exception e) {
                log.debug("No Feign bridges for nodeId='{}': {}", nodeId, e.getMessage());
            }
            try {
                List<Neo4jMethodNodeRepository.FeignBridgeCaller> feignCallers =
                        methodNodeRepository.findFeignBridgeCallers(nodeId);
                for (var fc : feignCallers) {
                    result.add(new Bridge(fc.feignNodeId(), "FEIGN", fc.ifaceNodeId()));
                }
            } catch (Exception e) {
                log.debug("No Feign caller bridges for nodeId='{}': {}", nodeId, e.getMessage());
            }
            return result;
        } catch (Exception e) {
            log.warn("bridges failed for nodeId='{}': {}", nodeId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<SqlMapping> mybatisSql(String mapperInterface, String projectPath) {
        return mybatisSql(mapperInterface, List.of(PathUtils.normalize(projectPath)));
    }

    @Override
    public List<SqlMapping> mybatisSql(String mapperInterface, List<String> projectPaths) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (normPaths.isEmpty()) return Collections.emptyList();
        try {
            List<SqlNode> sqlNodes;
            if (mapperInterface != null && !mapperInterface.isBlank()) {
                sqlNodes = sqlNodeRepository.findByMapperInterfaceAndProjectPaths(normPaths, mapperInterface);
            } else {
                sqlNodes = sqlNodeRepository.findByProjectPaths(normPaths);
            }
            return sqlNodes.stream()
                    .map(s -> new SqlMapping(
                            s.getMapperInterface(),
                            s.getStatementType(),
                            Collections.emptyList()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("mybatisSql failed for mapper='{}': {}", mapperInterface, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<MethodBodyInfo> loadMethodBodies(List<String> nodeIds, String projectPath) {
        return loadMethodBodies(nodeIds, projectPath != null ? List.of(PathUtils.normalize(projectPath)) : List.of());
    }

    @Override
    public List<MethodBodyInfo> loadMethodBodies(List<String> nodeIds, List<String> projectPaths) {
        if (nodeIds == null || nodeIds.isEmpty()) return Collections.emptyList();
        try {
            List<MethodNode> nodes = methodNodeRepository.findAllByNodeIds(nodeIds);
            return nodes.stream()
                    .map(n -> new MethodBodyInfo(
                            n.getNodeId(),
                            n.getClassName(),
                            n.getMethodName(),
                            n.getDescription(),
                            n.getMethodBody(),
                            n.getFilePath()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("loadMethodBodies failed for {} nodeIds: {}", nodeIds.size(), e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Entry> rootEntryAncestors(List<String> nodeIds, String projectPath, int maxDepth) {
        String normPath = PathUtils.normalize(projectPath);
        return rootEntryAncestors(nodeIds, List.of(normPath), maxDepth);
    }

    @Override
    public List<Entry> rootEntryAncestors(List<String> nodeIds, List<String> projectPaths, int maxDepth) {
        List<String> normPaths = normalizePaths(projectPaths);
        if (nodeIds == null || nodeIds.isEmpty() || normPaths.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Set<String> seen = new HashSet<>();
            List<Entry> rootEntries = new ArrayList<>();
            for (String nodeId : nodeIds) {
                if (nodeId == null || seen.contains(nodeId)) continue;

                for (String p : normPaths) {
                    List<EntryPointNode> selfEps = entryPointRepository.findByProjectPathAndMethodNodeId(p, nodeId);
                    for (EntryPointNode ep : selfEps) {
                        rootEntries.add(toEntry(ep));
                    }
                }

                List<MethodNode> callers = methodNodeRepository.findCallersUpToDepth(nodeId, maxDepth);
                for (MethodNode caller : callers) {
                    if (seen.contains(caller.getNodeId())) continue;
                    seen.add(caller.getNodeId());
                    for (String p : normPaths) {
                        List<EntryPointNode> eps = entryPointRepository.findByProjectPathAndMethodNodeId(p, caller.getNodeId());
                        for (EntryPointNode ep : eps) {
                            rootEntries.add(toEntry(ep));
                        }
                    }
                }
            }
            return dedupEntries(rootEntries);
        } catch (Exception e) {
            log.warn("rootEntryAncestors failed for {} nodeIds: {}", nodeIds.size(), e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────── project path resolution ───────────────────────

    @Override
    public List<String> resolveProjectPaths(List<String> pathHints, List<String> classNames) {
        Set<String> resolved = new LinkedHashSet<>();

        // Strategy 1: Resolve by className (most reliable — direct Neo4j lookup)
        if (classNames != null) {
            for (String className : classNames) {
                if (className == null || className.isBlank()) continue;
                try {
                    List<String> paths = methodNodeRepository.findProjectPathsByClassName(className);
                    if (paths.isEmpty() && className.contains(".")) {
                        String shortName = className.substring(className.lastIndexOf('.') + 1);
                        paths = methodNodeRepository.findProjectPathsByClassName(shortName);
                    }
                    resolved.addAll(paths);
                    if (!paths.isEmpty()) {
                        log.debug("[resolveProjectPaths] className='{}' → paths={}", className, paths);
                    }
                } catch (Exception e) {
                    log.debug("[resolveProjectPaths] className lookup failed for '{}': {}", className, e.getMessage());
                }
            }
        }

        // Strategy 2: Resolve by path prefix matching (handles file paths)
        if (pathHints != null) {
            for (String hint : pathHints) {
                if (hint == null || hint.isBlank()) continue;
                String normalized = PathUtils.normalize(hint);
                if (normalized == null || normalized.isBlank()) continue;
                try {
                    List<String> paths = methodNodeRepository.findProjectPathsByPathPrefix(normalized);
                    resolved.addAll(paths);
                    if (!paths.isEmpty()) {
                        log.debug("[resolveProjectPaths] pathHint='{}' → paths={}", normalized, paths);
                    }
                } catch (Exception e) {
                    log.debug("[resolveProjectPaths] pathHint lookup failed for '{}': {}", normalized, e.getMessage());
                }
            }
        }

        if (resolved.isEmpty()) {
            log.warn("[resolveProjectPaths] no projectPaths resolved from hints={} classNames={}", pathHints, classNames);
        } else {
            log.info("[resolveProjectPaths] resolved {} projectPaths: {} (from hints={} classNames={})",
                    resolved.size(), resolved, pathHints, classNames);
        }

        return new ArrayList<>(resolved);
    }

    // ─────────────────────── private helpers ───────────────────────

    private List<String> normalizePaths(List<String> projectPaths) {
        if (projectPaths == null) return List.of();
        return projectPaths.stream()
                .map(PathUtils::normalize)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Entry> dedupEntries(List<Entry> entries) {
        Map<String, Entry> byNodeId = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.nodeId() != null) byNodeId.putIfAbsent(e.nodeId(), e);
        }
        return new ArrayList<>(byNodeId.values());
    }

    private List<Entry> dedupMethodNodes(List<MethodNode> nodes) {
        Map<String, MethodNode> byNodeId = new LinkedHashMap<>();
        for (MethodNode m : nodes) {
            if (m.getNodeId() != null) byNodeId.putIfAbsent(m.getNodeId(), m);
        }
        return byNodeId.values().stream().map(this::toEntry).collect(Collectors.toList());
    }

    /** Recursively build a callees tree up to {@code maxDepth}. */
    private CallTreeNode buildCallTree(MethodNode node, int maxDepth, int currentDepth, Set<String> visited) {
        if (node == null || node.getNodeId() == null) {
            return new CallTreeNode(null, null, null, currentDepth, List.of());
        }
        if (currentDepth >= maxDepth || visited.contains(node.getNodeId())) {
            return new CallTreeNode(node.getNodeId(), node.getClassName(), node.getMethodName(), currentDepth, List.of());
        }
        visited.add(node.getNodeId());

        List<MethodNode> directCallees = methodNodeRepository.findCallees(node.getNodeId());
        List<CallTreeNode> children = new ArrayList<>();
        for (MethodNode callee : directCallees) {
            children.add(buildCallTree(callee, maxDepth, currentDepth + 1, visited));
        }
        return new CallTreeNode(node.getNodeId(), node.getClassName(), node.getMethodName(), currentDepth, children);
    }

    /** Map {@link EntryPointNode} to the KG DTO {@link Entry}. */
    private Entry toEntry(EntryPointNode ep) {
        ClassMethod cm = extractClassMethodFromNodeId(ep.getMethodNodeId());
        return new Entry(
                ep.getMethodNodeId(),
                cm != null ? cm.className() : null,
                cm != null ? cm.methodName() : null,
                ep.getEntryType());
    }

    private static ClassMethod extractClassMethodFromNodeId(String nodeId) {
        if (nodeId == null) return null;
        int colon = nodeId.indexOf(':');
        if (colon < 0 || colon >= nodeId.length() - 1) return null;
        String afterColon = nodeId.substring(colon + 1);
        int lastDot = afterColon.lastIndexOf('.');
        if (lastDot <= 0) return null;
        int secondLastDot = afterColon.lastIndexOf('.', lastDot - 1);
        if (secondLastDot <= 0) return null;
        String className = afterColon.substring(0, secondLastDot);
        String methodName = afterColon.substring(secondLastDot + 1, lastDot);
        if (className.isEmpty() || methodName.isEmpty()) return null;
        return new ClassMethod(className, methodName);
    }

    private record ClassMethod(String className, String methodName) {}

    /** Map {@link MethodNode} to the KG DTO {@link Entry}. */
    private Entry toEntry(MethodNode m) {
        return new Entry(
                m.getNodeId(),
                m.getClassName(),
                m.getMethodName(),
                null);
    }

    /**
     * Resolve a MethodNode from className + methodName across all given project paths.
     */
    private MethodNode resolveMethod(String className, String methodName, List<String> normPaths) {
        // Strategy 1: nodeId direct lookup (project-agnostic)
        if (className != null && className.contains(":")) {
            MethodNode found = methodNodeRepository.findByNodeId(className).orElse(null);
            if (found != null) return found;
        }
        // Strategy 2: fully-qualified className + methodName lookup across all paths
        if (className != null && methodName != null && !methodName.isEmpty()) {
            List<MethodNode> candidates = methodNodeRepository.findByProjectPathsAndClassNameAndMethodName(
                    normPaths, className, methodName);
            if (!candidates.isEmpty()) return candidates.get(0);
        }
        // Strategy 3: short className (ENDS WITH) + methodName fallback
        if (className != null && methodName != null && !methodName.isEmpty()
                && !className.contains(".")) {
            List<MethodNode> candidates = methodNodeRepository.findByProjectPathsAndShortClassNameAndMethodName(
                    normPaths, className, methodName);
            if (!candidates.isEmpty()) {
                log.info("resolveMethod: short-className fallback matched {}#{} → nodeId={}",
                        className, methodName, candidates.get(0).getNodeId());
                return candidates.get(0);
            }
        }
        return null;
    }

    static String extractClassNameFromNodeId(String nodeId) {
        if (nodeId == null) return null;
        int colon = nodeId.indexOf(':');
        if (colon < 0 || colon >= nodeId.length() - 1) return null;
        String afterColon = nodeId.substring(colon + 1);
        int lastDot = afterColon.lastIndexOf('.');
        if (lastDot <= 0) return null;
        int secondLastDot = afterColon.lastIndexOf('.', lastDot - 1);
        if (secondLastDot <= 0) return null;
        return afterColon.substring(0, secondLastDot);
    }
}
