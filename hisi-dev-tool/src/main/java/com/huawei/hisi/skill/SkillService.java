package com.huawei.hisi.skill;

import com.huawei.hisi.skill.model.ProjectSkillStatus;
import com.huawei.hisi.skill.model.SkillDefinition;
import com.huawei.hisi.skill.model.SkillListResponse;

import java.util.List;

/**
 * 技能市场服务接口
 * 提供技能管理相关功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface SkillService {

    /**
     * 获取所有可用技能定义列表
     *
     * @return 技能列表响应（包含技能定义和分类）
     */
    SkillListResponse getAllSkills();

    /**
     * 获取指定技能的详细信息
     *
     * @param skillId 技能 ID
     * @return 技能定义，不存在则返回 null
     */
    SkillDefinition getSkillDetail(String skillId);

    /**
     * 检测指定项目的技能安装状态
     *
     * @param projectDir 项目目录路径
     * @return 项目技能状态
     */
    ProjectSkillStatus getProjectStatus(String projectDir);

    /**
     * 获取所有技能分类列表
     *
     * @return 分类列表
     */
    List<String> getCategories();

    /**
     * 根据分类筛选技能
     *
     * @param category 分类名称
     * @return 该分类下的技能列表
     */
    List<SkillDefinition> getSkillsByCategory(String category);
}