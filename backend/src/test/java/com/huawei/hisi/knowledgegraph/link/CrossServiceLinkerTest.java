package com.huawei.hisi.knowledgegraph.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrossServiceLinkerTest {

    private static final List<String> PROJECT_PATHS = List.of("/workspace/service-a", "/workspace/service-b");

    @Test
    @DisplayName("link executes all strategies in order")
    void link_executesAllStrategiesInOrder() {
        LinkStrategy first = mock(LinkStrategy.class, "first");
        LinkStrategy second = mock(LinkStrategy.class, "second");
        LinkStrategy third = mock(LinkStrategy.class, "third");

        var linker = new CrossServiceLinker(List.of(first, second, third));
        linker.link(PROJECT_PATHS);

        InOrder inOrder = inOrder(first, second, third);
        inOrder.verify(first).link(PROJECT_PATHS);
        inOrder.verify(second).link(PROJECT_PATHS);
        inOrder.verify(third).link(PROJECT_PATHS);
    }

    @Test
    @DisplayName("link continues when a strategy throws an exception")
    void link_continuesOnStrategyFailure() {
        LinkStrategy failing = mock(LinkStrategy.class, "failing");
        LinkStrategy second = mock(LinkStrategy.class, "second");
        LinkStrategy third = mock(LinkStrategy.class, "third");

        doThrow(new RuntimeException("boom")).when(failing).link(anyList());

        var linker = new CrossServiceLinker(List.of(failing, second, third));
        linker.link(PROJECT_PATHS);

        verify(failing).link(PROJECT_PATHS);
        verify(second).link(PROJECT_PATHS);
        verify(third).link(PROJECT_PATHS);
    }

    @Test
    @DisplayName("link handles empty strategies list without error")
    void link_handlesEmptyStrategiesList() {
        var linker = new CrossServiceLinker(Collections.emptyList());

        assertThatNoException().isThrownBy(() -> linker.link(PROJECT_PATHS));
    }

    @Test
    @DisplayName("link passes the exact projectPaths to each strategy")
    void link_passesCorrectProjectPaths() {
        List<String> specificPaths = List.of("/data/projects/alpha", "/data/projects/beta");
        LinkStrategy strategy = mock(LinkStrategy.class);

        var linker = new CrossServiceLinker(List.of(strategy));
        linker.link(specificPaths);

        verify(strategy).link(specificPaths);
        verifyNoMoreInteractions(strategy);
    }
}
