package com.huawei.hisi.apm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ApmErrorCode}.
 */
class ApmErrorCodeTest {

    @Test
    @DisplayName("All enum values have unique numeric codes")
    void allCodes_areUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ApmErrorCode ec : ApmErrorCode.values()) {
            assertThat(seen.add(ec.getCode()))
                .as("Duplicate code %d on %s", ec.getCode(), ec.name())
                .isTrue();
        }
    }

    @Test
    @DisplayName("fromCode returns matching enum")
    void fromCode_known_returnsValue() {
        assertThat(ApmErrorCode.fromCode(2001))
            .contains(ApmErrorCode.INGEST_PARSE_FAILED);
    }

    @Test
    @DisplayName("fromCode for unknown returns empty Optional")
    void fromCode_unknown_returnsEmpty() {
        assertThat(ApmErrorCode.fromCode(9999)).isEmpty();
    }

    @Test
    @DisplayName("All codes fall in their section's numeric range")
    void codes_areInSectionRange() {
        for (ApmErrorCode ec : ApmErrorCode.values()) {
            String prefix = ec.name().split("_")[0];
            int code = ec.getCode();
            switch (prefix) {
                case "OTEL" -> assertThat(code).isBetween(1000, 1999);
                case "INGEST" -> assertThat(code).isBetween(2000, 2999);
                case "DIAGNOSE" -> assertThat(code).isBetween(3000, 3999);
                case "LLM" -> assertThat(code).isBetween(4000, 4999);
                case "KG" -> assertThat(code).isBetween(5000, 5999);
                case "TEST" -> assertThat(code).isBetween(9000, 9999);
                default -> {
                    // No assertion required for unknown prefixes — guard test below.
                }
            }
        }
    }

    @Test
    @DisplayName("Every enum uses a recognized section prefix")
    void allCodes_useKnownPrefix() {
        Set<String> known = Set.of("OTEL", "INGEST", "DIAGNOSE", "LLM", "KG", "TEST");
        for (ApmErrorCode ec : ApmErrorCode.values()) {
            String prefix = ec.name().split("_")[0];
            assertThat(known).as("Unknown prefix on %s", ec.name()).contains(prefix);
        }
    }

    @Test
    @DisplayName("Default messages are non-blank")
    void defaultMessages_areNonBlank() {
        Arrays.stream(ApmErrorCode.values())
            .forEach(ec -> assertThat(ec.getDefaultMessage()).isNotBlank());
    }

    @Test
    @DisplayName("fromCode handles a sampling of expected mappings")
    void fromCode_samples() {
        Optional<ApmErrorCode> r = ApmErrorCode.fromCode(3001);
        assertThat(r).contains(ApmErrorCode.DIAGNOSE_TRACE_NOT_FOUND);
    }
}
