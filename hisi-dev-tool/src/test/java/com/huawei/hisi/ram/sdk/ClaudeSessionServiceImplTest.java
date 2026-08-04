package com.huawei.hisi.ram.sdk;

import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.sdk.impl.AnthropicHttpClient;
import com.huawei.hisi.ram.sdk.impl.ClaudeSessionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TDD test for {@link ClaudeSessionServiceImpl}. Mocks the {@link AnthropicHttpClient}
 * so we can drive a synthetic SSE stream and assert event-log persistence.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/ram-sdk-test.db",
        "spring.datasource.hikari.maximum-pool-size=1"
})
@ActiveProfiles("test")
@DisplayName("ClaudeSessionServiceImpl integration tests")
class ClaudeSessionServiceImplTest {

    @Autowired
    private ClaudeSessionService service;

    @Autowired
    private AgentEventRepository eventRepo;

    @Autowired
    private AgentSessionRepository sessionRepo;

    @MockBean
    private AnthropicHttpClient http;

    @Test
    @DisplayName("sendUserMessage streams two deltas + finish, and persists USER_MSG + ASSISTANT_DELTA")
    void sendUserMessage_streamsDeltasAndPersistsEvents() {
        long sid = service.createSession("user-sdk-1", null);

        when(http.stream(any(), any(), any())).thenReturn(Flux.just(
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}",
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}",
                "{\"type\":\"message_stop\"}"));

        Flux<SSEEvent> stream = service.sendUserMessage(sid, "hi", SendOptions.defaults());

        StepVerifier.create(stream)
                .expectNextMatches(e -> e.type() == SSEEvent.Type.DELTA && "Hello ".equals(e.text()))
                .expectNextMatches(e -> e.type() == SSEEvent.Type.DELTA && "world".equals(e.text()))
                .expectNextMatches(e -> e.type() == SSEEvent.Type.FINISH)
                .verifyComplete();

        List<AgentEvent> events = eventRepo.findBySessionId(sid);
        assertThat(events).extracting(AgentEvent::getType)
                .containsExactly(EventType.USER_MSG, EventType.ASSISTANT_DELTA);

        AgentEvent assistant = events.get(1);
        assertThat(assistant.getPayload()).contains("Hello world");
    }
}
