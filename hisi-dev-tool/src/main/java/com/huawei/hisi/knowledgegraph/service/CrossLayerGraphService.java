package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.model.CallChainGraphResponse;
import com.huawei.hisi.knowledgegraph.model.GraphEdge;
import com.huawei.hisi.knowledgegraph.model.GraphNode;
import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前后端跨层骨架图服务。
 *
 * <p>一次返回「前端路由 → 组件 → API 调用点 → 后端入口 → 入口方法」的整张跨层骨架图，
 * 供「前后端跨层」tab 直接铺开所有连接节点。调用链（Method -[:CALLS]-> Method）不在此展开，
 * 由前端点击 Method 节点后按需调用 {@code /call-chain/downstream} 懒加载。</p>
 *
 * <p>边类型（写入 {@link GraphEdge#getCallType}）：</p>
 * <ul>
 *   <li>{@code ROUTE_TO_COMPONENT}：FrontendRoute --componentName 字符串匹配--> Component</li>
 *   <li>{@code INVOKES}：Component -[:INVOKES]-> ApiClient</li>
 *   <li>{@code INVOKES_API}：ApiClient -[:INVOKES_API]-> EntryPoint</li>
 *   <li>{@code ENTRY_TO_METHOD}：EntryPoint --methodNodeId 字符串匹配--> Method</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossLayerGraphService {

    private final Neo4jFrontendRouteNodeRepository frontendRouteNodeRepository;
    private final Neo4jComponentNodeRepository componentNodeRepository;
    private final Neo4jApiClientNodeRepository apiClientNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointNodeRepository;
    private final Neo4jMethodNodeRepository methodNodeRepository;

    /**
     * 构建跨层骨架图。
     *
     * @param projectPaths 绑定展开后的项目路径列表（含前端 + 后端）
     * @return 跨层骨架图（nodes + edges；cycles 相关字段为空）
     */
    public CallChainGraphResponse buildSkeleton(List<String> projectPaths) {
        List<String> paths = projectPaths == null ? List.of() : projectPaths;
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        // 1. 前端三类节点（仅前端项目路径下存在）
        List<FrontendRouteNode> routes = frontendRouteNodeRepository.findByProjectPaths(paths);
        List<ComponentNode> components = componentNodeRepository.findByProjectPaths(paths);
        List<ApiClientNode> apiClients = apiClientNodeRepository.findByProjectPaths(paths);

        // 2. 后端 HTTP 入口点（仅后端项目路径下存在；跨层链接只命中 HTTP 入口）
        List<EntryPointNode> entryPoints = entryPointNodeRepository.findByProjectPathsAndEntryType(paths, "HTTP");

        // 3. 入口方法（懒加载起点：仅取入口方法本身，不展开 CALLS）
        Set<String> methodNodeIds = new LinkedHashSet<>();
        for (EntryPointNode ep : entryPoints) {
            if (ep.getMethodNodeId() != null && !ep.getMethodNodeId().isBlank()) {
                methodNodeIds.add(ep.getMethodNodeId());
            }
        }
        Map<String, MethodNode> methodById = new LinkedHashMap<>();
        for (String id : methodNodeIds) {
            methodNodeRepository.findByNodeId(id).ifPresent(m -> methodById.put(m.getNodeId(), m));
        }

        // 4. 组装节点（按层级赋 depth，便于从左到右布局：route(0)→component(1)→apiClient(2)→entry(3)→method(4)）
        for (FrontendRouteNode r : routes) {
            nodes.add(GraphNode.builder()
                    .id(r.getFrontendRouteId())
                    .name(r.getPath() != null ? r.getPath() : r.getName())
                    .className(r.getComponentName() != null ? r.getComponentName() : "")
                    .depth(0)
                    .inCycle(false)
                    .nodeType("FrontendRoute")
                    .build());
        }
        for (ComponentNode c : components) {
            nodes.add(GraphNode.builder()
                    .id(c.getComponentId())
                    .name(c.getComponentName())
                    .className(c.getComponentName())
                    .filePath(c.getFilePath())
                    .description(c.getDescription())
                    .depth(1)
                    .inCycle(false)
                    .nodeType("Component")
                    .build());
        }
        for (ApiClientNode a : apiClients) {
            nodes.add(GraphNode.builder()
                    .id(a.getApiClientId())
                    .name(a.getUrl())
                    .className(a.getMethod() != null ? a.getMethod() + " " + a.getUrl() : a.getUrl())
                    .filePath(a.getSourceFile())
                    .depth(2)
                    .inCycle(false)
                    .nodeType("ApiClient")
                    .build());
        }
        for (EntryPointNode e : entryPoints) {
            nodes.add(GraphNode.builder()
                    .id(e.getEntryId())
                    .name(e.getEntryKey())
                    .className(e.getEntryType())
                    .depth(3)
                    .inCycle(false)
                    .nodeType("EntryPoint")
                    .build());
        }
        for (MethodNode m : methodById.values()) {
            nodes.add(GraphNode.builder()
                    .id(m.getNodeId())
                    .name(m.getMethodName())
                    .className(m.getClassName())
                    .filePath(m.getFilePath())
                    .startLine(m.getStartLine())
                    .signature(m.getSignature())
                    .description(m.getDescription())
                    .depth(4)
                    .inCycle(false)
                    .nodeType("Method")
                    .build());
        }

        // 5. 组装边
        // 5a. FrontendRoute --componentName--> Component（同 projectPath 字符串匹配）
        for (FrontendRouteNode r : routes) {
            if (r.getComponentName() == null || r.getComponentName().isBlank()) {
                continue;
            }
            for (ComponentNode c : components) {
                if (r.getProjectPath() != null && r.getProjectPath().equals(c.getProjectPath())
                        && r.getComponentName().equals(c.getComponentName())) {
                    edges.add(edge(r.getFrontendRouteId(), c.getComponentId(), "ROUTE_TO_COMPONENT"));
                }
            }
        }
        // 5b. Component -[:INVOKES]-> ApiClient
        for (Neo4jComponentNodeRepository.InvokesEdge rel : componentNodeRepository.findInvokesEdgesByProjectPaths(paths)) {
            edges.add(edge(rel.componentId(), rel.apiClientId(), "INVOKES"));
        }
        // 5c. ApiClient -[:INVOKES_API]-> EntryPoint
        for (Neo4jApiClientNodeRepository.InvokesApiEdge rel : apiClientNodeRepository.findInvokesApiEdgesByProjectPaths(paths)) {
            edges.add(edge(rel.apiClientId(), rel.entryId(), "INVOKES_API"));
        }
        // 5d. EntryPoint --methodNodeId--> Method
        for (EntryPointNode e : entryPoints) {
            if (e.getMethodNodeId() != null && methodById.containsKey(e.getMethodNodeId())) {
                edges.add(edge(e.getEntryId(), e.getMethodNodeId(), "ENTRY_TO_METHOD"));
            }
        }

        log.info("[CrossLayerGraph] 跨层骨架: projects={}, nodes={}, edges={}, routes={}, components={}, apiClients={}, entries={}, methods={}",
                paths.size(), nodes.size(), edges.size(), routes.size(), components.size(),
                apiClients.size(), entryPoints.size(), methodById.size());

        return CallChainGraphResponse.builder()
                .totalNodes(nodes.size())
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    private GraphEdge edge(String source, String target, String callType) {
        return GraphEdge.builder()
                .source(source)
                .target(target)
                .callType(callType)
                .build();
    }
}
