package com.huawei.hisi.ram.kg.impl;

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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
        List<String> normPaths = projectPaths.stream()
                .map(PathUtils::normalize)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toList());
        return doHybridSearch(query, normPaths, limit);
    }

    private List<Seed> doHybridSearch(String query, List<String> normPaths, int limit) {
        try {
            // graphDepth=0: skip graph expansion inside HybridSearchService — RAM performs
            // its own callees-tree and impact-ring expansion, so the 2-hop graph expansion
            // only inflates result count without adding value.
            // 使用 projectPaths + language 重载，确保走 searchByNaturalLanguageWithScores
            // （含关键词补充召回，弥合需求术语与代码术语的语义鸿沟）
            String firstPath = normPaths.isEmpty() ? "" : normPaths.get(0);
            SearchResult result = hybridSearchService.hybridSearch(
                    query, firstPath, normPaths, null, limit, 0);
            if (result == null || result.getResults() == null) {
                return Collections.emptyList();
            }
            // 保留全部结果，不做 .limit(limit) 截断
            // RAM 的 MultiQuerySearcher 会做 RRF 融合和排序
            return result.getResults().stream()
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

    @Override
    public List<Entry> entryPoints(String projectPath, String entryType) {
        String normPath = PathUtils.normalize(projectPath);
        try {
            List<EntryPointNode> nodes;
            if (entryType == null || "ALL".equalsIgnoreCase(entryType)) {
                nodes = entryPointRepository.findByProjectPath(normPath);
            } else {
                nodes = entryPointRepository.findByProjectPathAndEntryType(normPath, entryType);
            }
            return nodes.stream()
                    .map(this::toEntry)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("entryPoints failed for projectPath='{}': {}", normPath, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Impl> implementations(String interfaceName, String projectPath) {
        String normPath = PathUtils.normalize(projectPath);
        try {
            // If interfaceName looks like a nodeId (contains ':'), try IMPLEMENTS-based lookup
            if (interfaceName != null && interfaceName.contains(":")) {
                List<String> implNodeIds = methodNodeRepository.findImplementationMethodsByInterfaceMethod(interfaceName);
                if (!implNodeIds.isEmpty()) {
                    final String ifaceName = interfaceName;
                    return implNodeIds.stream()
                            .map(implId -> new Impl(implId, null, ifaceName))
                            .collect(Collectors.toList());
                }
                // If no implementations found via nodeId, extract className and try as interface name
                String extracted = extractClassNameFromNodeId(interfaceName);
                if (extracted != null) {
                    interfaceName = extracted;
                } else {
                    return Collections.emptyList();
                }
            }
            final String resolvedName = interfaceName;
            List<String> projectPaths = List.of(normPath);
            List<String> implClassNames = methodNodeRepository.findImplementationsByInterface(resolvedName, projectPaths);
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
        String normPath = PathUtils.normalize(projectPath);
        try {
            MethodNode root = resolveMethod(className, methodName, normPath);
            if (root == null) {
                log.debug("calleesTree: no method found for className='{}' methodName='{}' in {}", className, methodName, normPath);
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
        String normPath = PathUtils.normalize(projectPath);
        try {
            MethodNode target = resolveMethod(className, methodName, normPath);
            if (target == null) {
                return Collections.emptyList();
            }
            List<MethodNode> callers = methodNodeRepository.findCallersUpToDepth(target.getNodeId(), 10);

            // Find entry points among the callers
            List<Entry> entries = new ArrayList<>();
            for (MethodNode caller : callers) {
                List<EntryPointNode> eps = entryPointRepository.findByProjectPathAndMethodNodeId(normPath, caller.getNodeId());
                for (EntryPointNode ep : eps) {
                    entries.add(toEntry(ep));
                }
            }
            // Also check the target method itself
            List<EntryPointNode> selfEps = entryPointRepository.findByProjectPathAndMethodNodeId(normPath, target.getNodeId());
            for (EntryPointNode ep : selfEps) {
                entries.add(toEntry(ep));
            }
            return entries;
        } catch (Exception e) {
            log.warn("rootEntries failed for {}#{}: {}", className, methodName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Entry> affecting(String className, String methodName, String projectPath, int maxDepth) {
        String normPath = PathUtils.normalize(projectPath);
        try {
            MethodNode target = resolveMethod(className, methodName, normPath);
            if (target == null) {
                log.debug("affecting: resolveMethod returned null for className='{}' methodName='{}' path='{}'",
                        className, methodName, normPath);
                return Collections.emptyList();
            }
            List<MethodNode> callers = methodNodeRepository.findCallersUpToDepth(target.getNodeId(), maxDepth);
            if (log.isDebugEnabled()) {
                log.debug("affecting: nodeId='{}' maxDepth={} → {} upstream callers",
                        target.getNodeId(), maxDepth, callers.size());
            }
            return callers.stream()
                    .map(this::toEntry)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("affecting failed for {}#{}: {}", className, methodName, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Entry> downstream(String nodeId, String projectPath, int maxDepth) {
        try {
            List<MethodNode> callees = methodNodeRepository.findCalleesUpToDepth(nodeId, maxDepth);
            return callees.stream()
                    .map(this::toEntry)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("downstream failed for nodeId='{}': {}", nodeId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Bridge> feignChain(String serviceName, String projectPath) {
        String normPath = PathUtils.normalize(projectPath);
        try {
            // Find all Feign client entry points for the service
            List<EntryPointNode> feignEntries = entryPointRepository.findByProjectPathAndEntryType(normPath, "FEIGN_CLIENT");
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
        String normPath = PathUtils.normalize(projectPath);
        try {
            // Find MQ consumer entry points matching the topic
            List<EntryPointNode> mqEntries = entryPointRepository.findByProjectPathAndEntryType(normPath, "MQ_CONSUMER");
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
        try {
            // Collect Feign bridges
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
        String normPath = PathUtils.normalize(projectPath);
        try {
            List<SqlNode> sqlNodes;
            if (mapperInterface != null && !mapperInterface.isBlank()) {
                sqlNodes = sqlNodeRepository.findByMapperInterfaceAndProjectPath(mapperInterface, normPath);
            } else {
                sqlNodes = sqlNodeRepository.findByProjectPath(normPath);
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
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyList();
        }
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
        if (nodeIds == null || nodeIds.isEmpty() || normPath == null || normPath.isBlank()) {
            return Collections.emptyList();
        }
        try {
            Set<String> seen = new HashSet<>();
            List<Entry> rootEntries = new ArrayList<>();
            for (String nodeId : nodeIds) {
                if (nodeId == null || seen.contains(nodeId)) continue;

                // 1. Check if the nodeId itself is an entry point
                List<EntryPointNode> selfEps = entryPointRepository.findByProjectPathAndMethodNodeId(normPath, nodeId);
                for (EntryPointNode ep : selfEps) {
                    rootEntries.add(toEntry(ep));
                }

                // 2. Trace callers upward and find entry points among them
                List<MethodNode> callers = methodNodeRepository.findCallersUpToDepth(nodeId, maxDepth);
                for (MethodNode caller : callers) {
                    if (seen.contains(caller.getNodeId())) continue;
                    seen.add(caller.getNodeId());
                    List<EntryPointNode> eps = entryPointRepository.findByProjectPathAndMethodNodeId(normPath, caller.getNodeId());
                    for (EntryPointNode ep : eps) {
                        rootEntries.add(toEntry(ep));
                    }
                }
            }
            return rootEntries;
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
                    // Try full qualified name first
                    List<String> paths = methodNodeRepository.findProjectPathsByClassName(className);
                    if (paths.isEmpty() && className.contains(".")) {
                        // Try short class name (last segment)
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
        // Extract className/methodName from methodNodeId format: projectPath:className.methodName.signatureHash
        ClassMethod cm = extractClassMethodFromNodeId(ep.getMethodNodeId());
        return new Entry(
                ep.getMethodNodeId(),
                cm != null ? cm.className() : null,
                cm != null ? cm.methodName() : null,
                ep.getEntryType());
    }

    /**
     * Extract className and methodName from a nodeId of the format
     * {@code projectPath:className.methodName.signatureHash}.
     * Returns {@code null} if the format is unrecognisable.
     */
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

    /** Simple holder for className + methodName parsed from a nodeId. */
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
     * Resolve a MethodNode from className + methodName, with nodeId-based fallback.
     * <p>If className looks like a nodeId (contains ':'), tries direct lookup first,
     * then extracts className/methodName from the nodeId format.</p>
     */
    private MethodNode resolveMethod(String className, String methodName, String normPath) {
        // Strategy 1: nodeId direct lookup
        if (className != null && className.contains(":")) {
            MethodNode found = methodNodeRepository.findByNodeId(className).orElse(null);
            if (found != null) return found;
        }
        // Strategy 2: fully-qualified className + methodName lookup
        if (className != null && methodName != null && !methodName.isEmpty()) {
            List<MethodNode> candidates = methodNodeRepository.findByProjectPathsAndClassNameAndMethodName(
                    List.of(normPath), className, methodName);
            if (!candidates.isEmpty()) return candidates.get(0);
        }
        // Strategy 3: short className (ENDS WITH) + methodName fallback
        // LLM often outputs short names like "RequireStatusServiceImpl" instead of
        // the fully-qualified "com.hisilicon.rms...RequireStatusServiceImpl"
        if (className != null && methodName != null && !methodName.isEmpty()
                && !className.contains(".")) {
            List<MethodNode> candidates = methodNodeRepository.findByProjectPathsAndShortClassNameAndMethodName(
                    List.of(normPath), className, methodName);
            if (!candidates.isEmpty()) {
                log.info("resolveMethod: short-className fallback matched {}#{} → nodeId={}",
                        className, methodName, candidates.get(0).getNodeId());
                return candidates.get(0);
            }
        }
        return null;
    }

    /**
     * Extract className from a nodeId of the format {@code projectPath:className.methodName.signatureHash}.
     * Returns {@code null} if the format is unrecognisable.
     */
    static String extractClassNameFromNodeId(String nodeId) {
        if (nodeId == null) return null;
        int colon = nodeId.indexOf(':');
        if (colon < 0 || colon >= nodeId.length() - 1) return null;
        String afterColon = nodeId.substring(colon + 1); // className.methodName.signatureHash
        // Split on dots; className is everything except the last two segments (methodName.hash)
        int lastDot = afterColon.lastIndexOf('.');
        if (lastDot <= 0) return null;
        int secondLastDot = afterColon.lastIndexOf('.', lastDot - 1);
        if (secondLastDot <= 0) return null;
        return afterColon.substring(0, secondLastDot);
    }
}
