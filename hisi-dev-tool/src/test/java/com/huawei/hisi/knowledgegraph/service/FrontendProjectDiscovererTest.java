package com.huawei.hisi.knowledgegraph.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FrontendProjectDiscoverer")
class FrontendProjectDiscovererTest {

    private final FrontendProjectDiscoverer discoverer = new FrontendProjectDiscoverer();

    @Test
    @DisplayName("同级探测发现 <后端名>-frontend 目录（含 package.json）")
    void discover_siblingFrontendDir_found(@TempDir Path tempDir) throws Exception {
        // 构造 backend 目录 + 同级 backend-frontend 目录（含 package.json）
        Path backend = Files.createDirectory(tempDir.resolve("hisi-dev-tool"));
        Path frontend = Files.createDirectory(tempDir.resolve("hisi-dev-tool-frontend"));
        Files.createFile(frontend.resolve("package.json"));

        List<String> found = discoverer.discover(backend.toString(), null);
        assertThat(found).hasSize(1);
        assertThat(found.get(0)).endsWith("hisi-dev-tool-frontend");
    }

    @Test
    @DisplayName("同级无前端目录时返回空")
    void discover_noFrontendDir_empty(@TempDir Path tempDir) throws Exception {
        Path backend = Files.createDirectory(tempDir.resolve("hisi-dev-tool"));
        List<String> found = discoverer.discover(backend.toString(), null);
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("前端目录存在但无 package.json 时返回空")
    void discover_frontendDirWithoutPackageJson_empty(@TempDir Path tempDir) throws Exception {
        Path backend = Files.createDirectory(tempDir.resolve("hisi-dev-tool"));
        Files.createDirectory(tempDir.resolve("hisi-dev-tool-frontend")); // 无 package.json
        List<String> found = discoverer.discover(backend.toString(), null);
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("显式指定前端路径（含 package.json）时返回该路径")
    void discover_explicitFrontendPath_found(@TempDir Path tempDir) throws Exception {
        Path backend = Files.createDirectory(tempDir.resolve("hisi-dev-tool"));
        Path frontend = Files.createDirectory(tempDir.resolve("custom-frontend"));
        Files.createFile(frontend.resolve("package.json"));

        List<String> found = discoverer.discover(backend.toString(), frontend.toString());
        assertThat(found).hasSize(1);
        assertThat(found.get(0)).endsWith("custom-frontend");
    }

    @Test
    @DisplayName("后端路径为空或不存在时返回空")
    void discover_blankOrMissingBackend_empty(@TempDir Path tempDir) {
        assertThat(discoverer.discover("", null)).isEmpty();
        assertThat(discoverer.discover(null, null)).isEmpty();
        assertThat(discoverer.discover(tempDir.resolve("nonexistent").toString(), null)).isEmpty();
    }
}
