package com.huawei.hisi.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * KG Skills 套件控制器
 * 提供全局 Skills 套件的安装/卸载功能（安装到 ~/.claude/skills）
 *
 * 套件包含：
 * - kg-search: 知识图谱混合检索（动态查询 MCP，支持多选）
 * - kg-trace: 调用链追踪（上游追溯+下游追踪+影响分析）
 * - kg-diagnose: 日志诊断与影响分析（根因分析+修复建议）
 */
@RestController
@RequestMapping("/api/kg-skills-kit")
public class KgSkillsKitController {

    @Value("${user.home}")
    private String userHome;

    // Skills 套件定义
    private static final List<Map<String, Object>> KG_SKILLS = Arrays.asList(
        createSkillDefinition("kg-search", "知识图谱混合检索",
            "analysis", "混合检索（关键词+向量+图遍历），动态查询 MCP，支持项目多选",
            Arrays.asList("检索", "调用链", "代码搜索", "kg", "hybrid")),
        createSkillDefinition("kg-trace", "调用链追踪",
            "analysis", "上游追溯+下游追踪+影响分析，完整调用链路追踪",
            Arrays.asList("调用链", "上下游", "影响", "trace", "callers")),
        createSkillDefinition("kg-diagnose", "日志诊断与影响分析",
            "diagnosis", "日志分析+根因诊断+修复建议，结合图谱进行错误定位",
            Arrays.asList("日志", "诊断", "根因", "错误", "diagnose"))
    );

