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
 * 技能市场控制器
 * 提供技能列表、安装/卸载等功能
 *
 * 技能分类说明:
 * - diagnosis: 诊断类技能 (日志分析、问题定位)
 * - analysis: 分析类技能 (调用链、代码分析)
 * - generation: 生成类技能 (代码生成、文档生成)
 * - operation: 操作类技能 (MCP工具、钩子、自动化)
 * - other: 其他通用技能
 */
@RestController
@RequestMapping("/api/skill-market")
public class SkillMarketController {

    @Value("${skill.market.codeai.path:}")
    private String codeaiPath;

    /**
     * 获取技能列表
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getSkillList(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {

        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> skills = new ArrayList<>();

        // MCP 工具 - operation 分类
        skills.add(createSkill(
            "mcp-hisi-dev-tool",
            "HiSi DevTool MCP",
            "operation",
            "调用链分析、日志查询、接口分析等 MCP 工具",
            Arrays.asList("调用链", "日志", "接口", "MCP"),
            true,
            "1.0.0"
        ));

        // 核心钩子 - operation 分类
        skills.add(createSkill(
            "hook-pre-tool-use",
            "安全执行钩子",
            "operation",
            "在执行命令前进行安全检查，防止危险操作",
            Arrays.asList("rm -rf", "DROP TABLE", "force push", "安全"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "hook-skill-forced-eval",
            "自我进化钩子",
            "operation",
            "任务完成后强制反思，记录教训避免重复犯错",
            Arrays.asList("反思", "教训", "改进", "进化"),
            false,
            "1.0.0"
        ));

        // 后端技能 - analysis 分类
        skills.add(createSkill(
            "skill-crud-development",
            "CRUD 开发规范",
            "analysis",
            "增删改查开发规范，包括 Controller/Service/Repository 层",
            Arrays.asList("CRUD", "增删改查", "接口开发", "后端"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-api-development",
            "API 开发规范",
            "analysis",
            "RESTful API 设计、参数校验、异常处理规范",
            Arrays.asList("API", "REST", "接口设计", "后端"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-database-ops",
            "数据库操作规范",
            "analysis",
            "SQL 编写、索引优化、事务处理规范",
            Arrays.asList("SQL", "数据库", "事务", "索引"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-error-handler",
            "异常处理规范",
            "diagnosis",
            "统一异常处理、错误码定义、日志记录规范",
            Arrays.asList("异常", "错误", "日志", "诊断"),
            false,
            "1.0.0"
        ));

        // 前端技能 - generation 分类
        skills.add(createSkill(
            "skill-ui-pc",
            "UI 组件开发规范",
            "generation",
            "Element Plus/Ant Design 组件使用规范",
            Arrays.asList("UI", "组件", "Element", "前端"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-http-client",
            "HTTP 客户端规范",
            "generation",
            "Axios 封装、请求/响应拦截、错误处理规范",
            Arrays.asList("HTTP", "Axios", "请求", "前端"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-router-pc",
            "路由配置规范",
            "generation",
            "Vue Router 配置、路由守卫、动态路由规范",
            Arrays.asList("路由", "Router", "导航", "前端"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-store-pc",
            "状态管理规范",
            "generation",
            "Pinia/Vuex 状态管理、模块划分规范",
            Arrays.asList("状态", "Store", "Pinia", "前端"),
            false,
            "1.0.0"
        ));

        // 通用技能 - other 分类
        skills.add(createSkill(
            "skill-code-patterns",
            "代码模式规范",
            "other",
            "命名规范、代码结构、注释规范",
            Arrays.asList("命名", "规范", "注释", "代码"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-git-workflow",
            "Git 工作流规范",
            "other",
            "分支管理、提交规范、合并策略",
            Arrays.asList("Git", "分支", "提交", "工作流"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-bug-detective",
            "问题诊断技能",
            "diagnosis",
            "日志分析、错误定位、调试技巧",
            Arrays.asList("诊断", "调试", "日志", "问题"),
            false,
            "1.0.0"
        ));
        skills.add(createSkill(
            "skill-lesson-learned",
            "教训记录技能",
            "other",
            "记录开发教训、避免重复犯错",
            Arrays.asList("教训", "经验", "反思", "记录"),
            false,
            "1.0.0"
        ));

        // 按分类筛选
        if (category != null && !category.isEmpty() && !category.equals("all")) {
            skills = skills.stream()
                .filter(s -> s.get("category").equals(category))
                .toList();
        }

        // 搜索筛选
        if (search != null && !search.isEmpty()) {
            String searchLower = search.toLowerCase();
            skills = skills.stream()
                .filter(s -> {
                    String name = (String) s.get("name");
                    String desc = (String) s.get("description");
                    List<String> tags = (List<String>) s.get("tags");
                    boolean tagMatch = tags != null && tags.stream()
                        .anyMatch(t -> t.toLowerCase().contains(searchLower));
                    return name.toLowerCase().contains(searchLower) ||
                           desc.toLowerCase().contains(searchLower) ||
                           tagMatch;
                })
                .toList();
        }

        // 计算分类统计
        Map<String, Integer> categoryStats = new HashMap<>();
        categoryStats.put("diagnosis", 0);
        categoryStats.put("analysis", 0);
        categoryStats.put("generation", 0);
        categoryStats.put("operation", 0);
        categoryStats.put("other", 0);

        for (Map<String, Object> skill : skills) {
            String cat = (String) skill.get("category");
            categoryStats.put(cat, categoryStats.getOrDefault(cat, 0) + 1);
        }

        response.put("skills", skills);
        response.put("total", skills.size());
        response.put("categoryStats", categoryStats);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取项目技能状态
     */
    @GetMapping("/project-status")
    public ResponseEntity<List<Map<String, Object>>> getProjectStatus(
            @RequestParam String projectDir) {

        List<Map<String, Object>> status = new ArrayList<>();

        // 检查项目目录是否存在
        Path projectPath = Paths.get(projectDir);
        if (!Files.exists(projectPath)) {
            return ResponseEntity.ok(status);
        }

        // 检查 Claude 配置目录
        Path claudeDir = projectPath.resolve(".claude");
        boolean hasClaudeDir = Files.exists(claudeDir);

        // MCP 工具状态 - 检查 settings.json 或 mcp.json
        boolean mcpInstalled = checkMcpInstalled(projectDir);
        status.add(createStatus("mcp-hisi-dev-tool", mcpInstalled, "1.0.0"));

        // 钩子状态 - 检查 hooks 目录
        boolean preToolUseInstalled = checkHookInstalled(projectDir, "pre-tool-use.json");
        status.add(createStatus("hook-pre-tool-use", preToolUseInstalled, "1.0.0"));

        boolean forcedEvalInstalled = checkHookInstalled(projectDir, "skill-forced-eval.json");
        status.add(createStatus("hook-skill-forced-eval", forcedEvalInstalled, "1.0.0"));

        // 技能状态 - 检查 skills 目录
        String[] skillIds = {"crud-development", "api-development", "error-handler",
                            "ui-pc", "http-client", "code-patterns"};
        for (String skillId : skillIds) {
            boolean installed = checkSkillInstalled(projectDir, skillId);
            status.add(createStatus("skill-" + skillId, installed, "1.0.0"));
        }

        return ResponseEntity.ok(status);
    }

