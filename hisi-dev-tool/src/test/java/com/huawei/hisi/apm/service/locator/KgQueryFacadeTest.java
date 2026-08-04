package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.DiagnoseReport;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KgQueryFacadeTest {

    private static final String SCOPE = "/abs/proj";
    private static final String CLASS = "com.example.Foo";
    private static final String METHOD = "bar";

    @Mock
    private Neo4jMethodNodeRepository repository;

    private KgQueryFacade facade;

    @BeforeEach
    void setUp() {
        facade = new KgQueryFacade(repository);
    }

    private MethodNode node(String nodeId, String className, String methodName) {
        return MethodNode.builder()
                .nodeId(nodeId)
                .className(className)
                .methodName(methodName)
                .filePath("/src/" + className + ".java")
                .startLine(42)
                .endLine(50)
                .description("does the bar thing")
                .build();
    }

    @Test
    @DisplayName("findMethodAnchor: maps repo hit to anchor with kg_method kind")
    void findMethodAnchor_hit() {
        when(repository.findByProjectPathAndClassNameAndMethodName(SCOPE, CLASS, METHOD))
                .thenReturn(List.of(node("n1", CLASS, METHOD)));

        Optional<DiagnoseReport.EvidenceAnchor> result = facade.findMethodAnchor(SCOPE, CLASS, METHOD);

        assertThat(result).isPresent();
        DiagnoseReport.EvidenceAnchor anchor = result.get();
        assertThat(anchor.type()).isEqualTo("kg_method");
        assertThat(anchor.className()).isEqualTo(CLASS);
        assertThat(anchor.methodName()).isEqualTo(METHOD);
        assertThat(anchor.filePath()).isEqualTo("/src/" + CLASS + ".java");
        assertThat(anchor.startLine()).isEqualTo(42);
        assertThat(anchor.snippet()).isEqualTo("does the bar thing");
    }

    @Test
    @DisplayName("findMethodAnchor: returns empty when repo has no match")
    void findMethodAnchor_miss() {
        when(repository.findByProjectPathAndClassNameAndMethodName(SCOPE, CLASS, METHOD))
                .thenReturn(List.of());

        assertThat(facade.findMethodAnchor(SCOPE, CLASS, METHOD)).isEmpty();
    }

    @Test
    @DisplayName("findCallerAnchors: returns mapped anchors up to maxCallers")
    void findCallerAnchors_returnsList() {
        when(repository.findByProjectPathAndClassNameAndMethodName(SCOPE, CLASS, METHOD))
                .thenReturn(List.of(node("n1", CLASS, METHOD)));
        when(repository.findCallers("n1")).thenReturn(List.of(
                node("c1", "com.example.A", "callA"),
                node("c2", "com.example.B", "callB")
        ));

        List<DiagnoseReport.EvidenceAnchor> result = facade.findCallerAnchors(SCOPE, CLASS, METHOD, 5);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(a -> assertThat(a.type()).isEqualTo("kg_caller"));
        assertThat(result.get(0).className()).isEqualTo("com.example.A");
    }

    @Test
    @DisplayName("findCallerAnchors: truncates when repo returns more than maxCallers")
    void findCallerAnchors_truncates() {
        when(repository.findByProjectPathAndClassNameAndMethodName(SCOPE, CLASS, METHOD))
                .thenReturn(List.of(node("n1", CLASS, METHOD)));
        when(repository.findCallers("n1")).thenReturn(List.of(
                node("c1", "com.example.A", "a"),
                node("c2", "com.example.B", "b"),
                node("c3", "com.example.C", "c"),
                node("c4", "com.example.D", "d"),
                node("c5", "com.example.E", "e")
        ));

        List<DiagnoseReport.EvidenceAnchor> result = facade.findCallerAnchors(SCOPE, CLASS, METHOD, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(2).className()).isEqualTo("com.example.C");
    }

    @Test
    @DisplayName("findCallerAnchors: returns empty list when target method not found")
    void findCallerAnchors_targetMiss() {
        when(repository.findByProjectPathAndClassNameAndMethodName(SCOPE, CLASS, METHOD))
                .thenReturn(List.of());

        assertThat(facade.findCallerAnchors(SCOPE, CLASS, METHOD, 3)).isEmpty();
    }
}
