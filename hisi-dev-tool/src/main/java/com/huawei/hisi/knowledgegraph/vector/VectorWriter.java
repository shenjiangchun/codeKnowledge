package com.huawei.hisi.knowledgegraph.vector;

import com.huawei.hisi.knowledgegraph.service.LLMDescriptionService;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable component that extracts the core embedding logic from VectorGenerationService.
 * Supports upsert (description + embeddings) for MethodNode and SqlNode,
 * and delete-by-filePath for incremental refresh scenarios.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorWriter {

    private final EmbeddingService embeddingService;
    private final LLMDescriptionService llmDescriptionService;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jSqlNodeRepository sqlNodeRepository;

    private static final int MAX_METHOD_BODY_LENGTH = 2000;

    /**
     * Generate description + embeddings for a MethodNode and persist to Neo4j.
     * This is the core logic extracted from VectorGenerationService.processMethod.
     */
    public void upsertMethod(MethodNode method) {
        // 1. Generate description via LLM (fallback: className.methodName - signature)
        String description = llmDescriptionService.generateDescriptionWithBody(method);
        if (description == null || description.isBlank()) {
            description = method.getClassName() + "." + method.getMethodName() + " - " + method.getSignature();
        }
        // 2. Generate descriptionEmbedding
        float[] descEmb = embeddingService.generateEmbedding(description);
        // 3. Build code text and generate codeEmbedding
        String codeText = buildCodeText(method);
        float[] codeEmb = embeddingService.generateEmbedding(codeText);
        // 4. Persist via repository
        methodNodeRepository.updateDescriptionAndCodeEmbedding(
            method.getNodeId(), description, toDoubleList(descEmb), toDoubleList(codeEmb));
    }

    /**
     * Generate embedding for a SqlNode and persist to Neo4j.
     */
    public void upsertSql(SqlNode sqlNode) {
        float[] sqlEmb = embeddingService.generateEmbedding(sqlNode.getSqlStatement());
        sqlNodeRepository.updateSqlEmbedding(sqlNode.getNodeId(), sqlEmb);
    }

    /**
     * Delete all nodes for a given filePath within a projectPath scope.
     * Uses DETACH DELETE to remove nodes and all their relationships.
     * Embeddings are cleared automatically since they're node properties.
     */
    public void deleteByFilePath(String filePath, String projectPath) {
        methodNodeRepository.detachDeleteByFilePathAndProjectPath(filePath, projectPath);
    }

    private String buildCodeText(MethodNode method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getClassName()).append(".").append(method.getMethodName());
        if (method.getSignature() != null) {
            sb.append("(").append(method.getSignature()).append(")");
        }
        sb.append("\n");
        if (method.getMethodBody() != null) {
            String body = method.getMethodBody();
            if (body.length() > MAX_METHOD_BODY_LENGTH) {
                body = body.substring(0, MAX_METHOD_BODY_LENGTH);
            }
            sb.append(body);
        }
        return sb.toString();
    }

    static List<Double> toDoubleList(float[] arr) {
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
