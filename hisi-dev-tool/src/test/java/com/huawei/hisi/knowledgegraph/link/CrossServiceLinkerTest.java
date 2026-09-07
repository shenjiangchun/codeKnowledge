package com.huawei.hisi.knowledgegraph.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CrossServiceLinkerTest {

    private static final List<String> PROJECT_PATHS = List.of("/workspace/service-a", "/workspace/service-b");

    /** 记录调用顺序的探针策略。 */
    private static final class RecordingStrategy implements LinkStrategy {
        final String name;
        final List<Map<String, Object>> result;
        final List<String> calls = new ArrayList<>();

        RecordingStrategy(String name, List<Map<String, Object>> result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public List<Map<String, Object>> link(List<String> projectPaths) {
            calls.addAll(projectPaths);
            return result;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    @DisplayName("link executes all strategies and aggregates non-empty matched relations")
    void link_executesAllStrategiesInOrder() {
        RecordingStrategy first = new RecordingStrategy("first", List.of(Map.of("callerId", "c1")));
        RecordingStrategy second = new RecordingStrategy("second", List.of());
        RecordingStrategy third = new RecordingStrategy("third", List.of(Map.of("callerId", "c2"), Map.of("callerId", "c3")));

        var linker = new CrossServiceLinker(List.of(first, second, third));
        Map<String, List<Map<String, Object>>> result = linker.link(PROJECT_PATHS);

        // 所有策略都被调用，且收到相同的 projectPaths
        for (RecordingStrategy s : List.of(first, second, third)) {
            assertThat(s.calls).containsExactlyElementsOf(PROJECT_PATHS);
        }

        // 非空结果被聚合（空结果省略）：c1 + c2 + c3 = 3 条
        int total = result.values().stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(3);
    }

    @Test
    @DisplayName("link surfaces strategy failure instead of swallowing it")
    void link_surfacesStrategyFailure() {
        LinkStrategy failing = projectPaths -> { throw new RuntimeException("boom"); };
        RecordingStrategy second = new RecordingStrategy("second", List.of());

        var linker = new CrossServiceLinker(List.of(failing, second));

        assertThatThrownBy(() -> linker.link(PROJECT_PATHS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom")
                .hasCauseInstanceOf(RuntimeException.class);

        // 失败后不再执行后续策略
        assertThat(second.calls).isEmpty();
    }

    @Test
    @DisplayName("link handles empty strategies list without error")
    void link_handlesEmptyStrategiesList() {
        var linker = new CrossServiceLinker(Collections.emptyList());

        assertThat(linker.link(PROJECT_PATHS)).isEmpty();
    }

    @Test
    @DisplayName("link passes the exact projectPaths to each strategy")
    void link_passesCorrectProjectPaths() {
        List<String> specificPaths = List.of("/data/projects/alpha", "/data/projects/beta");
        RecordingStrategy strategy = new RecordingStrategy("solo", List.of());

        var linker = new CrossServiceLinker(List.of(strategy));
        linker.link(specificPaths);

        assertThat(strategy.calls).containsExactlyElementsOf(specificPaths);
    }
}
