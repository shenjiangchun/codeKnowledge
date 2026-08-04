package com.huawei.hisi.apm.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure JSON smoke test for the three P0 OTLP fixtures. No Spring context,
 * no HTTP — only verifies that the fixtures are valid, structurally
 * meaningful, and uniquely identifiable.
 */
class FixtureReplayerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final java.util.Map<String, String> EXPECTED_TRACE_IDS =
            java.util.Map.of(
                    "npe",       "00000000000000000000000000000001",
                    "sql-fail",  "00000000000000000000000000000002",
                    "http-5xx",  "00000000000000000000000000000003");

    @Test
    @DisplayName("listFixtures returns the three P0 names")
    void listFixtures_returnsThreeP0Names() {
        assertThat(FixtureReplayer.listFixtures())
                .containsExactly("npe", "sql-fail", "http-5xx");
    }

    @Test
    @DisplayName("unknown fixture name is rejected")
    void loadFixture_unknownName_throws() {
        assertThatThrownBy(() -> FixtureReplayer.loadFixture("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown fixture");
    }

    @ParameterizedTest(name = "fixture {0} loads and parses as JSON")
    @ValueSource(strings = {"npe", "sql-fail", "http-5xx"})
    void fixture_loadsAndParses(String name) throws Exception {
        String json = FixtureReplayer.loadFixture(name);
        assertThat(json).as("payload not empty").isNotBlank();
        JsonNode root = MAPPER.readTree(json);
        assertThat(root.path("resourceSpans").isArray())
                .as("resourceSpans is an array").isTrue();
        assertThat(root.path("resourceSpans").size())
                .as("at least one resourceSpan").isGreaterThan(0);
    }

    @ParameterizedTest(name = "fixture {0} carries an ERROR status or exception event")
    @ValueSource(strings = {"npe", "sql-fail", "http-5xx"})
    void fixture_hasErrorOrExceptionEvent(String name) throws Exception {
        JsonNode root = MAPPER.readTree(FixtureReplayer.loadFixture(name));
        List<JsonNode> spans = collectSpans(root);
        assertThat(spans).as("at least one span").isNotEmpty();
        boolean hasError = spans.stream().anyMatch(s -> s.path("status").path("code").asInt() == 2);
        boolean hasExceptionEvent = spans.stream().anyMatch(FixtureReplayerTest::hasExceptionEvent);
        assertThat(hasError || hasExceptionEvent)
                .as("fixture %s must declare ERROR status or exception event", name)
                .isTrue();
    }

    @ParameterizedTest(name = "fixture {0} uses the expected traceId")
    @ValueSource(strings = {"npe", "sql-fail", "http-5xx"})
    void fixture_usesExpectedTraceId(String name) throws Exception {
        JsonNode root = MAPPER.readTree(FixtureReplayer.loadFixture(name));
        String expected = EXPECTED_TRACE_IDS.get(name);
        List<JsonNode> spans = collectSpans(root);
        assertThat(spans).allSatisfy(span ->
                assertThat(span.path("traceId").asText())
                        .as("every span shares the expected traceId for %s", name)
                        .isEqualTo(expected));
    }

    @Test
    @DisplayName("no two fixtures share a traceId")
    void allFixtures_haveDistinctTraceIds() throws Exception {
        Set<String> seen = new HashSet<>();
        for (String name : FixtureReplayer.listFixtures()) {
            JsonNode root = MAPPER.readTree(FixtureReplayer.loadFixture(name));
            for (JsonNode span : collectSpans(root)) {
                seen.add(span.path("traceId").asText());
            }
        }
        assertThat(seen).hasSize(FixtureReplayer.listFixtures().size());
    }

    private static List<JsonNode> collectSpans(JsonNode root) {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode rs : root.path("resourceSpans")) {
            for (JsonNode ss : rs.path("scopeSpans")) {
                for (JsonNode span : ss.path("spans")) {
                    out.add(span);
                }
            }
        }
        return out;
    }

    private static boolean hasExceptionEvent(JsonNode span) {
        for (JsonNode event : span.path("events")) {
            if ("exception".equals(event.path("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
