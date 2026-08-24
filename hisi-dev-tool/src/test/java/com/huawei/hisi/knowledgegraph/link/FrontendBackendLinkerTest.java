package com.huawei.hisi.knowledgegraph.link;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.repository.Neo4jApiClientNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FrontendBackendLinker")
class FrontendBackendLinkerTest {

    @Mock
    private Neo4jApiClientNodeRepository apiClientNodeRepository;

    @Mock
    private Neo4jEntryPointNodeRepository entryPointNodeRepository;

    private FrontendBackendLinker linker;

    @BeforeEach
    void setUp() {
        linker = new FrontendBackendLinker(apiClientNodeRepository, entryPointNodeRepository);
    }

    @Test
    @DisplayName("精确匹配建立 INVOKES_API 边")
    void link_exactMatch_createsEdge() {
        ApiClientNode client = ApiClientNode.builder()
            .apiClientId("fe:api/knowledgeGraph.ts:GET /v2/knowledge-graph/projects")
            .method("GET")
            .url("/v2/knowledge-graph/projects")
            .projectPath("/fe")
            .build();
        EntryPointNode ep = EntryPointNode.builder()
            .entryId("be:GET /api/v2/knowledge-graph/projects")
            .entryType("HTTP")
            .entryKey("GET /api/v2/knowledge-graph/projects")
            .projectPath("/be")
            .build();

        when(apiClientNodeRepository.findByProjectPath("/fe")).thenReturn(List.of(client));
        when(entryPointNodeRepository.findByProjectPathsAndEntryType(List.of("/be"), "HTTP")).thenReturn(List.of(ep));

        int count = linker.link("/fe", List.of("/be"));
        assertThat(count).isEqualTo(1);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiClientNodeRepository).createInvokesApiRelations(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).get("entryId")).isEqualTo("be:GET /api/v2/knowledge-graph/projects");
    }

    @Test
    @DisplayName("路径参数归一化后匹配（前端 ${var} vs 后端 {var}）")
    void link_pathParamNormalization_matches() {
        ApiClientNode client = ApiClientNode.builder()
            .apiClientId("fe:api/callChain.ts:DELETE /callchain/analysis/project/${projectName}")
            .method("DELETE")
            .url("/callchain/analysis/project/${projectName}")
            .projectPath("/fe")
            .build();
        EntryPointNode ep = EntryPointNode.builder()
            .entryId("be:DELETE /api/callchain/analysis/project/{projectName}")
            .entryType("HTTP")
            .entryKey("DELETE /api/callchain/analysis/project/{projectName}")
            .projectPath("/be")
            .build();

        when(apiClientNodeRepository.findByProjectPath("/fe")).thenReturn(List.of(client));
        when(entryPointNodeRepository.findByProjectPathsAndEntryType(List.of("/be"), "HTTP")).thenReturn(List.of(ep));

        int count = linker.link("/fe", List.of("/be"));
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("无匹配时不建边")
    void link_noMatch_zeroEdges() {
        ApiClientNode client = ApiClientNode.builder()
            .apiClientId("fe:api/x.ts:GET /unknown")
            .method("GET")
            .url("/unknown")
            .projectPath("/fe")
            .build();
        when(apiClientNodeRepository.findByProjectPath("/fe")).thenReturn(List.of(client));
        when(entryPointNodeRepository.findByProjectPathsAndEntryType(List.of("/be"), "HTTP")).thenReturn(List.of());

        int count = linker.link("/fe", List.of("/be"));
        assertThat(count).isEqualTo(0);
        verify(apiClientNodeRepository, org.mockito.Mockito.never()).createInvokesApiRelations(any());
    }

    @Test
    @DisplayName("空前端路径安全返回 0")
    void link_blankFrontendPath_zero() {
        assertThat(linker.link("", List.of("/be"))).isEqualTo(0);
        assertThat(linker.link(null, List.of("/be"))).isEqualTo(0);
    }
}
