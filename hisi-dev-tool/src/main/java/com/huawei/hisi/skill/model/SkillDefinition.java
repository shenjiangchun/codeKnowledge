package com.huawei.hisi.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 技能定义实体类
 * 定义技能市场中的技能结构
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    /**
     * 技能唯一标识，如 'crud-development'
     */
    private String id;

    /**
     * 显示名称，如 'CRUD 开发规范'
     */
    private String name;

    /**
     * 技能分类：mcp, hook, backend-skill, frontend-skill, general-skill
     */
    private String category;

    /**
     * Element Plus 图标名称
     */
    private String icon;

    /**
     * 简短描述
     */
    private String description;

    /**
     * 触发关键词列表（用于展示）
     */
    private List<String> triggerKeywords;

    /**
     * 需要安装的文件列表
     */
    private List<SkillFile> files;

    /**
     * 详细信息（用于详情弹窗）
     */
    private SkillDetail detail;
}