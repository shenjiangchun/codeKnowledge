package com.huawei.hisi.knowledgegraph.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectPathResolverTest {

    @Test
    void resolve_prefersProjectPaths_whenBothProvided() {
        List<String> result = ProjectPathResolver.resolve("single", List.of("a", "b"));
        assertThat(result).containsExactly("a", "b");
    }

    @Test
    void resolve_fallsBackToSingle_whenListEmpty() {
        List<String> result = ProjectPathResolver.resolve("single", List.of());
        assertThat(result).containsExactly("single");
    }

    @Test
    void resolve_fallsBackToSingle_whenListNull() {
        List<String> result = ProjectPathResolver.resolve("single", null);
        assertThat(result).containsExactly("single");
    }

    @Test
    void resolve_returnsEmpty_whenBothNull() {
        List<String> result = ProjectPathResolver.resolve(null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void resolve_normalizesBackslashes() {
        List<String> result = ProjectPathResolver.resolve("C:\\foo\\bar", null);
        assertThat(result).containsExactly("C:/foo/bar");
    }

    @Test
    void resolve_deduplicates() {
        List<String> result = ProjectPathResolver.resolve("a", List.of("a", "b", "a"));
        assertThat(result).containsExactly("a", "b");
    }
}
