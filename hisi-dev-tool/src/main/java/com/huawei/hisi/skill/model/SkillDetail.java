package com.huawei.hisi.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 技能详情信息
 * 用于技能详情弹窗展示
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDetail {

    /**
     * 使用说明
     */
    private String usage;

    /**
     * Hook 触发条件（仅 Hook 类型）
     */
    private String triggerCondition;

    /**
     * 安全钩子规则列表（仅 pre-tool-use Hook）
     */
    private List<String> safetyRules;

    /**
     * 自我进化核心功能列表（仅 skill-forced-eval Hook）
     */
    private List<String> coreFunctions;

    /**
     * 示例提问列表
     */
    private List<String> examples;

    /**
     * 适用项目类型
     */
    private String applicableProjects;
}