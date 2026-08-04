package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.config.DataSourceConfig;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * KG 路径管理控制器
 * 用于诊断和修复 PROJECT_DIR 配置变更导致的路径不一致问题
 */
@RestController
@RequestMapping("/api/knowledge-graph/admin")
@Slf4j
@RequiredArgsConstructor
public class KgPathAdminController {

    private final Driver neo4jDriver;
    private final SessionConfig neo4jSessionConfig;

    /**
     * 诊断 KG 路径问题
     * 列出所有 KG 项目路径，与当前配置对比
     */
    @GetMapping("/paths/diagnosis")
    public ApiResponse<Map<String, Object>> diagnosePaths() {
        String currentProjectDir = DataSourceConfig.PROJECT_DIR;
        if (currentProjectDir == null || currentProjectDir.isBlank()) {
            return ApiResponse.error(400, "PROJECT_DIR 未配置");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentProjectDir", normalizePath(currentProjectDir));

        // 查询 Neo4j 中所有 unique projectPath
        Set<String> kgPaths = new LinkedHashSet<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            // Method nodes
            Result methodResult = session.run(
                "MATCH (m:MethodNode) RETURN DISTINCT m.projectPath AS path"
            );
            while (methodResult.hasNext()) {
                Record record = methodResult.next();
                String path = record.get("path").asString(null);
                if (path != null && !path.isBlank()) {
                    kgPaths.add(path);
                }
            }

            // EntryPoint nodes
            Result entryResult = session.run(
                "MATCH (e:EntryPointNode) RETURN DISTINCT e.projectPath AS path"
            );
            while (entryResult.hasNext()) {
                Record record = entryResult.next();
                String path = record.get("path").asString(null);
                if (path != null && !path.isBlank()) {
                    kgPaths.add(path);
                }
            }
        }

        result.put("kgProjectPaths", new ArrayList<>(kgPaths));
        result.put("totalKgPaths", kgPaths.size());

        // 分析哪些路径与当前配置不一致
        List<Map<String, Object>> inconsistentPaths = new ArrayList<>();
        String currentNormalized = normalizePath(currentProjectDir);

        for (String kgPath : kgPaths) {
            String kgNormalized = normalizePath(kgPath);
            if (!kgNormalized.startsWith(currentNormalized)) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("path", kgPath);
                info.put("normalized", kgNormalized);
                info.put("reason", "KG 路径不以当前 PROJECT_DIR 开头");

                // 提取项目名（路径的最后部分）
                String projectName = extractProjectName(kgPath);
                String expectedPath = currentNormalized + "/remote-repos/" + projectName;
                info.put("projectName", projectName);
                info.put("expectedPath", expectedPath);

                inconsistentPaths.add(info);
            }
        }

        result.put("inconsistentPaths", inconsistentPaths);
        result.put("inconsistentCount", inconsistentPaths.size());

