package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.knowledgegraph.aggregation.AggregationPipeline;
import com.huawei.hisi.knowledgegraph.aggregation.stage.ClassLayerRoleDetector;
import com.huawei.hisi.knowledgegraph.codegraph.CodegraphSidecarService;
import com.huawei.hisi.knowledgegraph.codegraph.CodegraphSqliteReader;
import com.huawei.hisi.knowledgegraph.codegraph.CodegraphToNeo4jTransformer;
import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.knowledgegraph.scanner.JavaDataModelScanner;
import com.huawei.hisi.knowledgegraph.scanner.MyBatisXmlScanner;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import com.huawei.hisi.neo4j.repository.Neo4jClassNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jDataModelNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import com.huawei.hisi.scanner.FeignClientScanner;
import com.huawei.hisi.scanner.HttpCallScanner;
import com.huawei.hisi.scanner.MQEndpointScanner;
import com.huawei.hisi.scanner.ProxyClassScanner;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证知识图谱构建器的清理逻辑只清理向量任务状态，
 * 不会误删当前正在运行的 KG 任务记录（task_type=KG）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphBuilderTest {

    @Mock private CodeAnalysisCoreService coreService;
    @Mock private GlobalAnalysisCache globalCache;
    @Mock private KnowledgeGraphStorageService storageService;
    @Mock private Neo4jSqlNodeRepository neo4jSqlNodeRepository;
    @Mock private Neo4jMethodNodeRepository neo4jMethodNodeRepository;
    @Mock private Neo4jEntryPointNodeRepository neo4jEntryPointNodeRepository;
    @Mock private MapperCallResolver mapperCallResolver;
    @Mock private FeignClientScanner feignClientScanner;
    @Mock private MQEndpointScanner mqEndpointScanner;
    @Mock private HttpCallScanner httpCallScanner;
    @Mock private ProxyClassScanner proxyClassScanner;
    @Mock private MyBatisXmlScanner myBatisXmlScanner;
    @Mock private VectorGenerationService vectorGenerationService;
    @Mock private GenerationTaskRepository generationTaskRepository;
    @Mock private GitStatusService gitStatusService;
    @Mock private PythonKnowledgeGraphBuilder pythonKnowledgeGraphBuilder;
    @Mock private Neo4jGenerationCheckpointRepository checkpointRepository;
    @Mock private JavaDataModelScanner javaDataModelScanner;
    @Mock private Neo4jDataModelNodeRepository neo4jDataModelNodeRepository;
    @Mock private Neo4jClassNodeRepository neo4jClassNodeRepository;
    @Mock private ClassLayerRoleDetector classLayerRoleDetector;
    @Mock private CodegraphSidecarService codegraphSidecarService;
    @Mock private CodegraphSqliteReader codegraphSqliteReader;
    @Mock private CodegraphToNeo4jTransformer codegraphTransformer;
    @Mock private AggregationPipeline aggregationPipeline;

    @InjectMocks
    private KnowledgeGraphBuilder builder;

    @Test
    @DisplayName("cleanOldData 全量清理只删除向量任务，保留 KG 任务记录")
    void cleanOldData_shouldOnlyDeleteVectorTasks() {
        when(neo4jSqlNodeRepository.deleteByProjectPathBatch(anyString(), anyInt())).thenReturn(0L);

        builder.cleanOldData("C:/test/proj");

        verify(generationTaskRepository).deleteByProjectPathAndType("C:/test/proj", "VECTOR");
        verify(generationTaskRepository, never()).deleteByProjectPath(anyString());
    }

    @Test
    @DisplayName("cleanOldDataForReuse 复用清理只删除向量任务，保留 KG 任务记录")
    void cleanOldDataForReuse_shouldOnlyDeleteVectorTasks() {
        when(neo4jSqlNodeRepository.deleteByProjectPathBatch(anyString(), anyInt())).thenReturn(0L);

        builder.cleanOldDataForReuse("C:/test/proj");

        verify(generationTaskRepository).deleteByProjectPathAndType("C:/test/proj", "VECTOR");
        verify(generationTaskRepository, never()).deleteByProjectPath(anyString());
    }
}
