package com.huawei.hisi.knowledgegraph.aggregation.stage;

import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Maven pom.xml 解析器：扫描项目目录下的 pom.xml，提取构建模块坐标与一跳依赖坐标。
 *
 * <p>只解析「直接依赖声明」（不递归展开传递依赖），处理 parent 继承与单 pom 内 properties 占位符。
 * 坐标格式：{@code groupId:artifactId:version}，version 缺失时为空字符串。
 */
@Slf4j
@Component
public class PomDependencyParser {

    /** 一个构建模块的解析结果 */
    public record BuildModuleInfo(
        String groupId,
        String artifactId,
        String version,
        List<String> dependencyCoordinates  // 元素为 groupId:artifactId:version
    ) {}

    /** 扫描并解析项目目录下的所有 pom.xml（排除 target/、node_modules/ 等构建产物目录）。 */
    public List<BuildModuleInfo> parse(String projectPath) {
        List<BuildModuleInfo> result = new ArrayList<>();
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            log.warn("[PomParser] 项目目录不存在: {}", projectPath);
            return result;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> pomFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> "pom.xml".equals(p.getFileName().toString()))
                .filter(this::notExcluded)
                .toList();
            for (Path pomFile : pomFiles) {
                try {
                    BuildModuleInfo info = parsePom(pomFile);
                    if (info != null) result.add(info);
                } catch (Exception e) {
                    log.warn("[PomParser] 解析 pom 失败: {} - {}", pomFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[PomParser] 扫描 pom 失败: {} - {}", projectPath, e.getMessage());
        }
        return result;
    }

    private boolean notExcluded(Path p) {
        String s = p.toString().replace('\\', '/');
        return !s.contains("/target/")
            && !s.contains("/node_modules/")
            && !s.contains("/.git/")
            && !s.contains("/.claude/")
            && !s.contains("/.worktrees/")
            && !s.contains("/src/test/");
    }

    private BuildModuleInfo parsePom(Path pomFile) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(Files.newBufferedReader(pomFile));

        String groupId = model.getGroupId();
        String version = model.getVersion();
        // parent 继承（只读单 pom 内的 parent 坐标，不递归解析远端 parent）
        if (model.getParent() != null) {
            if (groupId == null) groupId = model.getParent().getGroupId();
            if (version == null) version = model.getParent().getVersion();
        }
        Properties props = model.getProperties();
        groupId = interpolate(groupId, props);
        version = interpolate(version, props);
        String artifactId = model.getArtifactId();

        if (artifactId == null) {
            log.warn("[PomParser] pom 缺少 artifactId，跳过: {}", pomFile);
            return null;
        }

        List<String> deps = new ArrayList<>();
        if (model.getDependencies() != null) {
            for (Dependency d : model.getDependencies()) {
                String dg = interpolate(d.getGroupId(), props);
                String da = d.getArtifactId();
                String dv = interpolate(d.getVersion(), props);
                deps.add(toCoordinate(dg, da, dv));
            }
        }
        return new BuildModuleInfo(groupId, artifactId, version, deps);
    }

    /** 单 pom 内 properties 占位符插值；无法插值时保留原始字符串。 */
    private String interpolate(String value, Properties props) {
        if (value == null || !value.contains("${")) return value;
        String result = value;
        for (String key : props.stringPropertyNames()) {
            result = result.replace("${" + key + "}", props.getProperty(key));
        }
        return result;
    }

    /** 拼接坐标 {@code groupId:artifactId:version}，version 缺失时为空字符串。 */
    private String toCoordinate(String groupId, String artifactId, String version) {
        return (groupId == null ? "" : groupId) + ":" + artifactId + ":" + (version == null ? "" : version);
    }
}
