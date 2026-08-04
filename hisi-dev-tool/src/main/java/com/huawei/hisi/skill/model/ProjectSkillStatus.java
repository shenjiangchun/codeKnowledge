package com.huawei.hisi.skill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 项目已安装技能状态响应
 * 用于检测指定项目的技能安装状态
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSkillStatus {

    /**
     * 项目目录路径
     */
    private String projectDir;

    /**
     * 是否存在 .claude/ 目录
     */
    private boolean hasClaudeDir;

    /**
     * 已安装的状态信息
     */
    private InstalledStatus installed;

    /**
     * settings.json 配置是否有效
     */
    private boolean settingsValid;

    /**
     * 已安装状态详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstalledStatus {

        /**
         * 已安装的技能 ID 列表
         */
        private List<String> skills;

        /**
         * 已安装的 Hook ID 列表
         */
        private List<String> hooks;

        /**
         * MCP 是否已配置
         */
        private boolean mcp;
    }
}