package com.huawei.hisi.knowledgegraph.link;

import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.HttpEntryInfo;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.OutboundHttpCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import org.mockito.Mockito;

@ExtendWith(MockitoExtension.class)
class HttpRestLinkStrategyTest {

    @Mock
    private Neo4jMethodNodeRepository methodNodeRepository;

    @InjectMocks
    private HttpRestLinkStrategy strategy;

    // ==================== normalizeUrl tests ====================

    @Test
    @DisplayName("normalizeUrl: colon-style placeholder")
    void normalizeUrl_colonStyle() {
        assertThat(HttpRestLinkStrategy.normalizeUrl("/users/:id")).isEqualTo("/users/{}");
    }

    @Test
    @DisplayName("normalizeUrl: brace-style placeholder")
    void normalizeUrl_braceStyle() {
        assertThat(HttpRestLinkStrategy.normalizeUrl("/users/{userId}")).isEqualTo("/users/{}");
    }

    @Test
    @DisplayName("normalizeUrl: Flask-style placeholder")
    void normalizeUrl_flaskStyle() {
        assertThat(HttpRestLinkStrategy.normalizeUrl("/users/<int:id>")).isEqualTo("/users/{}");
    }

    @Test
    @DisplayName("normalizeUrl: trailing slash stripped")
    void normalizeUrl_trailingSlash() {
        assertThat(HttpRestLinkStrategy.normalizeUrl("/api/users/")).isEqualTo("/api/users");
    }

    @Test
    @DisplayName("normalizeUrl: mixed placeholders")
    void normalizeUrl_mixed() {
        assertThat(HttpRestLinkStrategy.normalizeUrl("/api/:version/users/{id}"))
            .isEqualTo("/api/{}/users/{}");
    }

    // ==================== normalizeEntryKey tests ====================

    @Test
    @DisplayName("normalizeEntryKey: normalizes method and path")
    void normalizeEntryKey_normalizesMethodAndPath() {
        assertThat(HttpRestLinkStrategy.normalizeEntryKey("GET /users/{userId}"))
            .isEqualTo("GET /users/{}");
    }

    // ==================== link() integration tests ====================

    @Test
    @DisplayName("link: creates EXTERNAL_CALL edge when outbound matches entry")
    @SuppressWarnings("unchecked")
    void link_createsExternalCallEdge() {
        OutboundHttpCall outbound = mockOutbound("caller-1", "/svc-a", "/users/:id", "GET", 42);
        HttpEntryInfo entry = mockEntry("GET /users/{userId}", "handler-1", "/svc-b");
        List<String> paths = List.of("/svc-a", "/svc-b");

        when(methodNodeRepository.findOutboundHttpCalls(paths)).thenReturn(List.of(outbound));
        when(methodNodeRepository.findHttpEntries(paths)).thenReturn(List.of(entry));

        strategy.link(paths);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(methodNodeRepository).createCallRelations(captor.capture());

        List<Map<String, Object>> relations = captor.getValue();
        assertThat(relations).hasSize(1);
        Map<String, Object> rel = relations.get(0);
        assertThat(rel.get("callerId")).isEqualTo("caller-1");
        assertThat(rel.get("calleeId")).isEqualTo("handler-1");
        assertThat(rel.get("callType")).isEqualTo("EXTERNAL_CALL");
        assertThat(rel.get("bridgeType")).isEqualTo("HTTP");
        assertThat(rel.get("callLine")).isEqualTo(42);
    }

    @Test
    @DisplayName("link: skips same-service match")
    void link_skipsSameServiceMatch() {
        OutboundHttpCall outbound = mockOutbound("caller-1", "/svc-a", "/users/:id", "GET", 10);
        HttpEntryInfo entry = mockEntry("GET /users/{userId}", "handler-1", "/svc-a");
        List<String> paths = List.of("/svc-a");

        when(methodNodeRepository.findOutboundHttpCalls(paths)).thenReturn(List.of(outbound));
        when(methodNodeRepository.findHttpEntries(paths)).thenReturn(List.of(entry));

        strategy.link(paths);

        verify(methodNodeRepository, never()).createCallRelations(anyList());
    }

    @Test
    @DisplayName("link: no outbounds, no edges")
    void link_noOutbounds_noEdges() {
        HttpEntryInfo entry = mockEntry("GET /x", "h1", "/b");
        List<String> paths = List.of("/a", "/b");
        when(methodNodeRepository.findOutboundHttpCalls(paths)).thenReturn(Collections.emptyList());
        when(methodNodeRepository.findHttpEntries(paths)).thenReturn(List.of(entry));

        strategy.link(paths);

        verify(methodNodeRepository, never()).createCallRelations(anyList());
    }

    @Test
    @DisplayName("link: no entries, no edges")
    void link_noEntries_noEdges() {
        OutboundHttpCall outbound = mockOutbound("c1", "/a", "/x", "GET", 1);
        List<String> paths = List.of("/a", "/b");
        when(methodNodeRepository.findOutboundHttpCalls(paths)).thenReturn(List.of(outbound));
        when(methodNodeRepository.findHttpEntries(paths)).thenReturn(Collections.emptyList());

        strategy.link(paths);

        verify(methodNodeRepository, never()).createCallRelations(anyList());
    }

    // ==================== helpers ====================

    private OutboundHttpCall mockOutbound(String callerNodeId, String callerProjectPath,
                                          String targetEndpoint, String httpMethod, Integer callLine) {
        OutboundHttpCall mock = mock(OutboundHttpCall.class, Mockito.withSettings().lenient());
        when(mock.getCallerNodeId()).thenReturn(callerNodeId);
        when(mock.getCallerProjectPath()).thenReturn(callerProjectPath);
        when(mock.getTargetEndpoint()).thenReturn(targetEndpoint);
        when(mock.getHttpMethod()).thenReturn(httpMethod);
        when(mock.getCallLine()).thenReturn(callLine);
        return mock;
    }

    private HttpEntryInfo mockEntry(String entryKey, String methodNodeId, String projectPath) {
        HttpEntryInfo mock = mock(HttpEntryInfo.class, Mockito.withSettings().lenient());
        when(mock.getEntryKey()).thenReturn(entryKey);
        when(mock.getMethodNodeId()).thenReturn(methodNodeId);
        when(mock.getProjectPath()).thenReturn(projectPath);
        return mock;
    }
}
