package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KgEnricherImplTest {

    private static final String PROJECT = "/abs/proj";

    @Mock
    private KgQueryFacade facade;

    private KgEnricherImpl enricher;

    @BeforeEach
    void setUp() {
        enricher = new KgEnricherImpl(facade);
    }

    private static ApmSpanEntity spanWithAttrs(String spanId, Map<String, String> attrs) {
        return ApmSpanEntity.builder()
                .spanId(spanId)
                .statusCode("ERROR")
                .attributes(attrs)
                .build();
    }

    private static ApmSpanEntity spanWithName(String spanId, String name) {
        return ApmSpanEntity.builder()
                .spanId(spanId)
                .statusCode("ERROR")
                .operationName(name)
                .build();
    }

    private static DiagnoseReport.EvidenceAnchor anchor(String type, String cls, String method) {
        return new DiagnoseReport.EvidenceAnchor(type, cls, method, "/src/X.java", 1, null, "snip");
    }

    @Test
    @DisplayName("empty span list yields empty anchor list")
    void emptySpans_emptyAnchors() {
        assertThat(enricher.enrich(PROJECT, List.of())).isEmpty();
        assertThat(enricher.enrich(PROJECT, null)).isEmpty();
        verify(facade, never()).findMethodAnchor(any(), any(), any());
    }

    @Test
    @DisplayName("single span with operationName: 1 method anchor + up to 3 caller anchors")
    void singleSpan_methodPlusCallers() {
        ApmSpanEntity span = spanWithName("s1", "com.example.Foo.bar");
        when(facade.findMethodAnchor(PROJECT, "com.example.Foo", "bar"))
                .thenReturn(Optional.of(anchor("kg_method", "com.example.Foo", "bar")));
        when(facade.findCallerAnchors(PROJECT, "com.example.Foo", "bar", 3))
                .thenReturn(List.of(
                        anchor("kg_caller", "com.example.A", "a"),
                        anchor("kg_caller", "com.example.B", "b"),
                        anchor("kg_caller", "com.example.C", "c")
                ));

        List<DiagnoseReport.EvidenceAnchor> result = enricher.enrich(PROJECT, List.of(span));

        assertThat(result).hasSize(4);
        assertThat(result.get(0).type()).isEqualTo("kg_method");
        assertThat(result).filteredOn(a -> "kg_caller".equals(a.type())).hasSize(3);
    }

    @Test
    @DisplayName("two spans with same (class, method) are deduped to a single method anchor")
    void duplicateSpans_dedup() {
        ApmSpanEntity s1 = spanWithName("s1", "com.example.Foo.bar");
        ApmSpanEntity s2 = spanWithName("s2", "com.example.Foo.bar");
        when(facade.findMethodAnchor(PROJECT, "com.example.Foo", "bar"))
                .thenReturn(Optional.of(anchor("kg_method", "com.example.Foo", "bar")));
        when(facade.findCallerAnchors(eq(PROJECT), eq("com.example.Foo"), eq("bar"), anyInt()))
                .thenReturn(List.of());

        List<DiagnoseReport.EvidenceAnchor> result = enricher.enrich(PROJECT, List.of(s1, s2));

        assertThat(result).hasSize(1);
        verify(facade, times(1)).findMethodAnchor(PROJECT, "com.example.Foo", "bar");
    }

    @Test
    @DisplayName("span without class/method attrs and unparseable name is skipped")
    void unparseableSpan_skipped() {
        ApmSpanEntity span = spanWithName("s1", "rootspan");

        List<DiagnoseReport.EvidenceAnchor> result = enricher.enrich(PROJECT, List.of(span));

        assertThat(result).isEmpty();
        verify(facade, never()).findMethodAnchor(any(), any(), any());
        verify(facade, never()).findCallerAnchors(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("returned list is immutable")
    void returnedListIsImmutable() {
        ApmSpanEntity span = spanWithAttrs("s1",
                Map.of("code.namespace", "com.example.Foo", "code.function", "bar"));
        when(facade.findMethodAnchor(PROJECT, "com.example.Foo", "bar"))
                .thenReturn(Optional.of(anchor("kg_method", "com.example.Foo", "bar")));
        when(facade.findCallerAnchors(eq(PROJECT), eq("com.example.Foo"), eq("bar"), anyInt()))
                .thenReturn(List.of());

        List<DiagnoseReport.EvidenceAnchor> result = enricher.enrich(PROJECT, List.of(span));

        assertThatThrownBy(() -> result.add(anchor("kg_method", "x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
