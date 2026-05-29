package com.huawei.hisi.skill;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.skill.model.ProjectSkillStatus;
import com.huawei.hisi.skill.model.SkillDefinition;
import com.huawei.hisi.skill.model.SkillListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 技能市场 REST API 控制器
 * 提供技能定义查询、项目状态检测等接口
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    /**
     * 获取所有可用技能定义列表
     * GET /api/skills/list
     *
     * @return 技能列表响应（包含技能定义和分类）
     */
    @GetMapping("/list")
    public ApiResponse<SkillListResponse> getAllSkills() {
        log.info("API 调用: GET /api/skills/list");
        try {
            SkillListResponse response = skillService.getAllSkills();
            log.info("返回 {} 个技能定义", response.getSkills().size());
            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("获取技能列表失败", e);
            return ApiResponse.error("获取技能列表失败: " + e.getMessage());
        }
    }

    /**
     * 检测项目已安装技能状态
     * GET /api/skills/status?projectDir=xxx
     *
     * @param projectDir 项目目录路径
     * @return 项目技能状态
     */
    @GetMapping("/status")
    public ApiResponse<ProjectSkillStatus> getProjectStatus(@RequestParam String projectDir) {
        log.info("API 调用: GET /api/skills/status?projectDir={}", projectDir);
        try {
            if (projectDir == null || projectDir.trim().isEmpty()) {
                log.warn("项目目录参数为空");
                return ApiResponse.error(400, "项目目录不能为空");
            }

            ProjectSkillStatus status = skillService.getProjectStatus(projectDir);
            log.info("项目 {} 状态检测完成: hasClaudeDir={}, skills={}, hooks={}, mcp={}",
                    projectDir, status.isHasClaudeDir(),
                    status.getInstalled().getSkills().size(),
                    status.getInstalled().getHooks().size(),
                    status.getInstalled().isMcp());
            return ApiResponse.success(status);
        } catch (Exception e) {
            log.error("检测项目状态失败: {}", projectDir, e);
            return ApiResponse.error("检测项目状态失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定技能的详细信息
     * GET /api/skills/detail/{id}
     *
     * @param id 技能 ID
     * @return 技能定义详情
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<SkillDefinition> getSkillDetail(@PathVariable String id) {
        log.info("API 调用: GET /api/skills/detail/{}", id);
        try {
            if (id == null || id.trim().isEmpty()) {
                log.warn("技能 ID 参数为空");
                return ApiResponse.error(400, "技能 ID 不能为空");
            }

            SkillDefinition skill = skillService.getSkillDetail(id);
            if (skill == null) {
                log.warn("技能不存在: {}", id);
                return ApiResponse.error(404, "技能不存在: " + id);
            }

            log.info("返回技能详情: {} ({})", skill.getName(), skill.getId());
            return ApiResponse.success(skill);
        } catch (Exception e) {
            log.error("获取技能详情失败: {}", id, e);
            return ApiResponse.error("获取技能详情失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有技能分类列表
     * GET /api/skills/categories
     *
     * @return 分类列表
     */
    @GetMapping("/categories")
    public ApiResponse<List<String>> getCategories() {
        log.info("API 调用: GET /api/skills/categories");
        try {
            List<String> categories = skillService.getCategories();
            log.info("返回 {} 个分类", categories.size());
            return ApiResponse.success(categories);
        } catch (Exception e) {
            log.error("获取分类列表失败", e);
            return ApiResponse.error("获取分类列表失败: " + e.getMessage());
        }
    }

    /**
     * 根据分类筛选技能
     * GET /api/skills/by-category?category=xxx
     *
     * @param category 分类名称
     * @return 该分类下的技能列表
     */
    @GetMapping("/by-category")
    public ApiResponse<List<SkillDefinition>> getSkillsByCategory(@RequestParam String category) {
        log.info("API 调用: GET /api/skills/by-category?category={}", category);
        try {
            if (category == null || category.trim().isEmpty()) {
                log.warn("分类参数为空");
                return ApiResponse.error(400, "分类不能为空");
            }

            List<SkillDefinition> skills = skillService.getSkillsByCategory(category);
            log.info("返回 {} 个 {} 类型的技能", skills.size(), category);
            return ApiResponse.success(skills);
        } catch (Exception e) {
            log.error("按分类筛选技能失败: {}", category, e);
            return ApiResponse.error("按分类筛选技能失败: " + e.getMessage());
        }
    }
}