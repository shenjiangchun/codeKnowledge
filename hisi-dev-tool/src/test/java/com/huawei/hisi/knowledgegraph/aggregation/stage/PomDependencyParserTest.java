package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PomDependencyParser Maven pom 解析")
class PomDependencyParserTest {

    private final PomDependencyParser parser = new PomDependencyParser();

    @TempDir
    Path tempDir;

    private void writePom(String relative, String content) throws Exception {
        Path p = tempDir.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private String singleModulePom(String groupId, String artifactId, String version, String deps) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
            "  <modelVersion>4.0.0</modelVersion>\n" +
            "  <groupId>" + groupId + "</groupId>\n" +
            "  <artifactId>" + artifactId + "</artifactId>\n" +
            "  <version>" + version + "</version>\n" +
            "  <dependencies>\n" + deps + "\n  </dependencies>\n" +
            "</project>\n";
    }

    @Test
    @DisplayName("单模块项目解析出唯一构建模块")
    void singleModule_parsesOneModule() throws Exception {
        writePom("pom.xml", singleModulePom("com.huawei.hisi", "devTools", "1.0.0", ""));

        var result = parser.parse(tempDir.toString());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupId()).isEqualTo("com.huawei.hisi");
        assertThat(result.get(0).artifactId()).isEqualTo("devTools");
        assertThat(result.get(0).version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("多模块项目解析出多个构建模块")
    void multiModule_parsesMultipleModules() throws Exception {
        writePom("module-a/pom.xml", singleModulePom("com.a", "module-a", "1.0.0", ""));
        writePom("module-b/pom.xml", singleModulePom("com.b", "module-b", "2.0.0", ""));

        var result = parser.parse(tempDir.toString());

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PomDependencyParser.BuildModuleInfo::artifactId)
            .containsExactlyInAnyOrder("module-a", "module-b");
    }

    @Test
    @DisplayName("提取直接依赖坐标")
    void extractsDependencyCoordinates() throws Exception {
        String deps =
            "    <dependency><groupId>com.b</groupId><artifactId>common</artifactId><version>2.0.0</version></dependency>\n" +
            "    <dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId></dependency>\n";
        writePom("pom.xml", singleModulePom("com.a", "app", "1.0.0", deps));

        var result = parser.parse(tempDir.toString());

        assertThat(result.get(0).dependencyCoordinates()).containsExactlyInAnyOrder(
            "com.b:common:2.0.0",
            "org.springframework:spring-web:");
    }

    @Test
    @DisplayName("parent 继承 groupId/version")
    void parentInheritance_resolvesGroupIdAndVersion() throws Exception {
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
            "  <modelVersion>4.0.0</modelVersion>\n" +
            "  <parent><groupId>com.parent</groupId><artifactId>parent</artifactId><version>3.0.0</version></parent>\n" +
            "  <artifactId>child</artifactId>\n" +
            "</project>\n";
        writePom("pom.xml", pom);

        var result = parser.parse(tempDir.toString());

        assertThat(result.get(0).groupId()).isEqualTo("com.parent");
        assertThat(result.get(0).version()).isEqualTo("3.0.0");
        assertThat(result.get(0).artifactId()).isEqualTo("child");
    }

    @Test
    @DisplayName("properties 占位符插值")
    void propertiesPlaceholder_interpolates() throws Exception {
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
            "  <modelVersion>4.0.0</modelVersion>\n" +
            "  <groupId>com.a</groupId><artifactId>app</artifactId><version>${app.version}</version>\n" +
            "  <properties><app.version>1.2.3</app.version></properties>\n" +
            "</project>\n";
        writePom("pom.xml", pom);

        var result = parser.parse(tempDir.toString());

        assertThat(result.get(0).version()).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("无法插值的占位符保留原始字符串")
    void unresolvedPlaceholder_keepsRaw() throws Exception {
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
            "  <modelVersion>4.0.0</modelVersion>\n" +
            "  <groupId>com.a</groupId><artifactId>app</artifactId><version>${undefined.prop}</version>\n" +
            "</project>\n";
        writePom("pom.xml", pom);

        var result = parser.parse(tempDir.toString());

        assertThat(result.get(0).version()).isEqualTo("${undefined.prop}");
    }

    @Test
    @DisplayName("排除 target/node_modules 下的 pom")
    void excludesBuildDirs() throws Exception {
        writePom("pom.xml", singleModulePom("com.a", "app", "1.0.0", ""));
        writePom("target/pom.xml", singleModulePom("com.b", "ignored", "1.0.0", ""));

        var result = parser.parse(tempDir.toString());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).artifactId()).isEqualTo("app");
    }
}
