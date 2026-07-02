package com.huawei.hisi.ram.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("TurnRegistry per-session active turn tracking")
class TurnRegistryTest {

    @Mock private Disposable disposable1;
    @Mock private Disposable disposable2;

    private TurnRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TurnRegistry();
    }

    private TurnRegistry.ActiveTurn turn(String turnId, long sessionId, Disposable d, StringBuilder buf) {
        return new TurnRegistry.ActiveTurn(turnId, sessionId, d, buf, Instant.now(), "glm-5.1");
    }

    @Test
    @DisplayName("register: new session stores the turn and get() returns it")
    void register_newSession_storesTurn() {
        StringBuilder buf = new StringBuilder("hello");
        TurnRegistry.ActiveTurn t = turn("t1", 1L, disposable1, buf);

        registry.register(1L, t);

        Optional<TurnRegistry.ActiveTurn> found = registry.get(1L);
        assertThat(found).isPresent();
        assertThat(found.get().turnId()).isEqualTo("t1");
        assertThat(found.get().sessionId()).isEqualTo(1L);
        verifyNoInteractions(disposable1);
    }

    @Test
    @DisplayName("register: existing active turn is disposed before overwrite")
    void register_existingActiveTurn_disposesPreviousFirst() {
        StringBuilder buf1 = new StringBuilder("first");
        StringBuilder buf2 = new StringBuilder("second");
        registry.register(1L, turn("t1", 1L, disposable1, buf1));

        registry.register(1L, turn("t2", 1L, disposable2, buf2));

        verify(disposable1).dispose();
        assertThat(registry.get(1L)).isPresent();
        assertThat(registry.get(1L).get().turnId()).isEqualTo("t2");
    }

    @Test
    @DisplayName("interrupt: active turn is disposed and partial snapshot returned")
    void interrupt_activeTurn_disposesAndReturnsSnapshot() {
        StringBuilder buf = new StringBuilder("partial content");
        registry.register(7L, turn("t7", 7L, disposable1, buf));

        Optional<TurnRegistry.InterruptResult> res = registry.interrupt(7L);

        assertThat(res).isPresent();
        assertThat(res.get().turnId()).isEqualTo("t7");
        assertThat(res.get().partialText()).isEqualTo("partial content");
        verify(disposable1).dispose();
        assertThat(registry.get(7L)).isEmpty();
    }

    @Test
    @DisplayName("interrupt: no active turn returns empty and does not dispose anything")
    void interrupt_noActiveTurn_returnsEmpty() {
        Optional<TurnRegistry.InterruptResult> res = registry.interrupt(999L);

        assertThat(res).isEmpty();
        verifyNoInteractions(disposable1, disposable2);
    }

    @Test
    @DisplayName("complete: matching turnId removes the entry")
    void complete_matchingTurnId_removes() {
        registry.register(3L, turn("t3", 3L, disposable1, new StringBuilder()));

        registry.complete(3L, "t3");

        assertThat(registry.get(3L)).isEmpty();
    }

    @Test
    @DisplayName("complete: stale turnId does NOT remove a newer turn")
    void complete_staleTurnId_doesNotRemoveNewer() {
        // Simulate: old turn "t-old" is completed AFTER a new turn "t-new" was already registered.
        registry.register(5L, turn("t-new", 5L, disposable2, new StringBuilder("new")));

        registry.complete(5L, "t-old");

        assertThat(registry.get(5L)).isPresent();
        assertThat(registry.get(5L).get().turnId()).isEqualTo("t-new");
    }
}
