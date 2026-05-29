package com.huawei.hisi.knowledgegraph.link;

import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("MqLinkStrategy")
class MqLinkStrategyTest {

    @Mock
    private Neo4jMethodNodeRepository methodNodeRepository;

    @Captor
    private ArgumentCaptor<List<Map<String, Object>>> relationsCaptor;

    private MqLinkStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MqLinkStrategy(methodNodeRepository);
    }

    // --- normalizeTopic tests ---

    @Test
    @DisplayName("normalizeTopic strips MQ: prefix")
    void normalizeTopic_stripsMqPrefix() {
        assertThat(MqLinkStrategy.normalizeTopic("MQ:order.created")).isEqualTo("order.created");
    }

    @Test
    @DisplayName("normalizeTopic keeps plain topic unchanged")
    void normalizeTopic_keepsPlainTopic() {
        assertThat(MqLinkStrategy.normalizeTopic("tasks.process_order")).isEqualTo("tasks.process_order");
    }

    @Test
    @DisplayName("normalizeTopic handles null")
    void normalizeTopic_handlesNull() {
        assertThat(MqLinkStrategy.normalizeTopic(null)).isEqualTo("");
    }

    // --- link tests ---

    @Test
    @DisplayName("link creates EXTERNAL_CALL edge between producer and consumer")
    void link_createsEdgeBetweenProducerAndConsumer() {
        var producer = mockProducer("caller1", "/project-a", "order.created", 42);
        var consumer = mockConsumer("MQ:order.created", "method1", "/project-b");

        when(methodNodeRepository.findMqProducerCalls(List.of("/scope"))).thenReturn(List.of(producer));
        when(methodNodeRepository.findMqConsumerEntries(List.of("/scope"))).thenReturn(List.of(consumer));

        strategy.link(List.of("/scope"));

        verify(methodNodeRepository).createCallRelations(relationsCaptor.capture());
        List<Map<String, Object>> relations = relationsCaptor.getValue();
        assertThat(relations).hasSize(1);

        Map<String, Object> rel = relations.get(0);
        assertThat(rel.get("callerId")).isEqualTo("caller1");
        assertThat(rel.get("calleeId")).isEqualTo("method1");
        assertThat(rel.get("callType")).isEqualTo("EXTERNAL_CALL");
        assertThat(rel.get("bridgeType")).isEqualTo("MQ");
        assertThat(rel.get("targetEndpoint")).isEqualTo("order.created");
        assertThat(rel.get("callLine")).isEqualTo(42);
    }

    @Test
    @DisplayName("link skips same-service match")
    void link_skipsSameServiceMatch() {
        var producer = mockProducer("caller1", "/project-a", "order.created", 10);
        var consumer = mockConsumer("MQ:order.created", "method1", "/project-a");

        when(methodNodeRepository.findMqProducerCalls(List.of("/scope"))).thenReturn(List.of(producer));
        when(methodNodeRepository.findMqConsumerEntries(List.of("/scope"))).thenReturn(List.of(consumer));

        strategy.link(List.of("/scope"));

        verify(methodNodeRepository, never()).createCallRelations(any());
    }

    @Test
    @DisplayName("link does nothing when no producers")
    void link_noProducers_noEdges() {
        var consumer = mockConsumer("MQ:x", "m1", "/p1");
        when(methodNodeRepository.findMqProducerCalls(List.of("/scope"))).thenReturn(List.of());
        when(methodNodeRepository.findMqConsumerEntries(List.of("/scope"))).thenReturn(List.of(consumer));

        strategy.link(List.of("/scope"));

        verify(methodNodeRepository, never()).createCallRelations(any());
    }

    @Test
    @DisplayName("link does nothing when no consumers")
    void link_noConsumers_noEdges() {
        var producer = mockProducer("c1", "/p1", "topic", 1);
        when(methodNodeRepository.findMqProducerCalls(List.of("/scope"))).thenReturn(List.of(producer));
        when(methodNodeRepository.findMqConsumerEntries(List.of("/scope"))).thenReturn(List.of());

        strategy.link(List.of("/scope"));

        verify(methodNodeRepository, never()).createCallRelations(any());
    }

    @Test
    @DisplayName("link matches Celery-style plain topic")
    void link_celeryTopicMatch() {
        var producer = mockProducer("caller1", "/project-a", "tasks.process_order", 5);
        var consumer = mockConsumer("tasks.process_order", "method1", "/project-b");

        when(methodNodeRepository.findMqProducerCalls(List.of("/scope"))).thenReturn(List.of(producer));
        when(methodNodeRepository.findMqConsumerEntries(List.of("/scope"))).thenReturn(List.of(consumer));

        strategy.link(List.of("/scope"));

        verify(methodNodeRepository).createCallRelations(relationsCaptor.capture());
        List<Map<String, Object>> relations = relationsCaptor.getValue();
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).get("targetEndpoint")).isEqualTo("tasks.process_order");
    }

    // --- helpers ---

    private Neo4jMethodNodeRepository.MqProducerCall mockProducer(
            String callerNodeId, String callerProjectPath, String topic, Integer callLine) {
        var mock = mock(Neo4jMethodNodeRepository.MqProducerCall.class);
        lenient().when(mock.getCallerNodeId()).thenReturn(callerNodeId);
        lenient().when(mock.getCallerProjectPath()).thenReturn(callerProjectPath);
        lenient().when(mock.getTopic()).thenReturn(topic);
        lenient().when(mock.getCallLine()).thenReturn(callLine);
        return mock;
    }

    private Neo4jMethodNodeRepository.MqConsumerEntry mockConsumer(
            String entryKey, String methodNodeId, String projectPath) {
        var mock = mock(Neo4jMethodNodeRepository.MqConsumerEntry.class);
        lenient().when(mock.getEntryKey()).thenReturn(entryKey);
        lenient().when(mock.getMethodNodeId()).thenReturn(methodNodeId);
        lenient().when(mock.getProjectPath()).thenReturn(projectPath);
        return mock;
    }
}
