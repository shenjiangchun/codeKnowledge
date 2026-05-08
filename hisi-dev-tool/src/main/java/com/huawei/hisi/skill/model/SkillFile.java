package com.huawei.hisi.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 技能文件结构
 * 定义技能安装时需要的文件信息
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFile {

    /**
     * 源文件路径（在 devtool 资源目录）
     */
    private String source;

    /**
     * 目标路径模板，如 '.claude/skills/{id}/SKILL.md'
     */
    private String target;

    /**
     * 文件类型：skill, hook, command, memory, settings
     */
    private String type;
}