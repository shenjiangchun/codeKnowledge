package com.huawei.hisi.skill;

import com.huawei.hisi.skill.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SkillService 单元测试
 * 测试技能市场的核心功能：技能列表获取、项目状态检测、分类筛选
 *
 * 测试范围：
 * 1. getAllSkills() - 获取所有可用技能定义列表
 * 2. getSkillDetail() - 获取技能详细信息
 * 3. getProjectStatus() - 检测项目已安装技能状态
 * 4. getCategories() - 获取技能分类列表
 * 5. getSkillsByCategory() - 按分类筛选技能
 *
 * 使用 Mockito 模拟文件操作，避免实际创建文件
 */
@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @InjectMocks
    private SkillServiceImpl skillService;

    // 测试数据
    private String testProjectDir;

    @BeforeEach
    void setUp() {
        // 初始化测试项目目录
        testProjectDir = "D:\\projects\\test-project";
    }

    // ==================== getAllSkills Tests ====================

    @Test
    @DisplayName("获取技能列表 - 正常返回")
    void testGetAllSkills_Success() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        assertNotNull(response);
        assertNotNull(response.getSkills());
        assertFalse(response.getSkills().isEmpty());
        assertNotNull(response.getCategories());
        assertFalse(response.getCategories().isEmpty());

        // 验证技能定义包含必要字段
        for (SkillDefinition skill : response.getSkills()) {
            assertNotNull(skill.getId());
            assertNotNull(skill.getName());
            assertNotNull(skill.getCategory());
            assertNotNull(skill.getDescription());
        }
    }

    @Test
    @DisplayName("获取技能列表 - 包含预定义分类")
    void testGetAllSkills_ContainsCategories() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        List<String> categories = response.getCategories();
        assertTrue(categories.contains("mcp"));
        assertTrue(categories.contains("hook"));
        assertTrue(categories.contains("backend-skill"));
        assertTrue(categories.contains("frontend-skill"));
        assertTrue(categories.contains("general-skill"));
    }

    @Test
    @DisplayName("获取技能列表 - 包含 MCP 工具")
    void testGetAllSkills_ContainsMcpTools() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        List<SkillDefinition> mcpSkills = response.getSkills().stream()
                .filter(s -> "mcp".equals(s.getCategory()))
                .toList();

        assertFalse(mcpSkills.isEmpty());
        assertTrue(mcpSkills.stream().anyMatch(s -> "hisi-dev-tool".equals(s.getId())));
    }

    @Test
    @DisplayName("获取技能列表 - 包含安全钩子")
    void testGetAllSkills_ContainsHooks() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        List<SkillDefinition> hookSkills = response.getSkills().stream()
                .filter(s -> "hook".equals(s.getCategory()))
                .toList();

        assertFalse(hookSkills.isEmpty());
        assertTrue(hookSkills.stream().anyMatch(s -> "pre-tool-use".equals(s.getId())));
        assertTrue(hookSkills.stream().anyMatch(s -> "skill-forced-eval".equals(s.getId())));
    }

    @Test
    @DisplayName("获取技能列表 - 包含后端技能")
    void testGetAllSkills_ContainsBackendSkills() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        List<SkillDefinition> backendSkills = response.getSkills().stream()
                .filter(s -> "backend-skill".equals(s.getCategory()))
                .toList();

        assertFalse(backendSkills.isEmpty());
        assertTrue(backendSkills.stream().anyMatch(s -> "crud-development".equals(s.getId())));
        assertTrue(backendSkills.stream().anyMatch(s -> "api-development".equals(s.getId())));
    }

    @Test
    @DisplayName("获取技能列表 - 包含前端技能")
    void testGetAllSkills_ContainsFrontendSkills() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        List<SkillDefinition> frontendSkills = response.getSkills().stream()
                .filter(s -> "frontend-skill".equals(s.getCategory()))
                .toList();

        assertFalse(frontendSkills.isEmpty());
        assertTrue(frontendSkills.stream().anyMatch(s -> "ui-pc".equals(s.getId())));
    }

    @Test
    @DisplayName("获取技能列表 - 包含通用技能")
    void testGetAllSkills_ContainsGeneralSkills() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        List<SkillDefinition> generalSkills = response.getSkills().stream()
                .filter(s -> "general-skill".equals(s.getCategory()))
                .toList();

        assertFalse(generalSkills.isEmpty());
        assertTrue(generalSkills.stream().anyMatch(s -> "lesson-learned".equals(s.getId())));
    }

    // ==================== getSkillDetail Tests ====================

    @Test
    @DisplayName("获取技能详情 - 存在的技能")
    void testGetSkillDetail_ExistingSkill() {
        // Given
        String skillId = "crud-development";

        // When
        SkillDefinition skill = skillService.getSkillDetail(skillId);

        // Then
        assertNotNull(skill);
        assertEquals("crud-development", skill.getId());
        assertEquals("CRUD 开发规范", skill.getName());
        assertEquals("backend-skill", skill.getCategory());
        assertNotNull(skill.getDescription());
        assertNotNull(skill.getFiles());
        assertNotNull(skill.getDetail());
    }

    @Test
    @DisplayName("获取技能详情 - 不存在的技能返回 null")
    void testGetSkillDetail_NonExistingSkill() {
        // Given
        String skillId = "non-existing-skill";

        // When
        SkillDefinition skill = skillService.getSkillDetail(skillId);

        // Then
        assertNull(skill);
    }

    @Test
    @DisplayName("获取技能详情 - null ID 返回 null")
    void testGetSkillDetail_NullId() {
        // When
        SkillDefinition skill = skillService.getSkillDetail(null);

        // Then
        assertNull(skill);
    }

    @Test
    @DisplayName("获取技能详情 - 空 ID 返回 null")
    void testGetSkillDetail_EmptyId() {
        // When
        SkillDefinition skill = skillService.getSkillDetail("");

        // Then
        assertNull(skill);
    }

    @Test
    @DisplayName("获取技能详情 - Hook 类型技能")
    void testGetSkillDetail_HookSkill() {
        // Given
        String skillId = "pre-tool-use";

        // When
        SkillDefinition skill = skillService.getSkillDetail(skillId);

        // Then
        assertNotNull(skill);
        assertEquals("hook", skill.getCategory());
        assertNotNull(skill.getDetail().getTriggerCondition());
        assertNotNull(skill.getDetail().getSafetyRules());
        assertFalse(skill.getDetail().getSafetyRules().isEmpty());
    }

    // ==================== getProjectStatus Tests ====================

    @Test
    @DisplayName("检测项目状态 - 不存在的项目目录")
    void testGetProjectStatus_NonExistingProject() {
        // Given
        String nonExistingDir = "D:\\non-existing-path";

        // When
        ProjectSkillStatus status = skillService.getProjectStatus(nonExistingDir);

        // Then
        assertNotNull(status);
        assertEquals(nonExistingDir, status.getProjectDir());
        assertFalse(status.isHasClaudeDir());
        assertTrue(status.getInstalled().getSkills().isEmpty());
        assertTrue(status.getInstalled().getHooks().isEmpty());
        assertFalse(status.getInstalled().isMcp());
    }

    @Test
    @DisplayName("检测项目状态 - null 参数")
    void testGetProjectStatus_NullParameter() {
        // When & Then
        assertThrows(Exception.class, () -> {
            skillService.getProjectStatus(null);
        });
    }

    @Test
    @DisplayName("检测项目状态 - 空参数返回默认状态")
    void testGetProjectStatus_EmptyParameter() {
        // When
        ProjectSkillStatus status = skillService.getProjectStatus("");

        // Then
        assertNotNull(status);
        assertEquals("", status.getProjectDir());
        // 空路径会导致 Paths.get("") 解析为当前工作目录
        // 所以 hasClaudeDir 取决于当前目录是否有 .claude 文件夹
        assertNotNull(status.getInstalled());
    }

    // ==================== getCategories Tests ====================

    @Test
    @DisplayName("获取分类列表 - 正常返回")
    void testGetCategories_Success() {
        // When
        List<String> categories = skillService.getCategories();

        // Then
        assertNotNull(categories);
        assertEquals(5, categories.size());
        assertTrue(categories.contains("mcp"));
        assertTrue(categories.contains("hook"));
        assertTrue(categories.contains("backend-skill"));
        assertTrue(categories.contains("frontend-skill"));
        assertTrue(categories.contains("general-skill"));
    }

    // ==================== getSkillsByCategory Tests ====================

    @Test
    @DisplayName("按分类筛选 - backend-skill 分类")
    void testGetSkillsByCategory_BackendSkill() {
        // Given
        String category = "backend-skill";

        // When
        List<SkillDefinition> skills = skillService.getSkillsByCategory(category);

        // Then
        assertNotNull(skills);
        assertFalse(skills.isEmpty());
        assertTrue(skills.stream().allMatch(s -> "backend-skill".equals(s.getCategory())));
        assertTrue(skills.stream().anyMatch(s -> "crud-development".equals(s.getId())));
        assertTrue(skills.stream().anyMatch(s -> "api-development".equals(s.getId())));
        assertTrue(skills.stream().anyMatch(s -> "database-ops".equals(s.getId())));
        assertTrue(skills.stream().anyMatch(s -> "error-handler".equals(s.getId())));
    }

    @Test
    @DisplayName("按分类筛选 - frontend-skill 分类")
    void testGetSkillsByCategory_FrontendSkill() {
        // Given
        String category = "frontend-skill";

        // When
        List<SkillDefinition> skills = skillService.getSkillsByCategory(category);

        // Then
        assertNotNull(skills);
        assertFalse(skills.isEmpty());
        assertTrue(skills.stream().allMatch(s -> "frontend-skill".equals(s.getCategory())));
        assertTrue(skills.stream().anyMatch(s -> "ui-pc".equals(s.getId())));
        assertTrue(skills.stream().anyMatch(s -> "http-client".equals(s.getId())));
    }

    @Test
    @DisplayName("按分类筛选 - hook 分类")
    void testGetSkillsByCategory_Hook() {
        // Given
        String category = "hook";

        // When
        List<SkillDefinition> skills = skillService.getSkillsByCategory(category);

        // Then
        assertNotNull(skills);
        assertFalse(skills.isEmpty());
        assertTrue(skills.stream().allMatch(s -> "hook".equals(s.getCategory())));
        assertTrue(skills.stream().anyMatch(s -> "pre-tool-use".equals(s.getId())));
        assertTrue(skills.stream().anyMatch(s -> "skill-forced-eval".equals(s.getId())));
    }

    @Test
    @DisplayName("按分类筛选 - mcp 分类")
    void testGetSkillsByCategory_Mcp() {
        // Given
        String category = "mcp";

        // When
        List<SkillDefinition> skills = skillService.getSkillsByCategory(category);

        // Then
        assertNotNull(skills);
        assertEquals(1, skills.size());
        assertEquals("hisi-dev-tool", skills.get(0).getId());
    }

    @Test
    @DisplayName("按分类筛选 - 不存在的分类返回空列表")
    void testGetSkillsByCategory_NonExistingCategory() {
        // Given
        String category = "non-existing-category";

        // When
        List<SkillDefinition> skills = skillService.getSkillsByCategory(category);

        // Then
        assertNotNull(skills);
        assertTrue(skills.isEmpty());
    }

    @Test
    @DisplayName("按分类筛选 - null 分类返回空列表")
    void testGetSkillsByCategory_NullCategory() {
        // When
        List<SkillDefinition> skills = skillService.getSkillsByCategory(null);

        // Then
        assertNotNull(skills);
        assertTrue(skills.isEmpty());
    }

    // ==================== Skill Definition Structure Tests ====================

    @Test
    @DisplayName("技能定义结构 - 包含触发关键词")
    void testSkillDefinition_HasTriggerKeywords() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        for (SkillDefinition skill : response.getSkills()) {
            assertNotNull(skill.getTriggerKeywords());
            assertFalse(skill.getTriggerKeywords().isEmpty());
        }
    }

    @Test
    @DisplayName("技能定义结构 - 包含文件列表")
    void testSkillDefinition_HasFiles() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        for (SkillDefinition skill : response.getSkills()) {
            assertNotNull(skill.getFiles());
            // 大多数技能都应该有文件列表
        }
    }

    @Test
    @DisplayName("技能定义结构 - 包含详情")
    void testSkillDefinition_HasDetail() {
        // When
        SkillListResponse response = skillService.getAllSkills();

        // Then
        for (SkillDefinition skill : response.getSkills()) {
            assertNotNull(skill.getDetail());
            assertNotNull(skill.getDetail().getUsage());
            assertNotNull(skill.getDetail().getExamples());
            assertFalse(skill.getDetail().getExamples().isEmpty());
        }
    }

    @Test
    @DisplayName("技能定义结构 - Hook 类型包含安全规则")
    void testSkillDefinition_HookHasSafetyRules() {
        // When
        SkillDefinition preToolUse = skillService.getSkillDetail("pre-tool-use");

        // Then
        assertNotNull(preToolUse);
        assertNotNull(preToolUse.getDetail().getSafetyRules());
        assertTrue(preToolUse.getDetail().getSafetyRules().size() >= 3);
        assertTrue(preToolUse.getDetail().getSafetyRules().stream()
                .anyMatch(rule -> rule.contains("rm -rf")));
        assertTrue(preToolUse.getDetail().getSafetyRules().stream()
                .anyMatch(rule -> rule.contains("git push --force")));
    }

    @Test
    @DisplayName("技能定义结构 - 自我进化包含核心功能")
    void testSkillDefinition_SelfEvolutionHasCoreFunctions() {
        // When
        SkillDefinition skillForcedEval = skillService.getSkillDetail("skill-forced-eval");

        // Then
        assertNotNull(skillForcedEval);
        assertNotNull(skillForcedEval.getDetail().getCoreFunctions());
        assertTrue(skillForcedEval.getDetail().getCoreFunctions().size() >= 2);
        assertTrue(skillForcedEval.getDetail().getCoreFunctions().stream()
                .anyMatch(func -> func.contains("纠正关键词")));
    }
}