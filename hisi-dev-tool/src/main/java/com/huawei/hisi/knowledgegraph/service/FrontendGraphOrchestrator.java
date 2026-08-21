package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.codegraph.FrontendAstParser;
import com.huawei.hisi.knowledgegraph.link.FrontendBackendLinker;
import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端图编排器（独立链接阶段）。
 *
 * <p>在后端建图完成后（KgGenerationQueue.processItem 的聚合阶段之后）调用，
 * 负责：</p>
 * <ol>
 *   <li>自动发现与后端项目关联的前端目录（{@link FrontendProjectDiscoverer}）</li>
 *   <li>扫描前端源码，提取 ApiClient / FrontendRoute 节点并落库（{@link FrontendAstParser}）</li>
 *   <li>静态 URL 匹配构建跨层 INVOKES_API 边（{@link FrontendBackendLinker}）</li>
 * </ol>
 *
 * <p>与后端建图解耦：前端实体化与跨层链接作为独立步骤可单独重跑，不触发
 * 后端图全量重建。前端节点以「前端实际目录」为 projectPath，天然隔离于后端
 * cleanProjectData 的按路径精确删除。</p>
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

    /**
     * 编排前端实体化 + 跨层链接。
     *
     * @param backendProjectPath 后端项目路径
     * @param explicitFrontendPath 显式前端路径（可为 null，自动探测）
     */
    public void run(String backendProjectPath, String explicitFrontendPath) {
        List<String> frontendPaths = frontendProjectDiscoverer.discover(backendProjectPath, explicitFrontendPath);
        if (frontendPaths.isEmpty()) {
            log.info("[FrontendGraphOrchestrator] 未发现前端项目，跳过前端实体化: backend={}", backendProjectPath);
            return;
        }

        for (String frontendPath : frontendPaths) {
            try {
                // 1. 前端实体化：解析 ApiClient + FrontendRoute
                FrontendAstParser.ParseResult parseResult = frontendAstParser.parseDirectory(frontendPath, frontendPath);
                List<ApiClientNode> apiClients = parseResult.apiClients();
                List<FrontendRouteNode> routes = frontendAstParser.toFrontendRouteNodes(parseResult, frontendPath);
                if (!apiClients.isEmpty()) {
                    storageService.saveApiClientNodes(apiClients);
                }
                if (!routes.isEmpty()) {
                    storageService.saveFrontendRouteNodes(routes);
                }
                log.info("[FrontendGraphOrchestrator] 前端实体化完成: frontend={}, apiClients={}, routes={}",
                        frontendPath, apiClients.size(), routes.size());

                // 1b. 建 Component -[:INVOKES]-> ApiClient 边（仅组件内直调，componentName 非 null 的）
                List<Map<String, Object>> invokes = new ArrayList<>();
                for (ApiClientNode client : apiClients) {
                    if (client.getComponentName() != null && !client.getComponentName().isBlank()) {
                        Map<String, Object> rel = new LinkedHashMap<>();
                        rel.put("componentId", com.huawei.hisi.neo4j.model.ComponentNode
                                .generateComponentId(frontendPath, client.getComponentName()));
                        rel.put("apiClientId", client.getApiClientId());
                        invokes.add(rel);
                    }
                }
                if (!invokes.isEmpty()) {
                    componentNodeRepository.createInvokesRelations(invokes);
                }

                // 2. 跨层链接：静态 URL 匹配建 INVOKES_API 边
                int linked = frontendBackendLinker.link(frontendPath, List.of(backendProjectPath));
                log.info("[FrontendGraphOrchestrator] 跨层链接完成: frontend={}, linked={}", frontendPath, linked);
            } catch (Exception e) {
                // 前端实体化/链接失败不阻断后端建图主流程
                log.warn("[FrontendGraphOrchestrator] 前端图编排异常（不阻断）: frontend={}, error={}",
                        frontendPath, e.getMessage(), e);
            }
        }
    }
}
