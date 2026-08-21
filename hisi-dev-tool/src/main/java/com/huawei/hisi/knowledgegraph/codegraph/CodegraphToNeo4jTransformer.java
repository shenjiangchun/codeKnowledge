package com.huawei.hisi.knowledgegraph.codegraph;

import com.huawei.hisi.knowledgegraph.service.storage.Neo4jStorageService;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * codegraph SQLite 数据 → hisi Neo4j 实体转换器。
 *
 * <p>读取 {@link CodegraphSqliteReader.CodegraphDb}（nodes/edges/files 三表快照），
 * 按 {@code docs/evolution-plans/codegraph-integration-decision.md} §3.3 的 schema 映射，
 * 将 codegraph 的节点/边转换到 hisi 的 {@link MethodNode}、{@link EntryPointNode}，
 * 以及 CALLS / CONTAINS / IMPORTS / REFERENCES 四类关系 Map，最终通过
 * {@link Neo4jStorageService} + {@link Neo4jMethodNodeRepository} 落库。</p>
 *
 * <p><b>一期范围</b>：</p>
 * <ul>
 *   <li>节点：{@code function/method/component → Method}、{@code route → EntryPoint(HTTP)}</li>
 *   <li>边：{@code calls → CALLS}、{@code contains → CONTAINS}、{@code imports → IMPORTS}、
 *       {@code references → REFERENCES(refType=CALLBACK)}</li>
 *   <li>跳过：{@code class/struct/interface/trait/protocol}（Step 4 加 Class 标签后落地）、
 *       {@code file/module/namespace}（post-MVP）、{@code extends/implements/overrides}
 *       （hisi 已有扫描器处理 Java；非 Java 留后续阶段）</li>
 * </ul>
 *
 * <p><b>language 推断</b>：codegraph 一次 run 通常单语言，从首个 node 的 language 字段读取。
 * 若 db.nodes() 为空，language 置 "unknown" 但不阻塞流程。</p>
 */
@Service
@Slf4j
public class CodegraphToNeo4jTransformer {

    private final Neo4jStorageService neo4jStorageService;
    private final Neo4jMethodNodeRepository methodNodeRepository;

    public CodegraphToNeo4jTransformer(Neo4jStorageService neo4jStorageService,
                                       Neo4jMethodNodeRepository methodNodeRepository) {
        this.neo4jStorageService = neo4jStorageService;
        this.methodNodeRepository = methodNodeRepository;
    }

    /**
     * 将 codegraph 数据库快照转换并写入 Neo4j。
     *
     * @param db           codegraph SQLite 读取结果，不可为 null
     * @param projectPath  目标项目根路径，不可为 blank
     * @param serviceName  目标服务名（与 MethodNode.serviceName 一致）
     * @return 各类实体/关系的保存计数
     * @throws IllegalArgumentException db 为 null 或 projectPath 为 blank
     */
    public TransformResult transform(CodegraphSqliteReader.CodegraphDb db,
                                     String projectPath,
                                     String serviceName) {
        if (db == null) {
            throw new IllegalArgumentException("db 不能为空");
        }
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath 不能为空");
        }
        String service = serviceName == null ? "" : serviceName;

        // 1. 节点转换
        List<MethodNode> methodNodes = new ArrayList<>();
        List<ComponentNode> componentNodes = new ArrayList<>();
        Set<String> methodNodeIds = new LinkedHashSet<>();
        Map<String, EntryPointNode> entryPointsById = new LinkedHashMap<>();
        Map<String, String> nodeKindById = new LinkedHashMap<>();
        int skippedNodes = 0;
        Map<String, Integer> skipKindHist = new LinkedHashMap<>();

        String language = null;
        for (CodegraphSqliteReader.CodegraphNode node : db.nodes()) {
            nodeKindById.put(node.id(), node.kind());
            if (language == null && node.language() != null) {
                language = node.language();
            }
            switch (node.kind()) {
                case "function", "method" -> {
                    methodNodes.add(toMethodNode(node, projectPath, service));
                    methodNodeIds.add(node.id());
                }
                case "component" -> componentNodes.add(toComponentNode(node, projectPath, service));
                case "route" -> entryPointsById.put(node.id(), toEntryPoint(node, projectPath, service));
                case "class", "struct", "interface", "trait", "protocol" -> {
                    log.debug("跳过 Class-like 节点 kind={} id={}", node.kind(), node.id());
                    skippedNodes++;
                }
                case "file", "module", "namespace" -> {
                    log.debug("跳过 File-like 节点 kind={} id={}", node.kind(), node.id());
                    skippedNodes++;
                }
                default -> {
                    skippedNodes++;
                    skipKindHist.merge(node.kind(), 1, Integer::sum);
                    log.debug("跳过未识别节点 kind={} id={}", node.kind(), node.id());
                }
            }
        }
        if (language == null) {
            language = "unknown";
        }

        // 2. 边转换
        List<Map<String, Object>> callsRelations = new ArrayList<>();
        List<Map<String, Object>> containsRelations = new ArrayList<>();
        List<Map<String, Object>> importsRelations = new ArrayList<>();
        List<Map<String, Object>> referencesRelations = new ArrayList<>();
        int skippedEdges = 0;
        Map<String, Integer> skipEdgeKindHist = new LinkedHashMap<>();

