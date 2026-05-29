package com.huawei.hisi.skill;

import com.huawei.hisi.skill.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 技能市场服务实现类
 * 提供技能管理相关功能的实现
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class SkillServiceImpl implements SkillService {

    /**
     * 预定义的技能分类
     */
    private static final List<String> CATEGORIES = Arrays.asList(
            "mcp", "hook", "backend-skill", "frontend-skill", "general-skill"
    );

    /**
     * 预定义的技能列表
     * TODO: 后续可从配置文件或数据库加载
     */
    private final List<SkillDefinition> skillDefinitions;

    public SkillServiceImpl() {
        this.skillDefinitions = initSkillDefinitions();
    }

    /**
     * 初始化技能定义列表
     * 包含 MCP 工具、核心 Hooks、后端/前端/通用 Skills
     */
    private List<SkillDefinition> initSkillDefinitions() {
        List<SkillDefinition> skills = new ArrayList<>();

        // MCP 工具
        skills.add(SkillDefinition.builder()
                .id("hisi-dev-tool")
                .name("HiSi DevTool MCP")
                .category("mcp")
                .icon("Connection")
                .description("HiSi 开发工具 MCP Server - 调用链分析、日志查询、接口分析")
                .triggerKeywords(Arrays.asList("call chain", "log analysis", "接口分析"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("mcp/hisi-dev-tool-mcp")
                                .target(".claude/settings.json")
                                .type("settings")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("提供调用链分析、日志查询、接口分析等 MCP 工具功能")
                        .examples(Arrays.asList(
                                "帮我分析这个接口的调用链",
                                "查询最近一小时错误日志",
                                "生成接口文档"
                        ))
                        .applicableProjects("Java Spring Boot / Python")
                        .build())
                .build());

        // 安全钩子
        skills.add(SkillDefinition.builder()
                .id("pre-tool-use")
                .name("pre-tool-use (安全钩子)")
                .category("hook")
                .icon("Shield")
                .description("执行危险命令前的安全检查钩子")
                .triggerKeywords(Arrays.asList("Bash", "Write", "危险操作"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("hooks/pre-tool-use.js")
                                .target(".claude/hooks/pre-tool-use.js")
                                .type("hook")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("在 Bash 或 Write 工具执行前进行安全检查，阻止危险操作")
                        .triggerCondition("Bash|Write 工具执行前")
                        .safetyRules(Arrays.asList(
                                "阻止 rm -rf / 删除根目录",
                                "阻止 git push --force main/master",
                                "阻止 drop database",
                                "提醒写入 .env 等敏感文件"
                        ))
                        .examples(Arrays.asList(
                                "自动保护：执行危险命令前会自动检查"
                        ))
                        .applicableProjects("所有项目")
                        .build())
                .build());

        // 自我进化钩子
        skills.add(SkillDefinition.builder()
                .id("skill-forced-eval")
                .name("skill-forced-eval (自我进化)")
                .category("hook")
                .icon("Brain")
                .description("自动检测用户纠正，触发 lesson-learned Skill 记录错误")
                .triggerKeywords(Arrays.asList("不对", "错了", "应该是", "纠正"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("hooks/skill-forced-eval.js")
                                .target(".claude/hooks/skill-forced-eval.js")
                                .type("hook")
                                .build(),
                        SkillFile.builder()
                                .source("memory/lessons.md.template")
                                .target(".claude/memory/lessons.md")
                                .type("memory")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("用户每次提交问题时自动检测纠正关键词，触发学习机制")
                        .triggerCondition("UserPromptSubmit 事件")
                        .coreFunctions(Arrays.asList(
                                "自动检测纠正关键词（不对/错了/应该是）",
                                "激活 lesson-learned Skill 记录错误",
                                "下次对话自动注入经验库规则"
                        ))
                        .examples(Arrays.asList(
                                "自动触发：用户说「不对，应该是 xxx」时自动记录"
                        ))
                        .applicableProjects("所有项目")
                        .build())
                .build());

        // lesson-learned Skill
        skills.add(SkillDefinition.builder()
                .id("lesson-learned")
                .name("lesson-learned (经验学习)")
                .category("general-skill")
                .icon("Study")
                .description("从纠正中学习，避免重复犯错")
                .triggerKeywords(Arrays.asList("lesson", "经验", "教训"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/lesson-learned/SKILL.md")
                                .target(".claude/skills/lesson-learned/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("自动加载经验库规则，在开发过程中应用已学到的经验")
                        .examples(Arrays.asList(
                                "自动激活：每次对话开始时自动加载经验库"
                        ))
                        .applicableProjects("所有项目")
                        .build())
                .build());

        // 后端 Skills
        skills.add(SkillDefinition.builder()
                .id("crud-development")
                .name("CRUD 开发规范")
                .category("backend-skill")
                .icon("DataBoard")
                .description("三层架构标准 CRUD 开发模板")
                .triggerKeywords(Arrays.asList("CRUD", "增删改查", "Service", "DAO", "Controller"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/crud-development/SKILL.md")
                                .target(".claude/skills/crud-development/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("开发后端业务模块时自动激活，提供三层架构标准代码模板")
                        .examples(Arrays.asList(
                                "帮我开发用户管理模块",
                                "创建订单的 CRUD 功能",
                                "生成增删改查代码"
                        ))
                        .applicableProjects("Java Spring Boot / Python Django")
                        .build())
                .build());

        skills.add(SkillDefinition.builder()
                .id("api-development")
                .name("API 开发规范")
                .category("backend-skill")
                .icon("Prometheus")
                .description("RESTful API 设计与开发规范")
                .triggerKeywords(Arrays.asList("API", "REST", "接口", "Endpoint"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/api-development/SKILL.md")
                                .target(".claude/skills/api-development/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("开发 API 接口时自动激活，提供 RESTful 设计规范和代码模板")
                        .examples(Arrays.asList(
                                "帮我设计用户 API",
                                "创建订单接口",
                                "设计 RESTful API"
                        ))
                        .applicableProjects("Java Spring Boot / Python Flask")
                        .build())
                .build());

        skills.add(SkillDefinition.builder()
                .id("database-ops")
                .name("数据库操作规范")
                .category("backend-skill")
                .icon("Coin")
                .description("数据库设计与操作规范")
                .triggerKeywords(Arrays.asList("SQL", "数据库", "表", "查询优化"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/database-ops/SKILL.md")
                                .target(".claude/skills/database-ops/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("数据库相关操作时自动激活，提供安全高效的 SQL 编写规范")
                        .examples(Arrays.asList(
                                "帮我设计用户表",
                                "优化这个 SQL 查询",
                                "创建数据库索引"
                        ))
                        .applicableProjects("MySQL / PostgreSQL / OpenGauss")
                        .build())
                .build());

        skills.add(SkillDefinition.builder()
                .id("error-handler")
                .name("错误处理规范")
                .category("backend-skill")
                .icon("Warning")
                .description("统一异常处理与日志记录规范")
                .triggerKeywords(Arrays.asList("异常", "错误", "Exception", "日志"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/error-handler/SKILL.md")
                                .target(".claude/skills/error-handler/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("处理异常和错误时自动激活，提供统一的错误处理模式")
                        .examples(Arrays.asList(
                                "帮我设计全局异常处理",
                                "添加错误日志记录",
                                "处理这个异常"
                        ))
                        .applicableProjects("Java Spring Boot / Python")
                        .build())
                .build());

        // 前端 Skills
        skills.add(SkillDefinition.builder()
                .id("ui-pc")
                .name("PC 端 UI 开发规范")
                .category("frontend-skill")
                .icon("Monitor")
                .description("PC 端界面设计与开发规范")
                .triggerKeywords(Arrays.asList("UI", "PC", "Element Plus", "Ant Design"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/ui-pc/SKILL.md")
                                .target(".claude/skills/ui-pc/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("开发 PC 端界面时自动激活，提供 Element Plus / Ant Design 组件使用规范")
                        .examples(Arrays.asList(
                                "帮我创建用户列表页面",
                                "设计表单组件",
                                "添加表格筛选功能"
                        ))
                        .applicableProjects("Vue 3 + Element Plus / React + Ant Design")
                        .build())
                .build());

        skills.add(SkillDefinition.builder()
                .id("http-client")
                .name("HTTP 客户端规范")
                .category("frontend-skill")
                .icon("Link")
                .description("前端 HTTP 请求与 API 调用规范")
                .triggerKeywords(Arrays.asList("HTTP", "axios", "fetch", "API 调用"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/http-client/SKILL.md")
                                .target(".claude/skills/http-client/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("前端 HTTP 请求时自动激活，提供 axios / fetch 最佳实践")
                        .examples(Arrays.asList(
                                "帮我封装 axios",
                                "创建 API 请求函数",
                                "处理请求错误"
                        ))
                        .applicableProjects("Vue 3 / React")
                        .build())
                .build());

        // 通用 Skills
        skills.add(SkillDefinition.builder()
                .id("code-patterns")
                .name("代码模式规范")
                .category("general-skill")
                .icon("Document")
                .description("通用代码设计与最佳实践")
                .triggerKeywords(Arrays.asList("代码", "模式", "设计", "最佳实践"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/code-patterns/SKILL.md")
                                .target(".claude/skills/code-patterns/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("编写代码时自动激活，提供设计模式和最佳实践指导")
                        .examples(Arrays.asList(
                                "帮我重构这段代码",
                                "使用单例模式",
                                "优化代码结构"
                        ))
                        .applicableProjects("所有项目")
                        .build())
                .build());

        skills.add(SkillDefinition.builder()
                .id("git-workflow")
                .name("Git 工作流规范")
                .category("general-skill")
                .icon("Branch")
                .description("Git 分支管理与提交规范")
                .triggerKeywords(Arrays.asList("Git", "分支", "提交", "merge", "commit"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/git-workflow/SKILL.md")
                                .target(".claude/skills/git-workflow/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("Git 操作时自动激活，提供安全的分支管理和提交规范")
                        .examples(Arrays.asList(
                                "帮我创建新分支",
                                "写一个提交信息",
                                "合并代码"
                        ))
                        .applicableProjects("所有项目")
                        .build())
                .build());

        skills.add(SkillDefinition.builder()
                .id("bug-detective")
                .name("Bug 侦探规范")
                .category("general-skill")
                .icon("Search")
                .description("Bug 定位与调试方法论")
                .triggerKeywords(Arrays.asList("Bug", "调试", "定位", "排查"))
                .files(Arrays.asList(
                        SkillFile.builder()
                                .source("skills/bug-detective/SKILL.md")
                                .target(".claude/skills/bug-detective/SKILL.md")
                                .type("skill")
                                .build()
                ))
                .detail(SkillDetail.builder()
                        .usage("调试 Bug 时自动激活，提供系统化的 Bug 定位方法")
                        .examples(Arrays.asList(
                                "帮我排查这个 Bug",
                                "定位内存泄漏",
                                "分析报错原因"
                        ))
                        .applicableProjects("所有项目")
                        .build())
                .build());

        log.info("初始化技能定义列表完成，共 {} 个技能", skills.size());
        return skills;
    }

    @Override
    public SkillListResponse getAllSkills() {
        log.info("获取所有技能定义列表");
        return SkillListResponse.builder()
                .skills(skillDefinitions)
                .categories(CATEGORIES)
                .build();
    }

    @Override
    public SkillDefinition getSkillDetail(String skillId) {
        log.info("获取技能详情: {}", skillId);
        return skillDefinitions.stream()
                .filter(skill -> skill.getId().equals(skillId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public ProjectSkillStatus getProjectStatus(String projectDir) {
        log.info("检测项目技能状态: {}", projectDir);

        Path projectPath = Paths.get(projectDir);
        Path claudeDir = projectPath.resolve(".claude");

        // 检查 .claude 目录是否存在
        boolean hasClaudeDir = Files.exists(claudeDir) && Files.isDirectory(claudeDir);

        // 检查已安装的 Skills
        List<String> installedSkills = new ArrayList<>();
        if (hasClaudeDir) {
            Path skillsDir = claudeDir.resolve("skills");
            if (Files.exists(skillsDir)) {
                try {
                    installedSkills = Files.list(skillsDir)
                            .filter(Files::isDirectory)
                            .filter(p -> Files.exists(p.resolve("SKILL.md")))
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.toList());
                } catch (IOException e) {
                    log.warn("读取 skills 目录失败: {}", e.getMessage());
                }
            }
        }

        // 检查已安装的 Hooks
        List<String> installedHooks = new ArrayList<>();
        if (hasClaudeDir) {
            Path hooksDir = claudeDir.resolve("hooks");
            if (Files.exists(hooksDir)) {
                try {
                    installedHooks = Files.list(hooksDir)
                            .filter(Files::isRegularFile)
                            .map(p -> p.getFileName().toString())
                            .filter(name -> name.endsWith(".js"))
                            .map(name -> name.substring(0, name.length() - 3))
                            .collect(Collectors.toList());
                } catch (IOException e) {
                    log.warn("读取 hooks 目录失败: {}", e.getMessage());
                }
            }
        }

        // 检查 MCP 配置
        boolean mcpConfigured = false;
        if (hasClaudeDir) {
            Path settingsFile = claudeDir.resolve("settings.json");
            if (Files.exists(settingsFile)) {
                try {
                    String content = Files.readString(settingsFile);
                    mcpConfigured = content.contains("hisi-dev-tool") && content.contains("mcpServers");
                } catch (IOException e) {
                    log.warn("读取 settings.json 失败: {}", e.getMessage());
                }
            }
        }

        // 检查 settings.json 是否有效
        boolean settingsValid = false;
        if (hasClaudeDir) {
            Path settingsFile = claudeDir.resolve("settings.json");
            if (Files.exists(settingsFile)) {
                try {
                    String content = Files.readString(settingsFile);
                    settingsValid = content.contains("hasCompletedOnboarding") || content.contains("hooks") || content.contains("mcpServers");
                } catch (IOException e) {
                    log.warn("读取 settings.json 失败: {}", e.getMessage());
                }
            }
        }

        ProjectSkillStatus.InstalledStatus installedStatus = ProjectSkillStatus.InstalledStatus.builder()
                .skills(installedSkills)
                .hooks(installedHooks)
                .mcp(mcpConfigured)
                .build();

        return ProjectSkillStatus.builder()
                .projectDir(projectDir)
                .hasClaudeDir(hasClaudeDir)
                .installed(installedStatus)
                .settingsValid(settingsValid)
                .build();
    }

    @Override
    public List<String> getCategories() {
        log.info("获取技能分类列表");
        return CATEGORIES;
    }

    @Override
    public List<SkillDefinition> getSkillsByCategory(String category) {
        log.info("按分类筛选技能: {}", category);
        return skillDefinitions.stream()
                .filter(skill -> skill.getCategory().equals(category))
                .collect(Collectors.toList());
    }
}