package com.huawei.hisi.ram.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();

    @Test
    void validate_clarifyOutput_missingProjectPaths_returnsMissingFields() {
        Map<String, Object> payload = Map.of("intent", "add feature");

        ValidationResult result = validator.validate("clarify.output", payload);

        assertFalse(result.passed(), "Expected validation to fail when project_paths missing");
        assertTrue(
                result.missingFields().stream().anyMatch(f -> f.contains("project_paths")),
                "missingFields should mention 'project_paths', got: " + result.missingFields()
        );
    }

    @Test
    void validate_clarifyOutput_valid_passes() {
        Map<String, Object> payload = Map.of(
                "intent", "add RAM contracts",
                "project_paths", List.of("hisi-dev-tool"),
                "acceptance_criteria", List.of("schemas validate", "tests pass")
        );

        ValidationResult result = validator.validate("clarify.output", payload);

        assertTrue(
                result.passed(),
                "Expected valid payload to pass. missing=" + result.missingFields()
                        + " violations=" + result.violations()
        );
        assertTrue(result.missingFields().isEmpty());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void validate_impactOutput_invalidRiskLevel_violation() {
        Map<String, Object> payload = Map.of(
                "involved", Map.of("seeds", List.of(), "entries", List.of(), "impls", List.of()),
                "modified", Map.of("tree", List.of()),
                "impacted", Map.of(
                        "upstream", List.of(),
                        "downstream", List.of(),
                        "crossService", List.of(),
                        "bridges", List.of()
                ),
                "risk", Map.of("score", 0.5, "level", "XYZ"),
                "validation", Map.of("passed", true, "violations", List.of())
        );

        ValidationResult result = validator.validate("impact.output", payload);

        assertFalse(result.passed(), "Expected invalid risk.level to fail validation");
        assertFalse(result.violations().isEmpty(), "Expected at least one violation for bad enum");
    }

    @Test
    void validate_unknownSchema_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("does.not.exist", Map.of()));
    }

    @Test
    void schemaLoadsEvenWhenTheThreadsContextClassLoaderCannotSeeTheClasspath() throws Exception {
        // DAG 节点跑在 ForkJoinPool.commonPool 的工作线程上，这些线程的上下文类加载器不一定是应用自己的。
        // 在 Spring Boot 可执行 jar 里，它若看不到 BOOT-INF/classes，不带类加载器的 ClassPathResource 就找不到 schema
        SchemaValidator fresh = new SchemaValidator();
        ValidationResult[] result = new ValidationResult[1];
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                result[0] = fresh.validate("arch/react-step", Map.of());
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        worker.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        worker.start();
        worker.join();

        assertNull(failure[0], "schema 的加载不应依赖线程的上下文类加载器：" + failure[0]);
        assertNotNull(result[0]);
    }
}