        for (CodegraphSqliteReader.CodegraphEdge edge : db.edges()) {
            String srcKind = nodeKindById.get(edge.source());
            String tgtKind = nodeKindById.get(edge.target());
            switch (edge.kind()) {
                case "calls" -> {
                    // route→method：把 caller 改写为 route 关联的 Method，并把 route 的 Method 节点也建出来
                    if ("route".equals(srcKind) && ("function".equals(tgtKind)
                            || "method".equals(tgtKind) || "component".equals(tgtKind))
                            && entryPointsById.containsKey(edge.source())
                            && !methodNodeIds.contains(edge.source())) {
                        EntryPointNode ep = entryPointsById.get(edge.source());
                        methodNodes.add(toMethodNodeFromEntryPoint(ep, projectPath, service));
                        methodNodeIds.add(ep.getEntryId());
                        callsRelations.add(toCallsRelation(edge, projectPath));
                    } else if ("route".equals(srcKind) || "file".equals(srcKind)
                            || "module".equals(srcKind) || "namespace".equals(srcKind)
                            || "component".equals(srcKind)) {
                        // component 已映射为 Component 节点（非 Method），其调用边由 T2 的 INVOKES 边接管，跳过 CALLS
                        log.debug("跳过 calls 边：caller 非 Method kind={} src={} tgt={}",
                                srcKind, edge.source(), edge.target());
                        skippedEdges++;
                    } else {
                        callsRelations.add(toCallsRelation(edge, projectPath));
                    }
                }
                case "contains" -> containsRelations.add(toContainsRelation(edge, projectPath));
                case "imports" -> importsRelations.add(toImportsRelation(edge, projectPath));
                case "references" -> referencesRelations.add(toReferencesRelation(edge, projectPath));
                case "extends", "implements", "overrides" -> {
                    log.debug("跳过已由 hisi 扫描器处理的边 kind={} src={} tgt={}", edge.kind(), edge.source(), edge.target());
                    skippedEdges++;
                }
                default -> {
                    skippedEdges++;
                    skipEdgeKindHist.merge(edge.kind(), 1, Integer::sum);
                    log.debug("跳过未识别边 kind={} src={} tgt={}", edge.kind(), edge.source(), edge.target());
                }
            }
        }
        // 未识别 kind 直方图（DEBUG）
        if (!skipKindHist.isEmpty()) {
            log.debug("未识别节点 kind 直方图: {}", skipKindHist);
        }
        if (!skipEdgeKindHist.isEmpty()) {
            log.debug("未识别边 kind 直方图: {}", skipEdgeKindHist);
        }

        // 3. 持久化
        if (!methodNodes.isEmpty()) {
            neo4jStorageService.saveMethodNodes(methodNodes);
        }
        if (!componentNodes.isEmpty()) {
            neo4jStorageService.saveComponentNodes(componentNodes);
        }
        if (!entryPointsById.isEmpty()) {
            neo4jStorageService.saveEntryPoints(new ArrayList<>(entryPointsById.values()));
        }
        if (!callsRelations.isEmpty()) {
            methodNodeRepository.createCallRelations(callsRelations);
        }
        if (!containsRelations.isEmpty()) {
            methodNodeRepository.createContainsRelations(containsRelations);
        }
        if (!importsRelations.isEmpty()) {
            methodNodeRepository.createImportsRelations(importsRelations);
        }
        if (!referencesRelations.isEmpty()) {
            methodNodeRepository.createReferencesRelations(referencesRelations);
        }

        int totalSkipped = skippedNodes + skippedEdges;
        log.info("codegraph→Neo4j 转换完成 projectPath={} language={} methods={} components={} entryPoints={} "
                        + "calls={} contains={} imports={} references={} skipped={}",
                projectPath, language, methodNodes.size(), componentNodes.size(), entryPointsById.size(),
                callsRelations.size(), containsRelations.size(),
                importsRelations.size(), referencesRelations.size(), totalSkipped);

