package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.codegraph.FrontendAstParser;
import com.huawei.hisi.knowledgegraph.link.FrontendBackendLinker;
import com.huawei.hisi.knowledgegraph.service.storage.KnowledgeGraphStorageService;
import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.repository.Neo4jComponentNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FrontendGraphOrchestrator")
class FrontendGraphOrchestratorTest {

    @Mock
    private FrontendProjectDiscoverer discoverer;

    @Mock
    private FrontendAstParser parser;

    @Mock
    private FrontendBackendLinker linker;

    @Mock
    private KnowledgeGraphStorageService storageService;

    @Mock
    private Neo4jComponentNodeRepository componentNodeRepository;

    private FrontendGraphOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new FrontendGraphOrchestrator(discoverer, parser, linker, storageService, componentNodeRepository);
    }

    @Test
    @DisplayName("发现前端项目后编排实体化 + 链接")
    void run_discoveredFrontend_orchestrates() {
        when(discoverer.discover("/be", null)).thenReturn(List.of("/fe"));

        ApiClientNode client = ApiClientNode.builder()
            .apiClientId("fe:x.ts:GET /api")
            .method("GET").url("/api").projectPath("/fe").build();
        when(parser.parseDirectory("/fe", "/fe"))
            .thenReturn(new FrontendAstParser.ParseResult(List.of(client), List.of()));
        when(parser.toFrontendRouteNodes(any(), anyString())).thenReturn(List.of());
        when(linker.link("/fe", List.of("/be"))).thenReturn(1);

        orchestrator.run("/be", null);

        verify(storageService).saveApiClientNodes(anyList());
        verify(linker).link("/fe", List.of("/be"));
    }

    @Test
    @DisplayName("未发现前端项目时跳过，不调用落库")
    void run_noFrontend_skips() {
        when(discoverer.discover("/be", null)).thenReturn(List.of());
        orchestrator.run("/be", null);
        verify(storageService, never()).saveApiClientNodes(anyList());
        verify(linker, never()).link(anyString(), anyList());
    }
}
