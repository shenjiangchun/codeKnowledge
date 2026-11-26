package com.huawei.hisi.workbench.service.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpMappingScannerTest {

    private static final String CONTROLLER = """
            package com.example.controller;

            import org.springframework.web.bind.annotation.*;

            /**
             * User controller.
             * The service class for user endpoints.
             */
            @RestController
            @RequestMapping("/users")
            public class UserController {

                @GetMapping("/{id}")
                public User getUser(@PathVariable Long id) { return null; }

                @PostMapping
                public User create() { return null; }

                @GetMapping(value = "/search", produces = "application/json")
                public User search() { return null; }

                @RequestMapping(value = "/stats", method = RequestMethod.GET)
                public Object stats() { return null; }

                @DeleteMapping("/{id}")
                public void delete() { }
            }
            """;

    @Test
    void joinsClassPrefixWithMethodMappings() {
        List<String> mappings = HttpMappingScanner.scanFile(CONTROLLER);

        assertEquals(List.of("GET /users/{id}", "POST /users", "GET /users/search", "GET /users/stats", "DELETE /users/{id}"),
                mappings);
    }

    @Test
    void aJavadocMentioningClassDoesNotStealTheClassLevelPrefix() {
        // 注释里的「xxx class Y」不能被当成类型声明，否则类级前缀会被当成接口
        assertTrue(HttpMappingScanner.scanFile(CONTROLLER).contains("GET /users/{id}"));
        assertFalse(HttpMappingScanner.scanFile(CONTROLLER).contains("ANY /users"));
    }

    @Test
    void pathVariablesMatchByShapeNotByName() {
        List<String> mappings = HttpMappingScanner.scanFile(CONTROLLER);

        assertTrue(HttpMappingScanner.matches(mappings, "GET", "/users/{userId}"));
        assertFalse(HttpMappingScanner.matches(mappings, "GET", "/users/1"), "具体值不是模板形状：用例要写 {id}");
        assertFalse(HttpMappingScanner.matches(mappings, "POST", "/users/{id}"), "方法不同不算命中");
        assertFalse(HttpMappingScanner.matches(mappings, "GET", "/users/{id}/orders"), "段数不同不算命中");
        assertFalse(HttpMappingScanner.matches(mappings, "GET", "/orders/{id}"));
    }

    @Test
    void scansProjectSourcesIncludingOneLevelModules(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("src/main/java/com/a"));
        Files.createDirectories(project.resolve("api/src/main/java/com/b"));
        Files.createDirectories(project.resolve("web/src/main/java/com/b"));
        Files.writeString(project.resolve("src/main/java/com/a/RootController.java"), CONTROLLER);
        Files.writeString(project.resolve("api/src/main/java/com/b/HealthController.java"),
                "@RestController\npublic class HealthController {\n    @GetMapping(\"/health\")\n    public String health() { return \"ok\"; }\n}\n");
        Files.writeString(project.resolve("web/pom.xml"), "<project/>");

        List<String> mappings = HttpMappingScanner.scan(project);

        assertTrue(mappings.contains("GET /users/{id}"));
        assertTrue(mappings.contains("GET /health"));
        assertEquals(6, mappings.size());
    }

    @Test
    void nonJavaProjectHasNoMappings(@TempDir Path project) {
        assertTrue(HttpMappingScanner.scan(project).isEmpty());
    }
}
