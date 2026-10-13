package com.huawei.hisi.knowledgegraph.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FrontendProjectDiscoverer")
class FrontendProjectDiscovererTest {

    private final FrontendProjectDiscoverer discoverer = new FrontendProjectDiscoverer();

    @Test
    @DisplayName("显式前端目录含 package.json 时校验通过")
    void validate_validFrontend_passes(@TempDir Path tempDir) throws Exception {
        Path frontend = Files.createDirectory(tempDir.resolve("custom-frontend"));
        Files.createFile(frontend.resolve("package.json"));

        assertThatCode(() -> discoverer.validateFrontendProject(frontend.toString()))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("前端目录不含 package.json 时抛异常")
    void validate_noPackageJson_throws(@TempDir Path tempDir) throws Exception {
        Path frontend = Files.createDirectory(tempDir.resolve("custom-frontend"));

        assertThatThrownBy(() -> discoverer.validateFrontendProject(frontend.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("package.json");
    }

    @Test
    @DisplayName("前端目录不存在时抛异常")
    void validate_missingDir_throws(@TempDir Path tempDir) {
        assertThatThrownBy(() -> discoverer.validateFrontendProject(tempDir.resolve("nonexistent").toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("前端路径为空时抛异常")
    void validate_blankPath_throws() {
        assertThatThrownBy(() -> discoverer.validateFrontendProject(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discoverer.validateFrontendProject(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
