package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.codegraph.FrontendAstParser;
import com.huawei.hisi.knowledgegraph.link.FrontendBackendLinker;
import com.huawei.hisi.knowledgegraph.vector.FrontendNodeVectorizer;
import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端图编排器（绑定构建显式触发）。
 *
 * <p>由「构建前后端完整图谱」绑定端点显式调用，负责：</p>
 * <ol>
 *   <li>校验后端已建图、前端目录有效（{@link FrontendProjectDiscoverer}）</li>
 *   <li>扫描前端源码，提取 ApiClient / FrontendRoute 节点并落库（{@link FrontendAstParser}）</li>
 *   <li>建 Component -[:INVOKES]-> ApiClient 边</li>
 *   <li>静态 URL 匹配构建跨层 INVOKES_API 边（{@link FrontendBackendLinker}）</li>
 * </ol>
 *
 * <p>不再由后端全量建图（{@link KgGenerationQueue}）自动触发；前端目录由用户
 * 显式指定。任一步失败即抛异常，由调用方（绑定构建端点）显式返回错误。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FrontendGraphOrchestrator {

    private final FrontendProjectDiscoverer frontendProjectDiscoverer;
    private final FrontendAstParser frontendAstParser;
    private final FrontendBackendLinker frontendBackendLinker;
    private final KnowledgeGraphStorageService storageService;
    private final Neo4jComponentNodeRepository componentNodeRepository;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final FrontendNodeVectorizer frontendNodeVectorizer;

    /**
     * 编排前端实体化 + 跨层链接。
     *
     * @param backendProjectPath 后端项目路径（需已建图）
     * @param frontendProjectPath 显式指定的前端项目路径（需含 package.json）
     * @return 结构化结果（apiClients / routes / linked 计数）
     * @throws IllegalArgumentException 后端未建图 / 前端目录无效
     */
    public Map<String, Object> run(String backendProjectPath, String frontendProjectPath) {
        // 1. 后端已建图校验
        if (methodNodeRepository.countByProjectPath(backendProjectPath) == 0) {
            throw new IllegalArgumentException("后端目录尚未建图: " + backendProjectPath);
        }
        // 2. 前端目录显式校验
        frontendProjectDiscoverer.validateFrontendProject(frontendProjectPath);

        // 3. 前端实体化：解析 ApiClient + FrontendRoute
        FrontendAstParser.ParseResult parseResult =
                frontendAstParser.parseDirectory(frontendProjectPath, frontendProjectPath);
        List<ApiClientNode> apiClients = parseResult.apiClients();
        List<FrontendRouteNode> routes = frontendAstParser.toFrontendRouteNodes(parseResult, frontendProjectPath);
        List<ComponentNode> components = parseResult.components();
        if (!components.isEmpty()) {
            storageService.saveComponentNodes(components);
        }
        if (!apiClients.isEmpty()) {
            storageService.saveApiClientNodes(apiClients);
        }
        if (!routes.isEmpty()) {
            storageService.saveFrontendRouteNodes(routes);
        }
        log.info("[FrontendGraphOrchestrator] 前端实体化完成: frontend={}, components={}, apiClients={}, routes={}",
                frontendProjectPath, components.size(), apiClients.size(), routes.size());

        // 4. Component -[:INVOKES]-> ApiClient 边（仅组件内直调，componentName 非 null 的）
        List<Map<String, Object>> invokes = new ArrayList<>();
        for (ApiClientNode client : apiClients) {
            if (client.getComponentName() != null && !client.getComponentName().isBlank()) {
                Map<String, Object> rel = new LinkedHashMap<>();
                rel.put("componentId", ComponentNode.generateComponentId(frontendProjectPath, client.getComponentName()));
                rel.put("apiClientId", client.getApiClientId());
                invokes.add(rel);
            }
        }
        if (!invokes.isEmpty()) {
            componentNodeRepository.createInvokesRelations(invokes);
        }

        // 5. 前端节点向量化（失败不阻断，计数返回）
        Map<String, Object> vectorization = frontendNodeVectorizer.vectorize(frontendProjectPath);

        // 6. 跨层链接：静态 URL 匹配建 INVOKES_API 边
        int linked = frontendBackendLinker.link(frontendProjectPath, List.of(backendProjectPath));
        log.info("[FrontendGraphOrchestrator] 跨层链接完成: frontend={}, linked={}", frontendProjectPath, linked);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("components", components.size());
        result.put("apiClients", apiClients.size());
        result.put("routes", routes.size());
        result.put("linked", linked);
        result.putAll(vectorization);
        return result;
    }
}
