package com.huawei.hisi.knowledgegraph.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils.normalizePath;
import static com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils.relativeFilePath;
import static com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils.shouldExclude;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KnowledgeGraphCommonUtils}.
 *
 * <p>TDD: tests written first; class duplicates path/exclude helpers
 * from {@code KnowledgeGraphBuilder} as an isolated, additive utility.
 */
class KnowledgeGraphCommonUtilsTest {

    // ================== normalizePath ==================

    @Test
    void normalizePath_convertsBackslashesToForwardSlash() {
        assertThat(normalizePath("C:\\Users\\foo\\bar")).isEqualTo("C:/Users/foo/bar");
    }

    @Test
    void normalizePath_stripsTrailingSlash() {
        assertThat(normalizePath("/work/svc/")).isEqualTo("/work/svc");
    }

    @Test
    void normalizePath_resolvesDotDot() {
        assertThat(normalizePath("/work/svc/../other")).isEqualTo("/work/other");
    }

    @Test
    void normalizePath_keepsRootSlash() {
        assertThat(normalizePath("/")).isEqualTo("/");
    }

    @Test
    void normalizePath_handlesNull() {
        assertThat(normalizePath(null)).isNull();
    }

    @Test
    void normalizePath_handlesEmpty() {
        assertThat(normalizePath("")).isEmpty();
    }

    // ================== shouldExclude ==================

    @Test
    void shouldExclude_matchesSubstring() {
        assertThat(shouldExclude("/work/svc/build/foo.java", List.of("build"))).isTrue();
    }

    @Test
    void shouldExclude_matchesGlobStar() {
        assertThat(shouldExclude("/work/svc/target/Foo.class", List.of("**/target/**"))).isTrue();
    }

    @Test
    void shouldExclude_doesNotMatchUnrelated() {
        assertThat(shouldExclude("/work/svc/src/Foo.java", List.of("build", "**/target/**"))).isFalse();
    }

    @Test
    void shouldExclude_handlesNullList() {
        assertThat(shouldExclude("/x", null)).isFalse();
    }

    @Test
    void shouldExclude_handlesEmptyList() {
        assertThat(shouldExclude("/x", List.of())).isFalse();
    }

    @Test
    void shouldExclude_handlesNullPath() {
        assertThat(shouldExclude(null, List.of("build"))).isFalse();
    }

    @Test
    void shouldExclude_normalizesPathBeforeMatching() {
        assertThat(shouldExclude("C:\\work\\svc\\build\\foo.java", List.of("build"))).isTrue();
    }

    // ================== relativeFilePath ==================

    @Test
    void relativeFilePath_basicRelative() {
        assertThat(relativeFilePath("/work/svc", "/work/svc/src/Foo.java")).isEqualTo("src/Foo.java");
    }

    @Test
    void relativeFilePath_handlesBackslashes() {
        assertThat(relativeFilePath("C:\\work\\svc", "C:\\work\\svc\\src\\Foo.java")).isEqualTo("src/Foo.java");
    }

    @Test
    void relativeFilePath_unrelatedPath() {
        assertThat(relativeFilePath("/work/svc", "/elsewhere/Foo.java")).isEqualTo("/elsewhere/Foo.java");
    }

    @Test
    void relativeFilePath_identicalPath() {
        assertThat(relativeFilePath("/work/svc", "/work/svc")).isEmpty();
    }

    @Test
    void relativeFilePath_handlesNullProject() {
        assertThat(relativeFilePath(null, "/x/y")).isEqualTo("/x/y");
    }

    @Test
    void relativeFilePath_handlesNullAbsolute() {
        assertThat(relativeFilePath("/work/svc", null)).isNull();
    }
}