        return ApiResponse.success(result);
    }

    /**
     * 迁移 KG 路径
     * 将旧路径格式批量更新为当前 PROJECT_DIR 配置的路径
     *
     * @param oldBaseDir 旧的基础目录（如 D:/codeknowledge）
     * @param dryRun 是否只预览不执行
     */
    @PostMapping("/paths/migrate")
    public ApiResponse<Map<String, Object>> migratePaths(
            @RequestParam String oldBaseDir,
            @RequestParam(defaultValue = "true") boolean dryRun) {

        String currentProjectDir = DataSourceConfig.PROJECT_DIR;
        if (currentProjectDir == null || currentProjectDir.isBlank()) {
            return ApiResponse.error(400, "PROJECT_DIR 未配置");
        }

        String oldNormalized = normalizePath(oldBaseDir);
        String newNormalized = normalizePath(currentProjectDir);

        if (oldNormalized.equals(newNormalized)) {
            return ApiResponse.error(400, "新旧路径相同，无需迁移");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oldBaseDir", oldNormalized);
        result.put("newBaseDir", newNormalized);
        result.put("dryRun", dryRun);

        // 统计将受影响的节点数
        int methodCount = 0;
        int entryCount = 0;
        int sqlCount = 0;
        List<String> affectedPaths = new ArrayList<>();

        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            // 统计 MethodNode
            Result countMethod = session.run(
                "MATCH (m:MethodNode) WHERE m.projectPath STARTS WITH $oldPath RETURN count(m) AS cnt",
                Map.of("oldPath", oldNormalized)
            );
            if (countMethod.hasNext()) {
                methodCount = countMethod.next().get("cnt").asInt();
            }

            // 统计 EntryPointNode
            Result countEntry = session.run(
                "MATCH (e:EntryPointNode) WHERE e.projectPath STARTS WITH $oldPath RETURN count(e) AS cnt",
                Map.of("oldPath", oldNormalized)
            );
            if (countEntry.hasNext()) {
                entryCount = countEntry.next().get("cnt").asInt();
            }

            // 统计 SqlNode
            Result countSql = session.run(
                "MATCH (s:SqlNode) WHERE s.projectPath STARTS WITH $oldPath RETURN count(s) AS cnt",
                Map.of("oldPath", oldNormalized)
            );
            if (countSql.hasNext()) {
                sqlCount = countSql.next().get("cnt").asInt();
            }

            // 收集受影响的路径
            Result pathsResult = session.run(
                "MATCH (n) WHERE n.projectPath STARTS WITH $oldPath RETURN DISTINCT n.projectPath AS path",
                Map.of("oldPath", oldNormalized)
            );
            while (pathsResult.hasNext()) {
                affectedPaths.add(pathsResult.next().get("path").asString());
            }

            // 如果不是 dryRun，执行迁移
            if (!dryRun && methodCount + entryCount + sqlCount > 0) {
                // 更新 MethodNode
                session.run(
                    "MATCH (m:MethodNode) WHERE m.projectPath STARTS WITH $oldPath " +
                    "SET m.projectPath = replace(m.projectPath, $oldPath, $newPath)",
                    Map.of("oldPath", oldNormalized, "newPath", newNormalized)
                );

                // 更新 EntryPointNode
                session.run(
                    "MATCH (e:EntryPointNode) WHERE e.projectPath STARTS WITH $oldPath " +
                    "SET e.projectPath = replace(e.projectPath, $oldPath, $newPath)",
                    Map.of("oldPath", oldNormalized, "newPath", newNormalized)
                );

                // 更新 SqlNode
                session.run(
                    "MATCH (s:SqlNode) WHERE s.projectPath STARTS WITH $oldPath " +
                    "SET s.projectPath = replace(s.projectPath, $oldPath, $newPath)",
                    Map.of("oldPath", oldNormalized, "newPath", newNormalized)
                );

                // 更新 nodeId 中嵌入的路径（MethodNode nodeId 格式: projectPath:className.methodName.hash）
                session.run(
                    "MATCH (m:MethodNode) WHERE m.nodeId CONTAINS $oldPath " +
                    "SET m.nodeId = replace(m.nodeId, $oldPath, $newPath)",
                    Map.of("oldPath", oldNormalized, "newPath", newNormalized)
                );

                // 更新 EntryPointNode methodNodeId
                session.run(
                    "MATCH (e:EntryPointNode) WHERE e.methodNodeId CONTAINS $oldPath " +
                    "SET e.methodNodeId = replace(e.methodNodeId, $oldPath, $newPath)",
                    Map.of("oldPath", oldNormalized, "newPath", newNormalized)
                );

                log.info("[KG Path Migration] Migrated {} method nodes, {} entry points, {} SQL nodes",
                    methodCount, entryCount, sqlCount);
            }
        }

        result.put("methodCount", methodCount);
        result.put("entryCount", entryCount);
        result.put("sqlCount", sqlCount);
        result.put("totalAffected", methodCount + entryCount + sqlCount);
        result.put("affectedPaths", affectedPaths);

        if (dryRun) {
            result.put("message", "预览完成，未执行迁移。设置 dryRun=false 执行实际迁移。");
        } else if (methodCount + entryCount + sqlCount > 0) {
            result.put("message", "迁移完成。共更新 " + (methodCount + entryCount + sqlCount) + " 个节点。");
        } else {
            result.put("message", "未找到需要迁移的节点。");
        }

        return ApiResponse.success(result);
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String extractProjectName(String path) {
        String normalized = normalizePath(path);
        // 提取路径最后部分作为项目名
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < normalized.length() - 1) {
            return normalized.substring(lastSlash + 1);
        }
        return normalized;
    }
}