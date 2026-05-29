package com.huawei.hisi.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 技能列表响应
 * 包含所有可用技能定义和分类列表
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillListResponse {

    /**
     * 所有可用技能定义列表
     */
    private List<SkillDefinition> skills;

    /**
     * 技能分类列表
     */
    private List<String> categories;
}