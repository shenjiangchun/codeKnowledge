package com.huawei.hisi.knowledgegraph.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProjectLanguageDetector")
class ProjectLanguageDetectorTest {

    @Test
    @DisplayName("detectLanguage returns JAVA for non-existent path")
    void detectLanguage_nonExistentPath_returnsJava() {
        assertThat(ProjectLanguageDetector.detectLanguage("/no/such/path"))
            .isEqualTo(ProjectLanguageDetector.Language.JAVA);
    }

    @Test
    @DisplayName("detectLanguage returns JAVA when pom.xml exists")
    void detectLanguage_pomXml_returnsJava(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("pom.xml"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.JAVA);
    }

    @Test
    @DisplayName("detectLanguage returns JAVA when build.gradle exists")
    void detectLanguage_buildGradle_returnsJava(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("build.gradle"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.JAVA);
    }

    @Test
    @DisplayName("detectLanguage returns PYTHON when requirements.txt exists")
    void detectLanguage_requirementsTxt_returnsPython(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("requirements.txt"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.PYTHON);
    }

    @Test
    @DisplayName("detectLanguage returns PYTHON when pyproject.toml exists")
    void detectLanguage_pyprojectToml_returnsPython(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("pyproject.toml"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.PYTHON);
    }

    @Test
    @DisplayName("detectLanguage returns TYPESCRIPT when tsconfig.json exists")
    void detectLanguage_tsconfigJson_returnsTypeScript(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("tsconfig.json"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.TYPESCRIPT);
    }

    @Test
    @DisplayName("detectLanguage returns TYPESCRIPT when vue.config.js exists")
    void detectLanguage_vueConfigJs_returnsTypeScript(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("vue.config.js"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.TYPESCRIPT);
    }

    @Test
    @DisplayName("detectLanguage returns JAVASCRIPT when package.json exists without tsconfig")
    void detectLanguage_packageJsonOnly_returnsJavaScript(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("package.json"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.JAVASCRIPT);
    }

    @Test
    @DisplayName("Java marker takes priority over Python/TS markers")
    void detectLanguage_javaMarkerPriority(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("pom.xml"));
        Files.createFile(tmpDir.resolve("requirements.txt"));
        Files.createFile(tmpDir.resolve("tsconfig.json"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.JAVA);
    }

    @Test
    @DisplayName("Python marker takes priority over TS/JS markers")
    void detectLanguage_pythonOverTS(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("requirements.txt"));
        Files.createFile(tmpDir.resolve("tsconfig.json"));
        assertThat(ProjectLanguageDetector.detectLanguage(tmpDir.toString()))
            .isEqualTo(ProjectLanguageDetector.Language.PYTHON);
    }
}