    /**
     * 获取技能详情
     */
    @GetMapping("/detail/{id}")
    public ResponseEntity<Map<String, Object>> getSkillDetail(@PathVariable String id) {
        Map<String, Object> detail = new HashMap<>();

        // 根据技能 ID 返回详情
        switch (id) {
            case "mcp-hisi-dev-tool":
                detail.put("id", id);
                detail.put("name", "HiSi DevTool MCP");
                detail.put("usage", "通过 Claude CLI 调用，提供调用链分析、日志查询等功能");
                detail.put("triggerKeywords", Arrays.asList("调用链", "日志", "接口"));
                detail.put("examples", Arrays.asList(
                    "查找 xxx 方法的上游调用方",
                    "查询最近15分钟的错误日志",
                    "分析 /api/users 接口的实现"
                ));
                break;
            case "hook-pre-tool-use":
                detail.put("id", id);
                detail.put("name", "安全执行钩子");
                detail.put("triggerCondition", "PreToolUse - 在执行工具前触发");
                detail.put("safetyRules", Arrays.asList(
                    "禁止执行 rm -rf 命令",
                    "禁止 DROP TABLE 操作",
                    "禁止 git push --force 到 main 分支"
                ));
                detail.put("examples", Arrays.asList(
                    "执行命令前自动检查安全性"
                ));
                break;
            case "hook-skill-forced-eval":
                detail.put("id", id);
                detail.put("name", "自我进化钩子");
                detail.put("triggerCondition", "PostToolUse - 在任务完成后触发");
                detail.put("coreFunctions", Arrays.asList(
                    "自动反思任务执行结果",
                    "记录教训到 lesson-learned.md",
                    "下次遇到类似场景自动应用改进"
                ));
                detail.put("examples", Arrays.asList(
                    "完成任务后自动总结经验教训"
                ));
                break;
            default:
                detail.put("id", id);
                detail.put("name", id.replace("skill-", "").replace("-", " "));
                detail.put("usage", "技能使用说明");
                detail.put("examples", Arrays.asList("示例提问"));
        }

        return ResponseEntity.ok(detail);
    }

