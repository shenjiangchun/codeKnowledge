package com.huawei.hisi.apm.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-helper tests for {@link KgMethodIncludeBuilder}. The {@code build()} /
 * {@code buildFullProject()} paths require a Neo4j repository and are exercised
 * via integration tests; these unit tests pin the static OTel-grammar helpers
 * that proved to be the root cause of the {@code Invalid trace method config}
 * WARN seen at agent startup.
 */
class KgMethodIncludeBuilderTest {

    @Test
    @DisplayName("toBytecodeClassName converts nested source FQN to $-joined bytecode FQN")
    void toBytecodeClassName_nested() {
        assertThat(KgMethodIncludeBuilder.toBytecodeClassName("com.foo.Outer.Inner"))
                .isEqualTo("com.foo.Outer$Inner");
        assertThat(KgMethodIncludeBuilder.toBytecodeClassName("com.foo.Outer.Inner.Deep"))
                .isEqualTo("com.foo.Outer$Inner$Deep");
    }

    @Test
    @DisplayName("toBytecodeClassName leaves plain top-level classes untouched")
    void toBytecodeClassName_plain() {
        assertThat(KgMethodIncludeBuilder.toBytecodeClassName("com.foo.MyService"))
                .isEqualTo("com.foo.MyService");
    }

    @Test
    @DisplayName("toBytecodeClassName strips generics and array suffix")
    void toBytecodeClassName_stripsGenericsAndArrays() {
        assertThat(KgMethodIncludeBuilder.toBytecodeClassName("com.foo.Bar<String>"))
                .isEqualTo("com.foo.Bar");
        assertThat(KgMethodIncludeBuilder.toBytecodeClassName("com.foo.Bar[]"))
                .isEqualTo("com.foo.Bar");
    }

    @Test
    @DisplayName("isValidIdentifierPair rejects synthetic methods")
    void isValidIdentifierPair_rejectsSynthetics() {
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair("com.foo.Bar", "<init>")).isFalse();
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair("com.foo.Bar", "<clinit>")).isFalse();
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair("com.foo.Bar", "lambda$run$0")).isFalse();
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair("com.foo.Bar", "access$000")).isFalse();
    }

    @Test
    @DisplayName("isValidIdentifierPair accepts plain idents and $-nested classes")
    void isValidIdentifierPair_accepts() {
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair("com.foo.Bar", "doStuff")).isTrue();
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair("com.foo.Outer$Inner", "doStuff")).isTrue();
    }

    @Test
    @DisplayName("isValidIdentifierPair rejects null and empty inputs")
    void isValidIdentifierPair_rejectsNulls() {
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair(null, "x")).isFalse();
        assertThat(KgMethodIncludeBuilder.isValidIdentifierPair("com.foo.Bar", null)).isFalse();
    }
}
