package com.huawei.hisi.knowledgegraph.link;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端→后端 跨层链接器（独立链接阶段）。
 *
 * <p>当前端图（ApiClient 节点，projectPath=前端实际目录）与后端图
 * （EntryPoint 节点，projectPath=后端目录）均建图完成后，本链接器执行
 * 静态 URL 匹配：将前端 {@code ApiClient.url} 与后端 {@code EntryPoint.entryKey}
 * 归一化后按「HTTP 方法 + 路径」比对，命中则建立
 * {@code ApiClient -[:INVOKES_API]-> EntryPoint} 跨层边。</p>
 *
 * <p>同构 {@link CrossServiceLinker}：作为独立步骤可增量重跑，不触发前后端图
 * 的全量重建。后端图重建后（EntryPoint nodeId 变化），重跑本链接器即可刷新跨层边。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FrontendBackendLinker {

    private final Neo4jApiClientNodeRepository apiClientNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointNodeRepository;

    /**
     * 执行跨层链接。
     *
     * @param frontendProjectPath 前端项目路径（ApiClient.projectPath）
     * @param backendProjectPaths 后端项目路径列表（EntryPoint.projectPath）
     * @return 建立的 INVOKES_API 边数量
     */
    public int link(String frontendProjectPath, List<String> backendProjectPaths) {
        if (frontendProjectPath == null || frontendProjectPath.isBlank()) {
            log.warn("[FrontendBackendLinker] 前端 projectPath 为空，跳过链接");
            return 0;
        }
        if (backendProjectPaths == null || backendProjectPaths.isEmpty()) {
            log.warn("[FrontendBackendLinker] 后端 projectPaths 为空，跳过链接");
            return 0;
        }

        List<ApiClientNode> apiClients = apiClientNodeRepository.findByProjectPath(frontendProjectPath);
        List<EntryPointNode> entryPoints = entryPointNodeRepository.findByProjectPathsAndEntryType(backendProjectPaths, "HTTP");
        log.info("[FrontendBackendLinker] 前端 ApiClient={} 后端 HTTP EntryPoint={}", apiClients.size(), entryPoints.size());

        // 建立后端 entryKey 归一化索引：normalizedPath -> EntryPoint（同路径可能多条 HTTP 方法，按 method 区分）
        Map<String, EntryPointNode> backendIndex = new LinkedHashMap<>();
        for (EntryPointNode ep : entryPoints) {
            String norm = UrlNormalizer.normalizeEntryKey(ep.getEntryKey());
            backendIndex.putIfAbsent(norm, ep);
        }

        List<Map<String, Object>> relations = new ArrayList<>();
        for (ApiClientNode client : apiClients) {
            String normPath = UrlNormalizer.normalizeFrontendUrl(client.getUrl());
            String method = client.getMethod() == null ? "GET" : client.getMethod().toUpperCase();
            String key = method + " " + normPath;
            EntryPointNode matched = backendIndex.get(key);
            if (matched != null) {
                Map<String, Object> rel = new LinkedHashMap<>();
                rel.put("apiClientId", client.getApiClientId());
                rel.put("entryId", matched.getEntryId());
                rel.put("method", method);
                relations.add(rel);
            }
        }

        // 重跑前先清理旧的 INVOKES_API 边（幂等重跑：无论本次命中多少，都先清旧边）
        apiClientNodeRepository.deleteInvokesApiRelationsByProjectPath(frontendProjectPath);
        if (!relations.isEmpty()) {
            apiClientNodeRepository.createInvokesApiRelations(relations);
        }
        log.info("[FrontendBackendLinker] 跨层链接完成，命中 {}/{} 个 ApiClient", relations.size(), apiClients.size());
        return relations.size();
    }
}
