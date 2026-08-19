package com.huawei.hisi.knowledgegraph.aggregation.stage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 构建模块级依赖落库 Stage。
 *
 * <p>解析项目 pom.xml，生成/覆盖该项目的 {@code ModuleNode(level='build-module')} 节点，
 * 将一跳依赖坐标写入 {@code dependencyCoordinates} 属性。只记一跳，不建依赖边。
 *
 * <p>幂等：每次 FULL 构建先删除该项目旧的 build-module 节点，再按解析结果重建，
 * 保证 pom 改动后旧依赖不残留。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildModuleDependencyAggregator {

    private final PomDependencyParser pomDependencyParser;
    private final Driver neo4jDriver;

    public void aggregate(String projectPath) {
        List<PomDependencyParser.BuildModuleInfo> modules = pomDependencyParser.parse(projectPath);
        if (modules.isEmpty()) {
            log.info("[BuildModule] 未解析到 pom 模块，跳过: projectPath={}", projectPath);
            return;
        }

        try (Session session = neo4jDriver.session()) {
            // 幂等覆盖：先删该项目旧的 build-module 节点
            session.run(
                "MATCH (m:ModuleNode {level: 'build-module', projectPath: $projectPath}) DETACH DELETE m",
                Map.of("projectPath", projectPath));

            for (PomDependencyParser.BuildModuleInfo info : modules) {
                String moduleName = ga(info.groupId(), info.artifactId());
                String moduleId = gav(info.groupId(), info.artifactId(), info.version());
                session.run(
                    "MERGE (m:ModuleNode {moduleId: $moduleId})\n" +
                    "SET m.moduleName = $moduleName,\n" +
                    "    m.level = 'build-module',\n" +
                    "    m.groupId = $groupId,\n" +
                    "    m.artifactId = $artifactId,\n" +
                    "    m.version = $version,\n" +
                    "    m.projectPath = $projectPath,\n" +
                    "    m.language = 'java',\n" +
                    "    m.dependencyCoordinates = $dependencyCoordinates",
                    Map.of(
                        "moduleId", moduleId,
                        "moduleName", moduleName,
                        "groupId", info.groupId() == null ? "" : info.groupId(),
                        "artifactId", info.artifactId(),
                        "version", info.version() == null ? "" : info.version(),
                        "projectPath", projectPath,
                        "dependencyCoordinates", info.dependencyCoordinates()));
            }
            log.info("[BuildModule] 落库完成: projectPath={}, modules={}", projectPath, modules.size());
        }
    }

    /** 匹配键：groupId:artifactId（不带 version）。 */
    public static String ga(String groupId, String artifactId) {
        return (groupId == null ? "" : groupId) + ":" + artifactId;
    }

    /** 唯一键：groupId:artifactId:version。 */
    public static String gav(String groupId, String artifactId, String version) {
        return ga(groupId, artifactId) + ":" + (version == null ? "" : version);
    }

    /** 从坐标 groupId:artifactId:version 剥离 version，得匹配键 groupId:artifactId。 */
    public static String coordinateToGa(String coordinate) {
        if (coordinate == null) return "";
        int first = coordinate.indexOf(':');
        if (first < 0) return coordinate;
        int second = coordinate.indexOf(':', first + 1);
        return second > 0 ? coordinate.substring(0, second) : coordinate;
    }
}
