package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.neo4j.model.ModuleNode;
import com.huawei.hisi.neo4j.repository.ModuleNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModuleStatsAggregator {

    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final ModuleNodeRepository moduleNodeRepository;
    private final Driver neo4jDriver;

    public void aggregate(String projectPath, Set<String> dirtyPackageNames) {
        boolean isFull = (dirtyPackageNames == null || dirtyPackageNames.isEmpty());
        log.info("[Aggregation][ModuleStats] aggregate 开始, projectPath={}, isFull={}, dirtyPkgs={}",
            projectPath, isFull, dirtyPackageNames != null ? dirtyPackageNames.size() : 0);
        updateInOutDegree(projectPath, isFull ? null : dirtyPackageNames);
        log.info("[Aggregation][ModuleStats] inDegree/outDegree 更新完毕");
        aggregateModules(projectPath, isFull, dirtyPackageNames);
        log.info("[Aggregation][ModuleStats] 模块聚合完毕");
        updateLayerRole(projectPath);
        buildModuleDependencies(projectPath, isFull, dirtyPackageNames);
        buildContainsRelations(projectPath);
        log.info("[Aggregation][ModuleStats] 模块依赖边构建完毕, projectPath={}, full={}", projectPath, isFull);
    }

    /** 建立 ModuleNode -[:CONTAINS]-> MethodNode 边，支撑 DSM 下钻到包内方法 */
    private void buildContainsRelations(String projectPath) {
        String cypher =
            "MATCH (mod:ModuleNode {projectPath: $projectPath})\n" +
            "MATCH (m:Method {projectPath: $projectPath})\n" +
            "WHERE m.packageName = mod.moduleName\n" +
            "MERGE (mod)-[:CONTAINS]->(m)";
        try (Session session = neo4jDriver.session()) {
            session.run(cypher, Map.of("projectPath", projectPath));
        }
    }

    /**
     * 基于包名推断模块的分层角色（决策 #7：正则 + 注解推断）
     */
    private void updateLayerRole(String projectPath) {
        String cypher =
            "MATCH (mod:ModuleNode {projectPath: $projectPath})\n" +
            "WITH mod, split(toLower(mod.moduleName), '.')[-1] AS last\n" +
            "SET mod.layerRole = CASE\n" +
            "    WHEN last = 'controller' OR last = 'handler' OR last = 'endpoint' THEN 'CONTROLLER'\n" +
            "    WHEN last = 'service' THEN 'SERVICE'\n" +
            "    WHEN last = 'repository' OR last = 'dao' OR last = 'mapper' THEN 'REPOSITORY'\n" +
            "    WHEN last = 'dto' OR last = 'model' OR last = 'entity' OR last = 'vo' OR last = 'domain' THEN 'MODEL'\n" +
            "    WHEN last = 'util' OR last = 'common' OR last = 'config' OR last = 'constant' THEN 'UTILITY'\n" +
            "    ELSE 'UNKNOWN' END";
        try (Session session = neo4jDriver.session()) {
            session.run(cypher, Map.of("projectPath", projectPath));
        }
    }

    private void aggregateModules(String projectPath, boolean isFull, Set<String> dirtyPackageNames) {
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("projectPath", projectPath);

        if (isFull) {
            cypher =
                "MATCH (m:Method {projectPath: $projectPath})\n" +
                "WHERE m.packageName IS NOT NULL\n" +
                "WITH m.packageName AS pkg, m\n" +
                "WITH pkg,\n" +
                "     count(m) AS methodCount,\n" +
                "     count(DISTINCT m.className) AS classCount,\n" +
                "     avg(m.complexity) AS avgComplexity,\n" +
                "     sum(coalesce(m.inDegree, 0)) AS inDegree,\n" +
                "     sum(coalesce(m.outDegree, 0)) AS outDegree\n" +
                "WITH pkg, methodCount, classCount, avgComplexity, inDegree, outDegree,\n" +
                "     CASE WHEN inDegree + outDegree = 0 THEN 0.0\n" +
                "          ELSE toFloat(outDegree) / (inDegree + outDegree) END AS instability\n" +
                "MERGE (mod:ModuleNode {moduleId: $projectPath + ':' + pkg})\n" +
                "SET mod.moduleName = pkg,\n" +
                "    mod.level = 'package',\n" +
                "    mod.methodCount = methodCount,\n" +
                "    mod.classCount = classCount,\n" +
                "    mod.avgComplexity = avgComplexity,\n" +
                "    mod.inDegree = inDegree,\n" +
                "    mod.outDegree = outDegree,\n" +
                "    mod.instability = instability,\n" +
                "    mod.projectPath = $projectPath,\n" +
                "    mod.language = 'java'";
        } else {
            cypher =
                "MATCH (m:Method {projectPath: $projectPath})\n" +
                "WHERE m.packageName IN $dirtyPackages\n" +
                "WITH m.packageName AS pkg, m\n" +
                "WITH pkg,\n" +
                "     count(m) AS methodCount,\n" +
                "     count(DISTINCT m.className) AS classCount,\n" +
                "     avg(m.complexity) AS avgComplexity,\n" +
                "     sum(coalesce(m.inDegree, 0)) AS inDegree,\n" +
                "     sum(coalesce(m.outDegree, 0)) AS outDegree\n" +
                "WITH pkg, methodCount, classCount, avgComplexity, inDegree, outDegree,\n" +
                "     CASE WHEN inDegree + outDegree = 0 THEN 0.0\n" +
                "          ELSE toFloat(outDegree) / (inDegree + outDegree) END AS instability\n" +
                "MERGE (mod:ModuleNode {moduleId: $projectPath + ':' + pkg})\n" +
                "SET mod.moduleName = pkg,\n" +
                "    mod.level = 'package',\n" +
                "    mod.methodCount = methodCount,\n" +
                "    mod.classCount = classCount,\n" +
                "    mod.avgComplexity = avgComplexity,\n" +
                "    mod.inDegree = inDegree,\n" +
                "    mod.outDegree = outDegree,\n" +
                "    mod.instability = instability,\n" +
                "    mod.projectPath = $projectPath,\n" +
                "    mod.language = 'java'";
            params.put("dirtyPackages", new ArrayList<>(dirtyPackageNames));
        }

        try (Session session = neo4jDriver.session()) {
            session.run(cypher, params);
        }
    }

    private void updateInOutDegree(String projectPath, Set<String> dirtyPackageNames) {
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("projectPath", projectPath);

        if (dirtyPackageNames != null && !dirtyPackageNames.isEmpty()) {
            cypher =
                "MATCH (m:Method {projectPath: $projectPath})\n" +
                "WHERE m.packageName IS NOT NULL AND m.packageName IN $dirtyPackages\n" +
                "WITH m\n" +
                "OPTIONAL MATCH (m)<-[:CALLS]-(caller:Method)\n" +
                "WITH m, count(DISTINCT caller) AS inDeg\n" +
                "OPTIONAL MATCH (m)-[:CALLS]->(callee:Method)\n" +
                "WITH m, inDeg, count(DISTINCT callee) AS outDeg\n" +
                "SET m.inDegree = inDeg, m.outDegree = outDeg";
            params.put("dirtyPackages", new ArrayList<>(dirtyPackageNames));
        } else {
            cypher =
                "MATCH (m:Method {projectPath: $projectPath})\n" +
                "WHERE m.packageName IS NOT NULL\n" +
                "WITH m\n" +
                "OPTIONAL MATCH (m)<-[:CALLS]-(caller:Method)\n" +
                "WITH m, count(DISTINCT caller) AS inDeg\n" +
                "OPTIONAL MATCH (m)-[:CALLS]->(callee:Method)\n" +
                "WITH m, inDeg, count(DISTINCT callee) AS outDeg\n" +
                "SET m.inDegree = inDeg, m.outDegree = outDeg";
        }

        try (Session session = neo4jDriver.session()) {
            session.run(cypher, params);
        }
    }

    private void buildModuleDependencies(String projectPath, boolean isFull, Set<String> dirtyPackageNames) {
        String cypher;
        Map<String, Object> params = new HashMap<>();
        params.put("projectPath", projectPath);

        if (!isFull && dirtyPackageNames != null && !dirtyPackageNames.isEmpty()) {
            cypher =
                "MATCH (m1:Method {projectPath: $projectPath})-[r:CALLS]->(m2:Method {projectPath: $projectPath})\n" +
                "WHERE m1.packageName <> m2.packageName\n" +
                "  AND (m1.packageName IN $dirtyPackages OR m2.packageName IN $dirtyPackages)\n" +
                "WITH m1.packageName AS source, m2.packageName AS target,\n" +
                "     count(r) AS weight, collect(DISTINCT r.bridgeType) AS bridgeTypes\n" +
                "MATCH (ms:ModuleNode {moduleId: $projectPath + ':' + source})\n" +
                "MATCH (mt:ModuleNode {moduleId: $projectPath + ':' + target})\n" +
                "MERGE (ms)-[d:DEPENDS_ON]->(mt)\n" +
                "SET d.weight = weight, d.bridgeTypes = bridgeTypes";
            params.put("dirtyPackages", new ArrayList<>(dirtyPackageNames));
        } else {
            cypher =
                "MATCH (m1:Method {projectPath: $projectPath})-[r:CALLS]->(m2:Method {projectPath: $projectPath})\n" +
                "WHERE m1.packageName <> m2.packageName\n" +
                "WITH m1.packageName AS source, m2.packageName AS target,\n" +
                "     count(r) AS weight, collect(DISTINCT r.bridgeType) AS bridgeTypes\n" +
                "MATCH (ms:ModuleNode {moduleId: $projectPath + ':' + source})\n" +
                "MATCH (mt:ModuleNode {moduleId: $projectPath + ':' + target})\n" +
                "MERGE (ms)-[d:DEPENDS_ON]->(mt)\n" +
                "SET d.weight = weight, d.bridgeTypes = bridgeTypes";
        }

        try (Session session = neo4jDriver.session()) {
            session.run(cypher, params);
        }
    }
}