    /**
     * 获取套件列表
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getKitList() {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> skillsWithStatus = new ArrayList<>();

        Path skillsDir = getGlobalSkillsDir();

        for (Map<String, Object> skill : KG_SKILLS) {
            String skillId = (String) skill.get("id");
            boolean installed = checkSkillInstalled(skillsDir, skillId);

            Map<String, Object> skillWithStatus = new HashMap<>(skill);
            skillWithStatus.put("installed", installed);
            skillWithStatus.put("installPath", installed ?
                skillsDir.resolve(skillId).resolve("SKILL.md").toString() : null);
            skillsWithStatus.add(skillWithStatus);
        }

        int installedCount = (int) skillsWithStatus.stream()
            .filter(s -> (boolean) s.get("installed"))
            .count();

        response.put("skills", skillsWithStatus);
        response.put("total", KG_SKILLS.size());
        response.put("installedCount", installedCount);
        response.put("skillsDir", skillsDir.toString());
        response.put("kitVersion", "1.0.0");
        response.put("kitName", "KG Skills 开发套件");

        return ResponseEntity.ok(response);
    }

    /**
     * 安装单个 Skill
     */
    @PostMapping("/install/{skillId}")
    public ResponseEntity<Map<String, Object>> installSkill(@PathVariable String skillId) {
        Map<String, Object> result = new HashMap<>();

        try {
            Path skillsDir = getGlobalSkillsDir();
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
            }

            Path skillDir = skillsDir.resolve(skillId);
            if (Files.exists(skillDir)) {
                result.put("success", false);
                result.put("message", "Skill 已存在，无需重复安装");
                result.put("installed", true);
                return ResponseEntity.ok(result);
            }

            Files.createDirectories(skillDir);
            Path skillFile = skillDir.resolve("SKILL.md");
            String content = generateSkillContent(skillId);
            Files.writeString(skillFile, content);

            result.put("success", true);
            result.put("message", "Skill 安装成功");
            result.put("installPath", skillFile.toString());
            result.put("skillId", skillId);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "安装失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 卸载单个 Skill
     */
    @PostMapping("/uninstall/{skillId}")
    public ResponseEntity<Map<String, Object>> uninstallSkill(@PathVariable String skillId) {
        Map<String, Object> result = new HashMap<>();

        try {
            Path skillsDir = getGlobalSkillsDir();
            Path skillDir = skillsDir.resolve(skillId);

            if (!Files.exists(skillDir)) {
                result.put("success", false);
                result.put("message", "Skill 未安装");
                return ResponseEntity.ok(result);
            }

            deleteDirectory(skillDir);

            result.put("success", true);
            result.put("message", "Skill 卸载成功");
            result.put("skillId", skillId);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "卸载失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 一键安装全部 Skills
     */
    @PostMapping("/install-all")
    public ResponseEntity<Map<String, Object>> installAll() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> installResults = new ArrayList<>();
        int successCount = 0;
        int skipCount = 0;

        try {
            Path skillsDir = getGlobalSkillsDir();
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
            }

            for (Map<String, Object> skill : KG_SKILLS) {
                String skillId = (String) skill.get("id");
                Path skillDir = skillsDir.resolve(skillId);

                if (Files.exists(skillDir)) {
                    installResults.add(Map.of(
                        "skillId", skillId,
                        "success", true,
                        "message", "已存在，跳过",
                        "skipped", true
                    ));
                    skipCount++;
                    continue;
                }

                try {
                    Files.createDirectories(skillDir);
                    Path skillFile = skillDir.resolve("SKILL.md");
                    String content = generateSkillContent(skillId);
                    Files.writeString(skillFile, content);

                    installResults.add(Map.of(
                        "skillId", skillId,
                        "success", true,
                        "message", "安装成功",
                        "installPath", skillFile.toString()
                    ));
                    successCount++;

                } catch (Exception e) {
                    installResults.add(Map.of(
                        "skillId", skillId,
                        "success", false,
                        "message", "安装失败: " + e.getMessage()
                    ));
                }
            }

            result.put("success", true);
            result.put("message", String.format("安装完成：成功 %d，跳过 %d", successCount, skipCount));
            result.put("results", installResults);
            result.put("successCount", successCount);
            result.put("skipCount", skipCount);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "批量安装失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 一键卸载全部 Skills
     */
    @PostMapping("/uninstall-all")
    public ResponseEntity<Map<String, Object>> uninstallAll() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> uninstallResults = new ArrayList<>();
        int successCount = 0;

        try {
            Path skillsDir = getGlobalSkillsDir();

            for (Map<String, Object> skill : KG_SKILLS) {
                String skillId = (String) skill.get("id");
                Path skillDir = skillsDir.resolve(skillId);

                if (!Files.exists(skillDir)) {
                    uninstallResults.add(Map.of(
                        "skillId", skillId,
                        "success", true,
                        "message", "未安装，跳过"
                    ));
                    continue;
                }

                try {
                    deleteDirectory(skillDir);
                    uninstallResults.add(Map.of(
                        "skillId", skillId,
                        "success", true,
                        "message", "卸载成功"
                    ));
                    successCount++;

                } catch (Exception e) {
                    uninstallResults.add(Map.of(
                        "skillId", skillId,
                        "success", false,
                        "message", "卸载失败: " + e.getMessage()
                    ));
                }
            }

            result.put("success", true);
            result.put("message", String.format("卸载完成：成功 %d", successCount));
            result.put("results", uninstallResults);
            result.put("successCount", successCount);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "批量卸载失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取套件使用指南
     */
    @GetMapping("/guide")
    public ResponseEntity<Map<String, Object>> getGuide() {
        Map<String, Object> guide = new HashMap<>();

        guide.put("kitName", "KG Skills 开发套件");
        guide.put("version", "1.0.0");
        guide.put("description", "知识图谱 Skill 开发套件，为 Claude Code 提供图谱检索、调用链追踪、日志诊断等能力");

        guide.put("features", Arrays.asList(
            "动态项目查询：每次使用先调用 MCP kg_list_projects 获取已图谱化项目",
            "项目多选支持：AskUserQuestion 设置 multiSelect: true",
            "MCP + Skill 分层：MCP 负责数据查询，Skill 负责交互编排",
            "一键安装/卸载：提供跨平台安装脚本"
        ));

        guide.put("usage", Arrays.asList(
            "/kg-search 查找处理支付的方法",
            "/kg-trace 分析订单创建的调用链",
            "/kg-diagnose 分析这个错误日志的根因"
        ));

        guide.put("prerequisites", Arrays.asList(
            "MCP 配置：确保 hisi-mcp-server 已在 Claude Code MCP 配置中",
            "图谱生成：项目需先执行知识图谱生成才能检索",
            "后端服务：Neo4j 和后端服务正常运行"
        ));

        guide.put("mcpTools", Arrays.asList(
            "kg_list_projects - 获取已图谱化项目列表",
            "hybrid_search - 混合检索（关键词+向量+图遍历）",
            "kg_callees_tree - 下游调用树",
            "kg_root_entries - 上游根入口",
            "kg_affecting - 影响范围分析",
            "log_analyze - 提交日志分析任务",
            "log_report - 获取分析报告"
        ));

        return ResponseEntity.ok(guide);
    }

    // Helper methods

    private Path getGlobalSkillsDir() {
        return Paths.get(userHome, ".claude", "skills");
    }

    private boolean checkSkillInstalled(Path skillsDir, String skillId) {
        Path skillFile = skillsDir.resolve(skillId).resolve("SKILL.md");
        return Files.exists(skillFile);
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // ignore
                    }
                });
        } else {
            Files.deleteIfExists(dir);
        }
    }

