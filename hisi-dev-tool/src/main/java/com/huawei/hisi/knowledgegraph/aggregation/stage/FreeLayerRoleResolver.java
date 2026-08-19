package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.neo4j.model.ClassNode;
import com.huawei.hisi.neo4j.repository.Neo4jClassNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游离节点层级补全 Stage：LLM 补全游离类（ClassNode.classRole=UNKNOWN）与游离包（ModuleNode.layerRole=UNKNOWN）。
 *
 * <p>三级回退（注解/类名/包名 或 包名 CASE）仍无法识别的节点，批量分批调 LLM，
 * 输入「节点名 + 依赖结构」，LLM 判断其架构层级并落库（来源标记 LLM）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreeLayerRoleResolver {

    private final Neo4jClassNodeRepository neo4jClassNodeRepository;
    private final LayerRoleLlmService layerRoleLlmService;
    private final Driver neo4jDriver;

    /**
     * 补全游离节点层级。
     *
     * @return 补全的节点数量
     */
    public int resolve(String projectPath) {
        int resolved = 0;

        // 1. 类级游离节点（classRole = UNKNOWN 或 null）
        List<ClassNode> freeClasses = neo4jClassNodeRepository.findByProjectPath(projectPath).stream()
            .filter(c -> c.getClassRole() == null || "UNKNOWN".equals(c.getClassRole()))
            .toList();
        if (!freeClasses.isEmpty()) {
            List<Map<String, String>> items = new ArrayList<>();
            for (ClassNode c : freeClasses) {
                Map<String, String> item = new HashMap<>();
                item.put("name", c.getClassName());
                item.put("deps", buildClassDeps(projectPath, c.getClassName()));
                items.add(item);
            }
            var classResults = layerRoleLlmService.resolveRoles(items);
            for (var rr : classResults) {
                neo4jClassNodeRepository.updateClassRole(
                    ClassNode.generateClassId(projectPath, rr.name()), rr.role(), "LLM");
                resolved++;
            }
        }

        // 2. 包级游离节点（layerRole = UNKNOWN 或 null）
        List<String> freePackages = new ArrayList<>();
        try (Session session = neo4jDriver.session()) {
            var recs = session.run(
                "MATCH (m:ModuleNode {level: 'package', projectPath: $path})\n" +
                "WHERE m.layerRole IS NULL OR m.layerRole = 'UNKNOWN'\n" +
                "RETURN m.moduleName AS name",
                Map.of("path", projectPath));
            while (recs.hasNext()) {
                freePackages.add(recs.next().get("name").asString());
            }
        }
        if (!freePackages.isEmpty()) {
            List<Map<String, String>> items = new ArrayList<>();
            for (String name : freePackages) {
                Map<String, String> item = new HashMap<>();
                item.put("name", name);
                item.put("deps", buildPackageDeps(projectPath, name));
                items.add(item);
            }
            var pkgResults = layerRoleLlmService.resolveRoles(items);
            try (Session session = neo4jDriver.session()) {
                for (var rr : pkgResults) {
                    session.run(
                        "MATCH (m:ModuleNode {level: 'package', projectPath: $path, moduleName: $name})\n" +
                        "SET m.layerRole = $role",
                        Map.of("path", projectPath, "name", rr.name(), "role", rr.role()));
                    resolved++;
                }
            }
        }
        return resolved;
    }

    private String buildClassDeps(String projectPath, String className) {
        try (Session session = neo4jDriver.session()) {
            var recs = session.run(
                "MATCH (c:Class {projectPath: $path, className: $cls})-[h:HAS_METHOD]->(m:Method)-[r:CALLS]->(m2:Method)<-[h2:HAS_METHOD]-(c2:Class)\n" +
                "RETURN collect(DISTINCT c2.className) AS deps",
                Map.of("path", projectPath, "cls", className));
            if (recs.hasNext()) {
                var v = recs.next().get("deps");
                return v.isNull() ? "无" : String.join(",", v.asList(x -> x.asString()));
            }
        }
        return "无";
    }

    private String buildPackageDeps(String projectPath, String packageName) {
        try (Session session = neo4jDriver.session()) {
            var recs = session.run(
                "MATCH (m:ModuleNode {level: 'package', projectPath: $path, moduleName: $pkg})-[d:DEPENDS_ON]->(m2:ModuleNode)\n" +
                "RETURN collect(DISTINCT m2.moduleName) AS deps",
                Map.of("path", projectPath, "pkg", packageName));
            if (recs.hasNext()) {
                var v = recs.next().get("deps");
                return v.isNull() ? "无" : String.join(",", v.asList(x -> x.asString()));
            }
        }
        return "无";
    }
}