        return new TransformResult(
                methodNodes.size(),
                componentNodes.size(),
                entryPointsById.size(),
                callsRelations.size(),
                containsRelations.size(),
                importsRelations.size(),
                referencesRelations.size(),
                totalSkipped
        );
    }

    /** codegraph node → hisi MethodNode（function/method 共用） */
    private MethodNode toMethodNode(CodegraphSqliteReader.CodegraphNode node, String projectPath, String serviceName) {
        return MethodNode.builder()
                .nodeId(node.id())
                .className(deriveClassName(node.qualifiedName(), node.name()))
                .methodName(node.name())
                .signature(node.signature())
                .filePath(node.filePath())
                .startLine(node.startLine())
                .endLine(node.endLine())
                .language(node.language())
                .framework("unknown")
                .serviceName(serviceName)
                .projectPath(projectPath)
                .description(node.docstring())
                .comment(null)
                .build();
    }

    /** codegraph component node → hisi ComponentNode（前端组件，不再抹平为 Method） */
    private ComponentNode toComponentNode(CodegraphSqliteReader.CodegraphNode node, String projectPath, String serviceName) {
        return ComponentNode.builder()
                .componentId(ComponentNode.generateComponentId(projectPath, node.name()))
                .componentName(node.name())
                .filePath(node.filePath())
                .projectPath(projectPath)
                .language(node.language())
                .framework("unknown")
                .description(node.docstring())
                .build();
    }

    /** codegraph route → hisi EntryPointNode（一期 entryType 统一 HTTP） */
    private EntryPointNode toEntryPoint(CodegraphSqliteReader.CodegraphNode node, String projectPath, String serviceName) {
        return EntryPointNode.builder()
                .entryId(node.id())
                .entryType(EntryPointNode.TYPE_HTTP)
                .entryKey(node.name())
                .methodNodeId(node.id())
                .projectPath(projectPath)
                .language(node.language())
                .framework("unknown")
                .serviceName(serviceName)
                .build();
    }

    /**
     * route→method 的 calls 边场景下，把 route 节点也写成 Method 节点，
     * 让 CALLS 关系两端都落在 :Method 标签上（CREATE 不依赖 caller 为 Method）。
     *
     * <p>route 本质是 HTTP 入口，既映射为 EntryPoint，也作为 Method 出现，
     * 这样 CALLS 关系在 Neo4j 里能完整建立，调用链可视化不会断在入口处。</p>
     */
    private MethodNode toMethodNodeFromEntryPoint(EntryPointNode ep, String projectPath, String serviceName) {
        return MethodNode.builder()
                .nodeId(ep.getEntryId())
                .className("")
                .methodName(ep.getEntryKey())
                .signature(null)
                .filePath(null)
                .startLine(null)
                .endLine(null)
                .language(ep.getLanguage())
                .framework(ep.getFramework())
                .serviceName(serviceName)
                .projectPath(projectPath)
                .description(null)
                .comment(null)
                .build();
    }

    /** calls 边 → CALLS 关系 Map（与 Neo4jStorageService.saveCallRelations 同构） */
    private static Map<String, Object> toCallsRelation(CodegraphSqliteReader.CodegraphEdge edge, String projectPath) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("callerId", edge.source());
        map.put("calleeId", edge.target());
        map.put("callType", "DIRECT");
        map.put("callLine", edge.line() != null ? edge.line() : 0);
        map.put("bridgeType", null);
        map.put("sqlId", null);
        map.put("targetService", null);
        map.put("targetEndpoint", null);
        return map;
    }

    /** contains 边 → CONTAINS 关系 Map（parent=source, child=target） */
    private static Map<String, Object> toContainsRelation(CodegraphSqliteReader.CodegraphEdge edge, String projectPath) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("parentId", edge.source());
        map.put("childId", edge.target());
        map.put("projectPath", projectPath);
        return map;
    }

    /** imports 边 → IMPORTS 关系 Map */
    private static Map<String, Object> toImportsRelation(CodegraphSqliteReader.CodegraphEdge edge, String projectPath) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceId", edge.source());
        map.put("targetId", edge.target());
        map.put("projectPath", projectPath);
        return map;
    }

    /** references 边 → REFERENCES 关系 Map（refType=CALLBACK） */
    private static Map<String, Object> toReferencesRelation(CodegraphSqliteReader.CodegraphEdge edge, String projectPath) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceId", edge.source());
        map.put("targetId", edge.target());
        map.put("projectPath", projectPath);
        map.put("refType", "CALLBACK");
        return map;
    }

    /**
     * 从 codegraph qualifiedName 派生 className。
     * 规则：qualifiedName 形如 {@code Foo.bar.hello}，取最后一个 {@code .} 之前的部分作为 className；
     * 若没有 {@code .} 或与 name 相同则 className 留空字符串（顶层函数）。
     */
    private static String deriveClassName(String qualifiedName, String name) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return "";
        }
        int idx = qualifiedName.lastIndexOf('.');
        if (idx <= 0) {
            return "";
        }
        String parent = qualifiedName.substring(0, idx);
        // 若 parent 等于 name 自身（无命名空间），返回空
        if (parent.equals(name) || parent.isEmpty()) {
            return "";
        }
        return parent;
    }

    /**
     * 转换结果统计。
     *
     * @param methodsSaved       写入的 Method 节点数
     * @param componentsSaved    写入的 Component 节点数
     * @param entryPointsSaved   写入的 EntryPoint 节点数
     * @param callsRelations     CALLS 关系数
     * @param containsRelations  CONTAINS 关系数
     * @param importsRelations   IMPORTS 关系数
     * @param referencesRelations REFERENCES 关系数
     * @param skipped            跳过的节点/边总数（未识别 kind + MVP 不处理）
     */
    public record TransformResult(
            int methodsSaved,
            int componentsSaved,
            int entryPointsSaved,
            int callsRelations,
            int containsRelations,
            int importsRelations,
            int referencesRelations,
            int skipped
    ) {
        /**
         * 空结果（用于 db 为空时直接返回，避免 NPE）。
         */
        public static TransformResult empty() {
            return new TransformResult(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