    private static Map<String, Object> createSkillDefinition(String id, String name,
            String category, String description, List<String> tags) {
        Map<String, Object> skill = new HashMap<>();
        skill.put("id", id);
        skill.put("name", name);
        skill.put("category", category);
        skill.put("description", description);
        skill.put("tags", tags);
        skill.put("version", "1.0.0");
        skill.put("isOfficial", true);
        return skill;
    }

    private String generateSkillContent(String skillId) {
        switch (skillId) {
            case "kg-search":
                return """
---
name: kg-search
description: 知识图谱混合检索 Skill。支持跨项目检索代码方法、调用链分析。使用前必须先通过 MCP kg_list_projects 确认可用的图谱项目。
---

# 知识图谱混合检索

## 执行流程

### 步骤 1：获取可用项目（动态查询）

**必须先调用 MCP 工具获取已图谱化项目列表**：
使用 MCP 工具：mcp__hisi-mcp-server__kg_list_projects

### 步骤 2：项目选择（支持多选）

AskUserQuestion 设置 multiSelect: true，动态填充项目列表。

### 步骤 3：执行混合检索

调用 MCP hybrid_search 执行检索。

### 步骤 4：结果呈现

格式化返回结果。
""";

            case "kg-trace":
                return """
---
name: kg-trace
description: 调用链追踪 Skill。合并 callers/callees/downstream 查询。使用前必须先通过 MCP kg_list_projects 确认可用的图谱项目。
---

# 调用链追踪

## 执行流程

### 步骤 1：获取可用项目
调用 MCP kg_list_projects。

### 步骤 2：项目选择（支持多选）
AskUserQuestion 设置 multiSelect: true。

### 步骤 3：获取追踪目标
询问用户要追踪的方法。

### 步骤 4：执行追踪
调用 kg_root_entries / kg_callees_tree / kg_affecting。
""";

            case "kg-diagnose":
                return """
---
name: kg-diagnose
description: 日志诊断与影响分析 Skill。结合知识图谱进行错误根因分析。使用前必须先通过 MCP kg_list_projects 确认可用的图谱项目。
---

# 日志诊断与影响分析

## 执行流程

### 步骤 1：获取可用项目
调用 MCP kg_list_projects。

### 步骤 2：项目选择（支持多选）
AskUserQuestion 设置 multiSelect: true。

### 步骤 3：获取诊断输入
询问用户提供的日志/方法名/TraceId。

### 步骤 4：执行诊断
调用 log_analyze / kg_root_entries / kg_affecting。
""";

            default:
                return "# " + skillId + "\n\nSkill content not found.";
        }
    }
}