    /**
     * 安装技能
     */
    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> installSkill(@RequestBody Map<String, Object> request) {
        String skillId = (String) request.get("skillId");
        String projectDir = (String) request.get("projectDir");

        Map<String, Object> result = new HashMap<>();

        try {
            Path projectPath = Paths.get(projectDir);
            if (!Files.exists(projectPath)) {
                result.put("success", false);
                result.put("message", "项目目录不存在");
                return ResponseEntity.ok(result);
            }

            // 创建 .claude 目录
            Path claudeDir = projectPath.resolve(".claude");
            if (!Files.exists(claudeDir)) {
                Files.createDirectories(claudeDir);
            }

            // 根据技能类型安装到不同目录
            String category = skillId.split("-")[0];
            Path targetDir;
            if (category.equals("hook")) {
                targetDir = claudeDir.resolve("hooks");
            } else if (category.equals("skill")) {
                targetDir = claudeDir.resolve("skills");
            } else {
                targetDir = claudeDir;
            }

            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            // 创建技能配置文件
            String fileName = skillId.replace("hook-", "").replace("skill-", "") + ".md";
            Path skillFile = targetDir.resolve(fileName);

            String content = generateSkillContent(skillId);
            Files.writeString(skillFile, content);

            result.put("success", true);
            result.put("message", "技能安装成功");
            result.put("installPath", skillFile.toString());

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "安装失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 卸载技能
     */
    @PostMapping("/uninstall")
    public ResponseEntity<Map<String, Object>> uninstallSkill(@RequestBody Map<String, Object> request) {
        String skillId = (String) request.get("skillId");
        String projectDir = (String) request.get("projectDir");

        Map<String, Object> result = new HashMap<>();

        try {
            Path projectPath = Paths.get(projectDir);
            String category = skillId.split("-")[0];

            Path targetDir;
            if (category.equals("hook")) {
                targetDir = projectPath.resolve(".claude/hooks");
            } else if (category.equals("skill")) {
                targetDir = projectPath.resolve(".claude/skills");
            } else {
                targetDir = projectPath.resolve(".claude");
            }

            String fileName = skillId.replace("hook-", "").replace("skill-", "") + ".md";
            Path skillFile = targetDir.resolve(fileName);

            if (Files.exists(skillFile)) {
                Files.delete(skillFile);
                result.put("success", true);
                result.put("message", "技能卸载成功");
            } else {
                result.put("success", false);
                result.put("message", "技能文件不存在");
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "卸载失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 检查更新
     */
    @GetMapping("/check-updates")
    public ResponseEntity<List<Map<String, Object>>> checkUpdates(@RequestParam String projectDir) {
        // 目前返回空列表，后续可实现版本检查
        return ResponseEntity.ok(new ArrayList<>());
    }

    /**
     * 更新技能
     */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> updateSkill(@RequestBody Map<String, Object> request) {
        // 更新等同于重新安装
        return installSkill(request);
    }

    // ========== Helper Methods ==========

    /**
     * 创建技能数据对象 - 与前端 SkillDefinition 类型兼容（简化版，无统计数据）
     */
    private Map<String, Object> createSkill(String id, String name, String category,
            String description, List<String> tags, boolean isOfficial, String version) {
        Map<String, Object> skill = new HashMap<>();
        skill.put("id", id);
        skill.put("name", name);
        skill.put("category", category); // diagnosis, analysis, generation, operation, other
        skill.put("description", description);
        skill.put("tags", tags);
        skill.put("version", version);
        skill.put("isOfficial", isOfficial);
        skill.put("files", Arrays.asList(
            Map.of("name", id + ".md", "type", "prompt", "description", "技能提示文件")
        ));
        return skill;
    }

    /**
     * 创建状态对象 - 与前端 ProjectSkillStatus 类型兼容
     */
    private Map<String, Object> createStatus(String skillId, boolean installed, String version) {
        Map<String, Object> status = new HashMap<>();
        status.put("skillId", skillId);
        status.put("skillName", skillId.replace("hook-", "").replace("skill-", "").replace("mcp-", ""));
        status.put("status", installed ? "installed" : "not_installed");
        status.put("installedVersion", installed ? version : null);
        return status;
    }

    private boolean checkMcpInstalled(String projectDir) {
        Path settingsPath = Paths.get(projectDir).resolve(".claude/settings.json");
        if (Files.exists(settingsPath)) {
            try {
                String content = Files.readString(settingsPath);
                return content.contains("hisi-dev-tool");
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private boolean checkHookInstalled(String projectDir, String hookName) {
        Path hookPath = Paths.get(projectDir).resolve(".claude/hooks").resolve(hookName);
        return Files.exists(hookPath);
    }

    private boolean checkSkillInstalled(String projectDir, String skillId) {
        Path skillPath = Paths.get(projectDir).resolve(".claude/skills").resolve(skillId + ".md");
        return Files.exists(skillPath);
    }

    private String generateSkillContent(String skillId) {
        StringBuilder content = new StringBuilder();
        content.append("# ").append(skillId.replace("hook-", "").replace("skill-", "")).append("\n\n");

        if (skillId.startsWith("hook-")) {
            content.append("## 钩子配置\n\n");
            content.append("类型: ").append(skillId.contains("pre") ? "PreToolUse" : "PostToolUse").append("\n\n");
            content.append("## 触发条件\n\n");
            content.append("在执行工具前/后自动触发\n\n");
        } else {
            content.append("## 技能说明\n\n");
            content.append("此技能帮助 Claude 更好地理解和执行相关任务。\n\n");
            content.append("## 触发关键词\n\n");
            content.append("- 关键词1\n- 关键词2\n\n");
        }

        return content.toString();
    }
}