package com.huawei.hisi.knowledgegraph.vector;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jFrontendRouteNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端节点向量化服务。
 *
 * <p>在「构建前后端完整图谱」流程中，为前端节点（Component/FrontendRoute/ApiClient）
 * 生成描述文本并调用 embedding 服务写回 {@code descriptionEmbedding}。单个节点向量
 * 生成失败不阻断其余节点（保留节点、计数失败）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FrontendNodeVectorizer {

    private final EmbeddingService embeddingService;
    private final Neo4jComponentNodeRepository componentNodeRepository;
    private final Neo4jFrontendRouteNodeRepository frontendRouteNodeRepository;
    private final Neo4jApiClientNodeRepository apiClientNodeRepository;

    /**
     * 为某前端项目下所有尚无向量的前端节点生成 embedding 并写回。
     *
     * @param frontendProjectPath 前端项目路径
     * @return 结构化结果（各类节点已向量化数 / 失败数）
     */
    public Map<String, Object> vectorize(String frontendProjectPath) {
        int components = vectorizeComponents(frontendProjectPath);
        int routes = vectorizeRoutes(frontendProjectPath);
        int apiClients = vectorizeApiClients(frontendProjectPath);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("componentEmbeddings", components);
        result.put("routeEmbeddings", routes);
        result.put("apiClientEmbeddings", apiClients);
        result.put("totalEmbeddings", components + routes + apiClients);
        log.info("[FrontendNodeVectorizer] 前端向量化完成: frontend={}, total={}",
                frontendProjectPath, components + routes + apiClients);
        return result;
    }

    private int vectorizeComponents(String projectPath) {
        int ok = 0;
        int fail = 0;
        for (ComponentNode node : componentNodeRepository.findWithoutEmbeddingByProjectPath(projectPath)) {
            try {
                float[] emb = embeddingService.generateEmbedding(
                        FrontendNodeDescriptionBuilder.forComponent(node));
                componentNodeRepository.updateDescriptionEmbedding(node.getComponentId(), toDoubleList(emb));
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("[FrontendNodeVectorizer] Component 向量失败: {}", node.getComponentId(), e);
            }
        }
        logFail("Component", ok, fail);
        return ok;
    }

    private int vectorizeRoutes(String projectPath) {
        int ok = 0;
        int fail = 0;
        for (FrontendRouteNode node : frontendRouteNodeRepository.findWithoutEmbeddingByProjectPath(projectPath)) {
            try {
                float[] emb = embeddingService.generateEmbedding(
                        FrontendNodeDescriptionBuilder.forRoute(node));
                frontendRouteNodeRepository.updateDescriptionEmbedding(node.getFrontendRouteId(), toDoubleList(emb));
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("[FrontendNodeVectorizer] FrontendRoute 向量失败: {}", node.getFrontendRouteId(), e);
            }
        }
        logFail("FrontendRoute", ok, fail);
        return ok;
    }

    private int vectorizeApiClients(String projectPath) {
        int ok = 0;
        int fail = 0;
        for (ApiClientNode node : apiClientNodeRepository.findWithoutEmbeddingByProjectPath(projectPath)) {
            try {
                float[] emb = embeddingService.generateEmbedding(
                        FrontendNodeDescriptionBuilder.forApiClient(node));
                apiClientNodeRepository.updateDescriptionEmbedding(node.getApiClientId(), toDoubleList(emb));
                ok++;
            } catch (Exception e) {
                fail++;
                log.warn("[FrontendNodeVectorizer] ApiClient 向量失败: {}", node.getApiClientId(), e);
            }
        }
        logFail("ApiClient", ok, fail);
        return ok;
    }

    private void logFail(String type, int ok, int fail) {
        if (fail > 0) {
            log.warn("[FrontendNodeVectorizer] {} 向量化: 成功={}, 失败={}", type, ok, fail);
        }
    }

    private static List<Double> toDoubleList(float[] arr) {
        if (arr == null) {
            return List.of();
        }
        List<Double> list = new ArrayList<>(arr.length);
        for (float f : arr) {
            list.add((double) f);
        }
        return list;
    }
}
