package com.huawei.hisi.ram.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SchemaValidator 目录解析的向后兼容性：裸名字继续走 schemas/ram/，
 * 带路径分隔符的名字走 schemas/<dir>/。
 */
class SchemaValidatorPathResolutionTest {

    private final SchemaValidator validator = new SchemaValidator();

    @Test
    @DisplayName("裸名字（RAM）：继续从 schemas/ram/ 加载，行为不变")
    void bareNameStillResolvesUnderRam() {
        // 沿用既有用例路径：clarify.output 在 schemas/ram/ 下，校验逻辑不变
        var result = validator.validate("clarify.output", Map.of("intent", "x"));
        assertFalse(result.passed());
        assertTrue(result.missingFields().stream().anyMatch(f -> f.contains("project_paths")));
    }

    @Test
    @DisplayName("带路径分隔符的名字：从 schemas/<dir>/ 加载")
    void namespacedNameResolvesUnderDirectory() {
        Map<String, Object> valid = Map.of(
                "thought", "证据充分",
                "final_answer", Map.of(
                        "issues", List.of(
                                Map.of("ref", "i-1", "title", "循环依赖", "severity", "HIGH",
                                        "rootCause", "controller 直接依赖 repository",
                                        "evidenceRefs", List.of("module.cycles"),
                                        "suggestion", "引入接口层解耦",
                                        "verdict", "confirmed", "reason", "跨层循环属实")),
                        "report", Map.of(
                                "executive_summary", "s",
                                "views", Map.of(
                                        "logical", Map.of("insight", "a"),
                                        "process", Map.of("insight", "b"),
                                        "development", Map.of("insight", "c"),
                                        "physical", Map.of("insight", "d"),
                                        "scenario", Map.of("insight", "e")),
                                "roadmap", Map.of("p0", List.of(), "p1", List.of(), "p2", List.of()),
                                "evidence_appendix", "g")));

        assertDoesNotThrow(() -> {
            var result = validator.validate("arch/react-step", valid);
            assertTrue(result.passed(),
                    "expected valid payload to pass. missing=" + result.missingFields()
                            + " violations=" + result.violations());
        });
    }

    @Test
    @DisplayName("未知目录下的 schema：仍然抛 IllegalArgumentException")
    void unknownNamespaceStillThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("arch/does-not-exist", Map.of()));
    }
}
